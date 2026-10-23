package dev.denismasterherobrine.generatoraccelerator2.gpu.light;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuConfig;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.DataLayerStorageMapAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LayerLightSectionStorageAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.LightEngineAccessor;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin.SkyLightSectionStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
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
import net.minecraft.world.level.lighting.SkyLightEngine;
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

    private static final Object LOCK = new Object();
    private static volatile LightProgram program;
    private static volatile GpuDevice building;
    private static volatile String off;

    /** Per light layer (block 0, sky 1): the light thread's nanoseconds per queued entry on the GPU and on the CPU, -1 before a sample. */
    private static final double[] GPU_COST = { -1, -1 }, CPU_COST = { -1, -1 };
    private static final int[] SINCE_PROBE = new int[2];
    private static final int PROBE_EVERY = 32;

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
        double gpu = GPU_COST[layer], cpu = CPU_COST[layer];
        if (gpu < 0) {
            return true;
        }
        if (cpu < 0) {
            CPU_CHOSEN.increment();
            return false;
        }
        boolean gpuWins = gpu <= cpu;
        if (++SINCE_PROBE[layer] >= PROBE_EVERY) {
            // the other path once in a while, so its time follows the load
            SINCE_PROBE[layer] = 0;
            gpuWins = !gpuWins;
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
            sample(CPU_COST, sky, nanos, increases);
        }
    }

    private static void sample(double[] costs, boolean sky, long nanos, int entries) {
        int layer = sky ? 1 : 0;
        double x = (double) nanos / Math.max(1, entries);
        double old = costs[layer];
        costs[layer] = old < 0 ? x : old * 0.8 + x * 0.2;
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
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet seedSections = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        /** Direct host buffers by {@link LightProgram.Buf}, kept between batches. */
        final ByteBuffer[] host = new ByteBuffer[LightProgram.Buf.values().length];
        final LongArrayList keys = new LongArrayList();
        final Long2ObjectOpenHashMap<ChunkInfo> chunks = new Long2ObjectOpenHashMap<>();
        final LongArrayList words = new LongArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList sinfo = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final it.unimi.dsi.fastutil.shorts.ShortArrayList pal = new it.unimi.dsi.fastutil.shorts.ShortArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList kslots = new it.unimi.dsi.fastutil.ints.IntArrayList();
        byte[] sn = new byte[0];
        byte[] storing = new byte[0];
        int[] nbr = new int[0];
        int[] hazard = new int[0];
        int[] seeds = new int[0];
        int[] changed = new int[0];
        byte[] out = new byte[0];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        { slotOf.defaultReturnValue(-1); }
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
            synchronized (LOCK) {
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
        sample(GPU_COST, sky, dt, n / 2);
        return result;
    }

    /** The batch on the GPU. Returns the entries processed, or -1 when the light thread must propagate it itself. */
    private static int run(LightProgram p, LightEngineAccessor e, boolean sky, Scratch s, long[] entries, int count) {
        LayerLightSectionStorageAccessor storage = (LayerLightSectionStorageAccessor) e.ga2pl$storage();
        var updating = storage.ga2pl$updatingSectionData();
        Long2ObjectOpenHashMap<DataLayer> map = ((DataLayerStorageMapAccessor) updating).ga2pl$map();
        BlockGetter level = e.ga2pl$chunkSource().getLevel();

        // the sections the batch can reach: its entries' sections and their neighbours
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
                LARGE.increment();
                return -1;
            }
        }
        int slots = keys.size();

        // per section: stored or not, the levels, the neighbours, and for the sky the empty-section hazard
        if (s.sn.length < slots * 2048) {
            s.sn = new byte[slots * 2048 + (slots << 9)];
        }
        if (s.storing.length < slots) {
            int c = slots + (slots >> 1);
            s.storing = new byte[c];
            s.nbr = new int[c * 6];
            s.hazard = new int[c];
            s.changed = new int[c];
        }
        byte[] sn = s.sn;
        for (int slot = 0; slot < slots; slot++) {
            long key = keys.getLong(slot);
            DataLayer layer = map.get(key);
            s.storing[slot] = (byte) (layer != null ? 1 : 0);
            int base = slot * 2048;
            if (layer == null) {
                java.util.Arrays.fill(sn, base, base + 2048, (byte) 0);
            } else if (layer.isDefinitelyHomogenous()) {
                int v = layer.get(0, 0, 0);
                java.util.Arrays.fill(sn, base, base + 2048, (byte) (v | v << 4));
            } else {
                System.arraycopy(layer.getData(), 0, sn, base, 2048);
            }
            for (Direction d : Direction.values()) {
                s.nbr[slot * 6 + d.ordinal()] = slotOf.get(SectionPos.offset(key, d));
            }
            s.hazard[slot] = sky ? hazard(map, (SkyLightSectionStorageAccessor) storage, key) : 0;
        }

        // the entries, each in a stored section of the batch (vanilla's propagation reads its stored level)
        if (s.seeds.length < count * 2) {
            s.seeds = new int[count * 2 + count];
        }
        for (int i = 0; i < count; i++) {
            long pos = entries[2 * i];
            int slot = slotOf.get(SectionPos.blockToSection(pos));
            if (slot < 0 || s.storing[slot] == 0) {
                UNSTORED.increment();
                return -1;
            }
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            s.seeds[2 * i] = slot * 4096 + ((y & 15) << 8 | (z & 15) << 4 | (x & 15));
            s.seeds[2 * i + 1] = (int) (entries[2 * i + 1] & 0xFFF);
        }

        // the blocks of every stored section
        LightTypes types = s.types;
        types.reset();
        s.chunks.clear();
        s.words.clear();
        s.sinfo.clear();
        s.pal.clear();
        s.kslots.clear();
        for (int slot = 0; slot < slots; slot++) {
            if (s.storing[slot] != 0) {
                s.kslots.add(slot);
                blocks(s, types, level, e, slot, keys.getLong(slot));
            }
        }
        s.chunks.clear();
        int nk = s.kslots.size();
        LightTypes.Faces faces = types.faceTables();

        // upload, propagate, read back
        long q = p.queue;
        var cl = p.cl;
        long words = p.upload(LightProgram.Buf.WORDS, longs(s, LightProgram.Buf.WORDS, s.words));
        long sinfo = p.upload(LightProgram.Buf.SINFO, ints(s, LightProgram.Buf.SINFO, s.sinfo.elements(), s.sinfo.size()));
        long pal = p.upload(LightProgram.Buf.PAL, shorts(s, LightProgram.Buf.PAL, s.pal.elements(), s.pal.size()));
        long typesMem = p.buffer(LightProgram.Buf.TYPES, (long) slots * 4096 * 2);
        long snMem = p.upload(LightProgram.Buf.SN, bytes(s, LightProgram.Buf.SN, sn, slots * 2048));
        long cur = p.buffer(LightProgram.Buf.CUR, (long) slots * 4096 * 4);
        long seeds = p.upload(LightProgram.Buf.SEEDS, ints(s, LightProgram.Buf.SEEDS, s.seeds, count * 2));
        long nbr = p.upload(LightProgram.Buf.NBR, ints(s, LightProgram.Buf.NBR, s.nbr, slots * 6));
        long storingMem = p.upload(LightProgram.Buf.STORING, bytes(s, LightProgram.Buf.STORING, s.storing, slots));
        long hazard = p.upload(LightProgram.Buf.HAZARD, ints(s, LightProgram.Buf.HAZARD, s.hazard, slots));
        long opac = p.upload(LightProgram.Buf.OPAC, bytes(s, LightProgram.Buf.OPAC, types.opacity, types.count));
        long face = p.upload(LightProgram.Buf.FACE, shorts(s, LightProgram.Buf.FACE, types.faces, types.count * 6));
        long cover = p.upload(LightProgram.Buf.COVER, bytes(s, LightProgram.Buf.COVER, faces.cover(), faces.cover().length));
        long part = p.upload(LightProgram.Buf.PART, shorts(s, LightProgram.Buf.PART, faces.part(), faces.part().length));
        long pairs = p.upload(LightProgram.Buf.PAIRS, ints(s, LightProgram.Buf.PAIRS, faces.pairs(), faces.pairs().length));
        long kslots = p.upload(LightProgram.Buf.KSLOTS, ints(s, LightProgram.Buf.KSLOTS, s.kslots.elements(), nk));
        java.util.Arrays.fill(s.changed, 0, slots, 0);
        long changed = p.upload(LightProgram.Buf.CHANGED, ints(s, LightProgram.Buf.CHANGED, s.changed, slots));
        long fail = p.upload(LightProgram.Buf.FAIL, ints(s, LightProgram.Buf.FAIL, new int[] { 0 }, 1));
        long out = p.buffer(LightProgram.Buf.OUT, (long) slots * 2048);

        int a = 0;
        cl.argMem(p.kUnpack, a++, words);
        cl.argMem(p.kUnpack, a++, sinfo);
        cl.argMem(p.kUnpack, a++, pal);
        cl.argMem(p.kUnpack, a++, typesMem);
        cl.argInt(p.kUnpack, a++, nk);
        cl.argMem(p.kUnpack, a, fail);
        rel(cl, cl.launch(q, p.kUnpack, (long) nk * 4096, 64));
        cl.argMem(p.kInit, 0, snMem);
        cl.argMem(p.kInit, 1, cur);
        cl.argInt(p.kInit, 2, slots);
        rel(cl, cl.launch(q, p.kInit, (long) slots * 4096, 64));
        cl.argMem(p.kEmit, 0, seeds);
        cl.argInt(p.kEmit, 1, count);
        cl.argMem(p.kEmit, 2, snMem);
        cl.argMem(p.kEmit, 3, cur);
        rel(cl, cl.launch(q, p.kEmit, count, 64));
        long[] stepArgs = { nbr, storingMem, snMem, cur, typesMem, opac, face, cover, part, pairs };
        for (int lvl = 15; lvl >= 2; lvl--) {
            a = 0;
            cl.argInt(p.kSeeds, a++, lvl);
            cl.argInt(p.kSeeds, a++, sky ? 1 : 0);
            cl.argMem(p.kSeeds, a++, seeds);
            cl.argInt(p.kSeeds, a++, count);
            a = stepArgs(cl, p.kSeeds, a, stepArgs, faces.partials(), hazard, fail);
            rel(cl, cl.launch(q, p.kSeeds, count, 64));
            a = 0;
            cl.argInt(p.kRound, a++, lvl);
            cl.argInt(p.kRound, a++, sky ? 1 : 0);
            cl.argMem(p.kRound, a++, kslots);
            cl.argInt(p.kRound, a++, nk);
            stepArgs(cl, p.kRound, a, stepArgs, faces.partials(), hazard, fail);
            rel(cl, cl.launch(q, p.kRound, (long) nk * 4096, 64));
        }
        cl.argMem(p.kChanged, 0, kslots);
        cl.argInt(p.kChanged, 1, nk);
        cl.argMem(p.kChanged, 2, snMem);
        cl.argMem(p.kChanged, 3, cur);
        cl.argMem(p.kChanged, 4, changed);
        rel(cl, cl.launch(q, p.kChanged, (long) nk * 4096, 64));
        cl.argMem(p.kPack, 0, kslots);
        cl.argInt(p.kPack, 1, nk);
        cl.argMem(p.kPack, 2, cur);
        cl.argMem(p.kPack, 3, out);
        rel(cl, cl.launch(q, p.kPack, (long) nk * 2048, 64));
        ByteBuffer failHost = host(s, LightProgram.Buf.FAIL, 4);
        ByteBuffer changedHost = host(s, LightProgram.Buf.CHANGED, slots * 4);
        rel(cl, cl.read(q, fail, true, 0, failHost.limit(4)));
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
        rel(cl, cl.read(q, changed, true, 0, changedHost.limit(slots * 4)));
        changedHost.asIntBuffer().get(s.changed, 0, slots);
        if (s.out.length < slots * 2048) {
            s.out = new byte[slots * 2048 + (slots << 9)];
        }
        ByteBuffer outHost = host(s, LightProgram.Buf.OUT, slots * 2048);
        rel(cl, cl.read(q, out, true, 0, outHost.limit(slots * 2048)));
        outHost.get(s.out, 0, slots * 2048);

        // the changed sections, as setStoredLevel leaves them
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
        // sections were replaced in the map. Its lookup cache may still hold the old objects
        updating.clearCache();
        BATCHES.increment();
        ENTRIES.add(count);
        SECTIONS.add(slots);
        CHANGED.add(changedCount);
        return count;
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
                int wo = s.words.size();
                if (bits != 0) {
                    long[] raw = storage.getRaw();
                    s.words.addElements(wo, raw, 0, raw.length);
                }
                s.sinfo.addElements(s.sinfo.size(), new int[] { slot, bits, wo, po, size });
                return;
            }
            s.pal.size(po);
        }
        raw(s, types, level, slot, sx, sy, sz, i -> dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(data, i));
    }

    /** A section of one block. */
    private static void single(Scratch s, int slot, int type) {
        int po = s.pal.size();
        s.pal.add((short) type);
        s.sinfo.addElements(s.sinfo.size(), new int[] { slot, 0, 0, po, 1 });
    }

    /** A section's types block by block (16 bits each), a block whose properties may depend on its position asked there. */
    private static void raw(Scratch s, LightTypes types, BlockGetter level, int slot, int sx, int sy, int sz, java.util.function.IntFunction<BlockState> state) {
        int wo = s.words.size();
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
                s.words.add(word);
                word = 0;
            }
        }
        s.sinfo.addElements(s.sinfo.size(), new int[] { slot, 16, wo, -1, 65536 });
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

    private static int stepArgs(dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi cl, long k, int a, long[] mems, int np, long hazard, long fail) {
        for (int i = 0; i < 10; i++) {
            cl.argMem(k, a++, mems[i]);
        }
        cl.argInt(k, a++, np);
        cl.argMem(k, a++, hazard);
        cl.argMem(k, a++, fail);
        return a;
    }

    private static void rel(dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi cl, long event) {
        if (event != 0) {
            cl.releaseEvent(event);
        }
    }

    /** This thread's host buffer {@code b} with room for {@code bytes}, cleared. */
    private static ByteBuffer host(Scratch s, LightProgram.Buf b, int bytes) {
        ByteBuffer h = s.host[b.ordinal()];
        if (h == null || h.capacity() < bytes) {
            h = LightProgram.direct(Math.max(bytes, h == null ? 0 : h.capacity() + (h.capacity() >> 1)));
            s.host[b.ordinal()] = h;
        }
        h.clear();
        return h;
    }

    private static ByteBuffer bytes(Scratch s, LightProgram.Buf buf, byte[] a, int n) {
        ByteBuffer b = host(s, buf, n);
        b.put(a, 0, n).flip();
        return b;
    }

    private static ByteBuffer ints(Scratch s, LightProgram.Buf buf, int[] a, int n) {
        ByteBuffer b = host(s, buf, n * 4);
        b.asIntBuffer().put(a, 0, n);
        return b.limit(n * 4);
    }

    private static ByteBuffer shorts(Scratch s, LightProgram.Buf buf, short[] a, int n) {
        ByteBuffer b = host(s, buf, n * 2);
        b.asShortBuffer().put(a, 0, n);
        return b.limit(n * 2);
    }

    private static ByteBuffer longs(Scratch s, LightProgram.Buf buf, LongArrayList list) {
        int n = list.size();
        ByteBuffer b = host(s, buf, n * 8);
        b.asLongBuffer().put(list.elements(), 0, n);
        return b.limit(n * 8);
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
        lines.add(String.format(java.util.Locale.ROOT, "  %,d batches on the GPU (%,d entries, %,d sections, %,d changed), %.2f ms each on the light thread;"
                + " back to the CPU: %,d empty-section fills, %,d palettes changed while read, %,d too large, %,d other; %,d left to the CPU by time",
            batches, ENTRIES.sum(), SECTIONS.sum(), CHANGED.sum(), batches == 0 ? 0.0 : NANOS.sum() / 1e6 / batches, FILL.sum(), RACE.sum(), LARGE.sum(),
            OUTSIDE.sum() + UNSTORED.sum() + ERRORS.sum(), CPU_CHOSEN.sum()));
        lines.add(String.format(java.util.Locale.ROOT, "  ns per queued entry, GPU / CPU: block light %s / %s, sky light %s / %s", cost(GPU_COST[0]), cost(CPU_COST[0]),
            cost(GPU_COST[1]), cost(CPU_COST[1])));
        return lines;
    }

    private static String cost(double c) {
        return c < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.0f", c);
    }
}
