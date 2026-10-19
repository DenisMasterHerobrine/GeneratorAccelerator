package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuConfig;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BeardKernelAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.GpuBiomeSourceInspectMixin;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.XoroshiroFactoryAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The GPU path for one dimension's generator and {@code RandomState}. It holds the generated program, the program's constant
 * tables, the region cache and the batches of chunks whose blocks the GPU decides. A region is a square group of chunks whose
 * structure-independent data lives on the device. Requests come from any thread. Every OpenCL call runs on the device's GPU
 * thread ({@link #pump}).
 *
 * <p>A region's kernels (flat caches, hoisted values, shared corners, surface levels, aquifer cells, biomes) run when the
 * region's first chunk asks for biomes or blocks. The blocks of requesting chunks are decided in batches of up to
 * {@link GpuConfig#batchChunks} chunks from one region. A batch launches as soon as the GPU is idle, the batch is full or the
 * oldest request has waited long enough.
 */
public final class GpuWorldgen implements GpuDevice.Pump {
    public enum State { PREPARING, READY, FAILED }

    /** The program's view of one chunk's structures: pieces (8 ints each) and junctions (3 ints each), as the GPU's beardifier reads them. */
    public record Beard(int[] pieces, int[] junctions) {
        public static final Beard NONE = new Beard(new int[0], new int[0]);
    }

    /**
     * One chunk's biomes. {@code quarts} holds, per quart in (y, x, z) order, the climate tree's leaf ({@link #leaf}). Bit 31 marks a
     * tie. For a tie, vanilla's answer depends on the searching thread's last result, so the CPU searches that quart as vanilla does.
     * {@code tieTargets} lists the climate point of each tie as the quart followed by the six parameters. {@code complete} tells whether
     * every tie is listed. If not, the unlisted ties need the chunk's sampler.
     *
     * <p>With TerraBlender's regions, a quart's leaf is from its region's tree. Where that leaf is TerraBlender's placeholder
     * ({@link #deferred}), {@code fallbacks} holds the quart's leaf from region 0's tree. A tie in either search marks the quart. Null
     * without regions.
     */
    public record BiomeResult(int[] quarts, long[] tieTargets, boolean complete, int[] fallbacks) {
        /** Whether quart {@code i} is a tie. False past the program's quarts. Those belong to a taller chunk and are decided on the CPU. */
        public boolean tie(int i) {
            return i < quarts.length && quarts[i] < 0;
        }

        /** The climate point of tie quart {@code i}, or null when it is not listed. */
        public long[] target(int i) {
            for (int k = 0; k < tieTargets.length; k += 7) {
                if (tieTargets[k] == i) {
                    return new long[] { tieTargets[k + 1], tieTargets[k + 2], tieTargets[k + 3], tieTargets[k + 4], tieTargets[k + 5], tieTargets[k + 6] };
                }
            }
            return null;
        }

        public int ties() {
            int n = 0;
            for (int q : quarts) {
                n += q < 0 ? 1 : 0;
            }
            return n;
        }
    }

    /**
     * One chunk's blocks as the GPU packed them. Per section: 32 meta ints ({@link ClEmitter#META}), 256 storage words (null for an
     * air section) and 64 mark words (null without marks). Per column: two tops. {@code terrain}: the aquifer cells and quart
     * surface levels {@code k_terrain} writes, or null (no aquifers).
     */
    public record NoiseResult(long[][] storage, int[] meta, long[][] marks, int[] tops, int[] terrain) {}

    public static final LongAdder NOISE_CHUNKS = new LongAdder();
    public static final LongAdder BIOME_CHUNKS = new LongAdder();
    public static final LongAdder BATCHES = new LongAdder();
    public static final LongAdder REGIONS = new LongAdder();

    final GpuDevice device;
    final ClApi cl;
    public final String name;
    volatile State state = State.PREPARING;
    volatile String reason = "preparing";

    // ---- setup (immutable once READY) ----
    final NoiseGeneratorSettings settings;
    final RandomState randomState;
    public ClEmitter gen;
    public BlockState[] states;
    long aqLo, aqHi, oreLo, oreHi;
    /**
     * The climate trees, flat (GA2's FlatClimateTree layout) one after another, when the GPU decides biomes. Each tree's children are
     * contiguous. {@link #treeRoots} holds each tree's root node.
     */
    long[] treeBounds, treeOffset;
    int[] treeFirst, treeCount, treeLeaf;
    /** The root node of each tree, -1 for a missing tree. Per node: the tree it belongs to, and 1 for a TerraBlender placeholder leaf. */
    int[] treeRoots, nodeTree, treeDefer;
    public final List<Holder<Biome>> biomeList = new ArrayList<>();
    public MultiNoiseBiomeSource biomeSource;
    /**
     * The trees the biome source searches. One tree (the parameter list's) for vanilla. With TerraBlender, one per region index
     * ({@link #tbRegions}). The CPU keeps each tree's per-thread last result, as vanilla's searches do.
     */
    private Climate.RTree<Holder<Biome>>[] biomeTrees;
    /** Tests only: the regions of a parameter list, in place of TerraBlender's. Read when a dimension is prepared. */
    public static volatile java.util.function.Function<Climate.ParameterList<Holder<Biome>>, BiomeRegions> testRegions;
    /** TerraBlender's regions of the biome source, or null when the source searches the one tree. */
    private BiomeRegions tbRegions;
    /** The trees' leaves by the GPU's node index (null for inner nodes). */
    private Climate.RTree.Leaf<Holder<Biome>>[] leaves;

    /** The leaf of the GPU's node index {@code node}. */
    public Climate.RTree.Leaf<Holder<Biome>> leaf(int node) {
        return leaves[node];
    }

    /** The index of the tree that node {@code node} belongs to. */
    public int treeOf(int node) {
        return nodeTree[node];
    }

    /** Whether leaf {@code node} is TerraBlender's placeholder. The quart then takes region 0's answer ({@link BiomeResult#fallbacks}). */
    public boolean deferred(int node) {
        return treeDefer != null && treeDefer[node] != 0;
    }

    /** Whether the GPU searches TerraBlender's region trees. */
    public boolean biomeRegions() {
        return tbRegions != null;
    }

    /** This thread's last result of every tree (null for a missing tree). */
    @SuppressWarnings("unchecked")
    public Climate.RTree.Leaf<Holder<Biome>>[] lastResults() {
        Climate.RTree.Leaf<Holder<Biome>>[] last = new Climate.RTree.Leaf[biomeTrees.length];
        readLastResults(last);
        return last;
    }

    /** Reads this thread's last result of every tree into {@code last}. */
    public void readLastResults(Climate.RTree.Leaf<Holder<Biome>>[] last) {
        for (int t = 0; t < biomeTrees.length; t++) {
            if (biomeTrees[t] != null) {
                last[t] = biomeTrees[t].lastResult.get();
            }
        }
    }

    /** Sets this thread's last result of every tree. */
    public void setLastResults(Climate.RTree.Leaf<Holder<Biome>>[] last) {
        for (int t = 0; t < biomeTrees.length; t++) {
            if (biomeTrees[t] != null) {
                biomeTrees[t].lastResult.set(last[t]);
            }
        }
    }

    /** The biome source's answer for {@code target} at quart {@code x, y, z}. Searches as the source does, last results included. */
    public Holder<Biome> findBiome(Climate.TargetPoint target, int x, int y, int z) {
        return tbRegions != null ? tbRegions.find(target, x, y, z) : biomeSource.getNoiseBiome(target);
    }

    /** The biome source's answer at quart {@code x, y, z}, sampled with {@code sampler}. */
    public Holder<Biome> findBiome(int x, int y, int z, Climate.Sampler sampler) {
        return tbRegions != null ? tbRegions.find(sampler.sample(x, y, z), x, y, z) : biomeSource.getNoiseBiome(x, y, z, sampler);
    }
    public String biomesOff;
    float[] beardKernel;
    public int minY, height, cellWidth, cellHeight;
    int rsize;
    public double buildMs;
    public int sourceChars;

    /** Corners and blocks run in certified float, with flagged cells rechecked in double ({@link ClEmitter#certify}). If not, {@code certifiedOff} says why. */
    public boolean certified;
    public String certifiedOff;
    /**
     * Extra build options for tests, from {@code -Dgeneratoraccelerator2.gpu.buildDefines}, such as {@code -D PERTURB} or
     * {@code -D WRITE_DENSITY}. {@code -DGA2_NONCE=7} gives a build the caches cannot answer.
     */
    public String buildDefines = System.getProperty("generatoraccelerator2.gpu.buildDefines", "").isEmpty() ? "" : " " + System.getProperty("generatoraccelerator2.gpu.buildDefines");
    int cellsPerChunk3d;
    /** GPU thread time: launching batches, finishing batches (copies, futures), finishing regions, launching regions, and the part of that spent allocating region buffers. */
    public static final LongAdder[] HOST_NS = { new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder() };
    public static final LongAdder CERT_CELLS = new LongAdder();
    public static final LongAdder RECHECKED_CELLS = new LongAdder();
    public static final LongAdder RECHECKED_CORNERS = new LongAdder();
    /** Listed cells by reason ({@link ClEmitter#RECHECK_REASONS}). */
    public static final LongAdder[] RECHECK_REASONS = java.util.stream.Stream.generate(LongAdder::new).limit(ClEmitter.RECHECK_REASONS.length).toArray(LongAdder[]::new);
    public static final LongAdder RECHECK_OVERFLOWS = new LongAdder();
    /** Latencies. Region: launch until its biomes reach the host. Queue: a noise request's wait for its batch. Batch: launch to results. */
    public static final Lat REGION_LAT = new Lat(), QUEUE_LAT = new Lat(), BATCH_LAT = new Lat();
    public static final LongAdder BATCH_CHUNKS = new LongAdder();

    // ---- device objects ----
    long program;
    long kFlat, kHoist, kCorner, kBHoist, kSurface, kSurfaceIn, kStatus, kLevels, kClimate, kBiome, kCornerPc, kBlock, kPack, kHeights, kTerrain;
    /** Flags, per chunk, whether the columns of the chunk's aquifer cells disagree on an order-sensitive cache_2d ({@link ClEmitter.C2d#FLOODED}). 0 if absent. */
    long kC2Flags;
    /** Chunks the GPU handed back to the CPU because of such a disagreement. */
    public static final LongAdder C2D_CPU = new LongAdder();
    long kCorner32, kCorner32Pc, kBlock32, kZero, kRecheckMark, kRecheckCorners, kRecheckBlocks;
    long perm, od, oi, of, tb, toff, tfirst, tcount, tleaf, troot, tdefer, bk;

    /** Chunks per region side. */
    public final int side = Integer.getInteger("generatoraccelerator2.gpu.region", 16);
    private int maxRegions = 16;

    // ---- GPU thread state ----
    private final ConcurrentLinkedQueue<Object> incoming = new ConcurrentLinkedQueue<>();
    /** By key, least recently used first (a lookup that uses a region moves it last). */
    private final Long2ObjectLinkedOpenHashMap<Region> regions = new Long2ObjectLinkedOpenHashMap<>(32);
    /** The next region queue a region takes (GPU thread). */
    private int regionTurn;
    private final ArrayDeque<NoiseReq> pending = new ArrayDeque<>();
    private final ArrayDeque<Batch> inflight = new ArrayDeque<>();
    private final ArrayDeque<Slot> slots = new ArrayDeque<>();
    private int slotCount;
    private final List<Region> reading = new ArrayList<>();

    private record BiomeReq(ChunkPos pos, CompletableFuture<BiomeResult> future) {}

    private record NoiseReq(ChunkPos pos, Beard beard, CompletableFuture<NoiseResult> future, long since) {}

    GpuWorldgen(GpuDevice device, String name, NoiseGeneratorSettings settings, RandomState randomState) {
        this.device = device;
        this.cl = device.cl;
        this.name = name;
        this.settings = settings;
        this.randomState = randomState;
    }

    public State state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    /** The world closed. Whatever the program build creates after this is released again ({@code gpu.Gpu.prepare}). */
    volatile boolean closed;
    /** A failed dimension's close is queued on the GPU thread (once). */
    private boolean closeQueued;

    public boolean closed() {
        return closed;
    }

    void fail(String why) {
        if (state != State.FAILED) {
            reason = why;
            state = State.FAILED;
            GpuDevice.LOGGER.warn("off for {}: {}", name, why);
            device.wake(); // the pump fails what is queued
        }
    }

    // ---- setup ----------------------------------------------------------------------------------------------------

    /** The generator's global fluid picker, when known before {@link #prepare} (null: vanilla's). */
    public net.minecraft.world.level.levelgen.Aquifer.FluidPicker fluidPicker;
    /** {@link #fluidPicker} as the program writes it ({@link PickerShape}), or null for vanilla's. */
    private java.util.List<int[]> pickerIntervals;
    /** The settings' default fluid is lava ({@link ClEmitter#fluidLava}). */
    private boolean fluidLava;
    /** The dimension's height, when known before {@link #prepare}. The noise range is clamped to it, as a NoiseChunk clamps it. */
    public net.minecraft.world.level.LevelHeightAccessor levelHeights;

    /** Builds the program and its tables. Throws {@link ClEmitter.Unsupported} (or an OpenCL error) when the GPU cannot do this world. */
    void prepare(BiomeSource source) {
        ProgramSet set = ((Ga2RandomState) (Object) randomState).ga2$programs();
        if (set == null) {
            throw new ClEmitter.Unsupported("GA2's density function compiler did not compile this router");
        }
        if (set.column() == null) {
            throw new ClEmitter.Unsupported("the router has no interpolated functions");
        }
        if (!set.column().statefulMarkers.isEmpty()) {
            throw new ClEmitter.Unsupported("the router keeps per-chunk state (" + set.column().statefulMarkers.size() + " stateful markers)");
        }
        for (var cell : set.cells()) {
            if (!cell.statefulMarkers.isEmpty()) {
                throw new ClEmitter.Unsupported("the router keeps per-chunk state in its cell functions");
            }
        }
        // NoiseChunk.forChunk fills the noise range clamped to the level's range. Jaden's Nether has a range reaching below or above the world.
        NoiseSettings ns = levelHeights != null ? settings.noiseSettings().clampToHeightAccessor(levelHeights) : settings.noiseSettings();
        cellWidth = ns.getCellWidth();
        cellHeight = ns.getCellHeight();
        minY = ns.minY();
        height = ns.height();
        if (height <= 0 || 16 % cellWidth != 0 || height % 16 != 0 || height % cellHeight != 0 || minY % 16 != 0) {
            throw new ClEmitter.Unsupported("noise settings with cells of " + cellWidth + "x" + cellHeight + " over " + minY + ".." + (minY + height));
        }
        boolean aquifers = settings.isAquifersEnabled();
        boolean veins = settings.oreVeinsEnabled();
        if (aquifers || veins) {
            long[] aq = seeds(randomState.aquiferRandom());
            long[] ore = seeds(randomState.oreRandom());
            aqLo = aq[0];
            aqHi = aq[1];
            oreLo = ore[0];
            oreHi = ore[1];
        }
        BlockState fluid = settings.defaultFluid();
        if (aquifers && fluid != Blocks.WATER.defaultBlockState() && fluid != Blocks.LAVA.defaultBlockState()) {
            throw new ClEmitter.Unsupported("an aquifer with " + fluid + " as the default fluid");
        }
        dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps.decideHooks();
        if (aquifers && !dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets.aquiferVanilla()) {
            // the program decides every block's aquifer as vanilla's code does (YUNG's Better Caves changes it)
            throw new ClEmitter.Unsupported("other mods change the aquifer " + dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets.aquiferChanges());
        }
        fluidLava = fluid == Blocks.LAVA.defaultBlockState();
        states = new BlockState[] { Blocks.AIR.defaultBlockState(), settings.defaultBlock(), fluid, Blocks.LAVA.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(), Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState() };
        int motion = 0, nonAir = 0, fluids = 0;
        for (int id = 0; id < states.length; id++) {
            BlockState s = states[id];
            if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(s)) {
                motion |= 1 << id;
            }
            if (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(s)) {
                nonAir |= 1 << id;
            }
            if (!s.getFluidState().isEmpty()) {
                fluids |= 1 << id;
            }
        }
        if (states[ClEmitter.ID_STONE].isAir()) {
            throw new ClEmitter.Unsupported("the default block is air");
        }
        // the generator's own picker as intervals of y, or null for vanilla's. A subclass may install one (The Undergarden does).
        pickerIntervals = fluidPicker == null ? null : PickerShape.of(fluidPicker, minY, height, settings.seaLevel(), states, aquifers);
        beardKernel = BeardKernelAccessor.ga2$beardKernel().clone();
        biomesOff = biomeSetup(source);
        String noiseOff = dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps.noiseHooksOff();
        if (noiseOff != null && biomesOff != null) {
            // the GPU would never be asked for noise or biomes. The surface reads the GPU's terrain, so it would not be asked either.
            // Skip the program, its build on a CPU core and its device memory.
            throw new ClEmitter.Unsupported("nothing for the GPU to do: " + noiseOff + ", and the biomes are decided on the CPU (" + biomesOff + ")");
        }
        if (biomesOff != null && source != null) {
            // the surface reads the biomes the chunks hold, whoever decided them. Every biome the source can return gets an index.
            Map<Holder<Biome>, Integer> index = new IdentityHashMap<>();
            for (Holder<Biome> h : source.possibleBiomes()) {
                if (index.putIfAbsent(h, biomeList.size()) == null) {
                    biomeList.add(h);
                }
            }
            this.biomeIndex = index;
        }
        if (!SURFACE) {
            surfaceOff = "off (-Dgeneratoraccelerator2.gpu.surface=false)";
        } else if (biomeList.isEmpty()) {
            surfaceOff = "no biomes known (biome source " + (source == null ? "unknown" : Names.mojangName(source.getClass())) + ")";
        } else if (!aquifers) {
            surfaceOff = "no aquifers (no surface levels on the GPU)";
        } else if (surfaceGenerator == null) {
            surfaceOff = "no generator";
        } else if ((surfaceOff = dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob.disallowed()) != null) {
            // the surface step would never ask. Skip the surface program and its build time.
        } else {
            try {
                surfaceSpec = new dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec(surfaceGenerator, settings, randomState, Arrays.asList(states),
                    biomeList, worldSeed, minY, height, levelHeights);
                surfaceOff = null;
            } catch (dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec.Unsupported e) {
                surfaceOff = e.getMessage();
            }
        }

        Node finalDensity = set.routerRoots().get("final_density");
        if (finalDensity == null) {
            throw new ClEmitter.Unsupported("the router has no final density");
        }
        Node beard = new Node.Beardifier(DensityFunctions.BeardifierMarker.INSTANCE, 0.0, 0.0);
        Node cellRoot = new Node.Ap2(Node.Ap2Type.ADD, finalDensity, beard, finalDensity.min(), finalDensity.max());
        ClEmitter g = null;
        String src = null;
        if (GpuConfig.certified()) {
            g = emitter(set, cellRoot, aquifers, veins, motion, nonAir, fluids);
            g.certify = true;
            try {
                src = g.source();
                certified = true;
            } catch (ClEmitter.Unsupported e) {
                certifiedOff = e.getMessage();
                g = null;
            }
        } else {
            certifiedOff = "precision fp64 in the config";
        }
        if (g == null) {
            g = emitter(set, cellRoot, aquifers, veins, motion, nonAir, fluids);
            src = g.source();
        }
        this.gen = g;
        this.sourceChars = src.length();
        // -Dgeneratoraccelerator2.gpu.dumpSource=<dir>: the generated program, for reading
        String dump = System.getProperty("generatoraccelerator2.gpu.dumpSource");
        if (dump != null) {
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(dump);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve(Integer.toHexString(src.hashCode()) + ".cl"), src);
            } catch (java.io.IOException e) {
                // a debugging aid only
            }
        }
        rsize = g.prec == ClEmitter.Prec.F64 ? 8 : 4;
        int per = 16 / cellWidth;
        cellsPerChunk3d = per * per * (height / cellHeight);

        long t0 = System.nanoTime();
        StringBuilder log = new StringBuilder();
        program = ProgramCache.build(device, src, "-cl-std=CL1.2 -cl-fp32-correctly-rounded-divide-sqrt" + buildDefines, log);
        buildMs = (System.nanoTime() - t0) / 1e6;
        kFlat = cl.createKernel(program, "k_flat");
        kHoist = cl.createKernel(program, "k_hoist");
        kCorner = certified ? 0 : cl.createKernel(program, "k_corner"); // a certified program runs the float corners and blocks
        kBHoist = cl.createKernel(program, "k_bhoist");
        kSurface = cl.createKernel(program, "k_surface");
        kSurfaceIn = cl.createKernel(program, "k_surface_in");
        kStatus = cl.createKernel(program, "k_status");
        kLevels = cl.createKernel(program, "k_levels");
        if (g.c2dKind == ClEmitter.C2d.FLOODED) {
            kC2Flags = cl.createKernel(program, "k_c2flags");
        }
        // the program has the climate kernels only when it decides biomes
        kClimate = decidesBiomes() ? cl.createKernel(program, "k_climate") : 0;
        kBiome = decidesBiomes() ? cl.createKernel(program, "k_biome") : 0;
        kCornerPc = certified ? 0 : cl.createKernel(program, "k_corner_pc");
        kBlock = certified ? 0 : cl.createKernel(program, "k_block");
        kPack = cl.createKernel(program, "k_pack");
        kHeights = cl.createKernel(program, "k_heights");
        kTerrain = cl.createKernel(program, "k_terrain");
        if (certified) {
            kCorner32 = cl.createKernel(program, "k_corner32");
            kCorner32Pc = cl.createKernel(program, "k_corner32_pc");
            kBlock32 = cl.createKernel(program, "k_block32");
            kZero = cl.createKernel(program, "k_zero");
            kRecheckMark = cl.createKernel(program, "k_recheck_mark");
            kRecheckCorners = cl.createKernel(program, "k_recheck_corners");
            kRecheckBlocks = cl.createKernel(program, "k_recheck_blocks");
        }
        long ro = ClApi.MEM_READ_ONLY;
        perm = cl.createBuffer(device.context, ro, bytes(g.permTable()));
        od = cl.createBuffer(device.context, ro, doubles(g.od));
        oi = cl.createBuffer(device.context, ro, ints(g.oi));
        of = cl.createBuffer(device.context, ro, floats(g.of));
        tb = cl.createBuffer(device.context, ro, longs(treeBounds != null ? treeBounds : new long[2]));
        toff = cl.createBuffer(device.context, ro, longs(treeOffset != null ? treeOffset : new long[2]));
        tfirst = cl.createBuffer(device.context, ro, ints(treeFirst != null ? treeFirst : new int[4]));
        tcount = cl.createBuffer(device.context, ro, ints(treeCount != null ? treeCount : new int[4]));
        tleaf = cl.createBuffer(device.context, ro, ints(treeLeaf != null ? treeLeaf : new int[4]));
        troot = cl.createBuffer(device.context, ro, ints(treeRoots != null ? treeRoots : new int[4]));
        tdefer = cl.createBuffer(device.context, ro, ints(treeDefer != null ? treeDefer : new int[4]));
        bk = cl.createBuffer(device.context, ro, floats(beardKernel));
        checkExterns(g);
        if (surfaceSpec != null) {
            surfaces = new dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches(cl, device.context, device.queue, device.hostQueue, program, surfaceSpec,
                height, perm, od, oi, of, device::wake);
        }
        long budget = (long) (device.info.globalMemory() * GpuConfig.vramShare());
        // each cached region also keeps its biome answers on the heap (finishRegion). They may use at most 1/128 of the heap, so a
        // large GPU does not take its share of a tight heap. A region is about 0.8 MB at 384 blocks high. Without GPU biomes a
        // region keeps no answers on the heap, and the climate tree is not laid out.
        long heapRegions = Long.MAX_VALUE;
        if (decidesBiomes()) {
            long heapPerRegion = (long) side * side * 16 * (height / 4) * (leaves.length <= 0x8000 ? Character.BYTES : Integer.BYTES);
            if (tbRegions != null) {
                // and region 0's answers of the quarts whose region tree gave TerraBlender's placeholder
                heapPerRegion += (long) side * side * 16 * (height / 4) * Integer.BYTES;
            }
            heapRegions = Math.max(16, Runtime.getRuntime().maxMemory() / 128 / Math.max(1, heapPerRegion));
        }
        maxRegions = (int) Math.max(4, Math.min(Math.min(256, heapRegions), budget / Math.max(1, regionBytes())));
    }

    /**
     * Checks each extern sampler the program computes against the mod's own object, at a spread of points on this GPU. An extern
     * sampler is another mod's noise, computed as fast noise. Throws when one point differs. The dimension then stays on the CPU.
     */
    private void checkExterns(ClEmitter g) {
        for (var e : g.externOffsets.entrySet()) {
            int index = e.getKey(), n = 2048;
            int[] off = e.getValue();
            java.util.Random r = new java.util.Random(0x657874L + index);
            double[] xyz = new double[n * 3];
            for (int i = 0; i < n; i++) {
                double span = i < n / 2 ? 4096.0 : 3.0e7;
                xyz[i * 3] = (r.nextDouble() * 2.0 - 1.0) * span;
                xyz[i * 3 + 1] = (r.nextDouble() * 2.0 - 1.0) * 512.0;
                xyz[i * 3 + 2] = (r.nextDouble() * 2.0 - 1.0) * span;
                if ((i & 3) == 0) {
                    for (int a = 0; a < 3; a++) {
                        xyz[i * 3 + a] = Math.floor(xyz[i * 3 + a]);
                    }
                }
            }
            long k = cl.createKernel(program, "k_fnl_cellular_test");
            long in = cl.createBuffer(device.context, ClApi.MEM_READ_ONLY, doubles(xyz));
            long out = cl.createBuffer(device.context, ClApi.MEM_WRITE_ONLY, (long) n * 4);
            try {
                cl.argMem(k, 0, oi);
                cl.argMem(k, 1, of);
                cl.argInt(k, 2, off[0]);
                cl.argInt(k, 3, off[1]);
                cl.argInt(k, 4, off[2]);
                cl.argMem(k, 5, in);
                cl.argInt(k, 6, n);
                cl.argMem(k, 7, out);
                cl.releaseEvent(cl.launch(device.queue, k, n, 64));
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder());
                cl.releaseEvent(cl.read(device.queue, out, true, 0, b));
                for (int i = 0; i < n; i++) {
                    float want = (float) dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers.sample(index, xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
                    float got = b.getFloat(i * 4);
                    if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)) {
                        throw new ClEmitter.Unsupported("another mod's noise (extern " + index + ") differs on this GPU at " + xyz[i * 3] + ", " + xyz[i * 3 + 1] + ", "
                            + xyz[i * 3 + 2] + ": " + want + " vs " + got);
                    }
                }
            } finally {
                cl.releaseKernel(k);
                cl.releaseMem(in);
                cl.releaseMem(out);
            }
        }
    }

    /** Each id's lowest id with the same state ({@link ClEmitter#canonicalIds}), or null when the states are all different. */
    static int[] canonicalIds(BlockState[] states) {
        int[] canon = null;
        for (int id = 0; id < states.length; id++) {
            for (int j = 0; j < id; j++) {
                if (states[j] == states[id]) {
                    if (canon == null) {
                        canon = new int[states.length];
                        for (int k = 0; k < canon.length; k++) {
                            canon[k] = k;
                        }
                    }
                    canon[id] = j;
                    break;
                }
            }
        }
        return canon;
    }

    private ClEmitter emitter(ProgramSet set, Node cellRoot, boolean aquifers, boolean veins, int motion, int nonAir, int fluids) {
        List<dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec> specs = set.specs();
        var sf = surfaceSpec;
        if (sf != null) {
            // the surface's noises after the router's, in the same tables
            specs = new ArrayList<>(specs);
            sf.specBase = specs.size();
            specs.addAll(sf.noiseSpecs);
        }
        ClEmitter g = new ClEmitter(ClEmitter.Prec.F64, true, specs, set.blendedSpecs(), set.column().rootMarkers, cellRoot, set.routerRoots(),
            cellWidth, cellHeight, minY, height, settings.seaLevel());
        if (sf != null) {
            g.extra = e -> sf.source(e.commonArgs(), "", e.commonInit(), ClEmitter.META);
        }
        g.aquifers = aquifers;
        g.veins = veins;
        g.pickerIntervals = pickerIntervals;
        g.fluidLava = fluidLava;
        g.canonicalIds = canonicalIds(states);
        g.biomes = biomesOff == null;
        g.biomeRegions = biomesOff == null && tbRegions != null;
        g.motionMask = motion;
        g.nonAirMask = nonAir;
        g.fluidMask = fluids;
        return g;
    }

    /** Why the GPU does not decide this dimension's biomes, or null when it does. In that case the climate tree is laid out flat. */
    private String biomeSetup(BiomeSource source) {
        if (!GpuConfig.biomes()) {
            return "off in the config";
        }
        if (source == null || source.getClass() != MultiNoiseBiomeSource.class) {
            return "biome source " + (source == null ? "unknown" : source.getClass().getName());
        }
        if (!dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.allowed(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.GPU_BIOMES)) {
            return "other mods change the biome step or biome source " + dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.foreign(
                dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.GPU_BIOMES);
        }
        MultiNoiseBiomeSource multi = (MultiNoiseBiomeSource) source;
        Climate.ParameterList<Holder<Biome>> list = ((GpuBiomeSourceInspectMixin) multi).ga2$gpuParameters();
        BiomeRegions tb;
        try {
            tb = testRegions != null ? testRegions.apply(list) : dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderBiomes.of(list);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        @SuppressWarnings("unchecked")
        Climate.RTree<Holder<Biome>>[] trees = tb != null ? tb.trees() : new Climate.RTree[] { list.index };
        if (trees[0] == null) {
            return "the parameter list builds its climate tree lazily";
        }
        // the trees one after another. A tree's nodes are laid out breadth first, so the children of a node are contiguous
        List<Climate.RTree.Node<Holder<Biome>>> order = new ArrayList<>();
        List<int[]> children = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        int[] roots = new int[trees.length];
        for (int t = 0; t < trees.length; t++) {
            if (trees[t] == null) {
                roots[t] = -1;
                continue;
            }
            int head = order.size();
            roots[t] = head;
            order.add(trees[t].root);
            while (head < order.size()) {
                Climate.RTree.Node<Holder<Biome>> node = order.get(head++);
                owner.add(t);
                if (node instanceof Climate.RTree.SubTree<Holder<Biome>> sub) {
                    int start = order.size();
                    order.addAll(Arrays.asList(sub.children));
                    children.add(new int[] { start, sub.children.length });
                } else {
                    children.add(new int[] { 0, 0 });
                }
            }
        }
        int n = order.size();
        treeBounds = new long[n * 12];
        treeOffset = new long[n];
        treeFirst = new int[n];
        treeCount = new int[n];
        treeLeaf = new int[n];
        nodeTree = new int[n];
        treeDefer = tb != null ? new int[n] : null;
        @SuppressWarnings("unchecked")
        Climate.RTree.Leaf<Holder<Biome>>[] ls = new Climate.RTree.Leaf[n];
        Map<Holder<Biome>, Integer> index = new IdentityHashMap<>();
        this.biomeIndex = index;
        for (int id = 0; id < n; id++) {
            nodeTree[id] = owner.get(id);
            Climate.RTree.Node<Holder<Biome>> node = order.get(id);
            Climate.Parameter[] space = node.parameterSpace;
            for (int d = 0; d < 6; d++) {
                treeBounds[id * 12 + 2 * d] = space[d].min();
                treeBounds[id * 12 + 2 * d + 1] = space[d].max();
            }
            long above = -space[6].max();
            long below = space[6].min();
            long t = above > 0 ? above : Math.max(below, 0L);
            treeOffset[id] = t * t;
            treeFirst[id] = children.get(id)[0];
            treeCount[id] = children.get(id)[1];
            if (node instanceof Climate.RTree.Leaf<Holder<Biome>> leaf) {
                ls[id] = leaf;
                if (tb != null && tb.deferred(leaf.value)) {
                    // never a chunk's biome. The quart takes region 0's answer instead
                    treeDefer[id] = 1;
                    treeLeaf[id] = -1;
                    continue;
                }
                Integer b = index.get(leaf.value);
                if (b == null) {
                    b = biomeList.size();
                    biomeList.add(leaf.value);
                    index.put(leaf.value, b);
                }
                treeLeaf[id] = b;
            }
        }
        this.biomeSource = multi;
        this.biomeTrees = trees;
        this.tbRegions = tb;
        this.treeRoots = roots;
        this.leaves = ls;
        return null;
    }

    private static long[] seeds(PositionalRandomFactory f) {
        if (f.getClass() != XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class) {
            throw new ClEmitter.Unsupported("a " + Names.mojangSimpleName(f.getClass()) + " for the aquifer and ore veins (legacy random source)");
        }
        XoroshiroFactoryAccessor a = (XoroshiroFactoryAccessor) f;
        return new long[] { a.ga2$seedLo(), a.ga2$seedHi() };
    }

    private static final long[] NO_TIES = new long[0];

    /** Device bytes of one region's buffers. */
    private long regionBytes() {
        int w = side;
        int per = 16 / cellWidth;
        long nqx = 4L * w + 1, ncx = (long) per * w + 1, ncy = height / cellHeight + 1, nbx = 16L * w;
        long b = Math.max(1, gen.flats.size()) * nqx * nqx * rsize;
        b += Math.max(1, gen.hoistCount()) * ncx * ncx * rsize;
        b += gen.perChunkCorners ? 16 : (long) gen.roots.size() * ncx * ncx * ncy * rsize;
        b += gen.blockHoisted.isEmpty() ? 16 : nbx * nbx * gen.blockHoisted.size() * rsize;
        b += (4L * w + 23) * (4L * w + 15) * 4 + (long) w * w * 25 * 4;
        b += (long) w * w * gen.cellsPerChunk * 5 * 4;
        if (decidesBiomes()) {
            // quart biomes, climate columns, the tie list and its count
            b += (long) w * w * 16 * (height / 4) * 4 + (long) w * w * 16 * 6 * rsize + (long) tieCap() * 7 * 8 + 16;
            if (tbRegions != null) {
                // the region of every quart column, and region 0's answers
                b += (long) w * w * 16 * 4 + (long) w * w * 16 * (height / 4) * 4;
            }
        }
        b += (long) w * w * 4;
        if (gen.c2dKind == ClEmitter.C2d.FLOODED) {
            b += (long) w * w * gen.cellsPerChunk * 4 + (long) w * w * 4;
        }
        return b;
    }

    // ---- requests (any thread) -----------------------------------------------------------------------------------

    public boolean ready() {
        return state == State.READY;
    }

    // ---- governor: a GPU busy with other work (a client's frames, another program) never holds chunks back ----

    /** A GPU answer later than this many milliseconds leaves its chunk to the CPU ({@code -Dgeneratoraccelerator2.gpu.answerMs}). */
    public static final long ANSWER_MS = Long.getLong("generatoraccelerator2.gpu.answerMs", 400L);
    /** How long a dimension's chunks go to the CPU once many answers were late ({@code -Dgeneratoraccelerator2.gpu.pauseSeconds}). */
    private static final long PAUSE_NS = Long.getLong("generatoraccelerator2.gpu.pauseSeconds", 30L) * 1_000_000_000L;
    /** Answers given to the CPU because they were late, and the times a dimension stopped asking for a while. */
    public static final LongAdder LATE_ANSWERS = new LongAdder(), PAUSES = new LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger windowAsked = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger windowLate = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long pausedUntil;
    private volatile boolean paused;

    /** False while late answers keep this dimension's chunks on the CPU ({@link #timed}). */
    public boolean asking() {
        if (!paused) {
            return true;
        }
        if (System.nanoTime() - pausedUntil < 0) {
            return false;
        }
        paused = false;
        return true;
    }

    /**
     * Returns {@code answer}, or null when the GPU has not given it within {@link #ANSWER_MS} of now. On null, the CPU does the chunk.
     * If more than 4 of a window of 64 answers are late, this dimension stops asking for {@link #PAUSE_NS}. Such a GPU is slower than
     * the CPU it was meant to relieve, and more asks only add load. Counting starts after the asks are made, so the CPU's own queues
     * do not count.
     */
    public <T> CompletableFuture<T> timed(CompletableFuture<T> answer) {
        if (answer.isDone()) {
            answered(false);
            return answer;
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        java.util.concurrent.ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
            if (out.complete(null)) {
                answered(true);
            }
        }, ANSWER_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        answer.whenComplete((r, e) -> {
            // cancel the deadline as soon as the answer comes. That also releases the deadline's hold on the answer.
            deadline.cancel(false);
            boolean won = e != null ? out.completeExceptionally(e) : out.complete(r);
            if (won) {
                answered(false);
            }
        });
        return out;
    }

    /** Every dimension's answer deadlines ({@link #timed}), on one daemon thread. A cancelled deadline leaves the queue at once. */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        java.util.concurrent.ScheduledThreadPoolExecutor e = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "GA2 GPU deadlines");
            t.setDaemon(true);
            return t;
        });
        e.setRemoveOnCancelPolicy(true);
        return e;
    }

    /** Counts one answer for the governor ({@link #timed}). A late surface counts too. */
    public void answered(boolean late) {
        if (late) {
            LATE_ANSWERS.increment();
            windowLate.incrementAndGet();
        }
        if (windowAsked.incrementAndGet() >= 64) {
            int lateOnes = windowLate.getAndSet(0);
            windowAsked.set(0);
            if (lateOnes > 4 && !paused) {
                pausedUntil = System.nanoTime() + PAUSE_NS;
                paused = true;
                PAUSES.increment();
                GpuDevice.LOGGER.info("{}/64 recent chunks of {} answered after >{} ms -> CPU for {} s",
                    lateOnes, name, ANSWER_MS, PAUSE_NS / 1_000_000_000L);
            }
        }
    }

    public boolean decidesBiomes() {
        return biomesOff == null;
    }

    private volatile Object terrainSpec;
    /** Why this dimension's chunks keep their NoiseChunk (once asked), or null. */
    private volatile String terrainOff;

    /** The SURFACE step on the GPU ({@code gpu.surface}), or null with the reason in {@link #surfaceOff}. */
    public dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec surfaceSpec;
    public dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches surfaces;
    /** The GPU's copy of chunks for decoration ({@code gpu.decor}, an option), or null. Set on the GPU thread once the decoration program is built. */
    public volatile dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor decor;
    /** GPU decoration's own program (0 until built), and why it is not on. */
    private long decorProgram;
    private volatile String decorOff = "not asked for";
    private double decorBuildMs;

    /**
     * Sets up GPU decoration (an option). Decoration has its own program, built after this dimension's program. That build is long,
     * and the noise and surface do not wait for it. The decoration objects come next and are handed to the GPU thread. Chunks whose
     * surfaces were made before then decorate on the CPU.
     */
    public void prepareDecoration() {
        if (!dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor.ENABLED) {
            return;
        }
        if (surfaces == null || surfaceSpec == null) {
            decorOff = "the surface is on the CPU";
            return;
        }
        decorOff = "building its program";
        try {
            int wins = surfaceSpec.windowInts;
            int voidAir = dev.denismasterherobrine.generatoraccelerator2.gpu.decor.StateIds.voidAir(surfaceSpec.states);
            String src = ClEmitter.decorPrelude() + surfaceSpec.decorHelpers() + dev.denismasterherobrine.generatoraccelerator2.gpu.decor.Resident.source(height, wins)
                + dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor.kernelSource(height, minY, wins, 256L * height, voidAir);
            String dump = System.getProperty("generatoraccelerator2.gpu.dumpSource");
            if (dump != null) {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(dump));
                java.nio.file.Files.writeString(java.nio.file.Path.of(dump).resolve("decor-" + Integer.toHexString(src.hashCode()) + ".cl"), src);
            }
            long t0 = System.nanoTime();
            long prog = ProgramCache.build(device, src, "-cl-std=CL1.2 -cl-fp32-correctly-rounded-divide-sqrt" + buildDefines, new StringBuilder());
            decorBuildMs = (System.nanoTime() - t0) / 1e6;
            var d = new dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor(surfaceSpec.states, minY, height, device::wake);
            d.resident = new dev.denismasterherobrine.generatoraccelerator2.gpu.decor.Resident(cl, device.context, device.queue, device.residentQueue, device.hostQueue, prog, height,
                wins, (long) (device.info.globalMemory() * GpuConfig.vramShare() / 2));
            d.resident.onEvict = d::evicted;
            d.prepare(cl, device.context, device.queue, device.decorQueues, device.hostQueue, prog, surfaceGenerator, biomeList, surfaces.table());
            device.submit(() -> {
                if (state == State.FAILED || surfaces == null) {
                    d.close();
                    cl.releaseProgram(prog);
                    return;
                }
                decorProgram = prog;
                decor = d;
                surfaces.resident = d.resident;
                decorOff = null;
            });
        } catch (Throwable e) {
            decorOff = "its program failed: " + e;
            GpuDevice.LOGGER.warn("decoration off for {}", name, e);
        }
    }
    public String surfaceOff = "not set up";
    private net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator surfaceGenerator;
    private long worldSeed;
    /** The GPU's biome index of each biome the source returns. */
    public Map<Holder<Biome>, Integer> biomeIndex = Map.of();
    /** {@code -Dgeneratoraccelerator2.gpu.surface=false}: the SURFACE step stays on the CPU. */
    private static final boolean SURFACE = !"false".equals(System.getProperty("generatoraccelerator2.gpu.surface"));

    /** What the GPU surface needs besides the router: the generator (its vertical anchors) and the world seed (the biome zoom). */
    public void surfaceInputs(net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator, long seed) {
        this.surfaceGenerator = generator;
        this.worldSeed = seed;
    }

    /**
     * The spec that turns a chunk's GPU aquifer cells and surface levels into what the chunk's SURFACE and CARVERS steps read
     * ({@link dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain}). Null when this dimension's chunks keep their NoiseChunk.
     */
    public dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.Spec terrainSpec(java.util.function.Supplier<net.minecraft.world.level.levelgen.Aquifer.FluidPicker> picker) {
        Object s = terrainSpec;
        if (s == null) {
            // a walk-only cache_2d (ClEmitter.C2d.WALK) gives the walk's first value at a column. The GPU's levels are computed that
            // way, and so are the spec's walks beyond the chunk (GpuTerrain.Spec#walkLevels).
            s = gen.aquifers ? dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.Spec.of(randomState, gen.cellsPerChunk,
                gen.minGridY(), minY, height, cellHeight, settings.defaultFluid(), picker.get(), gen.flatCachesXz("barrier")) : null;
            if (s == null) {
                terrainOff = !gen.aquifers ? "no aquifers" : dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.Spec.unsupported(randomState, gen.flatCachesXz("barrier"));
            }
            terrainSpec = s = s == null ? Boolean.FALSE : s;
        }
        return s instanceof dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.Spec spec ? spec : null;
    }

    /** The biomes of a chunk. Completes with null when the GPU cannot give them exactly, and exceptionally on a GPU failure. */
    public CompletableFuture<BiomeResult> biomes(ChunkPos pos) {
        CompletableFuture<BiomeResult> f = new CompletableFuture<>();
        if (state == State.FAILED) {
            // no pump drains a failed dimension's queue once it is closed
            f.completeExceptionally(new IllegalStateException("GPU generation is off: " + reason));
            return f;
        }
        incoming.add(new BiomeReq(pos, f));
        device.wake();
        return f;
    }

    /** The blocks of a chunk with these structures. */
    public CompletableFuture<NoiseResult> noise(ChunkPos pos, Beard beard) {
        CompletableFuture<NoiseResult> f = new CompletableFuture<>();
        if (state == State.FAILED) {
            f.completeExceptionally(new IllegalStateException("GPU generation is off: " + reason));
            return f;
        }
        incoming.add(new NoiseReq(pos, beard, f, System.nanoTime()));
        device.wake();
        return f;
    }

    // ---- GPU thread ------------------------------------------------------------------------------------------------

    private final class Region {
        final long key;
        final int cx0, cz0;
        long flat, hz, corners, bh, surf, surfIn, cells, levels, biomes;
        /** Certified float corners and their errors (instead of {@link #corners}). */
        long corners32, cornersE;
        long readEvent;
        ByteBuffer biomeHost;
        /** The pinned readback buffer while the biomes are read (back to the pool after). */
        RegionHost host;
        /**
         * The region's quart answers ({@link BiomeResult#quarts}, chunk by chunk) while the region is cached. They are chars when the
         * tree's node ids fit: the leaf in the low 15 bits, the tie mark in bit 15. That halves what a cached region holds on the heap.
         * Otherwise they are ints.
         */
        char[] quartChars;
        int[] quarts;
        /** With TerraBlender's regions: per quart, region 0's leaf where the region's tree gave the placeholder ({@link BiomeResult#fallbacks}). */
        int[] fallbacks;
        /** Per chunk, its listed ties ({@link BiomeResult#ties}). Then whether the region's list held every tie, and the list's capacity. */
        long[][] ties;
        boolean tiesComplete;
        int tieCap;
        final List<BiomeReq> waiting = new ArrayList<>();
        int users;
        long launched;
        /** The region queue's event after the work that batches read (corners, hoists, aquifer cells). 0 once known complete. */
        long done;
        /** Per chunk, whether the columns of its aquifer cells disagree ({@link ClEmitter.C2d#FLOODED}), or null. Read before {@link #done}. */
        ByteBuffer c2host;

        Region(long key, int cx0, int cz0) {
            this.key = key;
            this.cx0 = cx0;
            this.cz0 = cz0;
        }

        /** Chunks whose blocks were answered (bit per chunk, x * side + z), and how many. */
        final long[] answered = new long[(side * side + 63) >> 6];
        int answeredCount;

        /** Marks the chunk answered. True when that makes every chunk of the region answered. */
        boolean answered(ChunkPos pos) {
            int i = (pos.x - cx0) * side + (pos.z - cz0);
            long bit = 1L << (i & 63);
            if ((answered[i >> 6] & bit) == 0) {
                answered[i >> 6] |= bit;
                return ++answeredCount == side * side;
            }
            return false;
        }

        boolean biomesReady() {
            return quarts != null || quartChars != null;
        }

        /** The pooled device buffers this region uses (back to the pool when it is dropped). */
        RegionBuffers rb;

        void release() {
            if (readEvent != 0) {
                cl.releaseEvent(readEvent);
                readEvent = 0;
            }
            if (done != 0) {
                cl.releaseEvent(done);
                done = 0;
            }
            if (host != null) {
                regionHosts.add(host);
                host = null;
            }
            freeRegionBuffers.add(rb);
            rb = null;
        }
    }

    /** One region's device buffers, pooled. Allocating them per region cost more GPU thread time than the region's kernels. */
    private static final class RegionBuffers {
        long flat, hz, corners, corners32, cornersE, bh, surf, surfIn, cells, levels, biomes, climate, tieList, tieCount;
        /** With TerraBlender's regions: the region of every quart column, and region 0's answers. 0 otherwise. */
        long regionMap, fallbacks;
        /** Ties {@link #tieList} holds ({@link #tieCap()} when it was made). */
        int tieCap;
        /** For {@link ClEmitter.C2d#FLOODED}. Per aquifer cell, whether the cell's column disagrees. Per chunk, the same, with a host copy. */
        long c2, c2flags;
        ByteBuffer c2host;

        long[] all() {
            return new long[] { flat, hz, corners, corners32, cornersE, bh, surf, surfIn, cells, levels, biomes, climate, tieList, tieCount, c2, c2flags, regionMap,
                fallbacks };
        }
    }

    private final ArrayDeque<RegionBuffers> freeRegionBuffers = new ArrayDeque<>();
    private final List<RegionBuffers> allRegionBuffers = new ArrayList<>();

    private RegionBuffers takeRegionBuffers() {
        RegionBuffers b = freeRegionBuffers.poll();
        if (b != null) {
            if (decidesBiomes() && b.tieCap < tieCap()) {
                // the dimension's tie lists grew since these were made
                cl.releaseMem(b.tieList);
                b.tieCap = tieCap();
                b.tieList = cl.createBuffer(device.context, ClApi.MEM_WRITE_ONLY, (long) b.tieCap * 7 * 8);
            }
            return b;
        }
        ClEmitter g = gen;
        long ctx = device.context;
        int w = side, h = side, nchunks = w * h;
        int per = 16 / cellWidth;
        int nqx = 4 * w + 1, nqz = 4 * h + 1, ncx = per * w + 1, ncz = per * h + 1, ncy = height / cellHeight + 1, nbx = 16 * w, nbz = 16 * h;
        int nsx = 4 * w + 23, nsz = 4 * h + 15;
        long rw = ClApi.MEM_READ_WRITE;
        b = new RegionBuffers();
        b.flat = cl.createBuffer(ctx, rw, (long) Math.max(1, g.flats.size()) * nqx * nqz * rsize);
        b.hz = cl.createBuffer(ctx, rw, (long) Math.max(1, g.hoistCount()) * ncx * ncz * rsize);
        long regionCorners = (long) g.roots.size() * ncx * ncz * ncy;
        b.corners = cl.createBuffer(ctx, rw, g.perChunkCorners || certified ? 16 : regionCorners * rsize);
        if (certified) {
            b.corners32 = cl.createBuffer(ctx, rw, g.perChunkCorners ? 16 : regionCorners * 4);
            b.cornersE = cl.createBuffer(ctx, rw, g.perChunkCorners ? 16 : regionCorners * 4);
        }
        // nothing x/z-only at block level (vanilla): k_bhoist is not launched and the blocks never read it
        b.bh = cl.createBuffer(ctx, rw, g.blockHoisted.isEmpty() ? 16 : (long) nbx * nbz * g.blockHoisted.size() * rsize);
        b.surf = cl.createBuffer(ctx, rw, (long) nsx * nsz * 4);
        b.surfIn = cl.createBuffer(ctx, rw, (long) nchunks * 25 * 4);
        b.cells = cl.createBuffer(ctx, rw, (long) nchunks * g.cellsPerChunk * 5 * 4);
        b.levels = cl.createBuffer(ctx, rw, (long) nchunks * 4);
        if (g.c2dKind == ClEmitter.C2d.FLOODED) {
            b.c2 = cl.createBuffer(ctx, rw, (long) nchunks * g.cellsPerChunk * 4);
            b.c2flags = cl.createBuffer(ctx, rw, (long) nchunks * 4);
            b.c2host = direct(nchunks * 4);
        }
        if (decidesBiomes()) {
            int quarts = nchunks * 16 * (height / 4);
            b.biomes = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) quarts * 4);
            b.climate = cl.createBuffer(ctx, rw, (long) nchunks * 16 * 6 * rsize);
            b.tieCap = tieCap();
            b.tieList = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) b.tieCap * 7 * 8);
            b.tieCount = cl.createBuffer(ctx, rw, 16);
            if (tbRegions != null) {
                b.regionMap = cl.createBuffer(ctx, ClApi.MEM_READ_ONLY, (long) nchunks * 16 * 4);
                b.fallbacks = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) quarts * 4);
            }
        }
        allRegionBuffers.add(b);
        return b;
    }

    /** Buffers for one batch of up to {@link GpuConfig#batchChunks} chunks, reused. */
    private final class Slot {
        final int cap;
        long lst, bidx, bp, bj, cornersPc, out, storage, meta, marks, tops, terrain;
        /**
         * Certified buffers. Per-chunk float corners and their errors. The flagged cells' marks, list and counts (cells, then corners).
         * The recheck's corner marks, corner list and double corners. The double corners are per chunk, in the corners' layout.
         */
        long cornersPc32, cornersPcE, cflag, rlist, rcount, cmark, clist, cval;
        final int rcap, ccap;
        int bpCap, bjCap;
        final ByteBuffer lstHost, bidxHost;
        ByteBuffer bpHost, bjHost;
        final ByteBuffer storageHost, metaHost, marksHost, topsHost, rcountHost, terrainHost;

        Slot(int cap) {
            this.cap = cap;
            long ctx = device.context;
            int nsec = height / 16;
            int per = 16 / cellWidth;
            int pc = (per + 1) * (per + 1);
            lst = cl.createBuffer(ctx, ClApi.MEM_READ_ONLY, cap * 4L);
            bidx = cl.createBuffer(ctx, ClApi.MEM_READ_ONLY, cap * 16L);
            bpCap = 64;
            bjCap = 64;
            bp = cl.createBuffer(ctx, ClApi.MEM_READ_ONLY, bpCap * 32L);
            bj = cl.createBuffer(ctx, ClApi.MEM_READ_ONLY, bjCap * 12L);
            long cornerCount = (long) cap * gen.roots.size() * pc * (height / cellHeight + 1);
            cornersPc = gen.perChunkCorners && !certified ? cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, cornerCount * rsize) : 0;
            // the recheck list holds an eighth of the batch's cells. With more flagged cells, the batch's chunks go to the CPU path.
            rcap = Math.max(64, cap * cellsPerChunk3d / 8);
            // at most 8 corners per listed cell. Rounded up to a multiple of 64 so each work-group of the corner recheck computes one root.
            ccap = (rcap * 8 + 63) & ~63;
            if (certified) {
                cornersPc32 = gen.perChunkCorners ? cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, cornerCount * 4) : 0;
                cornersPcE = gen.perChunkCorners ? cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, cornerCount * 4) : 0;
                cflag = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, (long) cap * cellsPerChunk3d * 4);
                rlist = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, rcap * 4L);
                rcount = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, 64);
                cmark = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, cornerCount / gen.roots.size() * 4);
                clist = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, ccap * 4L);
                cval = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, cornerCount * 8);
            }
            rcountHost = pin(64);
            out = cl.createBuffer(ctx, ClApi.MEM_READ_WRITE, (long) cap * 256 * height);
            storage = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) cap * nsec * 256 * 8);
            meta = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) cap * nsec * ClEmitter.META * 4);
            marks = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) cap * nsec * 64 * 8);
            tops = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) cap * 512 * 4);
            terrain = cl.createBuffer(ctx, ClApi.MEM_WRITE_ONLY, (long) cap * gen.terrainInts() * 4);
            lstHost = pin(cap * 4);
            bidxHost = pin(cap * 16);
            bpHost = pin(bpCap * 32);
            bjHost = pin(bjCap * 12);
            storageHost = pin(cap * nsec * 256 * 8);
            metaHost = pin(cap * nsec * ClEmitter.META * 4);
            marksHost = pin(cap * nsec * 64 * 8);
            topsHost = pin(cap * 512 * 4);
            terrainHost = gen.aquifers ? pin(cap * gen.terrainInts() * 4) : null;
        }

        /** Pinned host buffers of this slot (released with it). */
        final List<Object[]> pins = new ArrayList<>();

        ByteBuffer pin(int bytes) {
            long[] m = new long[1];
            ByteBuffer b = cl.pinned(device.context, device.hostQueue, bytes, m);
            pins.add(new Object[] { m[0], b });
            return b;
        }

        void ensure(int pieces, int junctions) {
            if (pieces > bpCap) {
                cl.releaseMem(bp);
                bpCap = Math.max(pieces, bpCap * 2);
                bp = cl.createBuffer(device.context, ClApi.MEM_READ_ONLY, bpCap * 32L);
                bpHost = pin(bpCap * 32);
            }
            if (junctions > bjCap) {
                cl.releaseMem(bj);
                bjCap = Math.max(junctions, bjCap * 2);
                bj = cl.createBuffer(device.context, ClApi.MEM_READ_ONLY, bjCap * 12L);
                bjHost = pin(bjCap * 12);
            }
        }
    }

    private final class Batch {
        final Region region;
        final Slot slot;
        final List<NoiseReq> reqs;
        final long event;
        /** Decided in certified float (else in double). */
        final boolean certified;
        final long launched = System.nanoTime();

        Batch(Region region, Slot slot, List<NoiseReq> reqs, long event, boolean certified) {
            this.region = region;
            this.slot = slot;
            this.reqs = reqs;
            this.event = event;
            this.certified = certified;
        }
    }

    /** GPU thread time in the surface batches' pump. */
    public static final java.util.concurrent.atomic.LongAdder SURFACE_PUMP_NS = new java.util.concurrent.atomic.LongAdder();

    @Override
    public long pump(long now) {
        if (state == State.FAILED) {
            failAll(new IllegalStateException("GPU generation is off: " + reason));
            if (!closeQueued) {
                // a dimension off for good: its device buffers, pinned host memory and program go now, not when the server stops
                closeQueued = true;
                device.submit(this::close);
            }
            return Long.MAX_VALUE;
        }
        try {
            return pumpUnchecked(now);
        } catch (RuntimeException e) {
            GpuDevice.LOGGER.error("generation failed for {}", name, e);
            fail(e.toString());
            failAll(e);
            return Long.MAX_VALUE;
        }
    }

    private long pumpUnchecked(long now) {
        Object o;
        while ((o = incoming.poll()) != null) {
            if (o instanceof BiomeReq b) {
                Region r = region(b.pos);
                prefetch(b.pos);
                if (r.biomesReady()) {
                    answer(r, b);
                } else {
                    r.waiting.add(b);
                }
            } else {
                NoiseReq n = (NoiseReq) o;
                region(n.pos);
                pending.add(n);
            }
        }
        // finished region readbacks and batches. Each readback is taken as it finishes. Regions on different queues finish out of order.
        for (Iterator<Region> it = reading.iterator(); it.hasNext(); ) {
            Region r = it.next();
            int st = cl.eventStatus(r.readEvent);
            if (st < 0) {
                throw new IllegalStateException("region readback failed: OpenCL status " + st);
            }
            if (st != ClApi.CL_COMPLETE) {
                continue;
            }
            it.remove();
            long h0 = System.nanoTime(); finishRegion(r); HOST_NS[2].add(System.nanoTime() - h0);
        }
        while (!inflight.isEmpty()) {
            Batch b = inflight.peek();
            int st = cl.eventStatus(b.event);
            if (st < 0) {
                throw new IllegalStateException("batch failed: OpenCL status " + st);
            }
            if (st != ClApi.CL_COMPLETE) {
                break;
            }
            inflight.poll();
            long h0 = System.nanoTime(); finishBatch(b); HOST_NS[1].add(System.nanoTime() - h0);
        }
        // new batches
        long wait = Long.MAX_VALUE;
        int max = Math.max(1, GpuConfig.batchChunks());
        long maxWait = 1_000_000L;
        boolean launched = false;
        while (!pending.isEmpty()) {
            // the oldest request whose region is done goes first. A batch launched for a region still running holds its slot, since
            // its kernels wait for the region on the device. Meanwhile, requests of finished regions would queue behind it.
            NoiseReq first = readyFirst();
            long age = now - first.since;
            boolean full = pending.size() >= max;
            if (!full && age < maxWait && !inflight.isEmpty()) {
                wait = Math.min(wait, maxWait - age);
                break;
            }
            Slot slot = takeSlot(max);
            if (slot == null) {
                break; // all slots in flight: a completion wakes the thread
            }
            Region r = regions.getAndMoveToLast(regionKey(first.pos));
            List<NoiseReq> take = new ArrayList<>();
            for (Iterator<NoiseReq> it = pending.iterator(); it.hasNext() && take.size() < slot.cap; ) {
                NoiseReq n = it.next();
                if (regionKey(n.pos) == r.key) {
                    take.add(n);
                    it.remove();
                }
            }
            long h0 = System.nanoTime(); launchBatch(r, slot, take); HOST_NS[0].add(System.nanoTime() - h0);
            launched = true;
        }
        if (launched) {
            cl.flush(device.queue);
            cl.flush(device.sideQueue);
        }
        if (surfaces != null) {
            long t0 = System.nanoTime();
            wait = Math.min(wait, surfaces.pump(now));
            SURFACE_PUMP_NS.add(System.nanoTime() - t0);
        }
        if (decor != null) {
            decor.pump(cl, device.queue);
        }
        if (PROFILE) {
            collectProfile();
            if (!profiled.isEmpty()) {
                wait = Math.min(wait, 1_000_000L);
            }
        }
        evict();
        if (incoming.isEmpty() && pending.isEmpty() && inflight.isEmpty() && reading.isEmpty() && !waiting()) {
            if (idleSince == 0) {
                idleSince = now;
            } else if (!trimmed && IDLE_TRIM_NS > 0 && now - idleSince > IDLE_TRIM_NS) {
                trim();
                trimmed = true;
            }
        } else {
            idleSince = 0;
            trimmed = false;
        }
        return wait;
    }

    /**
     * {@code -Dgeneratoraccelerator2.gpu.idleTrimSeconds=N}: a dimension that asked the GPU for nothing for N seconds gives its device memory
     * back ({@link #trim}). 0 keeps the memory until the world closes.
     */
    private static final long IDLE_TRIM_NS = Long.getLong("generatoraccelerator2.gpu.idleTrimSeconds", 30L) * 1_000_000_000L;
    private long idleSince;
    private boolean trimmed;
    /** Idle dimensions trimmed (status). */
    public static final LongAdder TRIMS = new LongAdder();

    private boolean waiting() {
        for (Region r : regions.values()) {
            if (!r.waiting.isEmpty() || r.users > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives the memory of a dimension that stopped generating back to the device. That covers the cached regions' buffers, the pooled
     * region buffers, the pinned readback buffers and the spare batch slots. Generating again allocates them anew as it goes.
     */
    private void trim() {
        for (Iterator<Region> it = regions.values().iterator(); it.hasNext(); ) {
            Region r = it.next();
            if (r.users > 0 || r.readEvent != 0 || !r.waiting.isEmpty()) {
                continue;
            }
            it.remove();
            r.release();
        }
        RegionBuffers b;
        while ((b = freeRegionBuffers.poll()) != null) {
            for (long m : b.all()) {
                cl.releaseMem(m);
            }
            allRegionBuffers.remove(b);
        }
        RegionHost h;
        while ((h = regionHosts.poll()) != null) {
            cl.unpin(device.hostQueue, h.mem, h.biomes);
            cl.unpin(device.hostQueue, h.tiesMem, h.ties);
            cl.unpin(device.hostQueue, h.tieCountMem, h.tieCount);
            allRegionHosts.remove(h);
        }
        Slot sl;
        while ((sl = slots.poll()) != null) {
            releaseSlot(sl);
            slotCount--;
        }
        if (surfaces != null) {
            surfaces.trim();
        }
        TRIMS.increment();
    }

    /** Regions found still running by one {@link #readyFirst} call (GPU thread). */
    private long[] runningRegions = new long[8];

    /**
     * The oldest pending request whose region's work is done, else the oldest request (GPU thread). Each region is checked once per
     * call, however many of its requests wait. A region still running costs one event query.
     */
    private NoiseReq readyFirst() {
        int running = 0;
        next:
        for (NoiseReq n : pending) {
            long k = regionKey(n.pos);
            for (int i = 0; i < running; i++) {
                if (runningRegions[i] == k) {
                    continue next;
                }
            }
            Region r = regions.getAndMoveToLast(k);
            if (r != null && after(r) == 0L) {
                return n;
            }
            if (running == runningRegions.length) {
                runningRegions = java.util.Arrays.copyOf(runningRegions, running * 2);
            }
            runningRegions[running++] = k;
        }
        return pending.peek();
    }

    private Slot takeSlot(int cap) {
        Slot s = slots.poll();
        if (s != null && s.cap != cap) {
            releaseSlot(s);
            slotCount--;
            s = null;
        }
        if (s == null && slotCount < SLOTS) {
            s = new Slot(cap);
            slotCount++;
        }
        return s;
    }

    private void releaseSlot(Slot s) {
        for (long m : new long[] { s.lst, s.bidx, s.bp, s.bj, s.cornersPc, s.out, s.storage, s.meta, s.marks, s.tops, s.terrain, s.cornersPc32, s.cornersPcE, s.cflag, s.rlist,
            s.rcount, s.cmark, s.clist, s.cval }) {
            cl.releaseMem(m);
        }
        for (Object[] p : s.pins) {
            cl.unpin(device.hostQueue, (Long) p[0], (ByteBuffer) p[1]);
        }
        s.pins.clear();
    }

    /** Batches in flight at most (each with its own buffers). */
    private static final int SLOTS = Integer.getInteger("generatoraccelerator2.gpu.slots", 4);
    /**
     * {@code -Dgeneratoraccelerator2.gpu.prefetch=N}: a biome request this many chunks or fewer from its region's edge also launches the
     * neighbouring regions. Their work is then done by the time the chunks there ask. 0 turns prefetching off.
     */
    private static final int PREFETCH = Integer.getInteger("generatoraccelerator2.gpu.prefetch", 4);

    private void prefetch(ChunkPos pos) {
        if (PREFETCH <= 0) {
            return;
        }
        int lx = Math.floorMod(pos.x, side), lz = Math.floorMod(pos.z, side);
        int dx = lx < PREFETCH ? -1 : lx >= side - PREFETCH ? 1 : 0;
        int dz = lz < PREFETCH ? -1 : lz >= side - PREFETCH ? 1 : 0;
        if (dx != 0) {
            prefetchRegion(pos.x + dx * PREFETCH, pos.z);
        }
        if (dz != 0) {
            prefetchRegion(pos.x, pos.z + dz * PREFETCH);
        }
        if (dx != 0 && dz != 0) {
            prefetchRegion(pos.x + dx * PREFETCH, pos.z + dz * PREFETCH);
        }
    }

    /**
     * Keys of the regions let go because every chunk was answered ({@link #evict}). A prefetch does not launch these regions again,
     * because the generation front has passed them. A chunk that does ask still launches its region, as any request does. The keys are
     * forgotten past a few thousand.
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet doneRegions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Launches the region holding chunk {@code x, z} unless it was let go. */
    private void prefetchRegion(int x, int z) {
        int rx = Math.floorDiv(x, side), rz = Math.floorDiv(z, side);
        long k = ChunkPos.asLong(rx, rz);
        if (!doneRegions.contains(k)) {
            region(k, rx, rz);
        }
    }

    /** The key of the region holding {@code pos}. */
    private long regionKey(ChunkPos pos) {
        return ChunkPos.asLong(Math.floorDiv(pos.x, side), Math.floorDiv(pos.z, side));
    }

    /** The region holding {@code pos}, launched (its kernels enqueued) when new. */
    private Region region(ChunkPos pos) {
        int rx = Math.floorDiv(pos.x, side), rz = Math.floorDiv(pos.z, side);
        return region(ChunkPos.asLong(rx, rz), rx, rz);
    }

    /** The region {@code rx, rz} (in regions) of key {@code k}, launched when new. */
    private Region region(long k, int rx, int rz) {
        Region r = regions.getAndMoveToLast(k);
        if (r == null) {
            r = new Region(k, rx * side, rz * side);
            regions.put(k, r);
            long h0 = System.nanoTime(); launchRegion(r); HOST_NS[3].add(System.nanoTime() - h0);
            REGIONS.increment();
        }
        return r;
    }

    /** The event that a batch's first region-reading kernel waits for. It is the region's work, or 0 (nothing) once that work is done. */
    private long after(Region r) {
        if (r.done != 0 && cl.eventStatus(r.done) == ClApi.CL_COMPLETE) {
            cl.releaseEvent(r.done);
            r.done = 0;
        }
        return r.done;
    }

    /**
     * Enqueues the region's shared work on a region queue. The biomes go first because their step comes first, so they are read back
     * before the rest runs. Then comes the work that batches read. Batches wait for the event of that work's last kernel.
     */
    private void launchRegion(Region r) {
        r.launched = System.nanoTime();
        ClEmitter g = gen;
        int w = side, h = side, nchunks = w * h;
        int per = 16 / cellWidth;
        int nqx = 4 * w + 1, nqz = 4 * h + 1, ncx = per * w + 1, ncz = per * h + 1, ncy = height / cellHeight + 1, nbx = 16 * w, nbz = 16 * h;
        int sqx0 = 4 * r.cx0 - 16, sqz0 = 4 * r.cz0 - 8, nsx = 4 * w + 23, nsz = 4 * h + 15;
        int bx0 = r.cx0 * 16, bz0 = r.cz0 * 16, qx0 = bx0 >> 2, qz0 = bz0 >> 2;
        long a0 = System.nanoTime();
        RegionBuffers rb = takeRegionBuffers();
        HOST_NS[4].add(System.nanoTime() - a0);
        r.rb = rb;
        r.flat = rb.flat;
        r.hz = rb.hz;
        r.corners = rb.corners;
        r.corners32 = rb.corners32;
        r.cornersE = rb.cornersE;
        r.bh = rb.bh;
        r.surf = rb.surf;
        r.surfIn = rb.surfIn;
        r.cells = rb.cells;
        r.levels = rb.levels;
        // regions take the region queues in turn. One region's kernels run in order. Different regions run side by side.
        long q = device.regionQueues[Math.floorMod(regionTurn++, device.regionQueues.length)];
        int a = common(kFlat, r, qx0, qz0, nqx, nqz);
        long lastK = kFlat, last = cl.launch(q, kFlat, (long) nqx * nqz, 64);
        if (decidesBiomes()) {
            int quarts = nchunks * 16 * (height / 4);
            r.biomes = rb.biomes;
            r.tieCap = rb.tieCap;
            r.host = takeRegionHost(quarts, rb.tieCap);
            // the climate parameters of x and z only, once per quart column, then every quart
            a = common(kClimate, r, qx0, qz0, nqx, nqz);
            cl.argMem(kClimate, a++, rb.climate);
            cl.argInt(kClimate, a++, r.cx0);
            cl.argInt(kClimate, a++, r.cz0);
            cl.argInt(kClimate, a++, h);
            cl.argInt(kClimate, a++, nchunks);
            cl.argMem(kClimate, a, rb.tieCount);
            prof(kClimate, cl.launch(q, kClimate, (long) nchunks * 16, 64));
            a = common(kBiome, r, qx0, qz0, nqx, nqz);
            cl.argMem(kBiome, a++, tb);
            cl.argMem(kBiome, a++, toff);
            cl.argMem(kBiome, a++, tfirst);
            cl.argMem(kBiome, a++, tcount);
            cl.argMem(kBiome, a++, tleaf);
            cl.argMem(kBiome, a++, r.biomes);
            cl.argInt(kBiome, a++, r.cx0);
            cl.argInt(kBiome, a++, r.cz0);
            cl.argInt(kBiome, a++, h);
            cl.argInt(kBiome, a++, nchunks);
            cl.argMem(kBiome, a++, rb.climate);
            cl.argMem(kBiome, a++, rb.tieList);
            cl.argMem(kBiome, a++, rb.tieCount);
            cl.argInt(kBiome, a++, rb.tieCap);
            if (tbRegions != null) {
                // each quart column's region from TerraBlender's own noise, in the climate columns' order (chunk, then x, then z)
                ByteBuffer map = r.host.regionMap.clear();
                for (int i = 0; i < w; i++) {
                    for (int j = 0; j < h; j++) {
                        int cqx = (r.cx0 + i) * 4, cqz = (r.cz0 + j) * 4;
                        for (int lx = 0; lx < 4; lx++) {
                            for (int lz = 0; lz < 4; lz++) {
                                map.putInt(tbRegions.region(cqx + lx, cqz + lz));
                            }
                        }
                    }
                }
                rel(cl.write(q, rb.regionMap, false, 0, map.flip()));
                cl.argMem(kBiome, a++, troot);
                cl.argInt(kBiome, a++, treeRoots.length);
                cl.argMem(kBiome, a++, tdefer);
                cl.argMem(kBiome, a++, rb.regionMap);
                cl.argMem(kBiome, a, rb.fallbacks);
            }
            prof(kBiome, cl.launch(q, kBiome, quarts, 64));
            r.biomeHost = r.host.biomes;
            rel(cl.read(q, r.biomes, false, 0, r.biomeHost.clear()));
            if (tbRegions != null) {
                rel(cl.read(q, rb.fallbacks, false, 0, r.host.fallbacks.clear()));
            }
            rel(cl.read(q, rb.tieCount, false, 0, r.host.tieCount.clear().limit(4)));
            r.readEvent = cl.read(q, rb.tieList, false, 0, r.host.ties.clear().limit(rb.tieCap * 7 * 8));
            cl.wakeOnComplete(r.readEvent);
            reading.add(r);
            cl.flush(q);
        }
        a = common(kHoist, r, qx0, qz0, nqx, nqz);
        cl.argMem(kHoist, a++, r.hz);
        cl.argInt(kHoist, a++, bx0);
        cl.argInt(kHoist, a++, bz0);
        cl.argInt(kHoist, a++, ncx);
        cl.argInt(kHoist, a, ncz);
        prof(lastK, last);
        lastK = kHoist;
        last = cl.launch(q, kHoist, (long) ncx * ncz, 64);
        if (!g.perChunkCorners && certified) {
            a = common(kCorner32, r, qx0, qz0, nqx, nqz);
            cl.argMem(kCorner32, a++, r.hz);
            cl.argMem(kCorner32, a++, r.corners32);
            cl.argMem(kCorner32, a++, r.cornersE);
            cl.argInt(kCorner32, a++, bx0);
            cl.argInt(kCorner32, a++, bz0);
            cl.argInt(kCorner32, a++, ncx);
            cl.argInt(kCorner32, a++, ncz);
            cl.argInt(kCorner32, a, ncy);
            prof(lastK, last);
            lastK = kCorner32;
            last = cl.launch(q, kCorner32, (long) ncx * ncz * ncy, 64);
        } else if (!g.perChunkCorners) {
            a = common(kCorner, r, qx0, qz0, nqx, nqz);
            cl.argMem(kCorner, a++, r.hz);
            cl.argMem(kCorner, a++, r.corners);
            cl.argInt(kCorner, a++, bx0);
            cl.argInt(kCorner, a++, bz0);
            cl.argInt(kCorner, a++, ncx);
            cl.argInt(kCorner, a++, ncz);
            cl.argInt(kCorner, a, ncy);
            prof(lastK, last);
            lastK = kCorner;
            last = cl.launch(q, kCorner, (long) ncx * ncz * ncy, 64);
        }
        if (!g.blockHoisted.isEmpty()) {
            a = common(kBHoist, r, qx0, qz0, nqx, nqz);
            cl.argMem(kBHoist, a++, r.bh);
            cl.argInt(kBHoist, a++, bx0);
            cl.argInt(kBHoist, a++, bz0);
            cl.argInt(kBHoist, a++, nbx);
            cl.argInt(kBHoist, a, nbz);
            prof(lastK, last);
            lastK = kBHoist;
            last = cl.launch(q, kBHoist, (long) nbx * nbz, 64);
        }
        if (g.aquifers) {
            a = common(kSurface, r, qx0, qz0, nqx, nqz);
            cl.argMem(kSurface, a++, r.surf);
            cl.argInt(kSurface, a++, sqx0);
            cl.argInt(kSurface, a++, sqz0);
            cl.argInt(kSurface, a++, nsx);
            cl.argInt(kSurface, a, nsz);
            prof(kSurface, cl.launch(q, kSurface, (long) nsx * nsz, 64));
            if (g.surfacePerChunk) {
                a = common(kSurfaceIn, r, qx0, qz0, nqx, nqz);
                cl.argMem(kSurfaceIn, a++, r.surfIn);
                cl.argInt(kSurfaceIn, a++, r.cx0);
                cl.argInt(kSurfaceIn, a++, r.cz0);
                cl.argInt(kSurfaceIn, a++, h);
                cl.argInt(kSurfaceIn, a, nchunks);
                prof(kSurfaceIn, cl.launch(q, kSurfaceIn, (long) nchunks * 25, 64));
            }
            a = common(kStatus, r, qx0, qz0, nqx, nqz);
            cl.argMem(kStatus, a++, r.surf);
            cl.argMem(kStatus, a++, r.surfIn);
            cl.argInt(kStatus, a++, sqx0);
            cl.argInt(kStatus, a++, sqz0);
            cl.argInt(kStatus, a++, nsz);
            cl.argMem(kStatus, a++, r.cells);
            cl.argInt(kStatus, a++, r.cx0);
            cl.argInt(kStatus, a++, r.cz0);
            cl.argInt(kStatus, a++, h);
            cl.argInt(kStatus, a++, nchunks);
            cl.argLong(kStatus, a++, aqLo);
            cl.argLong(kStatus, a++, aqHi);
            if (kC2Flags != 0) {
                cl.argMem(kStatus, a, rb.c2);
            }
            prof(kStatus, cl.launch(q, kStatus, (long) nchunks * g.cellsPerChunk, 64));
            cl.argMem(kLevels, 0, r.cells);
            cl.argMem(kLevels, 1, r.levels);
            cl.argInt(kLevels, 2, nchunks);
            prof(lastK, last);
            lastK = kLevels;
            last = cl.launch(q, kLevels, nchunks, 64);
            if (kC2Flags != 0) {
                // the chunks whose columns disagree reach the host before any batch of the region completes. Batches wait for this read.
                cl.argMem(kC2Flags, 0, rb.c2);
                cl.argMem(kC2Flags, 1, rb.c2flags);
                cl.argInt(kC2Flags, 2, nchunks);
                prof(lastK, last);
                prof(kC2Flags, cl.launch(q, kC2Flags, nchunks, 64));
                r.c2host = rb.c2host;
                lastK = 0;
                last = cl.read(q, rb.c2flags, false, 0, rb.c2host.clear());
            }
        }
        r.done = last;
        cl.flush(q);
    }

    /** Pinned buffers that a region's biomes are read back into. Pooled, because pinned allocations are slow. */
    private static final class RegionHost {
        ByteBuffer biomes, ties, tieCount;
        long mem, tiesMem, tieCountMem;
        /** With TerraBlender's regions: the region map written to the device, and region 0's answers read back. */
        ByteBuffer regionMap, fallbacks;
        long fallbacksMem;
        /** Ties {@link #ties} holds. */
        int tieCap;
    }

    /** Ties per chunk a region's list starts with, and the most it grows to. */
    private static final int TIES_START = 32, TIES_MAX = 256;
    /**
     * Ties per chunk a region lists now (GPU thread). When a region had more, it grows to at least double, and to twice the
     * region's own ties per chunk if that is larger. Capped at {@link #TIES_MAX}.
     */
    private int tiesPerChunk = TIES_START;

    /**
     * Ties a region lists (7 longs each, on the device and pinned on the host). An exact tie is rare in most worlds, so the lists start
     * small. When a region had more ties, the lists grow for the dimension's later regions to twice that region's ties per chunk. A
     * region with more ties than its list holds resolves the rest through the chunk's climate sampler ({@link BiomeResult#complete}).
     * The answers are the same either way. The list used to be 256 per chunk from the start: 3.7 MB of every cached region's device
     * memory, mostly unused.
     */
    private int tieCap() {
        return side * side * tiesPerChunk;
    }

    private final ArrayDeque<RegionHost> regionHosts = new ArrayDeque<>();
    private final List<RegionHost> allRegionHosts = new ArrayList<>();

    private RegionHost takeRegionHost(int quarts, int tieCap) {
        RegionHost h = regionHosts.poll();
        long[] m = new long[1];
        if (h == null) {
            h = new RegionHost();
            h.biomes = cl.pinned(device.context, device.hostQueue, (long) quarts * 4, m);
            h.mem = m[0];
            h.tieCount = cl.pinned(device.context, device.hostQueue, 16, m);
            h.tieCountMem = m[0];
            if (tbRegions != null) {
                h.regionMap = direct(quarts / (height / 4) * 4);
                h.fallbacks = cl.pinned(device.context, device.hostQueue, (long) quarts * 4, m);
                h.fallbacksMem = m[0];
            }
            allRegionHosts.add(h);
        }
        if (h.ties == null || h.tieCap < tieCap) {
            if (h.ties != null) {
                cl.unpin(device.hostQueue, h.tiesMem, h.ties);
            }
            h.ties = cl.pinned(device.context, device.hostQueue, (long) tieCap * 7 * 8, m);
            h.tiesMem = m[0];
            h.tieCap = tieCap;
        }
        return h;
    }

    private int common(long k, Region r, int qx0, int qz0, int nqx, int nqz) {
        int a = 0;
        cl.argMem(k, a++, perm);
        cl.argMem(k, a++, od);
        cl.argMem(k, a++, oi);
        cl.argMem(k, a++, of);
        cl.argMem(k, a++, r.flat);
        cl.argInt(k, a++, qx0);
        cl.argInt(k, a++, qz0);
        cl.argInt(k, a++, nqx);
        cl.argInt(k, a++, nqz);
        return a;
    }

    private void rel(long event) {
        cl.releaseEvent(event);
    }

    /** {@code -Dgeneratoraccelerator2.gpu.profile=true}: device time per kernel (the queue then records it). */
    static final boolean PROFILE = Boolean.getBoolean("generatoraccelerator2.gpu.profile");
    private final ArrayDeque<long[]> profiled = new ArrayDeque<>();
    public final Map<String, LongAdder> deviceNanos = new java.util.concurrent.ConcurrentHashMap<>();

    /** Releases a launch's event. When profiling, keeps the event until it completes, then adds its device time to the kernel's total. */
    private void prof(long kernel, long event) {
        if (!PROFILE) {
            cl.releaseEvent(event);
            return;
        }
        profiled.add(new long[] { kernel, event });
    }

    private void collectProfile() {
        while (!profiled.isEmpty() && cl.eventStatus(profiled.peek()[1]) == ClApi.CL_COMPLETE) {
            long[] p = profiled.poll();
            deviceNanos.computeIfAbsent(kernelName(p[0]), k -> new LongAdder()).add(cl.eventNanos(p[1]));
            cl.releaseEvent(p[1]);
        }
    }

    private String kernelName(long k) {
        long[] ks = { kFlat, kHoist, kCorner, kBHoist, kSurface, kSurfaceIn, kStatus, kLevels, kClimate, kBiome, kCornerPc, kBlock, kPack, kHeights, kTerrain, kCorner32, kCorner32Pc, kBlock32, kZero,
            kRecheckMark, kRecheckCorners, kRecheckBlocks, kC2Flags };
        String[] names = { "flat", "hoist", "corner", "bhoist", "surface", "surfaceIn", "status", "levels", "climate", "biome", "cornerPc", "block", "pack", "heights", "terrain", "corner32", "corner32pc",
            "block32", "zero", "recheckMark", "recheckCorners", "recheckBlocks", "c2flags" };
        for (int i = 0; i < ks.length; i++) {
            if (ks[i] == k) {
                return names[i];
            }
        }
        return "?";
    }

    private void finishRegion(Region r) {
        REGION_LAT.since(r.launched);
        cl.releaseEvent(r.readEvent);
        r.readEvent = 0;
        int per = 16 * (height / 4);
        java.nio.IntBuffer answers = r.biomeHost.clear().asIntBuffer();
        if (leaves.length <= 0x8000) {
            char[] q = new char[side * side * per];
            for (int i = 0; i < q.length; i++) {
                int v = answers.get(i);
                q[i] = (char) (v & 0x7fff | (v < 0 ? 0x8000 : 0));
            }
            r.quartChars = q;
        } else {
            int[] q = new int[side * side * per];
            answers.get(q);
            r.quarts = q;
        }
        if (tbRegions != null) {
            int[] f = new int[side * side * per];
            r.host.fallbacks.clear().asIntBuffer().get(f);
            r.fallbacks = f;
        }
        // the listed ties by chunk, as (quart in the chunk, climate point)
        int listed = r.host.tieCount.clear().getInt(0);
        r.tiesComplete = listed <= r.tieCap;
        if (!r.tiesComplete && tiesPerChunk < TIES_MAX) {
            // the dimension has more ties than the lists hold. Later regions list twice as many per chunk as this region had.
            int had = (listed + side * side - 1) / (side * side);
            tiesPerChunk = Math.min(TIES_MAX, Math.max(tiesPerChunk * 2, had * 2));
        }
        listed = Math.min(listed, r.tieCap);
        java.nio.LongBuffer tl = r.host.ties.clear().asLongBuffer();
        int[] count = new int[side * side];
        for (int k = 0; k < listed; k++) {
            count[(int) tl.get(k * 7) / per]++;
        }
        long[][] byChunk = new long[side * side][];
        for (int b = 0; b < byChunk.length; b++) {
            byChunk[b] = count[b] == 0 ? NO_TIES : new long[count[b] * 7];
            count[b] = 0;
        }
        for (int k = 0; k < listed; k++) {
            int g = (int) tl.get(k * 7);
            int b = g / per;
            long[] t = byChunk[b];
            int o = count[b]++ * 7;
            t[o] = g % per;
            for (int p = 1; p < 7; p++) {
                t[o + p] = tl.get(k * 7 + p);
            }
        }
        r.ties = byChunk;
        r.biomeHost = null;
        regionHosts.add(r.host);
        r.host = null;
        r.biomes = 0; // the buffer stays in the region's pooled set
        for (BiomeReq b : r.waiting) {
            answer(r, b);
        }
        r.waiting.clear();
    }

    /** Completes a biome request from the region's host copy. */
    private void answer(Region r, BiomeReq b) {
        if (!decidesBiomes()) {
            b.future.complete(null);
            return;
        }
        int per = 16 * (height / 4);
        int i = b.pos.x - r.cx0, j = b.pos.z - r.cz0;
        int base = (i * side + j) * per;
        BIOME_CHUNKS.increment();
        int[] quarts;
        if (r.quartChars != null) {
            char[] c = r.quartChars;
            quarts = new int[per];
            for (int k = 0; k < per; k++) {
                char v = c[base + k];
                quarts[k] = v & 0x7fff | ((v & 0x8000) != 0 ? 0x80000000 : 0);
            }
        } else {
            quarts = Arrays.copyOfRange(r.quarts, base, base + per);
        }
        int[] fallbacks = r.fallbacks != null ? Arrays.copyOfRange(r.fallbacks, base, base + per) : null;
        b.future.complete(new BiomeResult(quarts, r.ties[i * side + j], r.tiesComplete, fallbacks));
    }

    private void launchBatch(Region r, Slot s, List<NoiseReq> reqs) {
        ClEmitter g = gen;
        int n = reqs.size();
        int w = side, h = side;
        int per = 16 / cellWidth;
        int nqx = 4 * w + 1, nqz = 4 * h + 1, ncx = per * w + 1, ncz = per * h + 1, ncy = height / cellHeight + 1, nbz = 16 * h;
        int bx0 = r.cx0 * 16, bz0 = r.cz0 * 16, qx0 = bx0 >> 2, qz0 = bz0 >> 2;
        int pieces = 0, junctions = 0;
        long now = System.nanoTime();
        BATCH_CHUNKS.add(n);
        for (NoiseReq q : reqs) {
            QUEUE_LAT.add(now - q.since);
            pieces += q.beard.pieces().length / 8;
            junctions += q.beard.junctions().length / 3;
        }
        s.ensure(Math.max(1, pieces), Math.max(1, junctions));
        s.lstHost.clear();
        s.bidxHost.clear();
        s.bpHost.clear();
        s.bjHost.clear();
        int p = 0, jn = 0;
        for (NoiseReq q : reqs) {
            s.lstHost.putInt((q.pos.x - r.cx0) * h + (q.pos.z - r.cz0));
            int np = q.beard.pieces().length / 8, nj = q.beard.junctions().length / 3;
            s.bidxHost.putInt(p).putInt(np).putInt(jn).putInt(nj);
            for (int v : q.beard.pieces()) {
                s.bpHost.putInt(v);
            }
            for (int v : q.beard.junctions()) {
                s.bjHost.putInt(v);
            }
            p += np;
            jn += nj;
        }
        long q = device.queue;
        rel(cl.write(q, s.lst, false, 0, s.lstHost.flip()));
        rel(cl.write(q, s.bidx, false, 0, s.bidxHost.flip()));
        if (p > 0) {
            rel(cl.write(q, s.bp, false, 0, s.bpHost.flip()));
        }
        if (jn > 0) {
            rel(cl.write(q, s.bj, false, 0, s.bjHost.flip()));
        }
        if (certified) {
            // the rest of the batch follows its rechecks on the side queue
            q = launchCertified(r, s, n, nqx, nqz, ncx, ncz, ncy, nbz, bx0, bz0, qx0, qz0);
        } else {
            launchDouble(r, s, n, nqx, nqz, ncx, ncz, ncy, nbz, bx0, bz0, qx0, qz0);
        }
        int nsec = height / 16;
        cl.argMem(kPack, 0, s.out);
        cl.argMem(kPack, 1, s.storage);
        cl.argMem(kPack, 2, s.meta);
        cl.argMem(kPack, 3, s.marks);
        prof(kPack, cl.launch(q, kPack, (long) n * nsec * 256, 256));
        cl.argMem(kHeights, 0, s.out);
        cl.argInt(kHeights, 1, n);
        cl.argMem(kHeights, 2, s.tops);
        prof(kHeights, cl.launch(q, kHeights, (long) n * 256, 64));
        if (g.aquifers) {
            int sqx0 = 4 * r.cx0 - 16, sqz0 = 4 * r.cz0 - 8, nsz = 4 * h + 15;
            int a = 0;
            cl.argMem(kTerrain, a++, s.lst);
            cl.argInt(kTerrain, a++, n);
            cl.argMem(kTerrain, a++, r.cells);
            cl.argMem(kTerrain, a++, r.surf);
            cl.argMem(kTerrain, a++, r.surfIn);
            cl.argInt(kTerrain, a++, sqx0);
            cl.argInt(kTerrain, a++, sqz0);
            cl.argInt(kTerrain, a++, nsz);
            cl.argInt(kTerrain, a++, r.cx0);
            cl.argInt(kTerrain, a++, r.cz0);
            cl.argInt(kTerrain, a++, h);
            cl.argMem(kTerrain, a++, s.out);
            cl.argMem(kTerrain, a, s.terrain);
            prof(kTerrain, cl.launch(q, kTerrain, (long) n * g.terrainInts(), 64));
            rel(cl.read(q, s.terrain, false, 0, s.terrainHost.clear().limit(n * g.terrainInts() * 4)));
        }
        if (certified) {
            rel(cl.read(q, s.rcount, false, 0, s.rcountHost.clear()));
        }
        rel(cl.read(q, s.storage, false, 0, s.storageHost.clear().limit(n * nsec * 256 * 8)));
        rel(cl.read(q, s.meta, false, 0, s.metaHost.clear().limit(n * nsec * ClEmitter.META * 4)));
        rel(cl.read(q, s.marks, false, 0, s.marksHost.clear().limit(n * nsec * 64 * 8)));
        long ev = cl.read(q, s.tops, false, 0, s.topsHost.clear().limit(n * 512 * 4));
        cl.wakeOnComplete(ev);
        r.users++;
        inflight.add(new Batch(r, s, reqs, ev, certified));
        BATCHES.increment();
    }

    /**
     * Decides blocks in certified float. Cells whose decisions the float errors could change are listed and decided again in double.
     * The recheck runs on the side queue. It is a few work-groups that take a double evaluation's latency, and the next batch's float
     * work on the main queue fills that latency. Returns the side queue.
     */
    private long launchCertified(Region r, Slot s, int n, int nqx, int nqz, int ncx, int ncz, int ncy, int nbz, int bx0, int bz0, int qx0, int qz0) {
        ClEmitter g = gen;
        long q = device.queue;
        int h = side;
        int per = 16 / cellWidth;
        int cells = n * cellsPerChunk3d;
        int gridCorners = n * (per + 1) * (per + 1) * ncy;
        cl.argMem(kZero, 0, s.cflag);
        cl.argInt(kZero, 1, cells);
        cl.argMem(kZero, 2, s.cmark);
        cl.argInt(kZero, 3, gridCorners);
        cl.argMem(kZero, 4, s.rcount);
        cl.argInt(kZero, 5, 16);
        prof(kZero, cl.launch(q, kZero, Math.max(Math.max(cells, gridCorners), 16), 64));
        long corners = r.corners32, cornersE = r.cornersE;
        // the first kernel reading the region's buffers waits for the region's work on the region queue
        long dep = after(r);
        if (g.perChunkCorners) {
            int a = common(kCorner32Pc, r, qx0, qz0, nqx, nqz);
            cl.argMem(kCorner32Pc, a++, r.hz);
            cl.argMem(kCorner32Pc, a++, s.cornersPc32);
            cl.argMem(kCorner32Pc, a++, s.cornersPcE);
            cl.argInt(kCorner32Pc, a++, bx0);
            cl.argInt(kCorner32Pc, a++, bz0);
            cl.argInt(kCorner32Pc, a++, ncz);
            cl.argInt(kCorner32Pc, a++, ncy);
            cl.argInt(kCorner32Pc, a++, h);
            cl.argInt(kCorner32Pc, a++, n);
            cl.argMem(kCorner32Pc, a++, s.lst);
            cl.argMem(kCorner32Pc, a++, s.bp);
            cl.argMem(kCorner32Pc, a++, s.bj);
            cl.argMem(kCorner32Pc, a++, s.bidx);
            cl.argMem(kCorner32Pc, a, bk);
            prof(kCorner32Pc, cl.launch(q, kCorner32Pc, (long) n * (per + 1) * (per + 1) * ncy, 64, dep));
            dep = 0;
            corners = s.cornersPc32;
            cornersE = s.cornersPcE;
        }
        int a = common(kBlock32, r, qx0, qz0, nqx, nqz);
        cl.argMem(kBlock32, a++, corners);
        cl.argMem(kBlock32, a++, cornersE);
        cl.argMem(kBlock32, a++, s.out);
        cl.argMem(kBlock32, a++, densBuffer != 0 ? densBuffer : s.out);
        cl.argInt(kBlock32, a++, bx0);
        cl.argInt(kBlock32, a++, bz0);
        cl.argInt(kBlock32, a++, nbz);
        cl.argInt(kBlock32, a++, ncx);
        cl.argInt(kBlock32, a++, ncz);
        cl.argInt(kBlock32, a++, ncy);
        cl.argInt(kBlock32, a++, n);
        cl.argMem(kBlock32, a++, s.lst);
        cl.argMem(kBlock32, a++, r.cells);
        cl.argInt(kBlock32, a++, r.cx0);
        cl.argInt(kBlock32, a++, r.cz0);
        cl.argInt(kBlock32, a++, h);
        cl.argLong(kBlock32, a++, oreLo);
        cl.argLong(kBlock32, a++, oreHi);
        cl.argMem(kBlock32, a++, s.bp);
        cl.argMem(kBlock32, a++, s.bj);
        cl.argMem(kBlock32, a++, s.bidx);
        cl.argMem(kBlock32, a++, bk);
        cl.argMem(kBlock32, a++, r.bh);
        cl.argMem(kBlock32, a++, s.cflag);
        cl.argMem(kBlock32, a++, s.rlist);
        cl.argMem(kBlock32, a++, s.rcount);
        cl.argInt(kBlock32, a++, s.rcap);
        cl.argMem(kBlock32, a, r.levels);
        prof(kBlock32, cl.launch(q, kBlock32, (long) n * 256 * height, 128, dep));
        cl.argMem(kRecheckMark, 0, s.rlist);
        cl.argMem(kRecheckMark, 1, s.rcount);
        cl.argInt(kRecheckMark, 2, s.rcap);
        cl.argMem(kRecheckMark, 3, s.cmark);
        cl.argMem(kRecheckMark, 4, s.clist);
        cl.argInt(kRecheckMark, 5, ncy);
        long marked = cl.launch(q, kRecheckMark, (long) s.rcap * 8, 64);
        cl.flush(q);
        long side = device.sideQueue;
        a = common(kRecheckCorners, r, qx0, qz0, nqx, nqz);
        cl.argMem(kRecheckCorners, a++, r.hz);
        cl.argMem(kRecheckCorners, a++, s.cval);
        cl.argInt(kRecheckCorners, a++, bx0);
        cl.argInt(kRecheckCorners, a++, bz0);
        cl.argInt(kRecheckCorners, a++, ncz);
        cl.argInt(kRecheckCorners, a++, ncy);
        cl.argInt(kRecheckCorners, a++, h);
        cl.argMem(kRecheckCorners, a++, s.lst);
        cl.argMem(kRecheckCorners, a++, s.clist);
        cl.argMem(kRecheckCorners, a++, s.rcount);
        cl.argInt(kRecheckCorners, a++, s.ccap);
        cl.argMem(kRecheckCorners, a++, s.bp);
        cl.argMem(kRecheckCorners, a++, s.bj);
        cl.argMem(kRecheckCorners, a++, s.bidx);
        cl.argMem(kRecheckCorners, a, bk);
        prof(kRecheckCorners, cl.launch(side, kRecheckCorners, (long) s.ccap * gen.roots.size(), 64, marked));
        prof(kRecheckMark, marked);
        a = common(kRecheckBlocks, r, qx0, qz0, nqx, nqz);
        cl.argMem(kRecheckBlocks, a++, s.cval);
        cl.argMem(kRecheckBlocks, a++, s.out);
        cl.argInt(kRecheckBlocks, a++, bx0);
        cl.argInt(kRecheckBlocks, a++, bz0);
        cl.argInt(kRecheckBlocks, a++, nbz);
        cl.argInt(kRecheckBlocks, a++, ncy);
        cl.argInt(kRecheckBlocks, a++, h);
        cl.argMem(kRecheckBlocks, a++, s.lst);
        cl.argMem(kRecheckBlocks, a++, s.rlist);
        cl.argMem(kRecheckBlocks, a++, s.rcount);
        cl.argInt(kRecheckBlocks, a++, s.rcap);
        cl.argMem(kRecheckBlocks, a++, r.cells);
        cl.argInt(kRecheckBlocks, a++, r.cx0);
        cl.argInt(kRecheckBlocks, a++, r.cz0);
        cl.argLong(kRecheckBlocks, a++, oreLo);
        cl.argLong(kRecheckBlocks, a++, oreHi);
        cl.argMem(kRecheckBlocks, a++, s.bp);
        cl.argMem(kRecheckBlocks, a++, s.bj);
        cl.argMem(kRecheckBlocks, a++, s.bidx);
        cl.argMem(kRecheckBlocks, a++, bk);
        cl.argMem(kRecheckBlocks, a, r.bh);
        prof(kRecheckBlocks, cl.launch(side, kRecheckBlocks, (long) s.rcap * cellWidth * cellWidth * cellHeight, 128));
        return side;
    }

    /** Test hook: a buffer {@code k_block32} writes each block's float density and error to ({@code -D WRITE_DENSITY}). */
    public long densBuffer;

    private void launchDouble(Region r, Slot s, int n, int nqx, int nqz, int ncx, int ncz, int ncy, int nbz, int bx0, int bz0, int qx0, int qz0) {
        ClEmitter g = gen;
        long q = device.queue;
        int h = side;
        int per = 16 / cellWidth;
        long corners = r.corners;
        long dep = after(r);
        if (g.perChunkCorners) {
            int a = common(kCornerPc, r, qx0, qz0, nqx, nqz);
            cl.argMem(kCornerPc, a++, r.hz);
            cl.argMem(kCornerPc, a++, s.cornersPc);
            cl.argInt(kCornerPc, a++, bx0);
            cl.argInt(kCornerPc, a++, bz0);
            cl.argInt(kCornerPc, a++, ncz);
            cl.argInt(kCornerPc, a++, ncy);
            cl.argInt(kCornerPc, a++, h);
            cl.argInt(kCornerPc, a++, n);
            cl.argMem(kCornerPc, a++, s.lst);
            cl.argMem(kCornerPc, a++, s.bp);
            cl.argMem(kCornerPc, a++, s.bj);
            cl.argMem(kCornerPc, a++, s.bidx);
            cl.argMem(kCornerPc, a, bk);
            prof(kCornerPc, cl.launch(q, kCornerPc, (long) n * (per + 1) * (per + 1) * ncy, 64, dep));
            dep = 0;
            corners = s.cornersPc;
        }
        int a = common(kBlock, r, qx0, qz0, nqx, nqz);
        cl.argMem(kBlock, a++, corners);
        cl.argMem(kBlock, a++, s.out);
        cl.argMem(kBlock, a++, densBuffer != 0 ? densBuffer : s.out); // dens: written only with WRITE_DENSITY
        cl.argInt(kBlock, a++, bx0);
        cl.argInt(kBlock, a++, bz0);
        cl.argInt(kBlock, a++, nbz);
        cl.argInt(kBlock, a++, ncx);
        cl.argInt(kBlock, a++, ncz);
        cl.argInt(kBlock, a++, ncy);
        cl.argInt(kBlock, a++, n);
        cl.argMem(kBlock, a++, s.lst);
        cl.argMem(kBlock, a++, r.cells);
        cl.argInt(kBlock, a++, r.cx0);
        cl.argInt(kBlock, a++, r.cz0);
        cl.argInt(kBlock, a++, h);
        cl.argLong(kBlock, a++, oreLo);
        cl.argLong(kBlock, a++, oreHi);
        cl.argMem(kBlock, a++, s.bp);
        cl.argMem(kBlock, a++, s.bj);
        cl.argMem(kBlock, a++, s.bidx);
        cl.argMem(kBlock, a++, bk);
        cl.argMem(kBlock, a, r.bh);
        prof(kBlock, cl.launch(q, kBlock, (long) n * 256 * height, 128, dep));
    }

    private void finishBatch(Batch b) {
        BATCH_LAT.since(b.launched);
        cl.releaseEvent(b.event);
        Slot s = b.slot;
        int nsec = height / 16;
        int n = b.reqs.size();
        if (b.certified) {
            int flagged = s.rcountHost.getInt(0);
            CERT_CELLS.add((long) n * cellsPerChunk3d);
            if (flagged > s.rcap) {
                // more uncertain cells than the recheck list holds: the batch's chunks go to the CPU path
                RECHECK_OVERFLOWS.increment();
                b.region.users--;
                slots.add(s);
                for (NoiseReq q : b.reqs) {
                    answered(b.region, q.pos);
                    q.future.complete(null);
                }
                return;
            }
            RECHECKED_CELLS.add(flagged);
            RECHECKED_CORNERS.add(s.rcountHost.getInt(4));
            for (int i = 0; i < RECHECK_REASONS.length; i++) {
                RECHECK_REASONS[i].add(s.rcountHost.getInt(8 + 4 * i));
            }
        }
        var st = s.storageHost.clear().asLongBuffer();
        var me = s.metaHost.clear().asIntBuffer();
        var mk = s.marksHost.clear().asLongBuffer();
        var tp = s.topsHost.clear().asIntBuffer();
        var tr = s.terrainHost != null ? s.terrainHost.clear().asIntBuffer() : null;
        int ti = gen.terrainInts();
        List<NoiseResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // only what the chunk keeps: the storage of sections with blocks, the marks of sections with marks
            int[] meta = new int[nsec * ClEmitter.META];
            me.get(i * nsec * ClEmitter.META, meta);
            long[][] storage = new long[nsec][];
            long[][] marks = new long[nsec][];
            for (int sec = 0; sec < nsec; sec++) {
                int m = meta[sec * ClEmitter.META];
                if ((m & 0xff) != 0) {
                    st.get((i * nsec + sec) * 256, storage[sec] = new long[256]);
                }
                if ((m & 0x100) != 0) {
                    mk.get((i * nsec + sec) * 64, marks[sec] = new long[64]);
                }
            }
            int[] tops = new int[512];
            tp.get(i * 512, tops);
            int[] terrain = null;
            if (tr != null) {
                tr.get(i * ti, terrain = new int[ti]);
            }
            results.add(new NoiseResult(storage, meta, marks, tops, terrain));
        }
        ByteBuffer c2 = b.region.c2host;
        b.region.users--;
        slots.add(s);
        for (int i = 0; i < n; i++) {
            NoiseReq q = b.reqs.get(i);
            answered(b.region, q.pos);
            if (c2 != null && c2.getInt(((q.pos.x - b.region.cx0) * side + (q.pos.z - b.region.cz0)) * 4) != 0) {
                // a value depends on the order in which vanilla asks this chunk's aquifer cells. The CPU path fills the chunk.
                C2D_CPU.increment();
                q.future.complete(null);
                continue;
            }
            NOISE_CHUNKS.increment();
            q.future.complete(results.get(i));
        }
    }

    /** Regions where every chunk got its blocks' answer. Nothing will ask them again, so they go at once ({@link #evict}). */
    private final List<Region> completed = new ArrayList<>();
    /** Regions let go because every chunk was answered (status). */
    public static final LongAdder REGIONS_DONE = new LongAdder();

    private void answered(Region r, ChunkPos pos) {
        if (r.answered(pos)) {
            completed.add(r);
        }
    }

    /**
     * Lets go of regions where every chunk was answered, as soon as no batch, readback or request uses them. Then lets go of the least
     * recently used regions over the budget. A region holds the chunks' shared device work until each chunk has asked for its blocks.
     * Once all have, keeping the region until the budget evicts it only kept its pooled buffers (and their device memory) from going
     * back to the pool.
     */
    private void evict() {
        if (!completed.isEmpty()) {
            for (Iterator<Region> it = completed.iterator(); it.hasNext(); ) {
                Region r = it.next();
                if (regions.get(r.key) != r) {
                    it.remove();
                    continue;
                }
                if (r.users > 0 || r.readEvent != 0 || !r.waiting.isEmpty() || pendingFor(r)) {
                    continue;
                }
                it.remove();
                regions.remove(r.key);
                r.release();
                if (doneRegions.size() >= 4096) {
                    doneRegions.clear();
                }
                doneRegions.add(r.key);
                REGIONS_DONE.increment();
            }
        }
        if (regions.size() <= maxRegions) {
            return;
        }
        for (Iterator<Region> it = regions.values().iterator(); it.hasNext() && regions.size() > maxRegions; ) {
            Region r = it.next();
            if (r.users > 0 || r.readEvent != 0 || !r.waiting.isEmpty() || pendingFor(r)) {
                continue;
            }
            it.remove();
            r.release();
        }
    }

    private boolean pendingFor(Region r) {
        for (NoiseReq n : pending) {
            if (regionKey(n.pos) == r.key) {
                return true;
            }
        }
        return false;
    }

    private void failAll(Throwable e) {
        if (surfaces != null) {
            surfaces.failAll(e);
        }
        Object o;
        while ((o = incoming.poll()) != null) {
            if (o instanceof BiomeReq b) {
                b.future.completeExceptionally(e);
            } else {
                ((NoiseReq) o).future.completeExceptionally(e);
            }
        }
        for (NoiseReq n : pending) {
            n.future.completeExceptionally(e);
        }
        pending.clear();
        for (Batch b : inflight) {
            for (NoiseReq n : b.reqs) {
                n.future.completeExceptionally(e);
            }
            // return the slot to the free slots, which close releases after the queues finish. A dropped slot's buffers would never be released.
            if (b.slot != null) {
                slots.add(b.slot);
            }
        }
        inflight.clear();
        for (Region r : regions.values()) {
            for (BiomeReq b : r.waiting) {
                b.future.completeExceptionally(e);
            }
            r.waiting.clear();
        }
        reading.clear();
    }

    /** Releases every device object (GPU thread). Runs after a failure or when the world closes. */
    void close() {
        device.removePump(this);
        failAll(new IllegalStateException("GPU generation closed"));
        if (surfaces != null) {
            surfaces.close();
            surfaces = null;
        }
        if (decor != null) {
            decor.close();
            decor = null;
        }
        if (decorProgram != 0) {
            cl.releaseProgram(decorProgram);
            decorProgram = 0;
        }
        try {
            cl.finish(device.queue);
            cl.finish(device.sideQueue);
        } catch (RuntimeException ignored) {
            // a lost device: the objects go with the context
        }
        for (Region r : regions.values()) {
            r.release();
        }
        regions.clear();
        completed.clear();
        doneRegions.clear();
        for (Slot s : slots) {
            releaseSlot(s);
        }
        slots.clear();
        for (RegionHost h : allRegionHosts) {
            cl.unpin(device.hostQueue, h.mem, h.biomes);
            cl.unpin(device.hostQueue, h.tiesMem, h.ties);
            cl.unpin(device.hostQueue, h.tieCountMem, h.tieCount);
            if (h.fallbacks != null) {
                cl.unpin(device.hostQueue, h.fallbacksMem, h.fallbacks);
            }
        }
        allRegionHosts.clear();
        regionHosts.clear();
        for (RegionBuffers b : allRegionBuffers) {
            for (long m : b.all()) {
                cl.releaseMem(m);
            }
        }
        allRegionBuffers.clear();
        freeRegionBuffers.clear();
        for (long m : new long[] { perm, od, oi, of, tb, toff, tfirst, tcount, tleaf, troot, tdefer, bk }) {
            if (m != 0) {
                cl.releaseMem(m);
            }
        }
        perm = od = oi = of = tb = toff = tfirst = tcount = tleaf = troot = tdefer = bk = 0;
        // release every kernel. Each kernel holds the program, and the program release below frees it only once no kernel is left.
        for (long k : new long[] { kFlat, kHoist, kCorner, kBHoist, kSurface, kSurfaceIn, kStatus, kLevels, kClimate, kBiome, kCornerPc, kBlock, kPack, kHeights, kTerrain,
            kCorner32, kCorner32Pc, kBlock32, kZero, kRecheckMark, kRecheckCorners, kRecheckBlocks, kC2Flags }) {
            if (k != 0) {
                cl.releaseKernel(k);
            }
        }
        kFlat = kHoist = kCorner = kBHoist = kSurface = kSurfaceIn = kStatus = kLevels = kClimate = kBiome = kCornerPc = kBlock = kPack = kHeights = kTerrain = 0;
        kCorner32 = kCorner32Pc = kBlock32 = kZero = kRecheckMark = kRecheckCorners = kRecheckBlocks = kC2Flags = 0;
        if (program != 0) {
            cl.releaseProgram(program);
            program = 0;
        }
        // a second release (a world closed while its program built) releases only what was made in between. Drop the world's objects.
        levelHeights = null;
        fluidPicker = null;
        surfaceGenerator = null;
        biomeList.clear();
    }

    public String status() {
        return name + ": " + switch (state) {
            case PREPARING -> "preparing (" + reason + ")";
            case FAILED -> "off (" + reason + ")";
            case READY -> "on, " + (certified ? "certified fp32 (cells in doubt rechecked in fp64)" : "fp64 (" + certifiedOff + ")") + ", "
                + (gen.perChunkCorners ? "corners per chunk" : "shared corners") + ", biomes " + (biomesOff == null ? "on GPU" : "on CPU (" + biomesOff + ")")
                + ", surface " + (surfaces != null ? "on GPU" : "on CPU (" + surfaceOff + ")") + (terrainOff != null ? ", chunks keep their NoiseChunk (" + terrainOff + ")" : "")
                + String.format(java.util.Locale.ROOT, ", program %,d chars built in %.1f s, %d regions cached (at most %d)", sourceChars, buildMs / 1e3, regions.size(), maxRegions)
                + (!dev.denismasterherobrine.generatoraccelerator2.gpu.decor.GpuDecor.ENABLED ? ""
                    : decorOff == null ? String.format(java.util.Locale.ROOT, "; decoration on (its program built in %.1f s)", decorBuildMs / 1e3) : "; decoration off (" + decorOff + ")");
        };
    }

    // ---- buffers ---------------------------------------------------------------------------------------------------

    static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(Math.max(16, bytes)).order(ByteOrder.nativeOrder());
    }

    static ByteBuffer bytes(byte[] a) {
        ByteBuffer b = direct(a.length);
        b.put(a).clear();
        return b;
    }

    static ByteBuffer ints(int[] a) {
        ByteBuffer b = direct(a.length * 4);
        b.asIntBuffer().put(a);
        return b;
    }

    static ByteBuffer longs(long[] a) {
        ByteBuffer b = direct(a.length * 8);
        b.asLongBuffer().put(a);
        return b;
    }

    static ByteBuffer doubles(double[] a) {
        ByteBuffer b = direct(a.length * 8);
        b.asDoubleBuffer().put(a);
        return b;
    }

    static ByteBuffer floats(float[] a) {
        ByteBuffer b = direct(a.length * 4);
        b.asFloatBuffer().put(a);
        return b;
    }
}
