package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.ChunkGeneratorFeaturesAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.WorldgenRandomAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.Xoroshiro128Accessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.XoroshiroSourceAccessor;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.slf4j.Logger;

/**
 * The GPU-placed features ({@link DecorSpec}) of one chunk. The job is requested when the chunk's FEATURES step is scheduled. The
 * GPU places the features in vanilla's order on its copy of the 3x3 chunks as they are at that time ({@link Resident}). Each feature
 * starts from the random seed vanilla gives it. The decoration then runs as usual. At each of these features
 * ({@code PlacementPipeline.place}), the GPU's result is used when what it read still yields the same decisions. The writes of
 * everything placed on the CPU in between (structures, other features) are in the chunks' write logs ({@link DeltaLog}). A GPU result
 * that is not used marks its writes as changed for the results after it. A used result replays its effects (block writes, scheduled
 * ticks) in vanilla's order and leaves the random in the state vanilla's placement would.
 *
 * <p>Probation: the first uses of every GPU feature ({@code -Dgeneratoraccelerator2.gpu.probation}, 64) and a sample after that
 * ({@code -Dgeneratoraccelerator2.verify.gpuDecor}, 1 in 256) are also placed on the CPU and compared write by write. The CPU's
 * result is kept. A feature that ever differs is placed on the CPU from then on.
 */
public final class DecorJob {
    private static final Logger LOGGER = Ga2Log.get("GPU");
    /** The decoration running on this thread with a GPU job. */
    public static final ThreadLocal<DecorJob> CURRENT = new ThreadLocal<>();
    /** {@code -Dgeneratoraccelerator2.gpu.oreWaitMs}: how long a decoration waits for its GPU result before placing on the CPU. */
    private static final long WAIT_MS = Long.getLong("generatoraccelerator2.gpu.oreWaitMs", 20L);
    /** Uses of each GPU feature compared with the CPU before its results are used. */
    private static final int PROBATION = Integer.getInteger("generatoraccelerator2.gpu.probation", 64);
    /** {@code -Dgeneratoraccelerator2.verify.gpuDecor=N}: after probation, every N-th usable result is compared too (0: none). */
    private static final int VERIFY = Integer.getInteger("generatoraccelerator2.verify.gpuDecor", Integer.getInteger("generatoraccelerator2.verify.gpuOres", 256));
    private static final java.util.concurrent.atomic.AtomicLong VERIFY_SEEN = new java.util.concurrent.atomic.AtomicLong();

    public static final LongAdder JOBS = new LongAdder();
    public static final LongAdder USED = new LongAdder();
    public static final LongAdder CHANGED = new LongAdder();
    public static final LongAdder GPU_CPU = new LongAdder();
    public static final LongAdder LATE = new LongAdder();
    /** Decorations whose GPU answer, requested while they waited, had not arrived yet. These were placed on the CPU. */
    public static final LongAdder NOT_READY = new LongAdder();
    public static final LongAdder SEED = new LongAdder();
    public static final LongAdder VERIFIED = new LongAdder();
    public static final LongAdder DIFFER = new LongAdder();
    public static final LongAdder REJECTED = new LongAdder();
    public static final LongAdder WRITES = new LongAdder();
    /** Worker time: committing GPU results, placing rejected features again on the CPU (with the reconcile), binding. */
    public static final LongAdder WRITE_NS = new LongAdder(), RERUN_NS = new LongAdder(), BIND_NS = new LongAdder();
    /**
     * Four counters per GPU feature slot: results written, results that read a block changed before the decoration ran, results that
     * read a block the decoration changed, and results that read a heightmap or light value that now reads differently.
     */
    public static final java.util.concurrent.atomic.AtomicLongArray PER = new java.util.concurrent.atomic.AtomicLongArray(4096 * 4);
    /** Ore results written with some blocks decided again on the CPU, and the count of those blocks (changed since the GPU's copy). */
    public static final LongAdder REPAIRED = new LongAdder(), REPAIRED_BLOCKS = new LongAdder();
    private static final java.util.concurrent.atomic.AtomicLong SAMPLE = new java.util.concurrent.atomic.AtomicLong();
    /** Jobs that left a feature out because its GPU results kept reading changed blocks. */
    public static final LongAdder WASTEFUL = new LongAdder();

    /**
     * Whether the feature's GPU results mostly read blocks changed since the copy. An example is trees at chunk borders, where the
     * neighbours' decorations land. Such a result is placed on the CPU anyway, so the GPU's work and the extra job length are wasted.
     * Every 16th job still requests the feature, so the counts follow the terrain.
     */
    private static boolean wasteful(int slot) {
        int s = Math.min(slot, 4095) * 4;
        long used = PER.get(s), changed = PER.get(s + 1) + PER.get(s + 2) + PER.get(s + 3);
        if (used + changed >= 64 && changed > used) {
            WASTEFUL.increment();
            return true;
        }
        int c = Math.min(slot, 4095) * 2;
        long cn = COST.get(c + 1), gn = GPU_COST.get(c + 1);
        if (cn >= 16 && gn >= 32 && GPU_COST.get(c) * cn >= COST.get(c) * gn) {
            // the feature's GPU results cost the workers (checks, writes, re-placings) as much as placing the feature on the CPU
            UNPROFITABLE.increment();
            return true;
        }
        return false;
    }

    /**
     * Per slot: total nanoseconds and count. {@code COST} covers CPU placements (re-placed, or left out of the job). {@code GPU_COST}
     * covers uses of the GPU path.
     */
    static final java.util.concurrent.atomic.AtomicLongArray COST = new java.util.concurrent.atomic.AtomicLongArray(4096 * 2),
        GPU_COST = new java.util.concurrent.atomic.AtomicLongArray(4096 * 2);
    public static final LongAdder UNPROFITABLE = new LongAdder();

    /** Records a CPU placement of a feature the GPU can place (left out of the job, or re-placed). */
    static void cpuCost(int slot, long ns) {
        int c = Math.min(slot, 4095) * 2;
        COST.addAndGet(c, ns);
        COST.incrementAndGet(c + 1);
    }

    /** Records the cost of a CPU placement of {@code placed} outside the job ({@code PlacementPipeline}), for the cost comparison. */
    public void placedOnCpu(PlacedFeature placed, PlacementContext ctx, long ns) {
        if (ctx.topFeature().orElse(null) != placed) {
            return; // a placement nested in another feature's placement. Not the placed feature's own cost.
        }
        int slot = spec.slot(placed);
        if (slot >= 0) {
            cpuCost(slot, ns);
        }
    }

    /** The GPU path's cost for one use (checks, then the commit or the re-placing). */
    private static Boolean gpuCost(int slot, long t0, Boolean placed) {
        int c = Math.min(slot, 4095) * 2;
        GPU_COST.addAndGet(c, System.nanoTime() - t0);
        GPU_COST.incrementAndGet(c + 1);
        return placed;
    }

    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat READY = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();
    /** Time from the request to the arrival of the GPU's answer. */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat ANSWERED = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();
    /** Time from scheduling the decoration's step to requesting its job. */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat QUEUED = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();
    /** Cost of a request on the requesting thread. */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat SUBMIT = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();

    final GpuDecor decor;
    final DecorSpec spec;
    /** The decoration's region, once it runs ({@link #bind}). */
    private WorldGenLevel region;
    public final ChunkPos pos;
    final ChunkAccess[] chunks = new ChunkAccess[9];
    final DeltaLog[] logs = new DeltaLog[9];
    private final int[] scanned = new int[9];
    /** GPU feature slots in vanilla's order and their starting random states. */
    final int[] slots;
    final long[] lo, hi;
    private final IdentityHashMap<PlacedFeature, Integer> index = new IdentityHashMap<>();
    /** Per column of the 3x3 chunks: WORLD_SURFACE_WG then OCEAN_FLOOR_WG (WorldGenRegion.getHeight). */
    final int[] heights = new int[9 * 256 * 2];
    final CompletableFuture<Result> future = new CompletableFuture<>();
    final long asked = System.nanoTime();
    /** Build order. A job speculates only on older jobs. */
    final long seq = SEQ.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    /**
     * The GPU results of the neighbouring decorations that run before this one, as the job's copy has them over the chunks' copies
     * ({@link #overlayFrom}). Stored as (where, state id) pairs, one per block, or null. The job's lock request waits for those
     * decorations.
     */
    int[] overlay;
    /**
     * The ends ({@link #decorated}) of those decorations. Only the futures are kept, so a job does not retain the older jobs with
     * their 3x3 chunks and write logs. Retaining them would also retain, through their own deps, a chain of every job before.
     */
    CompletableFuture<?>[] deps;
    /** Completed when the chunk's decoration ended or the chunk went away. Jobs speculating on this one may then run. */
    final CompletableFuture<Void> decorated = new CompletableFuture<>();
    volatile boolean finished;
    /** Maximum overlay entries per job. Past this, the job speculates on nothing. */
    static final int MAXO = 16384;
    public static final LongAdder OVERLAID = new LongAdder(), OVERLAY_BLOCKS = new LongAdder(), OVERLAY_STALE = new LongAdder();
    private Result result;
    private boolean failed;
    private int next;
    /**
     * Blocks of the 3x3 chunks changed since the GPU's copy. Each chunk has {@link #cw} words, with bit {@code yi << 8 | z << 4 | x}
     * (the same index as a log entry). A block is marked in {@code stale} when written before the decoration, and in {@code own} when
     * written by the decoration. Per decoration thread.
     */
    private long[] stale, own;
    /** Per chunk and section ({@code ci * sections + section}): whether any block there is marked in {@code stale} or {@code own}. */
    private boolean[] secs;
    private int cw, ns;
    private Dirty marks;
    /**
     * Ore repairs ({@link #oreConflicts}). Maps a position (as an effect encodes it) to the state id the CPU decides now, or -1 for
     * no write. Per thread.
     */
    private it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap fix;
    /** The repaired ore's placed answer. */
    private boolean fixPlaced;

    /** Changed-block bits of a decoration thread. Cleared for each written chunk when the decoration ends. */
    private static final class Dirty {
        final long[] stale, own;
        final boolean[] secs;
        final int cw;
        final boolean[] used = new boolean[9];
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap fix = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

        Dirty(int cw) {
            this.cw = cw;
            stale = new long[9 * cw];
            own = new long[9 * cw];
            secs = new boolean[9 * (cw >> 6)];
            fix.defaultReturnValue(Integer.MIN_VALUE);
        }

        void clear() {
            for (int ci = 0; ci < 9; ci++) {
                if (used[ci]) {
                    Arrays.fill(stale, ci * cw, (ci + 1) * cw, 0L);
                    Arrays.fill(own, ci * cw, (ci + 1) * cw, 0L);
                    used[ci] = false;
                }
            }
            java.util.Arrays.fill(secs, false);
            fix.clear();
        }
    }

    private static final ThreadLocal<Dirty> BITS = new ThreadLocal<>();

    private static final ThreadLocal<it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap> GPU_WRITES = ThreadLocal.withInitial(() -> {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap m = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        m.defaultReturnValue(-1);
        return m;
    });

    /** Per spec, per top-level feature: uses compared so far (probation), and whether a difference took it off the GPU. */
    private static AtomicIntegerArray tried(DecorSpec s) {
        return s.tried();
    }

    private static java.util.concurrent.atomic.AtomicReferenceArray<String> off(DecorSpec s) {
        return s.off();
    }

    /** Why GPU results were not used (summed over features), and the ores repaired, for the status. */
    public static String conflictStatus() {
        long[] w = new long[4];
        for (int i = 0; i < 4096; i++) {
            for (int k = 1; k < 4; k++) {
                w[k] += PER.get(i * 4 + k);
            }
        }
        return String.format(java.util.Locale.ROOT, "read changed: %,d blocks changed before the decoration, %,d by it, %,d heightmaps or light; "
            + "%,d ores repaired (%,d blocks decided again); %,d left out as no cheaper on the GPU", w[1], w[2], w[3], REPAIRED.sum(), REPAIRED_BLOCKS.sum(),
            UNPROFITABLE.sum());
    }

    /** The features probation took off the GPU, with the first difference, for the status. */
    public static String rejectedStatus(DecorSpec s) {
        var o = off(s);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < o.length(); i++) {
            if (o.get(i) != null) {
                b.append(b.length() == 0 ? "" : "; ").append(DecorSpec.name(s.features.get(i))).append(": ").append(o.get(i));
            }
        }
        return b.toString();
    }

    /** What the GPU did: per feature 8 ints (flags, random state, draws, effect end, read end), effects and reads (2 ints each). */
    public record Result(int[] features, int[] effects, int[] reads) {}

    private DecorJob(GpuDecor decor, DecorSpec spec, ChunkPos pos, int[] slots, long[] lo, long[] hi) {
        this.decor = decor;
        this.spec = spec;
        this.pos = pos;
        this.slots = slots;
        this.lo = lo;
        this.hi = hi;
    }

    /** The chunks around a decoration ({@code getChunk} of its region). Returns null for a chunk that is not there. */
    public interface Chunks {
        ChunkAccess get(int x, int z);
    }

    /**
     * Asks the GPU for the chunk's features against the 3x3 chunks as the GPU has them now. Returns null when the GPU cannot place any
     * of the features. Callable from any thread. The chunk data read here (sent counts of the write logs, biomes, worldgen heightmaps)
     * either does not change before the chunk's decoration or is read safely.
     */
    public static DecorJob submit(GpuDecor decor, Chunks region, long seed, ChunkAccess chunk, ChunkGenerator generator) {
        return submit(decor, region, seed, chunk, generator, false);
    }

    /**
     * {@link #submit}. With {@code speculate}, the job runs over the GPU results of the neighbouring decorations still to run whose
     * jobs have answered. The caller then lets those decorations run first ({@link #deps}).
     */
    public static DecorJob submit(GpuDecor decor, Chunks region, long seed, ChunkAccess chunk, ChunkGenerator generator, boolean speculate) {
        long t0 = System.nanoTime();
        DecorJob job = build(decor, region, seed, chunk, generator, speculate);
        SUBMIT.since(t0);
        return job;
    }

    private static DecorJob build(GpuDecor decor, Chunks region, long seed, ChunkAccess chunk, ChunkGenerator generator, boolean speculate) {
        DecorSpec spec = decor.spec();
        if (spec == null || !spec.current() || chunk.isUpgrading()) {
            return null;
        }
        ChunkPos pos = chunk.getPos();
        ChunkAccess[] around = new ChunkAccess[9];
        DeltaLog[] logs = new DeltaLog[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ci = (dz + 1) * 3 + dx + 1;
                ChunkAccess c = dx == 0 && dz == 0 ? chunk : region.get(pos.x + dx, pos.z + dz);
                Ga2GpuChunk gc = c == null ? null : Ga2GpuChunk.of(c);
                DeltaLog log = gc == null ? null : gc.ga2$deltaLog();
                if (log == null || log.closed || log.owner != decor || !c.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)
                    || !c.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE_WG)) {
                    return null;
                }
                around[ci] = c;
                logs[ci] = log;
            }
        }
        // the features applyBiomeDecoration places, in its order. That is every step's features of the biomes in the 3x3 chunks,
        // sorted by index.
        Set<Holder<Biome>> set = new ObjectArraySet<>();
        for (int ci = 0; ci < 9; ci++) {
            Object[] bs = logs[ci].biomes;
            if (bs == null) {
                Set<Holder<Biome>> one = new ObjectArraySet<>();
                for (LevelChunkSection s : around[ci].getSections()) {
                    s.getBiomes().getAll(one::add);
                }
                logs[ci].biomes = bs = one.toArray();
            }
            for (Object b : bs) {
                @SuppressWarnings("unchecked")
                Holder<Biome> h = (Holder<Biome>) b;
                set.add(h);
            }
        }
        set.retainAll(generator.getBiomeSource().possibleBiomes());
        List<FeatureSorter.StepFeatureData> steps = ((ChunkGeneratorFeaturesAccessor) generator).ga2$featuresPerStep().get();
        int[] list = spec.features(set, () -> featureList(spec, set, steps, generator));
        var gone = off(spec);
        boolean fresh = SAMPLE.incrementAndGet() % 16 == 0;
        int n = 0;
        for (int f = 0; f < list.length / 3; f++) {
            if (gone.get(list[f * 3]) == null && (fresh || !wasteful(list[f * 3]))) {
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        WorldgenRandom seeds = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decoration = seeds.setDecorationSeed(seed, pos.getMinBlockX(), pos.getMinBlockZ());
        int[] slots = new int[n];
        long[] lo = new long[n], hi = new long[n];
        PlacedFeature[] placed = new PlacedFeature[n];
        int at = 0;
        for (int f = 0; f < list.length / 3; f++) {
            int slot = list[f * 3], i = list[f * 3 + 1], k = list[f * 3 + 2];
            if (gone.get(slot) != null || !fresh && wasteful(slot)) {
                continue;
            }
            RandomSupport.Seed128bit s = RandomSupport.upgradeSeedTo128bit(decoration + (long) i + (long) (10000 * k));
            long l = s.seedLo(), h = s.seedHi();
            if ((l | h) == 0L) {
                l = RandomSupport.GOLDEN_RATIO_64;
                h = RandomSupport.SILVER_RATIO_64;
            }
            slots[at] = slot;
            lo[at] = l;
            hi[at] = h;
            placed[at] = steps.get(k).features().get(i);
            at++;
        }
        DecorJob job = new DecorJob(decor, spec, pos, slots, lo, hi);
        for (int i = 0; i < n; i++) {
            // a feature twice in one chunk's list (two steps) keeps the GPU for its first placement only
            job.index.putIfAbsent(placed[i], i);
        }
        for (int ci = 0; ci < 9; ci++) {
            job.chunks[ci] = around[ci];
            job.logs[ci] = logs[ci];
            // what the GPU will have: everything queued for it so far. Later writes are read as changed.
            job.scanned[ci] = logs[ci].sentIndex;
            // WorldGenRegion.getHeight: the chunk's height + 1, i.e. its first available. The maps are primed (checked above).
            Heightmap ws = around[ci].getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            Heightmap of = around[ci].getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int c = ci * 256 + lz * 16 + lx;
                    job.heights[c * 2] = ws.getFirstAvailable(lx, lz);
                    job.heights[c * 2 + 1] = of.getFirstAvailable(lx, lz);
                }
            }
        }
        if (speculate) {
            job.overlayFrom(decor);
        }
        decor.pending(job);
        JOBS.increment();
        decor.submit(job);
        return job;
    }

    /**
     * Collects the writes of the GPU results of nearby jobs whose decorations are still to run. These jobs have answered and lie within
     * two chunks, so their 3x3 chunks meet this job's. Jobs are taken oldest first, keeping the newest write per block in this job's
     * chunks. The result is what the chunks will hold when this decoration runs, if those decorations run first and use their results.
     * Speculation only. At {@link #bind}, every such block that holds something else counts as changed.
     */
    private void overlayFrom(GpuDecor decor) {
        java.util.ArrayList<DecorJob> ps = null;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                DecorJob p = decor.pendingJob(pos.x + dx, pos.z + dz);
                if (p == null || p.finished || p.seq > seq || p.future.getNow(null) == null) {
                    continue;
                }
                if (ps == null) {
                    ps = new java.util.ArrayList<>();
                }
                ps.add(p);
            }
        }
        if (ps == null) {
            return;
        }
        ps.sort(java.util.Comparator.comparingLong(p -> p.seq));
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap at = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        at.defaultReturnValue(-1);
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (DecorJob p : ps) {
            Result r = p.future.getNow(null);
            int ox = p.pos.x - pos.x, oz = p.pos.z - pos.z;
            int nf = p.slots.length;
            for (int f = 0; f < nf; f++) {
                if ((r.features[f * 8] & DecorSpec.R_CPU) != 0) {
                    continue; // placed on the CPU: what it writes is not known
                }
                int w0 = f == 0 ? 0 : r.features[(f - 1) * 8 + 6], w1 = r.features[f * 8 + 6];
                for (int w = w0; w < w1; w++) {
                    int e = r.effects[w * 2 + 1], op = e >>> 28;
                    if (op != DecorSpec.EF_SECTION && op != DecorSpec.EF_SET && op != DecorSpec.EF_EDGE_SET) {
                        continue;
                    }
                    int where = r.effects[w * 2], ci = where >>> 20;
                    int rx = ox + ci % 3 - 1, rz = oz + ci / 3 - 1;
                    if (rx < -1 || rx > 1 || rz < -1 || rz > 1) {
                        continue;
                    }
                    int nw = ((rz + 1) * 3 + rx + 1) << 20 | (where & 0xFFFFF);
                    int k = at.get(nw);
                    if (k >= 0) {
                        out.set(k * 2 + 1, e & 0xFFFF);
                    } else {
                        at.put(nw, out.size() / 2);
                        out.add(nw);
                        out.add(e & 0xFFFF);
                    }
                }
            }
        }
        if (out.isEmpty() || out.size() / 2 > MAXO) {
            return;
        }
        overlay = out.toIntArray();
        CompletableFuture<?>[] ends = new CompletableFuture<?>[ps.size()];
        for (int i = 0; i < ends.length; i++) {
            ends[i] = ps.get(i).decorated;
        }
        deps = ends;
        OVERLAID.increment();
        OVERLAY_BLOCKS.add(overlay.length / 2);
    }

    /** Called when the chunk's decoration ended or the chunk went away. The jobs speculating on this one may then run. */
    void finish() {
        finished = true;
        decorated.complete(null);
        // only a pending job's speculation reads these
        deps = null;
        overlay = null;
    }

    /** The GPU features applyBiomeDecoration places for a set of biomes, in its order: (slot, index, step) each. */
    private static int[] featureList(DecorSpec spec, Set<Holder<Biome>> set, List<FeatureSorter.StepFeatureData> steps, ChunkGenerator generator) {
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int k = 0; k < steps.size() && out.size() < DecorSpec.MAXF * 3; k++) {
            FeatureSorter.StepFeatureData data = steps.get(k);
            IntSet indices = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            for (Holder<Biome> b : set) {
                List<HolderSet<PlacedFeature>> features = generator.getBiomeGenerationSettings(b).features();
                if (k < features.size()) {
                    for (Holder<PlacedFeature> h : features.get(k)) {
                        indices.add(data.indexMapping().applyAsInt(h.value()));
                    }
                }
            }
            int[] sorted = indices.toIntArray();
            Arrays.sort(sorted);
            for (int i : sorted) {
                int slot = spec.slot(data.features().get(i));
                if (slot >= 0 && out.size() < DecorSpec.MAXF * 3) {
                    out.add(slot);
                    out.add(i);
                    out.add(k);
                }
            }
        }
        return out.toIntArray();
    }

    /**
     * Starts the decoration with {@code region}, whose locks are held. Every write into the 3x3 chunks since the GPU's copy counts as
     * changed, including the GPU's own results from other jobs. From now on, only this decoration writes there. Returns false when the
     * region is not this job's.
     */
    public boolean bind(WorldGenLevel region) {
        long t0 = System.nanoTime();
        try {
            return bindTimed(region);
        } finally {
            BIND_NS.add(System.nanoTime() - t0);
        }
    }

    private boolean bindTimed(WorldGenLevel region) {
        if (region.getChunk(pos.x, pos.z) != chunks[4]) {
            return false;
        }
        int words = 256 * decor.height >> 6;
        Dirty b = BITS.get();
        if (b == null || b.cw != words) {
            b = new Dirty(words);
            BITS.set(b);
        } else {
            b.clear();
        }
        marks = b;
        stale = b.stale;
        own = b.own;
        secs = b.secs;
        cw = words;
        ns = words >> 6;
        fix = b.fix;
        for (int ci = 0; ci < 9; ci++) {
            if (region.getChunk(pos.x + ci % 3 - 1, pos.z + ci / 3 - 1) != chunks[ci] || logs[ci].closed) {
                unbind();
                return false;
            }
        }
        // the blocks the job speculated on. Such a block is changed where it holds something else. The bits in own mark these
        // blocks for the log pass.
        int[] ov = overlay;
        if (ov != null) {
            long[] cover = own;
            for (int k = 0; k < ov.length; k += 2) {
                int where = ov[k], ci = where >>> 20, idx = where & 0xFFFFF;
                int j = ci * words + (idx >>> 6);
                long m = 1L << idx;
                cover[j] |= m;
                b.used[ci] = true;
                if (decor.ids.id(blockAt(ci % 3 * 16 + (idx & 15), idx >>> 8, ci / 3 * 16 + ((idx >> 4) & 15))) != ov[k + 1]) {
                    stale[j] |= m;
                    secs[ci * (words >> 6) + (idx >>> 12)] = true;
                    OVERLAY_STALE.increment();
                }
            }
        }
        for (int ci = 0; ci < 9; ci++) {
            // a log entry's position is its bit (section << 12 | y << 8 = yi << 8)
            DeltaLog log = logs[ci];
            int n = log.sizeNow(), base = ci * words;
            int[] where = log.whereArray();
            long[] d = stale, cover = own;
            boolean[] sec = secs;
            int sb = ci * (words >> 6);
            if (ov == null) {
                for (int i = scanned[ci]; i < n; i++) {
                    int w = where[i] & ~DeltaLog.EXPECTED;
                    d[base + (w >>> 6)] |= 1L << w;
                    sec[sb + (w >>> 12)] = true;
                }
            } else {
                for (int i = scanned[ci]; i < n; i++) {
                    int w = where[i] & ~DeltaLog.EXPECTED;
                    int j = base + (w >>> 6);
                    long m = 1L << w;
                    if ((cover[j] & m) == 0) {
                        d[j] |= m;
                        sec[sb + (w >>> 12)] = true;
                    }
                }
            }
            if (n > scanned[ci]) {
                b.used[ci] = true;
            }
            scanned[ci] = n;
        }
        if (ov != null) {
            for (int k = 0; k < ov.length; k += 2) {
                int where = ov[k];
                own[(where >>> 20) * words + ((where & 0xFFFFF) >>> 6)] = 0L;
            }
        }
        this.region = region;
        return true;
    }

    /** Ends the decoration. The thread's bitsets are left clear. */
    public void unbind() {
        if (marks != null) {
            marks.clear();
        }
        marks = null;
        stale = null;
        own = null;
        fix = null;
        region = null;
    }

    /** Runs {@code body} with its writes into the 3x3 chunks counted as this job's ({@link DeltaLog#EXPECTED}). */
    private void committing(Runnable body) {
        for (DeltaLog l : logs) {
            l.committing = true;
        }
        try {
            body.run();
        } finally {
            for (DeltaLog l : logs) {
                l.committing = false;
            }
        }
    }

    /** True once the GPU's answer is in (used without waiting). */
    public boolean ready() {
        return future.isDone();
    }

    private Result result() {
        if (result != null || failed) {
            return result;
        }
        try {
            result = future.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LATE.increment();
            failed = true;
            return null;
        } catch (Exception e) {
            failed = true;
            return null;
        }
        if (result == null) {
            failed = true;
        } else {
            READY.since(asked);
        }
        return result;
    }

    private int effectsFrom(Result res, int fi) {
        return fi == 0 ? 0 : res.features[(fi - 1) * 8 + 6];
    }

    private int readsFrom(Result res, int fi) {
        return fi == 0 ? 0 : res.features[(fi - 1) * 8 + 7];
    }

    /**
     * {@code PlacementPipeline.place} at a placed feature. Returns true or false (the placement's return value) when the GPU's result
     * was written. Returns null to place the feature on the CPU.
     */
    public Boolean place(PlacedFeature placed, PlacementContext ctx, RandomSource random, BlockPos origin,
                         java.util.function.BooleanSupplier cpu) {
        Integer fi = index.get(placed);
        if (fi == null || fi < next || ctx.getLevel() != region || ctx.topFeature().orElse(null) != placed) {
            return null;
        }
        if (!(random instanceof WorldgenRandom wr) || !(wr.randomSource instanceof XoroshiroRandomSource xs)) {
            return null;
        }
        Xoroshiro128Accessor x = (Xoroshiro128Accessor) ((XoroshiroSourceAccessor) xs).ga2$rng();
        Result res = result();
        if (res == null) {
            return null;
        }
        // results the decoration skipped (it should not skip any). Their GPU writes are not in the chunks.
        for (int q = next; q < fi; q++) {
            dirtyFrom(res, q);
        }
        next = fi + 1;
        int slot = slots[fi];
        long t0 = System.nanoTime();
        if (off(spec).get(slot) != null) {
            return rerun(res, fi, cpu);
        }
        if (x.ga2$lo() != lo[fi] || x.ga2$hi() != hi[fi]) {
            SEED.increment();
            return rerun(res, fi, cpu);
        }
        int flags = res.features[fi * 8];
        if ((flags & DecorSpec.R_CPU) != 0) {
            GPU_CPU.increment();
            return rerun(res, fi, cpu);
        }
        if (!scan()) {
            return rerun(res, fi, cpu);
        }
        boolean ore = spec.kind(slot) == DecorSpec.K_ORE;
        int why = ore ? oreConflicts(res, fi, (flags & DecorSpec.R_PLACED) != 0) : conflicts(res, fi);
        if (why != 0) {
            CHANGED.increment();
            PER.incrementAndGet(Math.min(slot, 4095) * 4 + why);
            return gpuCost(slot, t0, rerun(res, fi, cpu));
        }
        boolean repaired = ore && (!fix.isEmpty() || fixPlaced != ((flags & DecorSpec.R_PLACED) != 0));
        PER.incrementAndGet(Math.min(slot, 4095) * 4);
        int tried = tried(spec).getAndIncrement(slot);
        if (tried < PROBATION || VERIFY > 0 && VERIFY_SEEN.incrementAndGet() % VERIFY == 0) {
            return repaired ? verifyRepaired(res, fi, wr, x, cpu) : verify(res, fi, wr, x, cpu);
        }
        long tw = System.nanoTime();
        int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
        if (repaired) {
            REPAIRED.increment();
            REPAIRED_BLOCKS.add(fix.size());
            committing(() -> replayRepaired(res.effects, w0, w1));
        } else {
            committing(() -> replay(res.effects, w0, w1));
        }
        WRITE_NS.add(System.nanoTime() - tw);
        WRITES.add(w1 - w0);
        x.ga2$setLo(state(res, fi, 1));
        x.ga2$setHi(state(res, fi, 3));
        WorldgenRandomAccessor wa = (WorldgenRandomAccessor) wr;
        wa.ga2$setCount(wa.ga2$count() + res.features[fi * 8 + 5]);
        USED.increment();
        return gpuCost(slot, t0, repaired ? fixPlaced : (flags & DecorSpec.R_PLACED) != 0);
    }

    /**
     * Handles a GPU result that is not used. The feature is placed on the CPU here. Only the blocks where the CPU's writes and the
     * GPU's writes differ count as changed for the results after it (most of a placement read nothing that changed).
     */
    private boolean rerun(Result res, int fi, java.util.function.BooleanSupplier cpu) {
        long t0 = System.nanoTime();
        try {
            int[] before = new int[9];
            for (int ci = 0; ci < 9; ci++) {
                before[ci] = logs[ci].sizeNow();
            }
            boolean[] placed = new boolean[1];
            long tc = System.nanoTime();
            committing(() -> placed[0] = cpu.getAsBoolean());
            cpuCost(slots[fi], System.nanoTime() - tc);
            reconcile(res, fi, before);
            return placed[0];
        } finally {
            RERUN_NS.add(System.nanoTime() - t0);
        }
    }

    /** Marks the blocks where the CPU's writes since {@code before} and the GPU's for feature {@code fi} differ. */
    private void reconcile(Result res, int fi, int[] before) {
        int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap gpu = GPU_WRITES.get();
        gpu.clear();
        for (int w = w0; w < w1; w++) {
            int op = res.effects[w * 2 + 1] >>> 28;
            if (op == DecorSpec.EF_SECTION || op == DecorSpec.EF_SET || op == DecorSpec.EF_EDGE_SET) {
                gpu.put(res.effects[w * 2], res.effects[w * 2 + 1] & 0xFFFF);
            }
        }
        for (int ci = 0; ci < 9; ci++) {
            DeltaLog log = logs[ci];
            for (int i = before[ci], n = log.sizeNow(); i < n; i++) {
                // a log entry's position is the effect's position without the chunk index (yi << 8 | z << 4 | x)
                int e = log.where(i) & ~DeltaLog.EXPECTED;
                if (gpu.remove(ci << 20 | e) != log.state(i)) {
                    markOwn(ci, e);
                }
            }
        }
        for (var it = gpu.keySet().iterator(); it.hasNext(); ) {
            int where = it.nextInt();
            markOwn(where >>> 20, where & 0xFFFFF);
        }
        gpu.clear();
    }

    private static long state(Result res, int fi, int at) {
        return (long) res.features[fi * 8 + at] & 0xFFFFFFFFL | (long) res.features[fi * 8 + at + 1] << 32;
    }

    private BlockPos worldPos(int where) {
        int ci = (where >>> 20) & 15, yi = (where >> 8) & 0xFFF;
        return new BlockPos((pos.x - 1 + ci % 3) * 16 + (where & 15), decor.minY + yi, (pos.z - 1 + ci / 3) * 16 + ((where >> 4) & 15));
    }

    /**
     * The effects in vanilla's order: section writes (BulkSectionAccess), region writes, scheduled ticks, and for a tree its edge pass
     * ({@code StructureTemplate.updateShapeAtEdge}, vanilla's own code) over the shape the GPU sent.
     */
    private void replay(int[] effects, int from, int to) {
        net.minecraft.world.phys.shapes.DiscreteVoxelShape shape = null;
        int tx = 0, ty = 0, tz = 0;
        for (int w = from; w < to; w++) {
            int where = effects[w * 2], e = effects[w * 2 + 1];
            int op = e >>> 28, id = e & 0xFFFF, flags = (e >>> 16) & 0xFFF;
            switch (op) {
                case DecorSpec.EF_SECTION -> {
                    int ci = where >>> 20, yi = (where >> 8) & 0xFFF;
                    LevelChunkSection s = chunks[ci].getSection(yi >> 4);
                    s.setBlockState(where & 15, yi & 15, (where >> 4) & 15, decor.ids.state(id), false);
                }
                case DecorSpec.EF_SET -> region.setBlock(worldPos(where), decor.ids.state(id), flags);
                case DecorSpec.EF_TICK_FLUID -> region.scheduleTick(worldPos(where), spec.tickFluids.get(id), flags);
                case DecorSpec.EF_TICK_BLOCK -> region.scheduleTick(worldPos(where), spec.tickBlocks.get(id), flags);
                case DecorSpec.EF_POST -> {
                    // Feature.markAboveForPostProcessing
                    BlockPos p = worldPos(where);
                    region.getChunk(p).markPosForPostprocessing(p);
                }
                case DecorSpec.EF_TREE -> {
                    // the box: minimum (relative to the 3x3 chunks), x and z spans. The next entry holds the y span.
                    tx = (pos.x - 1) * 16 + (where & 0xFF);
                    ty = decor.minY + ((where >> 8) & 0xFFF);
                    tz = (pos.z - 1) * 16 + ((where >> 20) & 0xFF);
                    int sx = e & 0xFF, sz = (e >> 8) & 0xFF, sy = effects[(w + 1) * 2];
                    shape = new net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape(sx, sy, sz);
                    w++;
                }
                case DecorSpec.EF_SHAPE -> {
                    BlockPos p = worldPos(where);
                    shape.fill(p.getX() - tx, p.getY() - ty, p.getZ() - tz);
                }
                case DecorSpec.EF_EDGE -> net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.updateShapeAtEdge(region, 3, shape, tx, ty, tz);
                case DecorSpec.EF_EDGE_SET -> {
                    // written by the edge pass above
                }
                default -> throw new IllegalStateException("effect " + op);
            }
        }
    }

    /** Places the feature on the CPU (its writes counted as the GPU's) and compares. The CPU's answer is kept. */
    private Boolean verify(Result res, int fi, WorldgenRandom wr, Xoroshiro128Accessor x, java.util.function.BooleanSupplier cpu) {
        int[] before = new int[9];
        for (int ci = 0; ci < 9; ci++) {
            before[ci] = logs[ci].sizeNow();
        }
        int count0 = ((WorldgenRandomAccessor) wr).ga2$count();
        boolean[] placed = new boolean[1];
        committing(() -> placed[0] = cpu.getAsBoolean());
        int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
        String why = null;
        int[] seen = new int[9];
        // the logs keep only writes that changed a block. So the GPU's writes are replayed over the states before the feature, and only
        // the GPU writes that change a block are compared. A block the CPU wrote had the state its first write replaced. Any other block
        // has its current state.
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap cur = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        cur.defaultReturnValue(-1);
        for (int ci = 0; ci < 9; ci++) {
            for (int i = before[ci]; i < logs[ci].sizeNow(); i++) {
                cur.putIfAbsent(ci << 20 | (logs[ci].where(i) & 0xFFFFF), logs[ci].old(i));
            }
        }
        for (int w = w0; w < w1 && why == null; w++) {
            int op = res.effects[w * 2 + 1] >>> 28;
            if (op != DecorSpec.EF_SECTION && op != DecorSpec.EF_SET && op != DecorSpec.EF_EDGE_SET) {
                continue;
            }
            int where = res.effects[w * 2], id = res.effects[w * 2 + 1] & 0xFFFF;
            int ci = where >>> 20, yi = (where >> 8) & 0xFFF;
            int expect = (yi >> 4) << 12 | (yi & 15) << 8 | (where & 0xFF);
            int key = ci << 20 | expect;
            int was = cur.get(key);
            if (was < 0) {
                was = decor.ids.id(blockAt(ci % 3 * 16 + (where & 15), yi, ci / 3 * 16 + ((where >> 4) & 15)));
            }
            cur.put(key, id);
            if (was == id) {
                continue;
            }
            int i = before[ci] + seen[ci]++;
            if (i >= logs[ci].sizeNow()) {
                why = "the CPU wrote fewer blocks into chunk " + ci;
            } else if ((logs[ci].where(i) & ~DeltaLog.EXPECTED) != expect || logs[ci].state(i) != id) {
                why = "write " + (w - w0) + " differs in chunk " + ci + " (GPU " + Integer.toHexString(expect) + "=" + decor.ids.state(id)
                    + ", CPU " + Integer.toHexString(logs[ci].where(i) & ~DeltaLog.EXPECTED) + "=" + decor.ids.state(logs[ci].state(i)) + ")";
            }
        }
        for (int ci = 0; ci < 9 && why == null; ci++) {
            if (logs[ci].sizeNow() - before[ci] != seen[ci]) {
                why = "the CPU wrote " + (logs[ci].sizeNow() - before[ci]) + " blocks into chunk " + ci + ", the GPU " + seen[ci];
            }
        }
        if (why == null && (x.ga2$lo() != state(res, fi, 1) || x.ga2$hi() != state(res, fi, 3))) {
            why = "the random ends elsewhere";
        }
        if (why == null && ((WorldgenRandomAccessor) wr).ga2$count() - count0 != res.features[fi * 8 + 5]) {
            why = "draws: CPU " + (((WorldgenRandomAccessor) wr).ga2$count() - count0) + ", GPU " + res.features[fi * 8 + 5];
        }
        if (why == null && placed[0] != ((res.features[fi * 8] & DecorSpec.R_PLACED) != 0)) {
            why = "placed: CPU " + placed[0];
        }
        if (why == null) {
            VERIFIED.increment();
        } else {
            DIFFER.increment();
            int slot = slots[fi];
            if (off(spec).compareAndSet(slot, null, why)) {
                REJECTED.increment();
                LOGGER.warn("feature {} mismatch vs CPU in chunk {} ({}) -> CPU from now on",
                    DecorSpec.name(spec.features.get(slot)), pos, why);
                Probation.reject(DecorSpec.name(spec.features.get(slot)), why);
                dumpDifference(res, fi, before, why);
            }
            reconcile(res, fi, before);
        }
        return placed[0];
    }

    /**
     * Writes a probation difference to {@code <game dir>/.ga2/decor-diff-<n>.txt}, for the first few differences only. The file lists
     * the GPU's effects and the CPU's writes in order, in world coordinates. It helps find which part of a transcription went wrong.
     */
    private void dumpDifference(Result res, int fi, int[] before, String why) {
        int n = (int) DIFFER.sum();
        if (n > 8) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(".ga2");
            java.nio.file.Files.createDirectories(dir);
            StringBuilder b = new StringBuilder();
            b.append("feature ").append(DecorSpec.name(spec.features.get(slots[fi]))).append(" in chunk ").append(pos).append(": ").append(why).append('\n');
            b.append("random start lo ").append(Long.toHexString(lo[fi])).append(" hi ").append(Long.toHexString(hi[fi])).append('\n');
            b.append("GPU effects:\n");
            int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
            for (int w = w0; w < w1; w++) {
                int where = res.effects[w * 2], e = res.effects[w * 2 + 1], op = e >>> 28;
                b.append(w - w0).append(' ').append(op).append(' ');
                if (op == DecorSpec.EF_TREE || op == DecorSpec.EF_EDGE) {
                    b.append(Integer.toHexString(where)).append(' ').append(Integer.toHexString(e & 0xFFFFFFF)).append('\n');
                } else {
                    b.append(worldPos(where).toShortString()).append(' ').append(op <= 1 || op == DecorSpec.EF_EDGE_SET ? decor.ids.state(e & 0xFFFF) : e & 0xFFFF).append('\n');
                }
            }
            b.append("CPU writes:\n");
            for (int ci = 0; ci < 9; ci++) {
                for (int i = before[ci], k = 0; i < logs[ci].sizeNow(); i++, k++) {
                    int w = logs[ci].where(i) & ~DeltaLog.EXPECTED;
                    int yi = ((w >> 12) & 0x7FF) * 16 + ((w >> 8) & 15);
                    int where = ci << 20 | yi << 8 | (w & 0xFF);
                    b.append(ci).append(':').append(k).append(' ').append(worldPos(where).toShortString()).append(' ').append(decor.ids.state(logs[ci].state(i))).append('\n');
                }
            }
            b.append("GPU reads:\n");
            int r0 = readsFrom(res, fi), r1 = res.features[fi * 8 + 7];
            for (int r = r0; r < r1 && r - r0 < 20000; r++) {
                int rec = res.reads[r * 2], aux = res.reads[r * 2 + 1];
                int type = (rec >>> 24) & 15;
                b.append(type).append(' ');
                if (type == DecorSpec.RT_HEIGHT) {
                    b.append(Integer.toHexString(rec & 0xFFFFFF)).append(' ').append(aux).append('\n');
                } else {
                    b.append(worldPos(rec & 0xFFFFFF).toShortString()).append(' ').append(Integer.toHexString(aux)).append('\n');
                }
            }
            java.nio.file.Files.writeString(dir.resolve("decor-diff-" + n + ".txt"), b.toString());
        } catch (Exception e) {
            LOGGER.warn("feature mismatch dump: write failed", e);
        }
    }

    /** Reads the new log entries. Every block not written from a GPU result counts as changed. Returns false when a log stopped. */
    private boolean scan() {
        for (int ci = 0; ci < 9; ci++) {
            DeltaLog log = logs[ci];
            if (log.closed) {
                failed = true;
                return false;
            }
            int n = log.sizeNow();
            if (n > scanned[ci]) {
                int[] where = log.whereArray();
                int base = ci * cw;
                long[] d = own;
                boolean[] sec = secs;
                int sb = ci * ns;
                boolean any = false;
                for (int i = scanned[ci]; i < n; i++) {
                    int w = where[i];
                    if ((w & DeltaLog.EXPECTED) == 0) {
                        d[base + (w >>> 6)] |= 1L << w;
                        sec[sb + (w >>> 12)] = true;
                        any = true;
                    }
                }
                if (any) {
                    marks.used[ci] = true;
                }
                scanned[ci] = n;
            }
        }
        return true;
    }

    /** Marks a block of chunk ci ({@code yi << 8 | z << 4 | x}) as one the decoration changed. */
    private void markOwn(int ci, int idx) {
        own[ci * cw + (idx >>> 6)] |= 1L << idx;
        secs[ci * ns + (idx >>> 12)] = true;
        marks.used[ci] = true;
    }

    /**
     * Whether any block is marked changed in the section that holds block idx of chunk ci. With {@code around}, the sections that hold
     * the block's six neighbours count too. When none is marked, neither the block nor its neighbours changed.
     */
    private boolean marksNear(int ci, int idx, boolean around) {
        int s = idx >>> 12, at = ci * ns + s;
        if (secs[at]) {
            return true;
        }
        if (!around) {
            return false;
        }
        int y = (idx >> 8) & 15, x = idx & 15, z = (idx >> 4) & 15;
        return y == 0 && s > 0 && secs[at - 1] || y == 15 && s + 1 < ns && secs[at + 1]
            || x == 0 && ci % 3 > 0 && secs[at - ns] || x == 15 && ci % 3 < 2 && secs[at + ns]
            || z == 0 && ci / 3 > 0 && secs[at - 3 * ns] || z == 15 && ci / 3 < 2 && secs[at + 3 * ns];
    }

    /** Whether a block of chunk ci changed since the GPU's copy: 0 no, 1 before the decoration, 2 by it. */
    private int changedWhy(int ci, int idx) {
        int k = ci * cw + (idx >>> 6);
        long m = 1L << idx;
        return (stale[k] & m) != 0 ? 1 : (own[k] & m) != 0 ? 2 : 0;
    }

    /**
     * {@link #changedWhy} at 3x3 coordinates. Returns 0 outside the area the GPU read or outside the height. The GPU leaves placements
     * that reach outside that area to the CPU.
     */
    private int changedAt(int x, int yi, int z) {
        if (x < 0 || x >= 48 || z < 0 || z >= 48 || yi < 0 || yi >= decor.height) {
            return 0;
        }
        return changedWhy((z >> 4) * 3 + (x >> 4), yi << 8 | (z & 15) << 4 | (x & 15));
    }

    private BlockState blockAt(int x, int yi, int z) {
        return chunks[(z >> 4) * 3 + (x >> 4)].getSection(yi >> 4).getBlockState(x & 15, yi & 15, z & 15);
    }

    private static final int[] NX = { 0, 0, 0, 0, -1, 1 }, NY = { -1, 1, 0, 0, 0, 0 }, NZ = { 0, 0, -1, 1, 0, 0 };

    /** {@code OreFeature.isAdjacentToAir} through the chunks (air outside the build height). */
    private boolean adjacentAir(int x, int yi, int z) {
        for (int k = 0; k < 6; k++) {
            int yy = yi + NY[k];
            if (yy < 0 || yy >= decor.height) {
                return true;
            }
            int nx = x + NX[k], nz = z + NZ[k];
            if (nx < 0 || nx >= 48 || nz < 0 || nz >= 48) {
                throw new IllegalStateException("air check outside the 3x3 chunks"); // the GPU leaves such placements to the CPU
            }
            if (blockAt(nx, yy, nz).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Handles a GPU result that was not written. The blocks it would have written do not hold what the results after it read. */
    private void dirtyFrom(Result res, int fi) {
        int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
        for (int w = w0; w < w1; w++) {
            int op = res.effects[w * 2 + 1] >>> 28;
            if (op == DecorSpec.EF_SECTION || op == DecorSpec.EF_SET || op == DecorSpec.EF_EDGE_SET) {
                int where = res.effects[w * 2];
                markOwn(where >>> 20, where & 0xFFFFF);
            }
        }
    }

    /**
     * Conflict check for the generic kinds. Checks whether a block the feature read changed since the copy so that a predicate the
     * feature asked now answers differently. Also checks whether a heightmap or light value the feature read now reads differently.
     * Returns 0 for no conflict, else the reason: 1 a block changed before the decoration, 2 a block changed by the decoration, 3 a
     * heightmap or light.
     */
    private int conflicts(Result res, int fi) {
        int r0 = readsFrom(res, fi), r1 = res.features[fi * 8 + 7];
        for (int r = r0; r < r1; r++) {
            int rec = res.reads[r * 2], aux = res.reads[r * 2 + 1];
            int type = (rec >>> 24) & 15;
            int ci = (rec >>> 20) & 15;
            if (type == DecorSpec.RT_PRED || type == DecorSpec.RT_STATE || type == DecorSpec.RT_VALUE) {
                int idx = rec & 0xFFFFF;
                int why = changedWhy(ci, idx);
                if (why == 0) {
                    continue;
                }
                BlockState now = blockAt(ci % 3 * 16 + (rec & 15), idx >>> 8, ci / 3 * 16 + ((rec >> 4) & 15));
                boolean differs = type == DecorSpec.RT_PRED ? spec.preds.answer(aux >>> 1, now) != (aux & 1)
                    : type == DecorSpec.RT_STATE ? decor.ids.id(now) != aux
                    : (spec.preds.value(aux >>> 20, now) & 0xFFFFF) != (aux & 0xFFFFF);
                if (differs) {
                    return why;
                }
            } else if (type == DecorSpec.RT_LIGHT) {
                if (!(region.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, worldPos(rec & 0xFFFFFF)) < 10)) {
                    return 3;
                }
            } else if (type == DecorSpec.RT_HEIGHT) {
                int t = (rec >> 8) & 0xF;
                Heightmap.Types map = Preds.FINAL[t - 2];
                int wx = (pos.x - 1 + ci % 3) * 16 + (rec & 15), wz = (pos.z - 1 + ci / 3) * 16 + ((rec >> 4) & 15);
                if (region.getHeight(map, wx, wz) != aux) {
                    return 3;
                }
            } else {
                return 3;
            }
        }
        return 0;
    }

    /**
     * Conflict check for ores. Checks whether a block the feature read on the GPU has since changed in a way that decides differently.
     * With air checks, the blocks next to it count too. Returns 0 for no conflict, else the reason: 1 changed before the decoration, 2
     * changed by it. An ore that draws nothing per block decides each block alone. For such an ore, a changed block is decided again here
     * instead ({@link #fix}, with the placed answer in {@link #fixPlaced}), and the result still holds.
     */
    private int oreConflicts(Result res, int fi, boolean placed) {
        int r0 = readsFrom(res, fi), r1 = res.features[fi * 8 + 7];
        int slot = slots[fi];
        boolean air = spec.airChecks(slot);
        DecorSpec.Check check = spec.check(slot);
        boolean repair = check != null && check.repairable();
        fix.clear();
        fixPlaced = placed;
        int decided = 0, delta = 0;
        for (int r = r0; r < r1; r++) {
            int rec = res.reads[r * 2];
            boolean wasDecided = (rec & (1 << 30)) != 0;
            if (wasDecided) {
                decided++;
            }
            int ci = (rec >>> 20) & 15, idx = rec & 0xFFFFF;
            if (!marksNear(ci, idx, air)) {
                continue;
            }
            int x = ci % 3 * 16 + (rec & 15), yi = idx >>> 8, z = ci / 3 * 16 + ((rec >> 4) & 15);
            int why = changedWhy(ci, idx);
            if (air) {
                for (int k = 0; k < 6 && why != 1; k++) {
                    why = Math.max(why, changedAt(x + NX[k], yi + NY[k], z + NZ[k]));
                }
            }
            if (why == 0) {
                continue;
            }
            if (check == null) {
                return why;
            }
            BlockState now = blockAt(x, yi, z);
            int tm = (rec >>> 24) & 31, tmNow = check.targets().mask(now) & 31;
            boolean wrote = rec < 0;
            if (repair) {
                // OreFeature.canPlaceOre without draws: the first target that matches, unless the block is next to air (discard chance 1)
                boolean decides = tmNow != 0 && !(air && adjacentAir(x, yi, z));
                BlockState want = decides ? check.first(tmNow) : now;
                BlockState gpu = wrote ? check.first(tm) : now;
                if (want != gpu) {
                    int id = decides ? decor.ids.id(want) : -1;
                    if (decides && id < 0) {
                        return why; // a state the GPU's copy cannot hold
                    }
                    fix.put(rec & 0xFFFFFF, id);
                }
                if (decides != wasDecided) {
                    delta += decides ? 1 : -1;
                }
                continue;
            }
            // the GPU's result holds if the block decides the same now (same targets matched, same air exposure) and the write the GPU
            // made there is still the one the CPU would make
            if (tmNow != tm) {
                return why;
            }
            if (air && tm != 0 && (adjacentAir(x, yi, z) ? 1 : 0) != ((rec >>> 29) & 1)) {
                return why;
            }
            if (wasDecided && !wrote) {
                // the GPU's write was the block it found. The CPU writes that state too, unless the block already holds it.
                if (check.draws() || now != check.first(tm)) {
                    return why;
                }
            }
        }
        if (delta != 0) {
            fixPlaced = decided + delta > 0;
        }
        return 0;
    }

    /**
     * Writes a repaired ore ({@link #oreConflicts}). The GPU's writes come first, in the GPU's order, with re-decided blocks written as
     * decided now. Re-decided blocks the GPU did not write follow. Each block is decided alone, so the order of the writes changes
     * nothing. The repaired blocks count as changed for the results after this one, since the GPU's copy has its own writes there.
     */
    private void replayRepaired(int[] effects, int from, int to) {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap f = fix;
        for (var it = f.keySet().iterator(); it.hasNext(); ) {
            int where = it.nextInt();
            markOwn(where >>> 20, where & 0xFFFFF);
        }
        for (int w = from; w < to; w++) {
            int where = effects[w * 2], id = effects[w * 2 + 1] & 0xFFFF;
            int now = f.remove(where);
            if (now != Integer.MIN_VALUE) {
                if (now < 0) {
                    continue;
                }
                id = now;
            }
            int ci = where >>> 20, yi = (where >> 8) & 0xFFF;
            chunks[ci].getSection(yi >> 4).setBlockState(where & 15, yi & 15, (where >> 4) & 15, decor.ids.state(id), false);
        }
        for (var e : f.int2IntEntrySet()) {
            int where = e.getIntKey(), id = e.getIntValue();
            if (id >= 0) {
                int ci = where >>> 20, yi = (where >> 8) & 0xFFF;
                chunks[ci].getSection(yi >> 4).setBlockState(where & 15, yi & 15, (where >> 4) & 15, decor.ids.state(id), false);
            }
        }
        f.clear();
    }

    /**
     * Compares a repaired ore with the CPU (probation, samples). Compares, block by block, what the CPU leaves against what the repaired
     * result would leave. The repaired result's added writes come after the GPU's. Also compares the random, the draws and the answer.
     * The CPU's writes are kept.
     */
    private Boolean verifyRepaired(Result res, int fi, WorldgenRandom wr, Xoroshiro128Accessor x, java.util.function.BooleanSupplier cpu) {
        int[] before = new int[9];
        for (int ci = 0; ci < 9; ci++) {
            before[ci] = logs[ci].sizeNow();
        }
        // what the repaired result leaves: where -> state id. The GPU's writes, with the repairs over them. -1 means the block as it
        // is now.
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap want = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int w0 = effectsFrom(res, fi), w1 = res.features[fi * 8 + 6];
        for (int w = w0; w < w1; w++) {
            want.put(res.effects[w * 2], res.effects[w * 2 + 1] & 0xFFFF);
        }
        for (var e : fix.int2IntEntrySet()) {
            want.put(e.getIntKey(), e.getIntValue());
        }
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap was = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        for (var it = want.keySet().iterator(); it.hasNext(); ) {
            int where = it.nextInt(), ci = where >>> 20;
            was.put(where, decor.ids.id(blockAt(ci % 3 * 16 + (where & 15), (where >> 8) & 0xFFF, ci / 3 * 16 + ((where >> 4) & 15))));
        }
        int count0 = ((WorldgenRandomAccessor) wr).ga2$count();
        boolean[] placed = new boolean[1];
        committing(() -> placed[0] = cpu.getAsBoolean());
        String why = null;
        for (var e : want.int2IntEntrySet()) {
            int where = e.getIntKey(), ci = where >>> 20, yi = (where >> 8) & 0xFFF;
            int expect = e.getIntValue() < 0 ? was.get(where) : e.getIntValue();
            int got = decor.ids.id(blockAt(ci % 3 * 16 + (where & 15), yi, ci / 3 * 16 + ((where >> 4) & 15)));
            if (got != expect) {
                why = "repaired block " + worldPos(where).toShortString() + ": CPU " + decor.ids.state(got) + ", GPU " + decor.ids.state(expect);
                break;
            }
        }
        for (int ci = 0; ci < 9 && why == null; ci++) {
            for (int i = before[ci]; i < logs[ci].sizeNow(); i++) {
                int where = ci << 20 | (logs[ci].where(i) & 0xFFFFF);
                if (!want.containsKey(where)) {
                    why = "the CPU wrote " + worldPos(where).toShortString() + ", the repaired result not";
                    break;
                }
            }
        }
        if (why == null && (x.ga2$lo() != state(res, fi, 1) || x.ga2$hi() != state(res, fi, 3))) {
            why = "the random ends elsewhere";
        }
        if (why == null && ((WorldgenRandomAccessor) wr).ga2$count() - count0 != res.features[fi * 8 + 5]) {
            why = "draws: CPU " + (((WorldgenRandomAccessor) wr).ga2$count() - count0) + ", GPU " + res.features[fi * 8 + 5];
        }
        if (why == null && placed[0] != fixPlaced) {
            why = "placed: CPU " + placed[0] + " (repaired)";
        }
        if (why == null) {
            VERIFIED.increment();
        } else {
            DIFFER.increment();
            int slot = slots[fi];
            if (off(spec).compareAndSet(slot, null, why)) {
                REJECTED.increment();
                LOGGER.warn("feature {} (repaired) mismatch vs CPU in chunk {} ({}) -> CPU from now on",
                    DecorSpec.name(spec.features.get(slot)), pos, why);
                Probation.reject(DecorSpec.name(spec.features.get(slot)), why);
                dumpDifference(res, fi, before, why);
            }
        }
        fix.clear();
        // the CPU's writes against the GPU's own writes, which the GPU's copy holds. Every block where they differ counts as changed.
        reconcile(res, fi, before);
        return placed[0];
    }
}
