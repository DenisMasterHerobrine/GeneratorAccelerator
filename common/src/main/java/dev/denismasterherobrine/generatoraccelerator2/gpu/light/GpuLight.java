package dev.denismasterherobrine.generatoraccelerator2.gpu.light;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuConfig;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.DataLayerStorageMapAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LayerLightSectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LightEngineAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.SkyLightSectionStorageAccessor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import org.slf4j.Logger;

/**
 * Light propagation on the GPU: the increases of a large light batch ({@link ParallelLight.IncreaseOffload}).
 *
 * <p>The light thread still runs everything vanilla runs, in vanilla's order, and the decreases of the batch. Then, instead of
 * taking the increase queue entry by entry, it hands the batch to the GPU. Vanilla's propagation reaches the same light whatever
 * order its entries are taken in (docs/PARALLEL_LIGHT.md, {@code light/LightOrderTest}), so the GPU may take them all at once:
 * <ul>
 *   <li>The batch's sections are the queued entries' sections and their neighbours (light falls at least 1 per step, so 15
 *   reaches no further). For each the GPU gets whether the section stores light, its levels, its blocks (the palette and packed
 *   indices as the chunk holds them, or the blocks as types where a block's light properties may depend on its position), and
 *   its neighbours.</li>
 *   <li>The kernels spread light level by level from 15 ({@link LightProgram}). Each step is vanilla's
 *   {@code propagateIncrease} for one direction.</li>
 *   <li>The light thread writes every changed section as {@code setStoredLevel} would have: the section copied at its first
 *   write in the batch and added to the changed sections, and every section around a changed block added to the affected
 *   ones.</li>
 * </ul>
 * One rule of the sky light is not a rise: sky light stepping sideways out of a section's bottom layer into a column whose
 * sections below are stored past empty ones fills those sections whatever they hold ({@code propagateFromEmptySections}). Where a
 * batch could reach it, and where a block's palette changed while it was read, the GPU's answer is dropped and the light thread
 * propagates the batch itself, from the same queue.
 *
 * <p>The choice per batch follows the light thread's time: per light layer, the time per queued entry on the GPU and on the
 * CPU, each taken every so often from the other path.
 */
public final class GpuLight implements ParallelLight.IncreaseOffload {
    private static final Logger LOGGER = Ga2Log.get("Light");
    public static final GpuLight INSTANCE = new GpuLight();

    /** {@code -Dgeneratoraccelerator2.gpu.light=false} turns it off. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.gpu.light"));
    /** Batches below this much work stay on the CPU (sky entries count 1, block light entries 16, as for the parallel light). */
    public static final int MIN_WORK = Integer.getInteger("generatoraccelerator2.gpu.lightMinWork", 4096);
    /** Most sections a batch may reach (about 60 MB on the device at 2,048). A larger batch stays on the CPU. */
    private static final int MAX_SLOTS = Integer.getInteger("generatoraccelerator2.gpu.lightMaxSections", 2048);

    /** Tests: the device to use, every batch to the GPU, any engine. */
    public static volatile GpuDevice testDevice;
    public static volatile boolean testAlways;

    private static volatile BooleanSupplier compatGate = () -> true;

    public static void setCompatGate(BooleanSupplier gate) {
        compatGate = gate;
    }

    public static final LongAdder BATCHES = new LongAdder(), ENTRIES = new LongAdder(), SECTIONS = new LongAdder(), CHANGED = new LongAdder(),
        NANOS = new LongAdder(), FILL = new LongAdder(), RACE = new LongAdder(), OUTSIDE = new LongAdder(), UNSTORED = new LongAdder(), LARGE = new LongAdder(),
        ERRORS = new LongAdder(), CPU_CHOSEN = new LongAdder();
    /** A batch's parts on the light thread: waiting for the lock, the host arrays, the GPU's work with its transfers, and the write-back. */
    public static final LongAdder LOCK_NANOS = new LongAdder(), PREP_NANOS = new LongAdder(), GPU_NANOS = new LongAdder(), BACK_NANOS = new LongAdder();
    /** The host arrays by part: the batch's slots, their levels and neighbours, the entries, the blocks, the face tables. Sections read block by block. */
    public static final LongAdder[] PREP_PARTS = { new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder() };
    public static final LongAdder RAW_SECTIONS = new LongAdder(), BLOCK_SECTIONS = new LongAdder();

    private static final Object LOCK = new Object();
    private static volatile LightProgram program;
    private static volatile GpuDevice building;
    private static volatile String off;

    /**
     * Recent samples of the light thread's nanoseconds per queued entry on one path. The choice goes by their median, so a few slow
     * batches move it little. The first batches on a cold JIT and driver took up to 70 ms where later ones took 8, and a moving
     * average kept the GPU out of most batches long after.
     */
    private static final class Costs {
        private final double[] ring = new double[8];
        private int n, next;

        synchronized void add(long nanos, int entries) {
            this.ring[this.next] = (double) nanos / Math.max(1, entries);
            this.next = (this.next + 1) % this.ring.length;
            this.n = Math.min(this.n + 1, this.ring.length);
        }

        /** The median of the recent samples, -1 before a sample. */
        synchronized double median() {
            if (this.n == 0) {
                return -1;
            }
            double[] s = Arrays.copyOf(this.ring, this.n);
            Arrays.sort(s);
            return s[this.n / 2];
        }
    }

    /** Per light layer (block 0, sky 1): the light thread's time per queued entry on the GPU and on the CPU. */
    private static final Costs[] GPU_COST = { new Costs(), new Costs() }, CPU_COST = { new Costs(), new Costs() };
    private static final int[] SINCE_PROBE = new int[2], DECIDED = new int[2];
    private static final int PROBE_EVERY = 32;
    /** Choices per layer that alternate between the two paths first, so each is measured before either is judged. */
    private static final int FIRST_CHOICES = 6;

    private GpuLight() {}

    // ------------------------------------------------------------------------------------------------ choice

    @Override
    public boolean wants(LightEngine<?, ?> engine, boolean sky, int increases, int decreases) {
        if (!ENABLED || (testDevice == null && !GpuConfig.enabled()) || !compatGate.getAsBoolean()) {
            return false;
        }
        long work = sky ? increases : increases * 16L;
        if (!testAlways && work < MIN_WORK) {
            return false;
        }
        if (ready() == null) {
            return false;
        }
        if (testAlways) {
            return true;
        }
        int layer = sky ? 1 : 0;
        boolean gpuWins;
        if (DECIDED[layer] < FIRST_CHOICES) {
            gpuWins = DECIDED[layer]++ % 2 == 0;
        } else {
            double gpu = GPU_COST[layer].median(), cpu = CPU_COST[layer].median();
            // A path without a sample yet goes first. The CPU path is only measured when its batch is not split.
            gpuWins = gpu < 0 || cpu < 0 || gpu <= cpu;
            if (gpu >= 0 && cpu >= 0 && ++SINCE_PROBE[layer] >= PROBE_EVERY) {
                // the other path once in a while, so its time follows the load
                SINCE_PROBE[layer] = 0;
                gpuWins = !gpuWins;
            }
        }
        if (!gpuWins) {
            CPU_CHOSEN.increment();
        }
        return gpuWins;
    }

    @Override
    public void cpuIncreases(boolean sky, int increases, long nanos) {
        long work = sky ? increases : increases * 16L;
        if (work >= MIN_WORK) {
            CPU_COST[sky ? 1 : 0].add(nanos, increases);
        }
    }

    /** The device's light program once built, else null. The first call for a device starts its build in the background. */
    @Nullable
    private static LightProgram ready() {
        if (off != null) {
            return null;
        }
        GpuDevice device = testDevice != null ? testDevice : Gpu.device();
        if (device == null) {
            return null;
        }
        LightProgram p = program;
        if (p != null && p.device == device && !p.closed) {
            return p;
        }
        if (building != device) {
            synchronized (LOCK) {
                if (building != device) {
                    building = device;
                    Thread t = new Thread(() -> build(device), "GA2 GPU light build");
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
        return null;
    }

    private static void build(GpuDevice device) {
        try {
            long t0 = System.nanoTime();
            LightProgram p = new LightProgram(device, Gpu.BUILD_OPTIONS);
            device.onClose(() -> {
                synchronized (LOCK) {
                    p.release();
                    if (program == p) {
                        program = null;
                    }
                }
            });
            program = p;
            LOGGER.info("light program built in {} ms -> large light batches can propagate on the GPU", (System.nanoTime() - t0) / 1_000_000);
        } catch (RuntimeException | LinkageError e) {
            off = "the light program did not build: " + e;
            LOGGER.warn("light program did not build ({}) -> light stays on the CPU", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ batch

    /** Per light thread: the batch's host arrays, kept between batches. */
    private static final class Scratch {
        long[] entries = new long[1024];
        final LightTypes types = new LightTypes();
        final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
        /** The entries' own sections, whose neighbourhoods are in the batch. */
        final LongOpenHashSet seedSections = new LongOpenHashSet();
        final LongArrayList keys = new LongArrayList();
        final Long2ObjectOpenHashMap<ChunkInfo> chunks = new Long2ObjectOpenHashMap<>();
        /** The batch's block words, a view of the program's pinned upload buffer, and how many are written. */
        LongBuffer words;
        int wordCount;
        final IntArrayList sinfo = new IntArrayList();
        final ShortArrayList pal = new ShortArrayList();
        final IntArrayList kslots = new IntArrayList();
        byte[] storing = new byte[0];
        int[] nbr = new int[0];
        int[] hazard = new int[0];
        int[] seeds = new int[0];
        int[] changed = new int[0];
        byte[] out = new byte[0];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        { slotOf.defaultReturnValue(-1); }
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    /** A section's nibble array with every level {@code v}, by v. The levels of a section without stored light are 0. */
    private static final byte[][] LEVELS = new byte[16][2048];

    static {
        for (int v = 0; v < 16; v++) {
            Arrays.fill(LEVELS[v], (byte) (v | v << 4));
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** A chunk as the light engine reads it ({@code LightEngine.getState}): its kind, sections and lowest section. */
    private record ChunkInfo(@Nullable LightChunk chunk, int kind, @Nullable LevelChunkSection[] sections, int minSection) {}

    private static final int KIND_NONE = 0, KIND_PROTO = 1, KIND_LEVEL = 2, KIND_OTHER = 3;

    @Override
    public int propagateIncreases(LightEngine<?, ?> engine, boolean sky) {
        LightEngineAccessor e = (LightEngineAccessor) engine;
        LongArrayFIFOQueue queue = e.ga2pl$increaseQueue();
        int n = queue.size();
        if (n == 0) {
            return 0;
        }
        LightProgram p = ready();
        if (p == null) {
            return -1;
        }
        long t0 = System.nanoTime();
        Scratch s = SCRATCH.get();
        if (s.entries.length < n) {
            s.entries = new long[Math.max(n, s.entries.length * 2)];
        }
        long[] entries = s.entries;
        for (int i = 0; i < n; i++) {
            entries[i] = queue.dequeueLong();
        }
        int result;
        try {
            long locking = System.nanoTime();
            synchronized (LOCK) {
                LOCK_NANOS.add(System.nanoTime() - locking);
                result = p.closed ? -1 : run(p, e, sky, s, entries, n / 2);
            }
        } catch (RuntimeException | LinkageError ex) {
            ERRORS.increment();
            off = "a light batch failed on the GPU: " + ex;
            LOGGER.warn("light batch failed on the GPU ({}) -> light stays on the CPU", ex.toString());
            result = -1;
        }
        if (result < 0) {
            // the queue as it was, for the light thread's own propagation
            for (int i = 0; i < n; i++) {
                queue.enqueue(entries[i]);
            }
            return -1;
        }
        long dt = System.nanoTime() - t0;
        NANOS.add(dt);
        GPU_COST[sky ? 1 : 0].add(dt, n / 2);
        return result;
    }

    /**
     * The batch on the GPU. Returns the entries processed, or -1 when the light thread must propagate it itself. Each of its loops
     * is a method of its own. A batch runs each loop many times, and the JIT then compiles each loop once on its own, instead of
     * the whole batch again from every loop.
     */
    private static int run(LightProgram p, LightEngineAccessor e, boolean sky, Scratch s, long[] entries, int count) {
        long tPrep = System.nanoTime();
        LayerLightSectionStorageAccessor storage = (LayerLightSectionStorageAccessor) e.ga2pl$storage();
        var updating = storage.ga2pl$updatingSectionData();
        Long2ObjectOpenHashMap<DataLayer> map = ((DataLayerStorageMapAccessor) updating).ga2pl$map();
        BlockGetter level = e.ga2pl$chunkSource().getLevel();

        if (!slots(s, entries, count)) {
            LARGE.increment();
            return -1;
        }
        int slots = s.keys.size();
        long tSlots = System.nanoTime();
        PREP_PARTS[0].add(tSlots - tPrep);

        if (s.storing.length < slots) {
            int c = slots + (slots >> 1);
            s.storing = new byte[c];
            s.nbr = new int[c * 6];
            s.hazard = new int[c];
            s.changed = new int[c];
        }
        ByteBuffer snHost = p.host(LightProgram.Buf.SN, slots * 2048);
        int stored = levels(s, snHost, map, sky ? (SkyLightSectionStorageAccessor) storage : null, slots);
        snHost.limit(slots * 2048);

        long tLevels = System.nanoTime();
        PREP_PARTS[1].add(tLevels - tSlots);
        if (s.seeds.length < count * 2) {
            s.seeds = new int[count * 2 + count];
        }
        if (!seeds(s, entries, count)) {
            UNSTORED.increment();
            return -1;
        }

        long tSeeds = System.nanoTime();
        PREP_PARTS[2].add(tSeeds - tLevels);
        // the blocks of every stored section, their words straight into the upload buffer (at most 1024 per section)
        LightTypes types = s.types;
        types.reset();
        s.chunks.clear();
        ByteBuffer wordsHost = p.host(LightProgram.Buf.WORDS, Math.max(1, stored) * 8192);
        s.words = wordsHost.asLongBuffer();
        s.wordCount = 0;
        s.sinfo.clear();
        s.pal.clear();
        s.kslots.clear();
        storedBlocks(s, types, level, e, slots);
        s.chunks.clear();
        int nk = s.kslots.size();
        long tBlocks = System.nanoTime();
        PREP_PARTS[3].add(tBlocks - tSeeds);
        BLOCK_SECTIONS.add(nk);
        LightTypes.Faces faces = types.faceTables();
        PREP_PARTS[4].add(System.nanoTime() - tBlocks);

        // upload, propagate, read back
        long tGpu = System.nanoTime();
        long q = p.queue;
        var cl = p.cl;
        long words = p.upload(LightProgram.Buf.WORDS, wordsHost.limit(s.wordCount * 8));
        long sinfo = p.upload(LightProgram.Buf.SINFO, ints(p, LightProgram.Buf.SINFO, s.sinfo.elements(), s.sinfo.size()));
        long pal = p.upload(LightProgram.Buf.PAL, shorts(p, LightProgram.Buf.PAL, s.pal.elements(), s.pal.size()));
        long typesMem = p.buffer(LightProgram.Buf.TYPES, (long) slots * 4096 * 2);
        long snMem = p.upload(LightProgram.Buf.SN, snHost);
        long cur = p.buffer(LightProgram.Buf.CUR, (long) slots * 4096 * 4);
        long seeds = p.upload(LightProgram.Buf.SEEDS, ints(p, LightProgram.Buf.SEEDS, s.seeds, count * 2));
        long nbr = p.upload(LightProgram.Buf.NBR, ints(p, LightProgram.Buf.NBR, s.nbr, slots * 6));
        long storingMem = p.upload(LightProgram.Buf.STORING, bytes(p, LightProgram.Buf.STORING, s.storing, slots));
        long hazard = p.upload(LightProgram.Buf.HAZARD, ints(p, LightProgram.Buf.HAZARD, s.hazard, slots));
        long opac = p.upload(LightProgram.Buf.OPAC, bytes(p, LightProgram.Buf.OPAC, types.opacity, types.count));
        long face = p.upload(LightProgram.Buf.FACE, shorts(p, LightProgram.Buf.FACE, types.faces, types.count * 6));
        long cover = p.upload(LightProgram.Buf.COVER, bytes(p, LightProgram.Buf.COVER, faces.cover(), faces.cover().length));
        long part = p.upload(LightProgram.Buf.PART, shorts(p, LightProgram.Buf.PART, faces.part(), faces.part().length));
        long pairs = p.upload(LightProgram.Buf.PAIRS, ints(p, LightProgram.Buf.PAIRS, faces.pairs(), faces.pairs().length));
        long kslots = p.upload(LightProgram.Buf.KSLOTS, ints(p, LightProgram.Buf.KSLOTS, s.kslots.elements(), nk));
        Arrays.fill(s.changed, 0, slots, 0);
        long changed = p.upload(LightProgram.Buf.CHANGED, ints(p, LightProgram.Buf.CHANGED, s.changed, slots));
        long fail = p.upload(LightProgram.Buf.FAIL, ints(p, LightProgram.Buf.FAIL, new int[] { 0 }, 1));
        long out = p.buffer(LightProgram.Buf.OUT, (long) slots * 2048);

        int a = 0;
        cl.argMem(p.kPrepare, a++, words);
        cl.argMem(p.kPrepare, a++, sinfo);
        cl.argMem(p.kPrepare, a++, pal);
        cl.argMem(p.kPrepare, a++, typesMem);
        cl.argInt(p.kPrepare, a++, nk);
        cl.argMem(p.kPrepare, a++, fail);
        cl.argMem(p.kPrepare, a++, snMem);
        cl.argMem(p.kPrepare, a++, cur);
        cl.argInt(p.kPrepare, a, slots);
        rel(cl, cl.launch(q, p.kPrepare, (long) slots * 4096, 64));
        // The level's arguments but the level and the emission flag stay. A launch copies them when it is queued.
        a = 3;
        cl.argMem(p.kLevel, a++, seeds);
        cl.argInt(p.kLevel, a++, count);
        cl.argMem(p.kLevel, a++, kslots);
        cl.argInt(p.kLevel, a++, nk);
        stepArgs(cl, p.kLevel, a, new long[] { nbr, storingMem, snMem, cur, typesMem, opac, face, cover, part, pairs }, faces.partials(), hazard, fail);
        cl.argInt(p.kLevel, 1, sky ? 1 : 0);
        for (int lvl = 15; lvl >= 2; lvl--) {
            cl.argInt(p.kLevel, 0, lvl);
            cl.argInt(p.kLevel, 2, lvl == 15 ? 1 : 0);
            rel(cl, cl.launch(q, p.kLevel, count + (long) nk * 4096, 64));
        }
        cl.argMem(p.kFinish, 0, kslots);
        cl.argInt(p.kFinish, 1, nk);
        cl.argMem(p.kFinish, 2, snMem);
        cl.argMem(p.kFinish, 3, cur);
        cl.argMem(p.kFinish, 4, changed);
        cl.argMem(p.kFinish, 5, out);
        rel(cl, cl.launch(q, p.kFinish, (long) nk * 4096, 64));
        // The reads go after the kernels in the queue. The FAIL and CHANGED host buffers were the uploads' sources. The queue runs in
        // order, so each read lands after its upload went out. One wait for the whole batch.
        ByteBuffer failHost = p.host(LightProgram.Buf.FAIL, 4);
        ByteBuffer changedHost = p.host(LightProgram.Buf.CHANGED, slots * 4);
        ByteBuffer outHost = p.host(LightProgram.Buf.OUT, slots * 2048);
        p.download(fail, failHost.limit(4));
        p.download(changed, changedHost.limit(slots * 4));
        p.download(out, outHost.limit(slots * 2048));
        cl.finish(q);
        int failed = failHost.getInt(0);
        if (failed != 0) {
            if ((failed & 2) != 0) {
                FILL.increment();
            } else if ((failed & 8) != 0) {
                RACE.increment();
            } else {
                OUTSIDE.increment();
            }
            return -1;
        }
        changedHost.asIntBuffer().get(s.changed, 0, slots);
        if (s.out.length < slots * 2048) {
            s.out = new byte[slots * 2048 + (slots << 9)];
        }
        outHost.get(s.out, 0, slots * 2048);

        long tBack = System.nanoTime();
        GPU_NANOS.add(tBack - tGpu);
        PREP_NANOS.add(tGpu - tPrep);

        int changedCount = applyChanged(s, storage, map, slots);
        // sections were replaced in the map. Its lookup cache may still hold the old objects
        updating.clearCache();
        BATCHES.increment();
        ENTRIES.add(count);
        SECTIONS.add(slots);
        CHANGED.add(changedCount);
        BACK_NANOS.add(System.nanoTime() - tBack);
        return count;
    }

    /**
     * The sections the batch can reach, its entries' sections and their neighbours, into {@code s.keys} and {@code s.slotOf}.
     * False when they are more than {@link #MAX_SLOTS}.
     */
    private static boolean slots(Scratch s, long[] entries, int count) {
        Long2IntOpenHashMap slotOf = s.slotOf;
        LongArrayList keys = s.keys;
        slotOf.clear();
        keys.clear();
        s.seedSections.clear();
        long lastSection = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long section = SectionPos.blockToSection(entries[2 * i]);
            if (section == lastSection) {
                continue;
            }
            lastSection = section;
            if (!s.seedSections.add(section)) {
                continue;
            }
            int sx = SectionPos.x(section), sy = SectionPos.y(section), sz = SectionPos.z(section);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long k = SectionPos.asLong(sx + dx, sy + dy, sz + dz);
                        if (!slotOf.containsKey(k)) {
                            slotOf.put(k, keys.size());
                            keys.add(k);
                        }
                    }
                }
            }
            if (keys.size() > MAX_SLOTS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Per section: stored or not, the levels (straight into the upload buffer), the neighbours, and for the sky ({@code sky} not
     * null) the empty-section hazard. Returns the stored sections.
     */
    private static int levels(Scratch s, ByteBuffer snHost, Long2ObjectOpenHashMap<DataLayer> map, SkyLightSectionStorageAccessor sky, int slots) {
        Long2IntOpenHashMap slotOf = s.slotOf;
        LongArrayList keys = s.keys;
        int stored = 0;
        for (int slot = 0; slot < slots; slot++) {
            long key = keys.getLong(slot);
            DataLayer layer = map.get(key);
            s.storing[slot] = (byte) (layer != null ? 1 : 0);
            int base = slot * 2048;
            if (layer == null) {
                snHost.put(base, LEVELS[0], 0, 2048);
            } else {
                stored++;
                snHost.put(base, layer.isDefinitelyHomogenous() ? LEVELS[layer.get(0, 0, 0)] : layer.getData(), 0, 2048);
            }
            int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
            for (Direction d : DIRECTIONS) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                s.nbr[slot * 6 + d.ordinal()] = slotOf.get(SectionPos.asLong(nx, ny, nz));
            }
            s.hazard[slot] = sky != null ? hazard(map, sky, key) : 0;
        }
        return stored;
    }

    /**
     * The entries, each in a stored section of the batch (vanilla's propagation reads its stored level), into {@code s.seeds}.
     * False when one is not.
     */
    private static boolean seeds(Scratch s, long[] entries, int count) {
        Long2IntOpenHashMap slotOf = s.slotOf;
        for (int i = 0; i < count; i++) {
            long pos = entries[2 * i];
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            int slot = slotOf.get(SectionPos.blockToSection(pos));
            if (slot < 0 || s.storing[slot] == 0) {
                return false;
            }
            s.seeds[2 * i] = slot * 4096 + ((y & 15) << 8 | (z & 15) << 4 | (x & 15));
            s.seeds[2 * i + 1] = (int) (entries[2 * i + 1] & 0xFFF);
        }
        return true;
    }

    /** The blocks of every stored section ({@link #blocks}), and the stored sections' slots into {@code s.kslots}. */
    private static void storedBlocks(Scratch s, LightTypes types, BlockGetter level, LightEngineAccessor e, int slots) {
        LongArrayList keys = s.keys;
        for (int slot = 0; slot < slots; slot++) {
            if (s.storing[slot] != 0) {
                s.kslots.add(slot);
                blocks(s, types, level, e, slot, keys.getLong(slot));
            }
        }
    }

    /** The changed sections, as setStoredLevel leaves them. Returns how many changed. */
    private static int applyChanged(Scratch s, LayerLightSectionStorageAccessor storage, Long2ObjectOpenHashMap<DataLayer> map, int slots) {
        LongArrayList keys = s.keys;
        LongSet changedSections = storage.ga2pl$changedSections();
        LongSet affected = storage.ga2pl$sectionsAffectedByLightUpdates();
        int changedCount = 0;
        for (int slot = 0; slot < slots; slot++) {
            int mask = s.changed[slot];
            if (mask == 0) {
                continue;
            }
            changedCount++;
            long key = keys.getLong(slot);
            DataLayer layer;
            if (changedSections.add(key)) {
                layer = map.get(key).copy();
                map.put(key, layer);
            } else {
                layer = map.get(key);
            }
            System.arraycopy(s.out, slot * 2048, layer.getData(), 0, 2048);
            int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
            for (int b = 0; b < 27; b++) {
                if ((mask >> b & 1) != 0) {
                    affected.add(SectionPos.asLong(sx + b / 9 - 1, sy + b / 3 % 3 - 1, sz + b % 3 - 1));
                }
            }
        }
        return changedCount;
    }

    /**
     * Bits per horizontal direction: sky light leaving this section's bottom layer that way would fill the target column's stored
     * sections below the empty ones ({@code countEmptySectionsBelowIfAtBorder}, {@code propagateFromEmptySections}).
     */
    private static int hazard(Long2ObjectOpenHashMap<DataLayer> map, SkyLightSectionStorageAccessor sky, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        int j = 0;
        while (map.get(SectionPos.asLong(sx, sy - j - 1, sz)) == null && sky.ga2pl$hasLightDataAtOrBelow(sy - j - 1)) {
            j++;
        }
        if (j == 0) {
            return 0;
        }
        int bits = 0;
        for (Direction d : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            int tx = sx + d.getStepX(), tz = sz + d.getStepZ();
            for (int y = sy - 1; y >= sy - j; y--) {
                if (map.get(SectionPos.asLong(tx, y, tz)) != null) {
                    bits |= 1 << d.ordinal();
                    break;
                }
            }
        }
        return bits;
    }

    /** The block types of stored section {@code key} (slot {@code slot}) as the light engine reads its blocks. */
    private static void blocks(Scratch s, LightTypes types, BlockGetter level, LightEngineAccessor e, int slot, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        ChunkInfo c = s.chunks.get(ChunkPos.asLong(sx, sz));
        if (c == null) {
            c = chunkInfo(e.ga2pl$chunkSource().getChunkForLighting(sx, sz));
            s.chunks.put(ChunkPos.asLong(sx, sz), c);
        }
        if (c.kind == KIND_NONE) {
            single(s, slot, types.type(Blocks.BEDROCK.defaultBlockState()));
            return;
        }
        if (c.kind == KIND_OTHER) {
            LightChunk chunk = c.chunk;
            raw(s, types, level, slot, sx, sy, sz, i -> chunk.getBlockState(s.pos.set((sx << 4) + (i & 15), (sy << 4) + (i >> 8), (sz << 4) + (i >> 4 & 15))));
            return;
        }
        int index = sy - c.minSection;
        if (index < 0 || index >= c.sections.length) {
            // ProtoChunk: void air outside the build height. LevelChunk: air
            single(s, slot, types.type((c.kind == KIND_PROTO ? Blocks.VOID_AIR : Blocks.AIR).defaultBlockState()));
            return;
        }
        LevelChunkSection section = c.sections[index];
        if (section.hasOnlyAir()) {
            single(s, slot, types.type(Blocks.AIR.defaultBlockState()));
            return;
        }
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        Palette<BlockState> palette = data.palette();
        var storage = data.storage();
        boolean known = (palette instanceof LinearPalette || palette instanceof HashMapPalette || palette instanceof SingleValuePalette)
            && (storage instanceof SimpleBitStorage || storage instanceof ZeroBitStorage);
        if (known) {
            int size = palette.getSize();
            int po = s.pal.size();
            boolean dynamic = false;
            for (int id = 0; id < size && !dynamic; id++) {
                BlockState state = palette.valueFor(id);
                if (LightTypes.dynamic(state)) {
                    dynamic = true;
                } else {
                    s.pal.add((short) types.type(state));
                }
            }
            if (!dynamic) {
                int bits = storage.getBits();
                int wo = s.wordCount;
                if (bits != 0) {
                    long[] raw = storage.getRaw();
                    s.words.put(wo, raw, 0, raw.length);
                    s.wordCount += raw.length;
                }
                info(s, slot, bits, wo, po, size);
                return;
            }
            s.pal.size(po);
        }
        raw(s, types, level, slot, sx, sy, sz, i -> StateReads.get(data, i));
    }

    /** A section's entry for {@code k_prepare}: its slot, bits per block, first word, palette offset (-1: none) and palette size. */
    private static void info(Scratch s, int slot, int bits, int words, int palette, int size) {
        IntArrayList info = s.sinfo;
        info.add(slot);
        info.add(bits);
        info.add(words);
        info.add(palette);
        info.add(size);
    }

    /** A section of one block. */
    private static void single(Scratch s, int slot, int type) {
        int po = s.pal.size();
        s.pal.add((short) type);
        info(s, slot, 0, 0, po, 1);
    }

    /** A section's types block by block (16 bits each), a block whose properties may depend on its position asked there. */
    private static void raw(Scratch s, LightTypes types, BlockGetter level, int slot, int sx, int sy, int sz, IntFunction<BlockState> state) {
        RAW_SECTIONS.increment();
        int wo = s.wordCount;
        long word = 0;
        for (int i = 0; i < 4096; i++) {
            BlockState b = state.apply(i);
            int t;
            if (LightTypes.dynamic(b)) {
                t = types.typeAt(b, level, new BlockPos((sx << 4) + (i & 15), (sy << 4) + (i >> 8), (sz << 4) + (i >> 4 & 15)));
            } else {
                t = types.type(b);
            }
            word |= (long) (t & 0xFFFF) << ((i & 3) << 4);
            if ((i & 3) == 3) {
                s.words.put(s.wordCount++, word);
                word = 0;
            }
        }
        info(s, slot, 16, wo, -1, 65536);
    }

    private static ChunkInfo chunkInfo(@Nullable LightChunk chunk) {
        if (chunk == null) {
            return new ChunkInfo(null, KIND_NONE, null, 0);
        }
        Class<?> c = chunk.getClass();
        if (c == ProtoChunk.class) {
            ChunkAccess a = (ChunkAccess) chunk;
            return new ChunkInfo(chunk, KIND_PROTO, a.getSections(), a.getMinSection());
        }
        LevelChunk level = c == LevelChunk.class ? (LevelChunk) chunk : c == ImposterProtoChunk.class ? ((ImposterProtoChunk) chunk).getWrapped() : null;
        if (level != null && level.getClass() == LevelChunk.class && !level.getLevel().isDebug()) {
            return new ChunkInfo(chunk, KIND_LEVEL, level.getSections(), level.getMinSection());
        }
        return new ChunkInfo(chunk, KIND_OTHER, null, 0);
    }

    // ------------------------------------------------------------------------------------------------ OpenCL helpers

    private static int stepArgs(ClApi cl, long k, int a, long[] mems, int np, long hazard, long fail) {
        for (int i = 0; i < 10; i++) {
            cl.argMem(k, a++, mems[i]);
        }
        cl.argInt(k, a++, np);
        cl.argMem(k, a++, hazard);
        cl.argMem(k, a++, fail);
        return a;
    }

    private static void rel(ClApi cl, long event) {
        if (event != 0) {
            cl.releaseEvent(event);
        }
    }

    // The program's pinned host buffers, filled for one upload each per batch

    private static ByteBuffer bytes(LightProgram p, LightProgram.Buf buf, byte[] a, int n) {
        ByteBuffer b = p.host(buf, n);
        b.put(a, 0, n).flip();
        return b;
    }

    private static ByteBuffer ints(LightProgram p, LightProgram.Buf buf, int[] a, int n) {
        ByteBuffer b = p.host(buf, n * 4);
        b.asIntBuffer().put(a, 0, n);
        return b.limit(n * 4);
    }

    private static ByteBuffer shorts(LightProgram p, LightProgram.Buf buf, short[] a, int n) {
        ByteBuffer b = p.host(buf, n * 2);
        b.asShortBuffer().put(a, 0, n);
        return b.limit(n * 2);
    }

    // ------------------------------------------------------------------------------------------------ status

    /** Status lines for {@code /ga2 light}. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        String state = !ENABLED ? "off (-Dgeneratoraccelerator2.gpu.light=false)" : off != null ? "off (" + off + ")"
            : testDevice == null && !GpuConfig.enabled() ? "off (GPU generation off)" : !compatGate.getAsBoolean() ? "off (another mod changes the light engine)"
            : program != null ? "on" : "waiting for the GPU";
        lines.add("GPU light: " + state + ", batches of at least " + MIN_WORK + " work");
        long batches = BATCHES.sum();
        lines.add(String.format(Locale.ROOT, "  %,d batches on the GPU (%,d entries, %,d sections, %,d changed), %.2f ms each on the light thread;"
                + " back to the CPU: %,d empty-section fills, %,d palettes changed while read, %,d too large, %,d other; %,d left to the CPU by time",
            batches, ENTRIES.sum(), SECTIONS.sum(), CHANGED.sum(), batches == 0 ? 0.0 : NANOS.sum() / 1e6 / batches, FILL.sum(), RACE.sum(), LARGE.sum(),
            OUTSIDE.sum() + UNSTORED.sum() + ERRORS.sum(), CPU_CHOSEN.sum()));
        lines.add(String.format(Locale.ROOT, "  ns per queued entry (median of the last 8 batches), GPU / CPU: block light %s / %s, sky light %s / %s", cost(GPU_COST[0].median()), cost(CPU_COST[0].median()),
            cost(GPU_COST[1].median()), cost(CPU_COST[1].median())));
        lines.add(String.format(Locale.ROOT, "  per batch on the light thread: %.2f ms waiting for the lock, %.2f ms for the host arrays, %.2f ms on the GPU"
                + " (transfers and kernels), %.2f ms writing back", batches == 0 ? 0.0 : LOCK_NANOS.sum() / 1e6 / batches, batches == 0 ? 0.0 : PREP_NANOS.sum() / 1e6 / batches,
            batches == 0 ? 0.0 : GPU_NANOS.sum() / 1e6 / batches, batches == 0 ? 0.0 : BACK_NANOS.sum() / 1e6 / batches));
        double per = Math.max(1L, batches) * 1e6;
        lines.add(String.format(Locale.ROOT, "  host arrays per batch: %.3f ms the slots, %.3f ms their levels and neighbours, %.3f ms the entries, %.3f ms the blocks,"
                + " %.3f ms the face tables; %,d of %,d stored sections read block by block",
            PREP_PARTS[0].sum() / per, PREP_PARTS[1].sum() / per, PREP_PARTS[2].sum() / per, PREP_PARTS[3].sum() / per, PREP_PARTS[4].sum() / per,
            RAW_SECTIONS.sum(), BLOCK_SECTIONS.sum()));
        return lines;
    }

    /** Sums so far, for {@link #report}. */
    public static long[] snapshot() {
        return new long[] { BATCHES.sum(), ENTRIES.sum(), SECTIONS.sum(), LOCK_NANOS.sum(), PREP_NANOS.sum(), GPU_NANOS.sum(), BACK_NANOS.sum() };
    }

    /** One line: the batches on the GPU per chunk, their entries and sections, and the light threads' ms per batch in each part. */
    public static String report(long[] before, long chunks) {
        long[] now = snapshot();
        long b = now[0] - before[0];
        double per = Math.max(1L, b);
        return String.format(Locale.ROOT, "GPU light: %.2f batches per chunk, %.0f entries and %.0f sections each; per batch %.2f ms waiting for the lock,"
                + " %.2f ms for the host arrays, %.2f ms on the GPU, %.2f ms writing back",
            b / (double) Math.max(1L, chunks), (now[1] - before[1]) / per, (now[2] - before[2]) / per, (now[3] - before[3]) / 1e6 / per,
            (now[4] - before[4]) / 1e6 / per, (now[5] - before[5]) / 1e6 / per, (now[6] - before[6]) / 1e6 / per);
    }

    private static String cost(double c) {
        return c < 0 ? "-" : String.format(Locale.ROOT, "%.0f", c);
    }
}
