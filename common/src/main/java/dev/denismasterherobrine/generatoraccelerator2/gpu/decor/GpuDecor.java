package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * The GPU's copy of chunks, from their surface to their neighbours' decoration (option {@code -Dgeneratoraccelerator2.gpu.decoration=true}).
 * A chunk whose surface the GPU built keeps its blocks on the GPU ({@link Resident}). Every later write into the chunk (carvers,
 * decoration) is logged ({@link DeltaLog}) and sent when the step that made it ends. So a step that starts later finds the GPU's copy
 * as the chunk is. That copy is what the GPU's decoration reads.
 */
public final class GpuDecor {
    private static final Logger LOGGER = Ga2Log.get("GPU");
    public static final boolean ENABLED = Boolean.getBoolean("generatoraccelerator2.gpu.decoration");
    /** {@code -Dgeneratoraccelerator2.verify.gpuResident=N}: every N-th chunk's GPU copy is read back at its FEATURES step and compared. */
    private static final int VERIFY = Integer.getInteger("generatoraccelerator2.verify.gpuResident", 0);
    private static final java.util.concurrent.atomic.AtomicLong SEEN = new java.util.concurrent.atomic.AtomicLong();
    public static final LongAdder TRACKED = new LongAdder();
    public static final LongAdder DELTAS = new LongAdder();
    public static final LongAdder VERIFIED = new LongAdder();
    public static final LongAdder DIFFERING = new LongAdder();

    public final StateIds ids;
    final int minY, height;
    /** The features the GPU places ({@link DecorSpec}), or null with the reason in {@link #specOff}. */
    private volatile DecorSpec spec;
    private DecorBatches batches;
    public volatile String specOff = "not set up";
    private final ConcurrentLinkedQueue<Object> incoming = new ConcurrentLinkedQueue<>();
    private final Runnable wake;
    /** GPU thread. */
    public Resident resident;
    /** Jobs asked whose decoration has not ended, by chunk ({@link DecorJob#overlayFrom}). */
    private final java.util.concurrent.ConcurrentHashMap<Long, DecorJob> pendingJobs = new java.util.concurrent.ConcurrentHashMap<>();

    void pending(DecorJob job) {
        DecorJob old = pendingJobs.put(Resident.key(job.pos), job);
        if (old != null && old != job) {
            old.finish();
        }
        if ((job.seq & 255) == 0) {
            // a chunk unloaded before its decoration never ends it. Its job, with the chunks the job holds, goes after a minute.
            long stale = System.nanoTime() - 60_000_000_000L;
            pendingJobs.values().removeIf(j -> {
                if (j.asked - stale < 0) {
                    j.finish();
                    return true;
                }
                return false;
            });
        }
    }

    DecorJob pendingJob(int x, int z) {
        return pendingJobs.get(ChunkPos.asLong(x, z) * 0x9E3779B97F4A7C15L);
    }

    /** Called when the chunk's decoration ended or the chunk went away. Its job is no longer one to speculate on. */
    private static void finished(ChunkAccess chunk, DecorJob taken) {
        if (taken != null) {
            taken.decor.pendingJobs.remove(Resident.key(taken.pos), taken);
            taken.finish();
        }
        GpuDecor d = of(chunk);
        DecorJob j = d == null ? null : d.pendingJobs.remove(Resident.key(chunk.getPos()));
        if (j != null) {
            j.finish();
        }
    }

    private record Drop(ChunkPos pos) {}

    private record Readback(ChunkPos pos, CompletableFuture<short[]> future) {}

    public GpuDecor(List<BlockState> surfaceStates, int minY, int height, Runnable wake) {
        this.ids = new StateIds(surfaceStates);
        this.minY = minY;
        this.height = height;
        this.wake = wake;
    }

    /**
     * Setup, once the program is built: the features the GPU can place, the angle tables and their check against this JVM, the batches.
     * Leaves {@link #spec} null with the reason when any part fails.
     */
    public void prepare(ClApi cl, long context, long queue, long[] decorQueues, long hostQueue, long program, net.minecraft.world.level.chunk.ChunkGenerator generator,
                        List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes, long surfaceTable) {
        if (generator == null) {
            specOff = "no generator";
            return;
        }
        try {
            DecorSpec spec = DecorSpec.build(generator, biomes, ids, minY, height);
            if (spec == null) {
                specOff = "no feature the GPU can place";
                return;
            }
            long t0 = System.nanoTime();
            SinCos sc = SinCos.build();
            String bad = checkSinCos(cl, context, queue, program, sc, "k_sincos_test", k -> (double) SinCos.angle(k));
            SinCos fancy = bad != null ? null : SinCos.buildFancy();
            if (fancy != null) {
                bad = checkSinCos(cl, context, queue, program, fancy, "k_fsincos_test", SinCos::fancyAngle);
            }
            if (bad != null) {
                specOff = bad;
                return;
            }
            batches = new DecorBatches(cl, context, queue, decorQueues, hostQueue, program, resident, spec, sc, fancy, surfaceTable);
            batches.beforeGather = this::sendPending;
            preload();
            this.spec = spec;
            specOff = null;
            LOGGER.info("decoration: {} features on GPU ({}; angle tables {} ms)", spec.features.size(), spec.kindStatus(),
                (System.nanoTime() - t0) / 1_000_000);
            for (String s : spec.skipped) {
                LOGGER.info("decoration: CPU-only: {}", s);
            }
        } catch (RuntimeException e) {
            specOff = "setup failed: " + e;
            LOGGER.warn("decoration setup failed", e);
        }
    }

    /**
     * Loads the classes that worker flushes and decorations use. Otherwise the first worker to need a class would load it under the
     * class loader's lock (NeoForge's transforming loader is slow), while the other workers wait on that lock.
     */
    private static void preload() {
        String[] names = { GpuDecor.class.getName() + "$Sections", GpuDecor.class.getName() + "$Drop", GpuDecor.class.getName() + "$Readback",
            GpuDecor.class.getName() + "$Writes", GpuDecor.class.getName() + "$FlushScratch", DecorJob.class.getName(), DecorJob.class.getName() + "$Dirty",
            DecorJob.Result.class.getName(), DeltaLog.class.getName(), Preds.Unknown.class.getName() };
        ClassLoader loader = GpuDecor.class.getClassLoader();
        for (String n : names) {
            try {
                Class.forName(n, true, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                LOGGER.debug("decoration: preload {} failed", n, e);
            }
        }
        // the collections' inner classes the repairs, the speculation and the checks iterate with
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap m = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        m.put(1, 1);
        for (var it = m.keySet().iterator(); it.hasNext(); ) {
            it.nextInt();
        }
        for (var e : m.int2IntEntrySet()) {
            e.getIntKey();
        }
        m.containsValue(1);
        m.remove(1);
        new it.unimi.dsi.fastutil.ints.IntArrayList().add(1);
        CompletableFuture.allOf(CompletableFuture.completedFuture(null)).completeOnTimeout(null, 1, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The GPU's corrected angles against {@code Math.sin}/{@code Math.cos} for a spread of draws and the edges. Null when they agree. */
    private static String checkSinCos(ClApi cl, long context, long queue, long program, SinCos sc, String kernel, java.util.function.IntToDoubleFunction angle) {
        int n = 65536 + 8;
        int[] ks = new int[n];
        for (int i = 0; i < 65536; i++) {
            ks[i] = (int) ((long) i * (SinCos.N - 1) / 65535);
        }
        int[] edge = { 1, 2, SinCos.N / 4, SinCos.N / 4 + 1, SinCos.N / 2, SinCos.N / 2 - 1, SinCos.N * 3 / 4, SinCos.N - 2 };
        System.arraycopy(edge, 0, ks, 65536, 8);
        long k = cl.createKernel(program, kernel);
        long bs = cl.createBuffer(context, ClApi.MEM_READ_ONLY, DecorBatches.ints(sc.sinFix));
        long bc = cl.createBuffer(context, ClApi.MEM_READ_ONLY, DecorBatches.ints(sc.cosFix));
        long bk = cl.createBuffer(context, ClApi.MEM_READ_ONLY, DecorBatches.ints(ks));
        long out = cl.createBuffer(context, ClApi.MEM_WRITE_ONLY, (long) n * 16);
        try {
            cl.argMem(k, 0, bs);
            cl.argMem(k, 1, bc);
            cl.argMem(k, 2, bk);
            cl.argInt(k, 3, n);
            cl.argMem(k, 4, out);
            cl.releaseEvent(cl.launch(queue, k, n, 64));
            ByteBuffer b = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder());
            cl.releaseEvent(cl.read(queue, out, true, 0, b));
            var d = b.asDoubleBuffer();
            int wrong = 0;
            for (int i = 0; i < n; i++) {
                double x = angle.applyAsDouble(ks[i]);
                double gs = d.get(i * 2), gc = d.get(i * 2 + 1);
                if (Double.isNaN(gs)) {
                    continue; // an angle the table leaves to the CPU
                }
                if (Double.doubleToRawLongBits(gs) != Double.doubleToRawLongBits(Math.sin(x)) || Double.doubleToRawLongBits(gc) != Double.doubleToRawLongBits(Math.cos(x))) {
                    if (wrong++ == 0) {
                        LOGGER.warn("angle {} ({}): sin GPU {} CPU {}, cos GPU {} CPU {}", ks[i], kernel, gs, Math.sin(x), gc, Math.cos(x));
                    }
                }
            }
            return wrong == 0 ? null : wrong + " of " + n + " angles (" + kernel + ") differ from the CPU's on this GPU";
        } finally {
            cl.releaseKernel(k);
            for (long m : new long[] { bs, bc, bk, out }) {
                cl.releaseMem(m);
            }
        }
    }

    /** Worker milliseconds spent on the GPU's decoration, and on ore features in all. */
    public static String workerStatus() {
        return String.format(java.util.Locale.ROOT, "workers: ore features %.0f ms (commits %.0f, CPU re-placing %.0f), binds %.0f, flushes %.0f (" + FLUSH_LAT.describe() + ")",
            dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline.ORE_NS.sum() / 1e6, DecorJob.WRITE_NS.sum() / 1e6,
            DecorJob.RERUN_NS.sum() / 1e6, DecorJob.BIND_NS.sum() / 1e6, FLUSH_NS.sum() / 1e6);
    }

    /** GPU thread milliseconds: the write pump, the last scatter, the ore batches. */
    public static String threadStatus() {
        return String.format(java.util.Locale.ROOT, "GPU thread: writes %.0f ms, scatter %.0f ms, ore batches %.0f ms, surface batches %.0f ms", PUMP_NS.sum() / 1e6, SCATTER_NS.sum() / 1e6, ORE_NS.sum() / 1e6,
            dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen.SURFACE_PUMP_NS.sum() / 1e6);
    }

    /** Where the GPU thread's time on decoration batches goes, and the uploads. */
    public static String batchTimeStatus() {
        return DecorBatches.timeStatus() + String.format(java.util.Locale.ROOT, "; %,d uploads, %,d pending entries died", UPLOADS.sum(), DIED.sum());
    }

    /** The batches' line for the status. */
    public static String batchStatus() {
        long b = DecorBatches.BATCHES.sum();
        return String.format(java.util.Locale.ROOT, "%,d batches of %.1f chunks in %s (kernel %s), %,d without their chunks' copies; asked after %s, answered after %s", b,
            b == 0 ? 0.0 : (double) DecorBatches.JOBS.sum() / b, DecorBatches.BATCH_LAT.describe(), DecorBatches.KERNEL.describe(), DecorBatches.NO_SLOT.sum(),
            DecorJob.QUEUED.describe(), DecorJob.ANSWERED.describe());
    }

    /**
     * Per GPU feature: written / not used. The not-used reasons are a read block changed before the decoration, a read block the
     * decoration changed, and a heightmap or light.
     */
    public String featureStatus() {
        DecorSpec s = spec;
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.features.size(); i++) {
            long used = DecorJob.PER.get(i * 4), changed = DecorJob.PER.get(i * 4 + 1) + DecorJob.PER.get(i * 4 + 2);
            if (used + changed == 0) {
                continue;
            }
            String name = s.features.get(i).feature().unwrapKey().map(k -> k.location().getPath()).orElse("#" + i);
            b.append(String.format(java.util.Locale.ROOT, " %s %d/%d", name, used, changed));
            if (changed > 0) {
                b.append(String.format(java.util.Locale.ROOT, "(%d,%d,%d)", DecorJob.PER.get(i * 4 + 1), DecorJob.PER.get(i * 4 + 2), DecorJob.PER.get(i * 4 + 3)));
            }
        }
        return b.toString();
    }

    /** The kernel's phases per job (built with -DOD_PROF and profiled), or "". */
    public static String phaseStatus() {
        long n = DecorBatches.PROFILED.sum();
        if (n == 0 || DecorBatches.PHASES.get(15) == 0) {
            return "";
        }
        String[] names = { "walk", "start", "gate", "spheres", "cull", "boxes", "A", "B", "draws", "C" };
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "per chunk %.2f ms:", DecorBatches.PHASES.get(15) / 1e6 / n));
        for (int i = 0; i < names.length; i++) {
            b.append(String.format(java.util.Locale.ROOT, " %s %.2f", names[i], DecorBatches.PHASES.get(i) / 1e6 / n));
        }
        b.append(String.format(java.util.Locale.ROOT, "; %.0f placements, %.0f placed, %.0f cells, %.1f features",
            (double) DecorBatches.PHASES.get(10) / n, (double) DecorBatches.PHASES.get(11) / n, (double) DecorBatches.PHASES.get(12) / n, (double) DecorBatches.PHASES.get(13) / n));
        return b.toString();
    }

    /** FEATURES steps that waited for the GPU's answer before taking their locks. */
    public static String holdStatus() {
        return String.format(java.util.Locale.ROOT, "decorations held for the GPU's answer: %,d (%,d let go after %d ms), held %s", HELD.sum(), HELD_TIMEOUT.sum(),
            HOLD_MS, HOLD.describe());
    }

    /** The decoration kernels' source (DecorKernel is package-private). */
    public static String kernelSource(int height, int minY, int windowShorts, long slotShorts, int voidAir) {
        return DecorKernel.source(height, minY, windowShorts, slotShorts, voidAir);
    }

    /** The features the GPU places, or null. */
    public DecorSpec spec() {
        return spec;
    }

    /** Hands a job to the GPU thread. The writes so far into the job's chunks are already queued before it. */
    void submit(DecorJob job) {
        incoming.add(job);
        wake.run();
    }

    /** Called when the device is going away. Everything in flight completes as left to the CPU. */
    public void close() {
        if (batches != null) {
            batches.close();
            batches = null;
        }
        Object o;
        while ((o = incoming.poll()) != null) {
            if (o instanceof DecorJob j) {
                j.future.complete(null);
            } else if (o instanceof Readback rb) {
                rb.future.complete(null);
            }
        }
        resident.close();
    }

    // ---- CPU side (worker threads) ---------------------------------------------------------------------------------------------

    /** Called when the chunk's surface came from the GPU. Its blocks are on the GPU from now on, and its writes are logged. */
    public void track(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (gc == null) {
            return;
        }
        DeltaLog log = new DeltaLog(chunk.getPos(), ids, this);
        gc.ga2$setDeltaLog(log);
        LevelChunkSection[] secs = chunk.getSections();
        for (int i = 0; i < secs.length; i++) {
            ((Ga2DeltaSection) secs[i]).ga2$track(log, i);
        }
        TRACKED.increment();
    }

    /** Forgets the chunk's GPU copy, which does not hold the chunk's blocks because its surface was built on the CPU. */
    public void drop(ChunkPos pos) {
        incoming.add(new Drop(pos));
        wake.run();
    }

    /** Worker time in flushes (deduplicating and queueing the writes). */
    public static final LongAdder FLUSH_NS = new LongAdder();
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat FLUSH_LAT = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();

    /** Called when a step that wrote into the chunk ended. Sends what the step wrote. */
    public void flush(ChunkAccess chunk) {
        if (flushQuiet(chunk)) {
            wake.run();
        }
    }

    /** {@link #flush} without waking the GPU thread. True when something was queued. The caller then wakes the thread. */
    private boolean flushQuiet(ChunkAccess chunk) {
        long t0 = System.nanoTime();
        try {
            return flushTimed(chunk);
        } finally {
            long d = System.nanoTime() - t0;
            FLUSH_NS.add(d);
            FLUSH_LAT.add(d);
        }
    }

    private boolean flushTimed(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
        if (log == null) {
            return false;
        }
        if (log.closed) {
            gc.ga2$setDeltaLog(null);
            untrackSections(chunk);
            drop(chunk.getPos());
            return false;
        }
        log.publish();
        int to = log.size(), from = log.handedOut();
        if (to > from) {
            Writes w = writes(chunk, log, from, to);
            if (w == null) {
                // a state past the ids the GPU can hold
                log.closed = true;
                gc.ga2$setDeltaLog(null);
                untrackSections(chunk);
                drop(chunk.getPos());
                return false;
            }
            if (w.whole != null) {
                DELTAS.add(w.whole.head.length / 5);
            }
            BLOCKS_SENT.add(w.blocks.length / 2);
            incoming.add(w);
            log.handOut(to);
            log.sentIndex = to;
            return true;
        }
        log.sentIndex = to;
        return false;
    }

    /** Blocks written into tracked chunks sent one by one, and sections sent whole. */
    public static final LongAdder BLOCKS_SENT = new LongAdder();
    /** A step's entries in one section from which the whole section is sent. Its packed storage is smaller than that many blocks. */
    private static final int WHOLE_MIN = 512;

    /** What a step wrote into a chunk: sections sent whole (or null), and the other blocks as (index yi << 8 | z << 4 | x, state id). */
    private record Writes(ChunkPos pos, Sections whole, int[] blocks) {}

    /** A worker's scratch for {@link #writes}: entries per section, the blocks already taken, the blocks. */
    private static final class FlushScratch {
        int[] perSection = new int[0];
        long[] seen = new long[0];
        int[] blocks = new int[2048];
    }

    private static final ThreadLocal<FlushScratch> FLUSH = ThreadLocal.withInitial(FlushScratch::new);

    /**
     * The log's entries {@code from} to {@code to} in the form the GPU's copy takes. A section with many entries goes as its current
     * contents. The rest go block by block, with each block's newest state. Null when a state has no id.
     */
    private Writes writes(ChunkAccess chunk, DeltaLog log, int from, int to) {
        FlushScratch f = FLUSH.get();
        int ns = chunk.getSections().length;
        if (f.perSection.length < ns) {
            f.perSection = new int[ns];
        }
        int words = 256 * height >> 6;
        if (f.seen.length < words) {
            f.seen = new long[words];
        }
        int[] per = f.perSection;
        int[] where = log.whereArray();
        for (int i = from; i < to; i++) {
            per[(where[i] & ~DeltaLog.EXPECTED) >>> 12]++;
        }
        long[] whole = null;
        for (int s = 0; s < ns; s++) {
            if (per[s] >= WHOLE_MIN) {
                if (whole == null) {
                    whole = new long[(ns + 63) >> 6];
                }
                whole[s >> 6] |= 1L << s;
            }
        }
        // newest first, so a block's first entry seen is its current state
        if (f.blocks.length < (to - from) * 2) {
            f.blocks = new int[Math.max((to - from) * 2, f.blocks.length * 2)];
        }
        int[] out = f.blocks;
        long[] seen = f.seen;
        int n = 0;
        for (int i = to - 1; i >= from; i--) {
            int w = where[i] & ~DeltaLog.EXPECTED;
            if (per[w >>> 12] >= WHOLE_MIN) {
                continue;
            }
            int k = w >>> 6;
            long m = 1L << w;
            if ((seen[k] & m) == 0) {
                seen[k] |= m;
                out[n * 2] = w;
                out[n * 2 + 1] = log.state(i);
                n++;
            }
        }
        for (int j = 0; j < n; j++) {
            seen[out[j * 2] >>> 6] = 0L;
        }
        java.util.Arrays.fill(per, 0, ns, 0);
        Sections s = null;
        if (whole != null) {
            s = snapshot(chunk, whole);
            if (s == null) {
                return null;
            }
        }
        return new Writes(chunk.getPos(), s, java.util.Arrays.copyOf(out, n * 2));
    }

    /** A chunk's sections as stored after a step wrote them: per section (index, bits, palette offset, storage offset, mode). */
    private record Sections(ChunkPos pos, int[] head, int[] pal, long[] raw) {}

    /**
     * The sections in {@code mask}, as their containers hold them now. The step has ended, and nothing else writes into the chunk until
     * the next step. Each section is its palette as state ids plus the packed storage, or all 4096 ids for a section on the global
     * palette. Null when a state has no id.
     */
    private Sections snapshot(ChunkAccess chunk, long[] mask) {
        LevelChunkSection[] secs = chunk.getSections();
        // first the sizes, then every array once at its size
        int n = 0, palLen = 0, rawLen = 0;
        for (int w = 0; w < mask.length; w++) {
            for (long bits = mask[w]; bits != 0L; bits &= bits - 1) {
                int i = w * 64 + Long.numberOfTrailingZeros(bits);
                if (i < secs.length) {
                    var data = secs[i].getStates().data;
                    n++;
                    if (data.palette() instanceof net.minecraft.world.level.chunk.GlobalPalette) {
                        palLen += 4096;
                    } else {
                        palLen += data.palette().getSize();
                        rawLen += data.storage().getRaw().length;
                    }
                }
            }
        }
        int[] head = new int[n * 5], pal = new int[palLen];
        long[] raw = new long[rawLen];
        int h = 0, pa = 0, ra = 0;
        for (int w = 0; w < mask.length; w++) {
            for (long bits = mask[w]; bits != 0L; bits &= bits - 1) {
                int i = w * 64 + Long.numberOfTrailingZeros(bits);
                if (i >= secs.length) {
                    continue;
                }
                var data = secs[i].getStates().data;
                net.minecraft.util.BitStorage st = data.storage();
                net.minecraft.world.level.chunk.Palette<BlockState> p = data.palette();
                int b = st.getBits(), mode = 0;
                head[h] = i;
                head[h + 1] = b;
                head[h + 2] = pa;
                head[h + 3] = ra;
                if (p instanceof net.minecraft.world.level.chunk.GlobalPalette) {
                    mode = 1;
                    int[] vals = new int[4096];
                    st.unpack(vals);
                    for (int v : vals) {
                        int id = ids.id(p.valueFor(v));
                        if (id < 0) {
                            return null;
                        }
                        pal[pa++] = id;
                    }
                } else {
                    for (int k = 0, size = p.getSize(); k < size; k++) {
                        int id = ids.id(p.valueFor(k));
                        if (id < 0) {
                            return null;
                        }
                        pal[pa++] = id;
                    }
                    long[] r = st.getRaw();
                    System.arraycopy(r, 0, raw, ra, r.length);
                    ra += r.length;
                }
                head[h + 4] = mode;
                h += 5;
            }
        }
        return new Sections(chunk.getPos(), head, pal, raw);
    }

    /** Called when the chunk's step ended. Sends its writes through the chunk's own decor (no-op for an untracked chunk). */
    public static void flushChunk(ChunkAccess chunk) {
        if (!ENABLED) {
            return;
        }
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
        if (log != null) {
            log.owner.flush(chunk);
        }
    }

    /** The job the decoration on this thread took ({@link #beforeFeatures}), until {@link #afterFeatures}. */
    private static final ThreadLocal<DecorJob> TAKEN = new ThreadLocal<>();

    /** Called when the chunk's decoration ended. Sends what it wrote and lets the decorations that speculated on it run. */
    public static void afterFeatures(WorldGenLevel level, ChunkAccess center) {
        flushAround(level, center);
        DecorJob taken = TAKEN.get();
        TAKEN.remove();
        finished(center, taken);
    }

    /** Called when a FEATURES step ended around {@code center}. Sends the writes of every tracked chunk. */
    public static void flushAround(WorldGenLevel level, ChunkAccess center) {
        if (!ENABLED) {
            return;
        }
        ChunkPos c = center.getPos();
        GpuDecor woke = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkAccess chunk = dx == 0 && dz == 0 ? center : level.getChunk(c.x + dx, c.z + dz);
                Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
                DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
                if (log != null && log.owner.flushQuiet(chunk)) {
                    woke = log.owner;
                }
            }
        }
        // one wake for the nine chunks (an unpark is a system call)
        if (woke != null) {
            woke.wake.run();
        }
    }

    /** The decor of a tracked chunk, or null. */
    public static GpuDecor of(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
        return log == null ? null : log.owner;
    }

    /**
     * Called when a chunk's decoration starts. Runs the residency check ({@code -Dgeneratoraccelerator2.verify.gpuResident}). Returns
     * the GPU features requested when the step was scheduled ({@link #prefetch}), bound to the decoration's region, or null.
     */
    public static DecorJob beforeFeatures(ChunkAccess chunk, WorldGenLevel region, net.minecraft.world.level.chunk.ChunkGenerator generator) {
        GpuDecor d = of(chunk);
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        DecorJob job = gc == null ? null : gc.ga2$decorJob();
        if (gc != null && job != null) {
            gc.ga2$setDecorJob(null);
        }
        TAKEN.set(job);
        if (d == null) {
            return null;
        }
        d.verify(chunk);
        if (job == null && !PREFETCH && d.spec != null) {
            job = DecorJob.submit(d, (x, z) -> region.getChunk(x, z), region.getSeed(), chunk, generator);
        } else if (job != null && !job.ready()) {
            DecorJob.NOT_READY.increment();
            job = null;
        }
        return job != null && job.bind(region) ? job : null;
    }

    /**
     * {@code -Dgeneratoraccelerator2.gpu.orePrefetch=false}: ask when the decoration runs (and wait for the answer) instead of while it waits
     * for its locks.
     */
    private static final boolean PREFETCH = !"false".equals(System.getProperty("generatoraccelerator2.gpu.orePrefetch"));
    /** Builds the jobs requested while decorations wait. Two threads suffice, since a job takes a fraction of a millisecond. */
    private static final java.util.concurrent.ExecutorService ASKER = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "GA2 GPU ore asks");
        t.setDaemon(true);
        // a job requested late is a job not ready. So this runs ahead of the worldgen pool (it takes little CPU).
        t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
        return t;
    });

    /**
     * {@code -Dgeneratoraccelerator2.gpu.decorHoldMs}: how long a FEATURES step waits for the GPU's answer before its lock request goes
     * ahead without it. With 0, the step does not wait, and the GPU is asked only while the step waits for its locks.
     */
    private static final long HOLD_MS = Long.getLong("generatoraccelerator2.gpu.decorHoldMs", 250L);
    private static final java.util.concurrent.ScheduledExecutorService TIMER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GA2 GPU decoration timer");
        t.setDaemon(true);
        return t;
    });
    public static final LongAdder HELD = new LongAdder(), HELD_TIMEOUT = new LongAdder();
    /** Time from scheduling a FEATURES step to releasing its lock request (answered, timed out or nothing asked). */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat HOLD = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();

    /** Whether the FEATURES step of {@code chunk} should ask the GPU first and hold its lock request until the answer ({@link #ask}). */
    public static boolean wantsAsk(ChunkAccess chunk) {
        if (!ENABLED || !PREFETCH || HOLD_MS <= 0) {
            return false;
        }
        GpuDecor d = of(chunk);
        return d != null && d.spec != null;
    }

    /**
     * Called when the FEATURES step is scheduled (worldgen mailbox). Asks the GPU, on another thread, for the chunk's features against
     * the chunks as the GPU has them at that time. Runs {@code answered} once, on any thread: when the answer is in, when
     * {@link #HOLD_MS} has passed, or at once when there is nothing to ask. Returns false when nothing was asked. {@code answered} does
     * not run then. What neighbours decorate into the chunks meanwhile is in their write logs, which the decoration checks against.
     */
    public static boolean ask(net.minecraft.world.level.chunk.status.WorldGenContext ctx, net.minecraft.world.level.chunk.status.ChunkStep step,
                              net.minecraft.util.StaticCache2D<net.minecraft.server.level.GenerationChunkHolder> cache, ChunkAccess chunk, Runnable answered) {
        GpuDecor d = of(chunk);
        if (d == null || d.spec == null) {
            return false;
        }
        HELD.increment();
        long scheduled = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (once.compareAndSet(false, true)) {
                HOLD.since(scheduled);
                answered.run();
            }
        };
        ChunkPos c = chunk.getPos();
        long seed = ctx.level().getSeed();
        net.minecraft.world.level.chunk.ChunkGenerator generator = ctx.generator();
        ChunkAccess[] around = around(step, cache, chunk);
        Runnable build = () -> ASKER.execute(() -> {
            DecorJob job = null;
            try {
                DecorJob.QUEUED.since(scheduled);
                job = DecorJob.submit(d, (x, z) -> around[(z - c.z + 1) * 3 + x - c.x + 1], seed, chunk, generator, SPECULATE);
                Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
                if (job != null && gc != null) {
                    gc.ga2$setDecorJob(job);
                }
            } finally {
                if (job == null) {
                    release.run();
                } else {
                    DecorJob j = job;
                    job.future.whenComplete((r, e) -> afterAnswer(j, r, release, once));
                    TIMER.schedule(() -> {
                        if (!j.future.isDone()) {
                            HELD_TIMEOUT.increment();
                            release.run();
                        }
                    }, HOLD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
        });
        // the jobs around that are already on the GPU answer first (for a while at most), so this job is placed over their results
        CompletableFuture<?>[] before = SPECULATE && DEFER_MS > 0 ? d.unanswered(c) : null;
        if (before != null) {
            DEFERRED.increment();
            CompletableFuture.allOf(before).completeOnTimeout(null, DEFER_MS, java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((v, e) -> build.run());
        } else {
            build.run();
        }
        return true;
    }

    /** {@code -Dgeneratoraccelerator2.gpu.speculateWaitMs}: how long a job waits for the answers of the jobs around it on the GPU before it is asked. */
    private static final long DEFER_MS = Long.getLong("generatoraccelerator2.gpu.speculateWaitMs", 250L);
    public static final LongAdder DEFERRED = new LongAdder();

    /** The answers still to come of the jobs within two chunks whose decorations are still to run, or null when there are none. */
    private CompletableFuture<?>[] unanswered(ChunkPos c) {
        java.util.ArrayList<CompletableFuture<?>> fs = null;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                DecorJob p = (dx | dz) == 0 ? null : pendingJob(c.x + dx, c.z + dz);
                if (p != null && !p.finished && !p.future.isDone()) {
                    if (fs == null) {
                        fs = new java.util.ArrayList<>();
                    }
                    fs.add(p.future);
                }
            }
        }
        return fs == null ? null : fs.toArray(new CompletableFuture<?>[0]);
    }

    /**
     * {@code -Dgeneratoraccelerator2.gpu.speculate}: a job requested while the decorations around it wait is placed over their GPU
     * results. Its decoration then waits for those decorations, at most {@link #DEP_HOLD_MS} after its answer. So the blocks the job
     * read are mostly what its decoration finds.
     */
    private static final boolean SPECULATE = !"false".equals(System.getProperty("generatoraccelerator2.gpu.speculate"));
    private static final long DEP_HOLD_MS = Long.getLong("generatoraccelerator2.gpu.speculateHoldMs", 1500L);
    public static final LongAdder DEP_HELD = new LongAdder(), DEP_TIMEOUT = new LongAdder();
    /** Time from a speculating job's answer until the decorations it speculated on ended. */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat DEP_WAIT = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();

    /** Called when the job's answer is in. Its lock request goes ahead now, or once the decorations it speculated on have ended. */
    private static void afterAnswer(DecorJob job, DecorJob.Result r, Runnable release, java.util.concurrent.atomic.AtomicBoolean once) {
        CompletableFuture<?>[] fs = job.deps;
        if (r == null || fs == null) {
            release.run();
            return;
        }
        DEP_HELD.increment();
        long t0 = System.nanoTime();
        CompletableFuture.allOf(fs).whenComplete((v, e) -> {
            DEP_WAIT.since(t0);
            release.run();
        });
        TIMER.schedule(() -> {
            if (!once.get()) {
                DEP_TIMEOUT.increment();
            }
            release.run();
        }, DEP_HOLD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The speculation's line for the status. */
    public static String speculationStatus() {
        return String.format(java.util.Locale.ROOT, "speculation: %,d jobs over the GPU results of decorations before them (%,d blocks, %,d found otherwise; "
            + "%,d asked after the answers around them); held for those decorations %,d (%,d let go after %d ms), waited %s", DecorJob.OVERLAID.sum(),
            DecorJob.OVERLAY_BLOCKS.sum(), DecorJob.OVERLAY_STALE.sum(), DEFERRED.sum(), DEP_HELD.sum(), DEP_TIMEOUT.sum(), DEP_HOLD_MS, DEP_WAIT.describe());
    }

    /** The 3x3 chunks of a FEATURES step, from the holders' cache, which belongs to this step. Indexed (dz + 1) * 3 + dx + 1. */
    private static ChunkAccess[] around(net.minecraft.world.level.chunk.status.ChunkStep step,
                                        net.minecraft.util.StaticCache2D<net.minecraft.server.level.GenerationChunkHolder> cache, ChunkAccess chunk) {
        ChunkPos c = chunk.getPos();
        ChunkAccess[] around = new ChunkAccess[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                around[(dz + 1) * 3 + dx + 1] = dx == 0 && dz == 0 ? chunk
                    : cache.get(c.x + dx, c.z + dz).getChunkIfPresentUnchecked(step.directDependencies().get(1));
            }
        }
        return around;
    }

    /**
     * Called while the FEATURES step waits for its locks (worldgen mailbox). Asks the GPU, on a worker, for the chunk's features against
     * the chunks as the GPU has them at that time. What neighbours decorate into those chunks meanwhile is in their write logs, which the
     * decoration checks against.
     */
    public static void prefetch(net.minecraft.world.level.chunk.status.WorldGenContext ctx, net.minecraft.world.level.chunk.status.ChunkStep step,
                                net.minecraft.util.StaticCache2D<net.minecraft.server.level.GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (!ENABLED || !PREFETCH) {
            return;
        }
        GpuDecor d = of(chunk);
        if (d == null || d.spec == null) {
            return;
        }
        ChunkPos c = chunk.getPos();
        long seed = ctx.level().getSeed();
        net.minecraft.world.level.chunk.ChunkGenerator generator = ctx.generator();
        ChunkAccess[] around = around(step, cache, chunk);
        long scheduled = System.nanoTime();
        ASKER.execute(() -> {
            DecorJob.QUEUED.since(scheduled);
            DecorJob job = DecorJob.submit(d, (x, z) -> around[(z - c.z + 1) * 3 + x - c.x + 1], seed, chunk, generator);
            Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
            if (job != null && gc != null) {
                gc.ga2$setDecorJob(job);
            }
        });
    }

    /** Called when the chunk is becoming a full chunk. Its writes are no longer logged. The GPU's copy is let go when its slot is needed. */
    public static void untrack(ChunkAccess proto, LevelChunkSection[] sections) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(proto);
        DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
        if (log != null) {
            log.closed = true;
            gc.ga2$setDeltaLog(null);
            log.owner.drop(proto.getPos());
        }
        if (gc != null) {
            DecorJob job = gc.ga2$decorJob();
            gc.ga2$setDecorJob(null);
            if (job != null) {
                job.decor.pendingJobs.remove(Resident.key(job.pos), job);
                job.finish();
            }
        }
        for (LevelChunkSection s : sections) {
            ((Ga2DeltaSection) s).ga2$track(null, 0);
        }
    }

    private static void untrackSections(ChunkAccess chunk) {
        for (LevelChunkSection s : chunk.getSections()) {
            ((Ga2DeltaSection) s).ga2$track(null, 0);
        }
    }

    /**
     * {@code -Dgeneratoraccelerator2.verify.gpuResident=N} at a chunk's FEATURES step: compares the GPU's copy against the chunk's blocks.
     * Every write before the step is sent first.
     */
    public void verify(ChunkAccess chunk) {
        if (VERIFY <= 0 || SEEN.incrementAndGet() % VERIFY != 0) {
            return;
        }
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (gc == null || gc.ga2$deltaLog() == null) {
            return;
        }
        flush(chunk);
        CompletableFuture<short[]> f = new CompletableFuture<>();
        incoming.add(new Readback(chunk.getPos(), f));
        wake.run();
        short[] gpu;
        try {
            gpu = f.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("chunk {} copy: readback failed", chunk.getPos(), e);
            return;
        }
        if (gpu == null) {
            return; // no slot any more
        }
        VERIFIED.increment();
        LevelChunkSection[] secs = chunk.getSections();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int yy = 0; yy < height; yy++) {
                    BlockState cpu = secs[yy >> 4].getBlockState(lx, yy & 15, lz);
                    int id = gpu[(lx * 16 + lz) * height + yy] & 0xFFFF;
                    BlockState g = id < ids.size() ? ids.state(id) : null;
                    if (g != cpu) {
                        DIFFERING.increment();
                        LOGGER.error("chunk {} copy mismatch at {},{},{}: CPU {}, GPU {}", chunk.getPos(), lx, minY + yy, lz, cpu, g);
                        return;
                    }
                }
            }
        }
    }

    // ---- GPU thread --------------------------------------------------------------------------------------------------------------

    /** GPU thread time: applying writes and drops (deltas), and the ore batches. */
    public static final LongAdder PUMP_NS = new LongAdder(), ORE_NS = new LongAdder(), SCATTER_NS = new LongAdder();

    /** Sections not yet sent to the GPU (GPU thread): headers (Resident.HEAD ints each, slot first), palettes, storages. */
    private int[] head = new int[Resident.HEAD * 256];
    private int pending, palLen, rawLen;
    private int[] pal = new int[4096];
    private long[] raw = new long[8192];
    /**
     * Blocks not yet sent (GPU thread), as (slot << 16 | state, index) pairs. An entry that a newer write of the same block replaced is
     * dead ({@link #DEAD}, skipped). So one upload writes each block once.
     */
    private int[] blocks = new int[8192];
    private int nblocks;
    private static final int DEAD = -1;
    /**
     * Per slot: {@link #gen} while something of it is pending, the ranges of its pending blocks (start, end each) and its pending
     * sections' headers.
     */
    private int[] pendingGen = new int[0];
    private int[][] ranges = new int[0][];
    private int[] nranges = new int[0];
    private int[][] heads = new int[0][];
    private int[] nheads = new int[0];
    /** Uploads sent, and pending entries a newer write, a drop or an eviction made dead before they were sent. */
    public static final LongAdder UPLOADS = new LongAdder(), DIED = new LongAdder();
    private int gen = 1;
    /** A chunk's newer blocks, as bits (cleared after each use). */
    private long[] newer = new long[0];
    /** Pending sections or blocks past these are sent without waiting for a reader. */
    private static final int PENDING_MAX = 4096, BLOCKS_MAX = 1 << 20;

    /** Sends what is pending (GPU thread). Called before the copies are read, or before a slot is given to another chunk. */
    public void sendPending() {
        if (pending > 0 || nblocks > 0) {
            long t = System.nanoTime();
            resident.upload(pending, head, pal, palLen, raw, rawLen, blocks, nblocks);
            SCATTER_NS.add(System.nanoTime() - t);
            UPLOADS.increment();
            pending = 0;
            palLen = 0;
            rawLen = 0;
            nblocks = 0;
            gen++;
        }
    }

    private boolean pendingFor(int slot) {
        return slot < pendingGen.length && pendingGen[slot] == gen;
    }

    private void markPending(int slot) {
        if (slot >= pendingGen.length) {
            int cap = Math.max(resident.capacity, slot + 1);
            pendingGen = java.util.Arrays.copyOf(pendingGen, cap);
            ranges = java.util.Arrays.copyOf(ranges, cap);
            nranges = java.util.Arrays.copyOf(nranges, cap);
            heads = java.util.Arrays.copyOf(heads, cap);
            nheads = java.util.Arrays.copyOf(nheads, cap);
        }
        if (pendingGen[slot] != gen) {
            pendingGen[slot] = gen;
            nranges[slot] = 0;
            nheads[slot] = 0;
        }
    }

    /**
     * Kills the slot's pending entries: all of them, or those in one section when {@code section >= 0}. Called when the slot's chunk
     * went, the slot is another chunk's now, or a newer write of the section replaces the entries.
     */
    private void kill(int slot, int section) {
        if (!pendingFor(slot)) {
            return;
        }
        int[] r = ranges[slot];
        for (int k = 0; k < nranges[slot]; k++) {
            for (int e = r[k * 2]; e < r[k * 2 + 1]; e++) {
                if (blocks[e * 2] != DEAD && (section < 0 || blocks[e * 2 + 1] >>> 12 == section)) {
                    blocks[e * 2] = DEAD;
                    DIED.increment();
                }
            }
        }
        int[] h = heads[slot];
        for (int k = 0; k < nheads[slot]; k++) {
            int at = h[k] * Resident.HEAD;
            if (head[at] >= 0 && (section < 0 || head[at + 1] == section)) {
                head[at] = -1;
                DIED.increment();
            }
        }
    }

    /** Called when the slot goes to another chunk (GPU thread, {@code Resident.take}). What is pending for the old chunk never lands. */
    public void evicted(int slot) {
        kill(slot, -1);
    }

    /**
     * Queues what a step wrote for the next upload. Sections go before blocks in an upload, so a whole section kills the chunk's
     * pending writes in that section. The chunk's new blocks take the place of its pending blocks at the same position.
     */
    private void queue(Writes w) {
        int slot = resident.slot(w.pos);
        if (slot < 0) {
            return;
        }
        if (w.whole != null) {
            if (pending + w.whole.head.length / 5 > PENDING_MAX) {
                sendPending();
            }
            // a section sent whole is newer than everything pending in it. The slot's pending writes there die, since an upload
            // writes sections first.
            for (int k = 0; k < w.whole.head.length; k += 5) {
                kill(slot, w.whole.head[k]);
            }
            markPending(slot);
            queueSections(slot, w.whole);
        }
        int[] b = w.blocks;
        int n = b.length / 2;
        if (n > 0) {
            if (nblocks + n > BLOCKS_MAX) {
                sendPending();
            }
            if ((nblocks + n) * 2 > blocks.length) {
                blocks = java.util.Arrays.copyOf(blocks, Math.max((nblocks + n) * 2, blocks.length * 2));
            }
            if (pendingFor(slot) && nranges[slot] > 0) {
                // this chunk has blocks pending. The pending blocks that these replace are dead.
                int words = 256 * height >> 6;
                if (newer.length < words) {
                    newer = new long[words];
                }
                for (int i = 0; i < n; i++) {
                    int idx = b[i * 2];
                    newer[idx >>> 6] |= 1L << idx;
                }
                int[] r = ranges[slot];
                for (int k = 0; k < nranges[slot]; k++) {
                    for (int e = r[k * 2]; e < r[k * 2 + 1]; e++) {
                        int idx = blocks[e * 2 + 1];
                        if (blocks[e * 2] != DEAD && (newer[idx >>> 6] & 1L << idx) != 0) {
                            blocks[e * 2] = DEAD;
                        }
                    }
                }
                for (int i = 0; i < n; i++) {
                    newer[b[i * 2] >>> 6] = 0L;
                }
            }
            markPending(slot);
            int start = nblocks;
            for (int i = 0; i < n; i++) {
                blocks[nblocks * 2] = slot << 16 | b[i * 2 + 1];
                blocks[nblocks * 2 + 1] = b[i * 2];
                nblocks++;
            }
            int[] r = ranges[slot];
            int k = nranges[slot];
            if (r == null || r.length < (k + 1) * 2) {
                r = ranges[slot] = r == null ? new int[8] : java.util.Arrays.copyOf(r, r.length * 2);
            }
            r[k * 2] = start;
            r[k * 2 + 1] = nblocks;
            nranges[slot] = k + 1;
        }
        markPending(slot);
    }

    /** Queues a chunk's sections for the next upload. */
    private void queueSections(int slot, Sections s) {
        int n = s.head.length / 5;
        if ((pending + n) * Resident.HEAD > head.length) {
            head = java.util.Arrays.copyOf(head, Math.max((pending + n) * Resident.HEAD, head.length * 2));
        }
        if (palLen + s.pal.length > pal.length) {
            pal = java.util.Arrays.copyOf(pal, Math.max(palLen + s.pal.length, pal.length * 2));
        }
        if (rawLen + s.raw.length > raw.length) {
            raw = java.util.Arrays.copyOf(raw, Math.max(rawLen + s.raw.length, raw.length * 2));
        }
        int[] hs = heads[slot];
        if (hs == null || hs.length < nheads[slot] + n) {
            hs = heads[slot] = hs == null ? new int[Math.max(8, n)] : java.util.Arrays.copyOf(hs, Math.max(hs.length * 2, nheads[slot] + n));
        }
        for (int k = 0; k < n; k++) {
            hs[nheads[slot]++] = pending + k;
            int h = (pending + k) * Resident.HEAD;
            head[h] = slot;
            head[h + 1] = s.head[k * 5];
            head[h + 2] = s.head[k * 5 + 1];
            head[h + 3] = palLen + s.head[k * 5 + 2];
            head[h + 4] = rawLen + s.head[k * 5 + 3];
            head[h + 5] = s.head[k * 5 + 4];
        }
        System.arraycopy(s.pal, 0, pal, palLen, s.pal.length);
        System.arraycopy(s.raw, 0, raw, rawLen, s.raw.length);
        pending += n;
        palLen += s.pal.length;
        rawLen += s.raw.length;
    }

    /** Applies what arrived: drops, writes (one scatter per pump), readbacks after them. */
    public void pump(ClApi cl, long queue) {
        long t0 = System.nanoTime();
        Object o;
        while ((o = incoming.poll()) != null) {
            if (o instanceof Writes w) {
                queue(w);
            } else if (o instanceof Drop dr) {
                // the chunk's pending writes die. The slot may be another chunk's before the next upload.
                int slot = resident.slot(dr.pos);
                if (slot >= 0) {
                    kill(slot, -1);
                }
                resident.drop(dr.pos);
            } else if (o instanceof DecorJob job) {
                // the writes queued before the job are sent when its batch gathers the copies of its chunks
                if (batches == null) {
                    job.future.complete(null);
                } else {
                    batches.add(job);
                }
            } else if (o instanceof Readback rb) {
                sendPending();
                int slot = resident.slot(rb.pos);
                if (slot < 0) {
                    rb.future.complete(null);
                    continue;
                }
                ByteBuffer b = ByteBuffer.allocateDirect((int) resident.slotShorts * 2).order(ByteOrder.nativeOrder());
                long ev = resident.read(slot, b);
                cl.waitFor(ev);
                cl.releaseEvent(ev);
                short[] out = new short[(int) resident.slotShorts];
                b.asShortBuffer().get(out);
                rb.future.complete(out);
            }
        }
        long t2 = System.nanoTime();
        if (batches != null) {
            batches.pump();
        }
        long t3 = System.nanoTime();
        PUMP_NS.add(t2 - t0);
        ORE_NS.add(t3 - t2);
    }
}
