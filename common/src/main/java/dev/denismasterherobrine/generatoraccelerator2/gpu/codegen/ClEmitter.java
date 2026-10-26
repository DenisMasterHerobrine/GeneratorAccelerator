package dev.denismasterherobrine.generatoraccelerator2.gpu.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * GA2's IR as OpenCL C. Mirrors GA2's chunk-mode code generator (the {@code COLUMN} and {@code CELL} modes of
 * {@code dfc.codegen.Emitter}) operation by operation, so the F64 output is bit for bit GA2's own NoiseChunk, noise fill and
 * biomes. The F32 variant keeps the noise lattice (coordinates, floor, fraction) in double and runs everything else in float.
 *
 * <p>Region kernels run over one rectangle of W x H chunks (the "region"), so neighbouring chunks share their border corner
 * columns. None of them depends on structures:
 * <ul>
 *   <li>{@code k_flat}: every {@code flat_cache} at every quart column of the region (y = 0, as vanilla's FlatCache fills it);</li>
 *   <li>{@code k_hoist}: the x/z-only parts of the interpolated roots that sample something, once per corner column;</li>
 *   <li>{@code k_corner}: every interpolated root at every cell corner. When the roots read the beardifier, corners are computed
 *   per chunk instead ({@code k_corner_pc});</li>
 *   <li>{@code k_bhoist}: the per-block tree's x/z-only parts, once per block column;</li>
 *   <li>{@code k_surface}: the preliminary surface level ({@code NoiseChunk.preliminarySurfaceLevel}) of every quart column the
 *   region's aquifers ask for. When a flat cache reads y, {@code k_surface_in} also covers each chunk's own columns;</li>
 *   <li>{@code k_status}: for every aquifer cell of every chunk, the cell's randomized centre and its {@code FluidStatus}
 *   ({@code NoiseBasedAquifer.computeFluid}), using the chunk's own flat-cache view;</li>
 *   <li>{@code k_biome}: the chunk climate sampler at every quart point and the climate R-tree search (vanilla's walk, no
 *   last-result seed). In vanilla, a tie's answer depends on the searching thread's last result. A tie is marked (bit 31), and
 *   the CPU asks the biome source for it.</li>
 * </ul>
 *
 * <p>Batch kernels run over a list of the region's chunks. The structures of those chunks are known by then:
 * <ul>
 *   <li>{@code k_corner_pc}: the listed chunks' own corners, when the roots read the beardifier;</li>
 *   <li>{@code k_block}: per block, the final density cell cache (lerp3 of the corners, as in vanilla's cell fill). Then the block
 *   the noise fill writes, from the aquifer's {@code computeSubstance}, the ore vein rule and the default block. The vein rule
 *   reads interpolators in the per-block Y, X, Z order. Output is one byte per block. Bit 7 is the aquifer's fluid update flag;</li>
 *   <li>{@code k_pack} and {@code k_heights}: the sections as the chunk keeps them and the two worldgen heightmaps.</li>
 * </ul>
 */
public final class ClEmitter {
    public enum Prec { F64, F32 }

    /**
     * Evaluation contexts. FLAT: a flat cache's own fill (markers transparent). HOIST and CORNER: the corner fill. Both read
     * flat caches, and CORNER also reads hoisted values. BLOCK: the cell fill (interpolators lerp3). VEIN: a block's interpolator
     * reads outside the cell fill (Y, X, Z order). APOINT: a {@code SinglePointContext} on the chunk-wrapped router. There a flat
     * cache gives its value only inside the chunk's quart range, and its child's value elsewhere. Every other marker is transparent.
     */
    enum Mode { FLAT, HOIST, CORNER, BLOCK, VEIN, APOINT, XZBLOCK }

    /** The router (or a setting) has something the GPU path does not cover; the message names it. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) {
            super(message);
        }
    }

    /**
     * Block ids of the noise fill's output. The runtime maps them to block states. Order: air, the settings' default block, the
     * default fluid, lava, then the ore vein blocks. The default fluid is water whenever the aquifer is on. The aquifer tells water
     * from lava.
     */
    public static final int ID_AIR = 0, ID_STONE = 1, ID_WATER = 2, ID_LAVA = 3, ID_COPPER_ORE = 4, ID_RAW_COPPER = 5, ID_GRANITE = 6,
        ID_DEEPSLATE_IRON_ORE = 7, ID_RAW_IRON = 8, ID_TUFF = 9;
    public static final int ID_COUNT = 10;

    /** Aquifer cells per chunk (3 x 35 x 3 for the overworld's height; computed from the layout). */
    final int cellsX = 3, cellsZ = 3;
    final int cellsY;
    final int minGridY;
    public final int cellsPerChunk;

    public final Prec prec;
    final boolean hoist;
    /**
     * Compile-time switches. {@code tables}: noise octaves read from data tables by one generic loop instead of unrolled code with
     * every constant inline. {@code noinline}: the large functions (noise, splines, shared values, roots, aquifer, climate search) are
     * kept as calls instead of being inlined into every kernel. {@code nonce}: a comment that makes the driver's compile cache miss.
     */
    public boolean tables = true;
    public boolean noinline = true;
    /** The settings' aquifer ({@code NoiseBasedAquifer}). When false: the disabled aquifer, the global fluid picker below zero density. */
    public boolean aquifers = true;
    /** Better Caves' liquid regions: the block kernels get a table per chunk and apply the mod's step to the aquifer's answer. */
    public boolean liquidRegions;
    /** The settings' ore veins ({@code OreVeinifier}). */
    public boolean veins = true;
    /**
     * The program decides biomes ({@code k_climate}, {@code k_biome}). When false, it leaves those kernels out, together with the
     * climate's point roots that the aquifer does not read. This is a shorter build for when another mod's biome source keeps the
     * biomes on the CPU.
     */
    public boolean biomes = true;
    /**
     * The biomes come from TerraBlender's regions. {@code k_biome} then searches each quart column's region tree, and region 0's tree
     * after a placeholder leaf ({@code compat.terrablender.TerraBlenderBiomes}).
     */
    public boolean biomeRegions;
    /** {@code k_biome} also writes every quart's climate point ({@code targets}), for Lithostitched's injectors on the CPU. */
    public boolean biomeTargets;
    /**
     * {@code k_biome} writes the answers the CPU's own biome lookups take ({@code gpu.runtime.BiomeHand}): per quart, the nearest leaf
     * and the nearest leaf of another biome of its tree, and of region 0's tree after a placeholder. Each only where it is the one leaf
     * at its distance. {@code tkey} numbers each leaf's biome.
     */
    public boolean biomeHand;
    /**
     * Also generate the certified float corner and block kernels (and the double recheck of the cells they flag) next to the double
     * program. {@link #source()} throws {@link Unsupported} when the router has something they do not cover.
     */
    public boolean certify;
    /**
     * Bit per block id: the state blocks motion ({@code OCEAN_FLOOR_WG}), is not air ({@code WORLD_SURFACE_WG}), has a fluid (gets
     * the aquifer's post-processing mark).
     */
    public int motionMask, nonAirMask, fluidMask;
    /**
     * The dimension's global fluid picker as intervals {@code {fromY, level, id}} in y order (the first from {@code Integer.MIN_VALUE}),
     * or null for vanilla's picker ({@code runtime.PickerShape}).
     */
    public List<int[]> pickerIntervals;
    /**
     * The default fluid (the id {@code ID_WATER}) is lava, as in a Nether with aquifers (Jaden's Nether Expansion). Vanilla's
     * aquifer tests water and lava by block. For that id, its water tests are therefore false and its lava tests true
     * ({@code IS_WATER}, {@code IS_LAVA}).
     */
    public boolean fluidLava;
    /** The interpolated roots read the beardifier (Terralith), so each chunk gets its own corners. Set by {@link #source()}. */
    public boolean perChunkCorners;
    /** Some flat cache reads y. Each chunk then also walks the surface levels of its own quart columns ({@code k_surface_in}). */
    public boolean surfacePerChunk;
    public String nonce;
    /** Noise tables ({@link #tables}): ints (spec records, octave and blended permutation offsets), doubles, floats. */
    public int[] oi = new int[4];
    public double[] od = new double[4];
    public float[] of = new float[4];
    private int oiOct, oiBl, odOct, odBl, ofOct, ofBl;
    /** Floats per octave in {@link #of}: amplitude, value factor, input factor, then the x, y, z offsets each as hi and lo. */
    private static final int OF_STRIDE = 9;
    /**
     * Floats per blended noise in {@link #of}, each value as hi and lo. Layout: the xz and y multipliers and factors, then vanilla's
     * {@code d6} and {@code d7} (doubles that vanilla's method computes from them), then the x, y, z offsets of each of the 40 octaves
     * (main, min limit, max limit).
     */
    private static final int OF_BL_STRIDE = 12 + 40 * 6;
    /** Whether every octave's input factor is a float exactly (the certified lattice relies on it). */
    private boolean exactPow2 = true;
    final List<NoiseSpec> specs;
    final List<BlendedSpec> blendedSpecs;
    public final List<Node> roots = new ArrayList<>();
    final Map<Node.Marker, Integer> lerpIndex = new IdentityHashMap<>();
    final Node cellRoot;
    final Map<String, Node> router;
    public final List<Node.Marker> flats = new ArrayList<>();
    final Map<Node.Marker, Integer> flatIndex = new IdentityHashMap<>();
    public final List<Node> hoisted = new ArrayList<>();
    final Map<Node, Integer> hoistIndex = new IdentityHashMap<>();
    public final List<Node> blockHoisted = new ArrayList<>();
    final Map<Node, Integer> blockHoistIndex = new IdentityHashMap<>();
    private final CoordDeps deps = new CoordDeps(true);
    private final Map<Node, Boolean> expensive = new IdentityHashMap<>();

    public final int cellWidth;
    public final int cellHeight;
    final int cellNoiseMinY;
    public final int cellCountY;
    public final int minY;
    public final int height;
    final int seaLevel;

    /** The APOINT roots, in this order: their functions are {@code ap_<name>}. */
    public static final String[] APOINT_ROOTS = { "initial_density_without_jaggedness", "erosion", "depth", "fluid_level_floodedness", "fluid_level_spread",
        "lava", "temperature", "vegetation", "continents", "ridges" };
    /** The APOINT roots that only the biomes read. The aquifer also reads erosion and depth. */
    private static final Set<String> CLIMATE_ONLY = Set.of("temperature", "vegetation", "continents", "ridges");

    /** Every spline the roots evaluate, children before their parents. Each spline's depth is kept too (1: constant values only). */
    private final List<Node.SplineMulti> splines = new ArrayList<>();
    private final Map<Node.SplineMulti, Integer> splineIndex = new IdentityHashMap<>();
    private final List<Integer> splineDepths = new ArrayList<>();
    private int maxSplineDepth;
    /** Where the spline tables start in oi and of (-1 until {@link #splineTables} appends them). */
    private int splOi = -1, splOf = -1;
    private final Map<Node, Integer> coordIndex = new IdentityHashMap<>();
    private final BitSet usedSpecs = new BitSet();
    private final BitSet usedBlended = new BitSet();
    private final StringBuilder helperProtos = new StringBuilder();
    private final StringBuilder helpers = new StringBuilder();
    private final Map<Mode, Integer> memoCounts = new EnumMap<>(Mode.class);
    /** Per APOINT memo slot: whether its value depends on x and z only (the surface walk keeps those across its steps). */
    private final List<Boolean> apointXz = new ArrayList<>();
    private final CoordDeps pointDeps = new CoordDeps(false);
    private final Map<Mode, Fn> fns = new EnumMap<>(Mode.class);
    /** Permutation tables of every octave the generated code reads, 512 bytes each. */
    private final ByteArrayOutputStream perm = new ByteArrayOutputStream();
    private final Map<int[], Integer> permOffset = new IdentityHashMap<>();
    private String source;

    public ClEmitter(Prec prec, boolean hoist, List<NoiseSpec> specs, List<BlendedSpec> blendedSpecs, List<Node.Marker> rootMarkers, Node cellRoot,
                     Map<String, Node> router, int cellWidth, int cellHeight, int minY, int height, int seaLevel) {
        this.prec = prec;
        this.hoist = hoist;
        this.specs = specs;
        this.blendedSpecs = blendedSpecs;
        for (int i = 0; i < rootMarkers.size(); i++) {
            roots.add(rootMarkers.get(i).child());
            lerpIndex.put(rootMarkers.get(i), i);
        }
        this.cellRoot = cellRoot;
        this.router = router;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
        this.cellNoiseMinY = Math.floorDiv(minY, cellHeight);
        this.cellCountY = height / cellHeight;
        // NoiseBasedAquifer's grid for a chunk: gridY(minY) - 1 .. gridY(minY + height) + 1
        this.minGridY = Math.floorDiv(minY, 12) - 1;
        this.cellsY = Math.floorDiv(minY + height, 12) + 1 - minGridY + 1;
        this.cellsPerChunk = cellsX * cellsY * cellsZ;
    }

    String R() {
        return prec == Prec.F64 ? "double" : "float";
    }

    /** Ints per chunk {@code k_terrain} writes: two per aquifer cell, 25 quart columns' surface levels, the fill's last fluid update flag. */
    public int terrainInts() {
        return 2 * cellsPerChunk + 26;
    }

    /** The lowest aquifer cell row of a chunk's grid ({@code NoiseBasedAquifer.minGridY}). */
    public int minGridY() {
        return minGridY;
    }

    /** Aquifer cell rows of a chunk's grid. */
    public int cellsY() {
        return cellsY;
    }

    private Node routerRoot(String name) {
        Node n = router.get(name);
        if (n == null) {
            throw new Unsupported("router has no " + name);
        }
        return n;
    }

    /** Builds the whole program; throws {@link Unsupported} naming the first node the GPU path does not cover. */
    public String source() {
        if (source != null) {
            return source;
        }
        classifyCache2d();
        List<Node> blockRoots = List.of(cellRoot, routerRoot("barrier"));
        List<Node> veinRoots = List.of(routerRoot("vein_toggle"), routerRoot("vein_ridged"), routerRoot("vein_gap"));
        List<Node> apointRoots = new ArrayList<>();
        List<String> apointNames = new ArrayList<>();
        for (String name : APOINT_ROOTS) {
            if (biomes || !CLIMATE_ONLY.contains(name)) {
                apointRoots.add(routerRoot(name));
                apointNames.add(name);
            }
        }
        // every flat cache any context reads (a flat cache inside another is read transparently by the flat fill)
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        for (List<Node> group : List.of(roots, blockRoots, veinRoots, apointRoots)) {
            for (Node r : group) {
                collectFlats(r, seen);
            }
        }
        // the shared surface level walk evaluates flat caches' children at the column itself, as outside any chunk. When a child reads
        // y, each chunk walks its own quart columns again. Inside the chunk's range, a flat cache gives the flat value at y = 0.
        for (Node.Marker m : flats) {
            surfacePerChunk |= !deps.of(m.child()).isXzOrConst();
        }
        Map<Node, Boolean> seenB = new IdentityHashMap<>();
        for (Node r : roots) {
            perChunkCorners |= readsBeardifier(r, seenB);
        }
        if (hoist) {
            Map<Node, Boolean> seenH = new IdentityHashMap<>();
            for (Node r : roots) {
                collectHoisted(r, seenH);
            }
        }
        if (hoist) {
            // the per-block tree's x/z-only parts that sample something, once per block column (GA2's column values)
            Map<Node, Boolean> seenX = new IdentityHashMap<>();
            collectBlockHoisted(cellRoot, seenX);
        }

        StringBuilder rootFns = new StringBuilder();
        StringBuilder rootProtos = new StringBuilder();
        List<Node> flatChildren = new ArrayList<>();
        for (Node.Marker m : flats) {
            flatChildren.add(m.child());
        }
        rootFunctions(Mode.FLAT, "fl_", flatChildren, null, rootFns, rootProtos);
        rootFunctions(Mode.HOIST, "hz_", hoisted, null, rootFns, rootProtos);
        rootFunctions(Mode.CORNER, "cr_", roots, null, rootFns, rootProtos);
        rootFunctions(Mode.BLOCK, "bk_", blockRoots, new String[] { "final", "barrier" }, rootFns, rootProtos);
        rootFunctions(Mode.VEIN, "vn_", veinRoots, new String[] { "toggle", "ridged", "gap" }, rootFns, rootProtos);
        rootFunctions(Mode.APOINT, "ap_", apointRoots, apointNames.toArray(String[]::new), rootFns, rootProtos);
        rootFunctions(Mode.XZBLOCK, "xb_", blockHoisted, null, rootFns, rootProtos);
        if (certify) {
            if (prec != Prec.F64) {
                throw new IllegalStateException("certified fp32 is generated next to the double program");
            }
            certifiedFunctions(rootFns, rootProtos);
        }

        StringBuilder src = new StringBuilder();
        src.append(prelude());
        if (certify) {
            src.append(CERT_PRELUDE);
        }
        int base = 0;
        StringBuilder resets = new StringBuilder();
        for (Mode mode : Mode.values()) {
            int n = memoCounts.getOrDefault(mode, 0);
            src.append("#define MB_").append(mode.name()).append(' ').append(base).append('\n');
            resets.append("void reset_").append(mode.name()).append("(Ctx* c) { for (int i = ").append(base).append("; i < ").append(base + n)
                .append("; i++) c->mk[i] = 0; }\n");
            base += n;
            if (mode == Mode.APOINT) {
                StringBuilder ry = new StringBuilder("void reset_APOINT_Y(Ctx* c) {");
                for (int i = 0; i < apointXz.size(); i++) {
                    if (!apointXz.get(i)) {
                        ry.append(" c->mk[").append(base - n + i).append("] = 0;");
                    }
                }
                resets.append(ry).append(" }\n");
            }
        }
        src.append("#define NM ").append(Math.max(1, base)).append("\n#define NCV ").append(Math.max(1, coordIndex.size())).append("\n");
        int fbase = 0;
        StringBuilder reset32 = new StringBuilder("void reset32(Ctx* c) { c->unc = 0;");
        if (certify) {
            for (CMode m : CMode.values()) {
                int n = cmemoCounts.getOrDefault(m, 0);
                src.append("#define MF_").append(m.name()).append(' ').append(fbase).append('\n');
                fbase += n;
            }
            reset32.append(" for (int i = 0; i < ").append(fbase).append("; i++) c->fk[i] = 0;");
            src.append("#define NF ").append(Math.max(1, fbase)).append("\n");
        }
        reset32.append(" }\n");
        src.append("""
            typedef struct {
              int bx, by, bz;
              int cqx0, cqz0;
              __global const uchar* pm;
              __global const double* od; __global const int* oi; __global const float* of;
              __global const R* flat; int qx0, qz0, nqz, fstride;
              __global const R* hz; int hbase;
              __global const R* bh; int bhbase;
              __global const R* cr; int cbase, rstride, csx, csz;
              R dx, dy, dz;
              R mv[NM]; uchar mk[NM];
              float cv[NCV];
              __global const int* bp; int bps, bpn; __global const int* bj; int bjs, bjn; __global const float* bk;
              __global const int* ek; int epn; int ovr;
            """);
        if (certify) {
            src.append("  float fv[NF]; float fe[NF]; uchar fk[NF]; float ce[NCV]; int unc; __global const float* cr32; __global const float* cre32;\n");
        }
        if (c2dKind == C2d.FLOODED) {
            // whether floodedness reached the cache_2d whose child reads y, and the child's value there (k_status)
            src.append("  int c2h; R c2v;\n");
        }
        src.append("} Ctx;\n");
        src.append(resets);
        if (certify) {
            src.append(reset32);
        }
        src.append(LERP3C);
        if (certify) {
            src.append(LERP3C32);
        }
        src.append(BEARD);
        src.append(noiseFunctions());
        if (certify) {
            src.append(certifiedNoiseFunctions());
        }
        src.append(splineFunctions());
        if (certify) {
            src.append(certifiedSplineFunctions());
        }
        src.append(helperProtos);
        src.append(chelperProtos);
        src.append(rootProtos);
        src.append(helpers);
        src.append(chelpers);
        src.append(rootFns);
        src.append(decisionCode());
        if (certify) {
            src.append(certifiedDecisionCode());
        }
        src.append(kernels());
        if (certify) {
            src.append(certifiedKernels());
        }
        if (extra != null) {
            src.append(extra.apply(this));
        }
        source = resolveExterns(src.toString());
        return source;
    }

    /** More source appended to the program (the GPU surface, {@code gpu.surface}): it may use every helper and {@code Ctx}. */
    public Function<ClEmitter, String> extra;

    /** The arguments every kernel that evaluates noise takes first (noise tables, flat caches). */
    public String commonArgs() {
        return COMMON_ARGS;
    }

    /** The statements that make a kernel's {@code Ctx c} from {@link #commonArgs}. */
    public String commonInit() {
        return ctxInit() + COMMON_INIT;
    }

    public byte[] permTable() {
        source();
        return perm.toByteArray();
    }

    public int hoistCount() {
        return hoisted.size();
    }

    private void rootFunctions(Mode mode, String prefix, List<Node> group, String[] names, StringBuilder out, StringBuilder protos) {
        Fn fn = new Fn(mode, group);
        fns.put(mode, fn);
        for (int i = 0; i < group.size(); i++) {
            String name = prefix + (names == null ? Integer.toString(i) : names[i]);
            fn.tmp = 0;
            StringBuilder body = new StringBuilder();
            String v = fn.ev(group.get(i), body);
            protos.append("R ").append(name).append("(Ctx* c);\n");
            out.append("NI R ").append(name).append("(Ctx* c) {\n").append(body).append("  return ").append(v).append(";\n}\n");
        }
    }

    // ---- analysis ------------------------------------------------------------------------------------------------

    private void collectFlats(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (n instanceof Node.Marker m && m.type() == Node.MarkerType.FLAT_CACHE) {
            collectFlats(m.child(), seen);
            if (!flatIndex.containsKey(m)) {
                flatIndex.put(m, flats.size());
                flats.add(m);
            }
            return;
        }
        for (Node c : RefCount.children(n)) {
            collectFlats(c, seen);
        }
    }

    /** Whether the corner fill of {@code n} evaluates a beardifier (below flat caches it would be another context). */
    private boolean readsBeardifier(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return false;
        }
        if (n instanceof Node.Beardifier) {
            return true;
        }
        boolean any = false;
        for (Node c : evalChildren(Mode.CORNER, n)) {
            any |= readsBeardifier(c, seen);
        }
        return any;
    }

    /** Maximal x/z-only parts of the block tree that sample something (reads of interpolators and flat caches stop the walk). */
    private void collectBlockHoisted(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null || n instanceof Node.Const) {
            return;
        }
        if (n instanceof Node.Marker m && (m.type() == Node.MarkerType.FLAT_CACHE || m.type() == Node.MarkerType.INTERPOLATED)) {
            return;
        }
        if (deps.of(n) == CoordDeps.Dep.XZ && isExpensive(n)) {
            if (!blockHoistIndex.containsKey(n)) {
                blockHoistIndex.put(n, blockHoisted.size());
                blockHoisted.add(n);
            }
            return;
        }
        for (Node c : RefCount.children(n)) {
            collectBlockHoisted(c, seen);
        }
    }

    private void collectHoisted(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (n instanceof Node.Const) {
            return;
        }
        if (n instanceof Node.Marker m && m.type() == Node.MarkerType.FLAT_CACHE) {
            return; // a read
        }
        if (deps.of(n) == CoordDeps.Dep.XZ && isExpensive(n)) {
            if (!hoistIndex.containsKey(n)) {
                hoistIndex.put(n, hoisted.size());
                hoisted.add(n);
            }
            return;
        }
        for (Node c : RefCount.children(n)) {
            collectHoisted(c, seen);
        }
    }

    private boolean isExpensive(Node n) {
        Boolean b = expensive.get(n);
        if (b != null) {
            return b;
        }
        boolean r = switch (n) {
            case Node.Noise x -> true;
            case Node.ShiftedNoise x -> true;
            case Node.ShiftA x -> true;
            case Node.ShiftB x -> true;
            case Node.Shift x -> true;
            case Node.Weird x -> true;
            case Node.Spline x -> true;
            case Node.Blended x -> true;
            case Node.NoiseAt x -> true;
            case Node.EndIslands x -> true;
            case Node.Marker m when m.type() == Node.MarkerType.FLAT_CACHE -> false;
            default -> {
                boolean any = false;
                for (Node c : RefCount.children(n)) {
                    any |= isExpensive(c);
                }
                yield any;
            }
        };
        expensive.put(n, r);
        return r;
    }

    /** The children a function of {@code mode} evaluates itself (reads of flat caches, hoisted values and corners stop the walk). */
    private List<Node> evalChildren(Mode mode, Node n) {
        if (n instanceof Node.Marker m) {
            return switch (m.type()) {
                case FLAT_CACHE -> mode == Mode.FLAT || mode == Mode.APOINT ? List.of(m.child()) : List.of();
                case INTERPOLATED -> mode == Mode.BLOCK || mode == Mode.VEIN || mode == Mode.XZBLOCK ? List.of() : List.of(m.child());
                default -> List.of(m.child());
            };
        }
        if (mode == Mode.BLOCK && blockHoistIndex.containsKey(n)) {
            return List.of();
        }
        if (mode == Mode.CORNER && hoistIndex.containsKey(n)) {
            return List.of();
        }
        return RefCount.children(n);
    }

    private Set<Node> shared(Mode mode, List<Node> fnRoots) {
        Map<Node, Integer> counts = new IdentityHashMap<>();
        for (Node r : fnRoots) {
            count(mode, r, counts);
        }
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Node, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1 && !(e.getKey() instanceof Node.Const)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private void count(Mode mode, Node n, Map<Node, Integer> counts) {
        Integer c = counts.get(n);
        counts.put(n, c == null ? 1 : c + 1);
        if (c != null) {
            return;
        }
        for (Node ch : evalChildren(mode, n)) {
            count(mode, ch, counts);
        }
    }

    // ---- kernels -------------------------------------------------------------------------------------------------

    private static final String COMMON_ARGS = "__global const uchar* pm, __global const double* od, __global const int* oi, __global const float* of, __global const R* flat, int qx0, int qz0, int nqx, int nqz";
    private static final String COMMON_INIT = "  c->pm = pm; c->od = od; c->oi = oi; c->of = of; c->flat = flat; c->qx0 = qx0; c->qz0 = qz0; c->nqz = nqz; c->fstride = nqx * nqz;\n";

    private String ctxInit() {
        StringBuilder sb = new StringBuilder("  Ctx cs; Ctx* c = &cs;\n");
        for (Mode m : Mode.values()) {
            sb.append("  reset_").append(m.name()).append("(c);\n");
        }
        return sb.toString();
    }

    private String kernels() {
        StringBuilder k = new StringBuilder();
        int cw = cellWidth;
        int per = 16 / cw;
        int pc = (per + 1) * (per + 1);
        int nh = Math.max(1, hoisted.size());
        int nb = Math.max(1, blockHoisted.size());
        int h = height;
        // ---- region kernels (no structures) ----
        // flat caches
        k.append("__kernel void k_flat(").append(COMMON_ARGS).append(") {\n");
        k.append("  int g = get_global_id(0); if (g >= nqx * nqz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bx = (qx0 + g / nqz) << 2; c->by = 0; c->bz = (qz0 + g % nqz) << 2;\n");
        k.append("  __global R* out = (__global R*) flat;\n");
        for (int i = 0; i < flats.size(); i++) {
            k.append("  out[").append(i).append(" * nqx * nqz + g] = fl_").append(i).append("(c);\n");
        }
        k.append("}\n");
        // hoisted x/z values, per corner column of the region
        k.append("__kernel void k_hoist(").append(COMMON_ARGS).append(", __global R* hz, int bx0, int bz0, int ncx, int ncz) {\n");
        k.append("  int g = get_global_id(0); if (g >= ncx * ncz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bx = bx0 + (g / ncz) * ").append(cw).append("; c->by = 0; c->bz = bz0 + (g % ncz) * ").append(cw).append(";\n");
        for (int i = 0; i < hoisted.size(); i++) {
            k.append("  hz[g * ").append(nh).append(" + ").append(i).append("] = hz_").append(i).append("(c);\n");
        }
        k.append("}\n");
        // corners shared by the region's chunks (the roots do not read the beardifier). A certified program leaves out the double
        // corner and block kernels and runs their float forms instead. Its rechecks keep the double functions. This shortens the build.
        int fp64Kernel = k.length();
        k.append("__kernel void k_corner(").append(COMMON_ARGS).append(", __global const R* hz, __global R* corners, int bx0, int bz0, int ncx, int ncz, int ncy) {\n");
        k.append("  int g = get_global_id(0); if (g >= ncx * ncz * ncy) return;\n");
        k.append("  int col = g / ncy; int cy = g % ncy;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bps = 0; c->bpn = 0; c->bjs = 0; c->bjn = 0; c->epn = 0; c->ovr = 0;\n");
        k.append("  c->hz = hz; c->hbase = col * ").append(nh).append(";\n");
        k.append("  c->bx = bx0 + (col / ncz) * ").append(cw).append("; c->bz = bz0 + (col % ncz) * ").append(cw)
            .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
        if (!perChunkCorners) {
            for (int i = 0; i < roots.size(); i++) {
                k.append("  corners[").append(i).append(" * ncx * ncz * ncy + g] = cr_").append(i).append("(c);\n");
            }
        }
        k.append("}\n");
        if (certify) {
            k.setLength(fp64Kernel);
        }
        // the block tree's x/z-only parts, once per block column of the region
        k.append("__kernel void k_bhoist(").append(COMMON_ARGS).append(", __global R* bh, int bx0, int bz0, int nbx, int nbz) {\n");
        k.append("  int g = get_global_id(0); if (g >= nbx * nbz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bx = bx0 + g / nbz; c->by = 0; c->bz = bz0 + g % nbz;\n");
        for (int i = 0; i < blockHoisted.size(); i++) {
            k.append("  bh[g * ").append(nb).append(" + ").append(i).append("] = xb_").append(i).append("(c);\n");
        }
        k.append("}\n");
        // preliminary surface levels (chunk-independent: no chunk's flat range applies)
        k.append("__kernel void k_surface(").append(COMMON_ARGS).append(", __global int* surf, int sqx0, int sqz0, int nsx, int nsz) {\n");
        k.append("  int g = get_global_id(0); if (g >= nsx * nsz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->cqx0 = 0x40000000; c->cqz0 = 0x40000000;\n");
        k.append("  c->bx = (sqx0 + g / nsz) << 2; c->bz = (sqz0 + g % nsz) << 2;\n");
        k.append("  int res = 0x7fffffff;\n");
        k.append("  for (int y = ").append(minY + height).append("; y >= ").append(minY).append("; y -= ").append(cellHeight).append(") {\n");
        k.append("    c->by = y; reset_APOINT_Y(c);\n");
        k.append("    if ((double) ap_initial_density_without_jaggedness(c) > 0.390625) { res = y; break; }\n");
        k.append("  }\n  surf[g] = res;\n}\n");
        // each chunk's own 5x5 quart columns, with the chunk's flat cache view (only used when a flat cache reads y)
        k.append("__kernel void k_surface_in(").append(COMMON_ARGS).append(", __global int* surfIn, int cx0, int cz0, int hchunks, int nchunks) {\n");
        k.append("  int g = get_global_id(0); if (g >= nchunks * 25) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / 25; int col = g % 25; int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  c->bx = (bcx * 4 + col / 5) << 2; c->bz = (bcz * 4 + col % 5) << 2;\n");
        k.append("  int res = 0x7fffffff;\n");
        k.append("  for (int y = ").append(minY + height).append("; y >= ").append(minY).append("; y -= ").append(cellHeight).append(") {\n");
        k.append("    c->by = y; reset_APOINT_Y(c);\n");
        k.append("    if ((double) ap_initial_density_without_jaggedness(c) > 0.390625) { res = y; break; }\n");
        k.append("  }\n  surfIn[g] = res;\n}\n");
        // aquifer cells of every chunk of the region
        k.append("__kernel void k_status(").append(COMMON_ARGS)
            .append(", __global const int* surf, __global const int* surfIn, int sqx0, int sqz0, int nsz, __global int* cells, int cx0, int cz0, int hchunks, int nchunks, ulong aqLo, ulong aqHi")
            .append(c2dKind == C2d.FLOODED ? ", __global int* c2" : "").append(") {\n");
        k.append("  int g = get_global_id(0); if (g >= nchunks * ").append(cellsPerChunk).append(") return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / ").append(cellsPerChunk).append("; int ci = g % ").append(cellsPerChunk).append(";\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  int ii = ci % 3; int t = ci / 3; int kk = t % 3; int jj = t / 3;\n");
        k.append("  int gx = bcx - 1 + ii; int gy = ").append(minGridY).append(" + jj; int gz = bcz - 1 + kk;\n");
        k.append("  Xo r = xo_at(aqLo, aqHi, gx, gy, gz);\n");
        k.append("  int lx = gx * 16 + xo_nextInt(&r, 10); int ly = gy * 12 + xo_nextInt(&r, 9); int lz = gz * 16 + xo_nextInt(&r, 10);\n");
        if (c2dKind == C2d.FLOODED) {
            k.append("  c->c2h = 0;\n");
        }
        k.append("  FS s = compute_fluid(c, lx, ly, lz, surf, surfIn, b, sqx0, sqz0, nsz);\n");
        k.append("  cells[g * 5] = lx; cells[g * 5 + 1] = ly; cells[g * 5 + 2] = lz; cells[g * 5 + 3] = s.level; cells[g * 5 + 4] = s.type;\n");
        if (c2dKind == C2d.FLOODED) {
            // the cache_2d that floodedness reads answers with the value of the first call at its column since the last call elsewhere.
            // Whatever order vanilla asks this chunk's cells in, a cell reads its own value when the grid's cells at its column give the
            // same value (bit for bit). Only cells of the same grid column can share that column. Otherwise the chunk goes to the CPU.
            k.append("  int conflict = 0;\n  if (c->c2h) {\n    long v0 = as_long((double) c->c2v);\n");
            k.append("    for (int j2 = 0; j2 < ").append(cellsY).append(" && !conflict; j2++) {\n      if (j2 == jj) continue;\n");
            k.append("      int gy2 = ").append(minGridY).append(" + j2;\n      Xo r2 = xo_at(aqLo, aqHi, gx, gy2, gz);\n");
            k.append("      int lx2 = gx * 16 + xo_nextInt(&r2, 10); int ly2 = gy2 * 12 + xo_nextInt(&r2, 9); int lz2 = gz * 16 + xo_nextInt(&r2, 10);\n");
            k.append("      if (lx2 != lx || lz2 != lz) continue;\n");
            // whether that cell's status reaches floodedness at all (computeFluid's early returns and deep dark test), and its value
            k.append("      c->c2h = 0;\n      (void) compute_fluid(c, lx2, ly2, lz2, surf, surfIn, b, sqx0, sqz0, nsz);\n");
            k.append("      if (c->c2h && as_long((double) c->c2v) != v0) conflict = 1;\n    }\n  }\n  c2[g] = conflict;\n");
        }
        k.append("}\n");
        if (c2dKind == C2d.FLOODED) {
            // per chunk: whether any of its cells' columns disagree (read back with the region)
            k.append("__kernel void k_c2flags(__global const int* c2, __global int* flags, int nchunks) {\n");
            k.append("  int g = get_global_id(0); if (g >= nchunks) return;\n  int f = 0;\n");
            k.append("  for (int i = 0; i < ").append(cellsPerChunk).append("; i++) f |= c2[g * ").append(cellsPerChunk).append(" + i];\n");
            k.append("  flags[g] = f;\n}\n");
        }
        // every chunk's highest aquifer cell fluid level (the cells its blocks can see)
        // with -DGA2_NONCE=<n>, a kernel whose code depends on n. No cache, the driver's included, has seen that build (cold build timing).
        k.append("#ifdef GA2_NONCE\n__kernel void k_nonce(__global int* o) { o[get_global_id(0)] = GA2_NONCE * (int) get_global_id(0); }\n#endif\n");
        k.append("__kernel void k_levels(__global const int* cells, __global int* levels, int nchunks) {\n");
        k.append("  int g = get_global_id(0); if (g >= nchunks) return;\n  int m = INT_MIN;\n");
        k.append("  for (int i = 0; i < ").append(cellsPerChunk).append("; i++) m = max(m, cells[(g * ").append(cellsPerChunk).append(" + i) * 5 + 3]);\n");
        k.append("  levels[g] = m;\n}\n");
        // what SURFACE and CARVERS read of each listed chunk (gpu.GpuTerrain). First, per aquifer cell: the fluid level, then the fluid
        // (bit 0: lava) with the cell centre's offsets in the cell (x, y, z in bits 8, 12, 16). Next, the surface levels of the chunk's
        // 5 x 5 quart columns, using the chunk's own flat-cache view, as the chunk's NoiseChunk computes them. Last, the aquifer's fluid
        // update flag after the fill's last block (x 15, z 15, the lowest y). A carver reads that flag before the aquifer computes
        // anything.
        int cpc = cellsPerChunk;
        k.append("__kernel void k_terrain(__global const int* lst, int n, __global const int* cells, __global const int* surf, __global const int* surfIn, "
            + "int sqx0, int sqz0, int nsz, int cx0, int cz0, int hchunks, __global const uchar* blocks, __global int* out) {\n");
        k.append("  int g = get_global_id(0); if (g >= n * ").append(terrainInts()).append(") return;\n");
        k.append("  int i = g / ").append(terrainInts()).append(", k = g % ").append(terrainInts()).append("; int b = lst[i];\n");
        k.append("  int bcx = cx0 + b / hchunks, bcz = cz0 + b % hchunks;\n");
        k.append("  if (k < ").append(2 * cpc).append(") {\n");
        k.append("    int ci = k >> 1; __global const int* c = cells + (b * ").append(cpc).append(" + ci) * 5;\n");
        k.append("    if ((k & 1) == 0) { out[g] = c[3]; } else {\n");
        k.append("      int ii = ci % 3; int t = ci / 3; int kk = t % 3; int jj = t / 3;\n");
        k.append("      int ox = c[0] - (bcx - 1 + ii) * 16, oy = c[1] - (").append(minGridY).append(" + jj) * 12, oz = c[2] - (bcz - 1 + kk) * 16;\n");
        k.append("      out[g] = (c[4] == ").append(ID_LAVA).append(" ? 1 : 0) | (ox << 8) | (oy << 12) | (oz << 16);\n    }\n");
        k.append("  } else if (k == ").append(terrainInts() - 1).append(") {\n");
        k.append("    out[g] = blocks[(long) i * ").append(256 * height).append(" + 255 * ").append(height).append("] >> 7 & 1;\n");
        k.append("  } else {\n    int col = k - ").append(2 * cpc).append("; int qx = bcx * 4 + col / 5, qz = bcz * 4 + col % 5;\n");
        k.append("    out[g] = ").append(surfacePerChunk ? "surfIn[b * 25 + col]" : "surf[(qx - sqx0) * nsz + (qz - sqz0)]").append(";\n  }\n}\n");
        if (biomes) {
            climateKernels(k);
        }

        // ---- batch kernels (a list of the region's chunks, with their structures) ----
        batchKernels(k, cw, per, pc, nh, nb, h);
        return k.toString();
    }

    private void climateKernels(StringBuilder k) {
        // The climate parameters, once per quart column of every chunk of the region. Each one is written only when it depends on x and
        // z alone. In vanilla that holds for temperature, vegetation, continents, erosion and ridges, but not for depth.
        String[] climate = { "temperature", "vegetation", "continents", "erosion", "depth", "ridges" };
        boolean[] xz = climateColumns();
        k.append("__kernel void k_climate(").append(COMMON_ARGS).append(", __global R* cz, int cx0, int cz0, int hchunks, int nchunks, __global int* tieCount) {\n");
        k.append("  int g = get_global_id(0); if (g == 0) *tieCount = 0; if (g >= nchunks * 16) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / 16; int qx = (g % 16) / 4; int qz = g % 4;\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  c->bx = bcx * 16 + qx * 4; c->by = ").append(minY).append("; c->bz = bcz * 16 + qz * 4;\n");
        for (int i = 0; i < climate.length; i++) {
            if (xz[i]) {
                k.append("  cz[g * ").append(climate.length).append(" + ").append(i).append("] = ap_").append(climate[i]).append("(c);\n");
            }
        }
        k.append("}\n");
        // biomes of every chunk of the region. Ties are listed with their climate point for the CPU.
        k.append("__kernel void k_biome(").append(COMMON_ARGS)
            .append(", __global const long* tb, __global const long* toff, __global const int* tfirst, __global const int* tcount, __global const int* tleaf,"
                + " __global int* biomes, int cx0, int cz0, int hchunks, int nchunks, __global const R* cz, __global long* tieList, __global int* tieCount, int tieCap")
            .append(biomeRegions ? ", __global const int* troot, int ntrees, __global const int* tdefer, __global const int* regionMap, __global int* fallbacks" : "")
            .append(biomeTargets ? ", __global int* targets" : "")
            .append(biomeHand ? ", __global const int* tkey, __global int* hand" : "")
            .append(") {\n");
        int quartsY = height / 4;
        // work-items in quart column order (y fastest). A warp's climate points differ in depth only, so the warp's walks meet the same
        // nodes. g is the quart's index in the chunk's (y, x, z) order, which the answers keep.
        k.append("  int gi = get_global_id(0); if (gi >= nchunks * ").append(16 * quartsY).append(") return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = gi / ").append(16 * quartsY).append("; int r = gi % ").append(16 * quartsY).append("; int qy = r % ").append(quartsY)
            .append("; int qx = r / ").append(4 * quartsY).append("; int qz = (r / ").append(quartsY).append(") % 4;\n");
        k.append("  int g = b * ").append(16 * quartsY).append(" + qy * 16 + qx * 4 + qz;\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  c->bx = bcx * 16 + qx * 4; c->by = ").append(minY).append(" + qy * 4; c->bz = bcz * 16 + qz * 4;\n");
        k.append("  __global const R* col = cz + (b * 16 + qx * 4 + qz) * ").append(climate.length).append(";\n");
        for (int i = 0; i < climate.length; i++) {
            k.append("  long q").append(i).append(" = (long) ((float) ").append(xz[i] ? "col[" + i + "]" : "ap_" + climate[i] + "(c)").append(" * 10000.0f);\n");
        }
        if (biomeHand) {
            // the handed answers: -1 where the CPU searches itself, -2 for no leaf of another biome, -3 for no second tree
            k.append("  int hu1 = -1, hp1 = -1, hu2 = -3, hp2 = -3;\n");
        }
        if (biomeRegions) {
            // TerraBlender: the column's region tree. A placeholder leaf sends the quart to region 0's tree. A region without a tree
            // is marked as a tie, so the CPU asks TerraBlender itself
            k.append("  int u = regionMap[b * 16 + qx * 4 + qz]; int root = u >= 0 && u < ntrees ? troot[u] : -1;\n");
            k.append("  int ties_n = 2; int leaf = 0; int fb = 0;\n");
            k.append("  if (root >= 0) {\n    leaf = tree_search(tb, toff, tfirst, tcount, root, q0, q1, q2, q3, q4, q5, &ties_n);\n");
            if (biomeHand) {
                k.append("    if (ties_n == 1) { hu1 = leaf; hp1 = hand_other(tb, toff, tfirst, tcount, tkey, root, leaf, q0, q1, q2, q3, q4, q5); }\n");
            }
            k.append("    if (ties_n <= 1 && tdefer[leaf]) {\n      int ties_f; int root0 = troot[0];\n");
            k.append("      if (root0 >= 0) { fb = tree_search(tb, toff, tfirst, tcount, root0, q0, q1, q2, q3, q4, q5, &ties_f);");
            if (biomeHand) {
                k.append(" hu2 = -1; if (ties_f == 1) { hu2 = fb; hp2 = hand_other(tb, toff, tfirst, tcount, tkey, root0, fb, q0, q1, q2, q3, q4, q5); }");
            }
            k.append(" if (ties_f > 1) ties_n = ties_f; }\n");
            k.append("      else ties_n = 2;\n    }\n  }\n  fallbacks[g] = fb;\n");
        } else {
            k.append("  int ties_n;\n  int leaf = tree_search(tb, toff, tfirst, tcount, 0, q0, q1, q2, q3, q4, q5, &ties_n);\n");
            if (biomeHand) {
                k.append("  if (ties_n == 1) { hu1 = leaf; hp1 = hand_other(tb, toff, tfirst, tcount, tkey, 0, leaf, q0, q1, q2, q3, q4, q5); }\n");
            }
        }
        if (biomeHand) {
            k.append("  __global int* hq = hand + (long) g * 4; hq[0] = hu1; hq[1] = hp1; hq[2] = hu2; hq[3] = hp2;\n");
        }
        // the leaf. The CPU keeps vanilla's per-thread last result from it. On a tie, vanilla's answer depends on that last result. A tie
        // is marked and listed with its climate point, and the CPU searches it as vanilla does.
        k.append("  biomes[g] = leaf | (ties_n > 1 ? (int) 0x80000000 : 0);\n");
        if (biomeTargets) {
            // the climate point, for a biome source that works on after the search (Lithostitched's injectors). Parameters of x and z
            // go once per quart column, the others per quart. A value beyond an int is marked, and the CPU samples that quart itself
            k.append("  #define TGT(q) ((q) >= -2147483647L && (q) <= 2147483647L ? (int) (q) : (int) 0x80000000)\n");
            k.append("  if (qy == 0) {\n");
            for (int i = 0; i < climate.length; i++) {
                if (xz[i]) {
                    k.append("    targets[(b * 16 + qx * 4 + qz) * ").append(climate.length).append(" + ").append(i).append("] = TGT(q").append(i).append(");\n");
                }
            }
            k.append("  }\n");
            int nq = 0;
            for (boolean c : xz) {
                nq += c ? 0 : 1;
            }
            int j = 0;
            for (int i = 0; i < climate.length; i++) {
                if (!xz[i]) {
                    k.append("  targets[(long) nchunks * ").append(16 * climate.length).append(" + (long) g * ").append(nq).append(" + ").append(j++).append("] = TGT(q")
                        .append(i).append(");\n");
                }
            }
            k.append("  #undef TGT\n");
        }
        k.append("  if (ties_n > 1) {\n    int s = atomic_inc(tieCount);\n    if (s < tieCap) {\n      __global long* t = tieList + (long) s * 7;\n");
        k.append("      t[0] = g; t[1] = q0; t[2] = q1; t[3] = q2; t[4] = q3; t[5] = q4; t[6] = q5;\n    }\n  }\n}\n");
    }

    /** Per climate parameter (temperature, vegetation, continents, erosion, depth, ridges), whether it is a function of x and z only. */
    public boolean[] climateColumns() {
        String[] climate = { "temperature", "vegetation", "continents", "erosion", "depth", "ridges" };
        boolean[] xz = new boolean[climate.length];
        for (int i = 0; i < climate.length; i++) {
            xz[i] = pointDeps.of(routerRoot(climate[i])).isXzOrConst();
        }
        return xz;
    }

    private void batchKernels(StringBuilder k, int cw, int per, int pc, int nh, int nb, int h) {
        // each listed chunk's own corners. The roots read the chunk's beardifier, so border corners differ by chunk. Then the blocks.
        // Both kernels are double. As with k_corner, a certified program leaves them out.
        int fp64Kernel = k.length();
        k.append("__kernel void k_corner_pc(").append(COMMON_ARGS).append(", __global const R* hz, __global R* corners, int bx0, int bz0, int ncz, int ncy,"
            + " int hchunks, int n, __global const int* lst, __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const int* ek) {\n");
        k.append("  int g = get_global_id(0); if (g >= n * ").append(pc).append(" * ncy) return;\n");
        k.append("  int li = g / (").append(pc).append(" * ncy); int rem = g % (").append(pc).append(" * ncy); int col = rem / ncy; int cy = rem % ncy;\n");
        k.append("  int b = lst[li]; int lcx = col / ").append(per + 1).append(", lcz = col % ").append(per + 1).append("; int ix = b / hchunks, iz = b % hchunks;\n");
        k.append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  c->hz = hz; c->hbase = ((ix * ").append(per).append(" + lcx) * ncz + iz * ").append(per).append(" + lcz) * ").append(nh).append(";\n");
        k.append("  c->bx = bx0 + ix * 16 + lcx * ").append(cw).append("; c->bz = bz0 + iz * 16 + lcz * ").append(cw)
            .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
        if (perChunkCorners) {
            for (int i = 0; i < roots.size(); i++) {
                k.append("  corners[((li * ").append(roots.size()).append(" + ").append(i).append(") * ").append(pc).append(" + col) * ncy + cy] = cr_").append(i).append("(c);\n");
            }
        }
        k.append("}\n");
        // blocks of the listed chunks: out[li][x][z][y]
        k.append("__kernel void k_block(").append(COMMON_ARGS)
            .append(", __global const R* corners, __global uchar* out, __global R* dens, int bx0, int bz0, int nbz, int ncx, int ncz, int ncy, int n, __global const int* lst,"
                + " __global const int* cells, int cx0, int cz0, int hchunks, ulong oreLo, ulong oreHi,"
                + " __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const int* ek, __global const R* bh" + lqArg() + ") {\n");
        k.append("  int g = get_global_id(0); if (g >= n * ").append(256 * h).append(") return;\n");
        k.append("  int li = g / ").append(256 * h).append("; int r = g % ").append(256 * h).append("; int Y = r % ").append(h).append("; int t = r / ").append(h)
            .append("; int lz = t & 15; int lx = t >> 4;\n");
        k.append("  int b = lst[li]; int X = (b / hchunks) * 16 + lx; int Z = (b % hchunks) * 16 + lz;\n");
        k.append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  int cellX = X / ").append(cw).append(", cellY = Y / ").append(cellHeight).append(", cellZ = Z / ").append(cw).append(";\n");
        if (!perChunkCorners) {
            k.append("  c->cr = corners; c->rstride = ncx * ncz * ncy; c->csx = ncz * ncy; c->csz = ncy; c->cbase = (cellX * ncz + cellZ) * ncy + cellY;\n");
        } else {
            k.append("  c->cr = corners; c->rstride = ").append(pc).append(" * ncy; c->csx = ").append(per + 1).append(" * ncy; c->csz = ncy;\n");
            k.append("  c->cbase = li * ").append(roots.size() * pc).append(" * ncy + ((lx / ").append(cw).append(") * ").append(per + 1)
                .append(" + lz / ").append(cw).append(") * ncy + cellY;\n");
        }
        k.append("  c->dx = (R) ((double) (X % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->dy = (R) ((double) (Y % ").append(cellHeight).append(") / (double) ").append(cellHeight).append(");\n");
        k.append("  c->dz = (R) ((double) (Z % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->bx = bx0 + X; c->by = ").append(minY).append(" + Y; c->bz = bz0 + Z;\n");
        k.append("  c->bh = bh; c->bhbase = (X * nbz + Z) * ").append(nb).append(";\n");
        k.append("  R d = bk_final(c);\n");
        k.append("#ifdef WRITE_DENSITY\n  dens[(long) b * (256 * H_) + (lx * 16 + lz) * H_ + Y] = d; /* by the chunk's place in its region */\n#endif\n".replace("H_",
            Integer.toString(h)));
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  int flag = 0;\n");
        if (aquifers) {
            k.append("  int id = aquifer_decide(c, (double) d, cells, b * ").append(cellsPerChunk).append(", bcx, bcz, &flag);\n");
        } else {
            k.append("  int id = aquifer_disabled(c, (double) d, &flag);\n");
        }
        k.append(lqLine());
        if (veins) {
            k.append("  if (id == ID_NONE) { id = vein_rule(c, oreLo, oreHi); if (id == ID_NONE) id = ").append(ID_STONE).append("; }\n");
        } else {
            k.append("  if (id == ID_NONE) id = ").append(ID_STONE).append(";\n");
        }
        k.append(OVERRIDE_LINE);
        k.append(canonLine()).append("  out[g] = (uchar) (id | (flag << 7));\n}\n");
        if (certify) {
            k.setLength(fp64Kernel);
        }
        k.append(packKernels());
    }

    /**
     * Ints per section in k_pack's meta output: [0] palette size (0: all air) in its low byte, bit 8 set when the section has fluid
     * update marks, [1..15] block ids in palette order, [16..31] ids' counts.
     */
    public static final int META = 32;

    /**
     * Why a certified block's cell is rechecked, by bit of the context's {@code unc}: a step (range choice, rarity), the
     * density's sign, the aquifer, the vein toggle, threshold, ridge, chance and gap, a lifted-code step (comparison, floor, cast, division,
     * square root), an uncertain corner. {@code k_block32} counts the reasons of each listed cell's first uncertain block.
     */
    public static final String[] RECHECK_REASONS = { "step", "sign", "aquifer", "vein toggle", "vein threshold", "vein ridge", "vein chance", "vein gap",
        "lifted code", "corner" };

    /**
     * The noise fill's result in the form the chunk keeps it. Per section: vanilla's linear palette (air, then each block id in the
     * order {@code doFill} first writes it), the 4-bit packed storage and a bitmask of the fluid update marks. Per column: the tops of
     * the two worldgen heightmaps. One work-group of 256 per section. Blocks are read from k_block's output: {@code out[li][x][z][y]}.
     */
    private String packKernels() {
        return """
            /* doFill's order inside a section: cell x, cell z, y descending, then x and z inside the cell */
            int fill_rank(int x, int y, int z) { return ((((x / CW) * (16 / CW) + (z / CW)) * 16 + (15 - y)) * (CW * CW)) + (x % CW) * CW + (z % CW); }
            __kernel __attribute__((reqd_work_group_size(256, 1, 1)))
            void k_pack(__global const uchar* out, __global long* storage, __global int* meta, __global long* marks) {
              int grp = get_group_id(0); int lid = get_local_id(0);
              int li = grp / NSEC; int sec = grp % NSEC;
              __global const uchar* src = out + (long) li * (256 * HEIGHT) + sec * 16;
              __local int hist[16]; __local int first[16]; __local int map[16]; __local int anyMark;
              /* the section's blocks, read once (16 consecutive y of a column per 16 work-items): sb[(x * 16 + z) * 16 + y] */
              __local uchar sb[4096];
              if (lid < 16) { hist[lid] = 0; first[lid] = 0x7fffffff; map[lid] = 0; }
              if (lid == 0) anyMark = 0;
              for (int e = lid; e < 4096; e += 256) sb[e] = src[(e >> 4) * HEIGHT + (e & 15)];
              barrier(CLK_LOCAL_MEM_FENCE);
              int mark = 0;
              for (int k = 0; k < 16; k++) {
                int i = lid * 16 + k; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int g = sb[(x * 16 + z) * 16 + y];
                int id = g & 0x7f;
                atomic_inc(&hist[id]);
                if (id != ID_AIR) atomic_min(&first[id], fill_rank(x, y, z));
                if ((g & 0x80) && ((FLUID_MASK >> id) & 1)) mark = 1;
              }
              if (mark) atomic_or(&anyMark, 1);
              barrier(CLK_LOCAL_MEM_FENCE);
              if (lid == 0) {
                int n = 0; int order[16];
                for (int id = 1; id < 16; id++) if (hist[id] > 0) order[n++] = id;
                for (int a = 1; a < n; a++) { int v = order[a]; int c = a - 1; while (c >= 0 && first[order[c]] > first[v]) { order[c + 1] = order[c]; c--; } order[c + 1] = v; }
                for (int a = 0; a < n; a++) map[order[a]] = a + 1;
                __global int* m = meta + grp * 32;
                m[0] = (n == 0 ? 0 : n + 1) | (anyMark << 8);
                for (int a = 0; a < 15; a++) m[1 + a] = a < n ? order[a] : -1;
                for (int a = 0; a < 16; a++) m[16 + a] = hist[a];
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              ulong word = 0;
              for (int j = 0; j < 16; j++) {
                int i = lid * 16 + j; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int id = sb[(x * 16 + z) * 16 + y] & 0x7f;
                word |= (ulong) map[id] << (4 * j);
              }
              storage[grp * 256 + lid] = (long) word;
              if (lid < 64) {
                ulong mk = 0;
                for (int j = 0; j < 64; j++) {
                  int i = lid * 64 + j; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                  int g = sb[(x * 16 + z) * 16 + y];
                  int id = g & 0x7f;
                  if ((g & 0x80) && ((FLUID_MASK >> id) & 1)) mk |= 1UL << j;
                }
                marks[grp * 64 + lid] = (long) mk;
              }
            }
            /* per column: the highest non-air block (WORLD_SURFACE_WG) and the highest block that blocks motion (OCEAN_FLOOR_WG) */
            __kernel void k_heights(__global const uchar* out, int n, __global int* tops) {
              int g = get_global_id(0); if (g >= n * 256) return;
              int li = g / 256; int col = g % 256; int x = col & 15, z = col >> 4;
              __global const uchar* p = out + (long) li * (256 * HEIGHT) + (x * 16 + z) * HEIGHT;
              int surf = INT_MIN, ocean = INT_MIN;
              for (int Y = HEIGHT - 1; Y >= 0; Y--) {
                int id = p[Y] & 0x7f;
                if (surf == INT_MIN && ((NONAIR_MASK >> id) & 1)) surf = MINY_B + Y;
                if ((MOTION_MASK >> id) & 1) { ocean = MINY_B + Y; break; }
              }
              tops[g * 2] = surf; tops[g * 2 + 1] = ocean;
            }
            """.replace("NSEC", Integer.toString(height / 16)).replace("HEIGHT", Integer.toString(height)).replace("MINY_B", Integer.toString(minY))
            .replace("FLUID_MASK", fluidMask + "u").replace("NONAIR_MASK", nonAirMask + "u").replace("MOTION_MASK", motionMask + "u")
            .replace("CW", Integer.toString(cellWidth));
    }

    /**
     * Maps each id to the lowest id with the same block state, or null when every id has its own state. Ids can share a state: the
     * default fluid is air on Ad Astra's planets and lava in the Nether. Blocks are written with the canonical id, so a section's palette
     * holds each state once, as vanilla's does.
     */
    public int[] canonicalIds;

    /** The statement mapping the decided {@code id} to its canonical id before it is written (nothing when ids and states are one to one). */
    private String canonLine() {
        if (canonicalIds == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("  ");
        for (int id = 0; id < canonicalIds.length; id++) {
            if (canonicalIds[id] != id) {
                b.append("if (id == ").append(id).append(") id = ").append(canonicalIds[id]).append("; ");
            }
        }
        return b.append('\n').toString();
    }

    /**
     * {@code picker(y)}: vanilla's two intervals, or the dimension's probed intervals ({@link #pickerIntervals}). Vanilla's form uses
     * the macros {@code PICK_LAVA_Y} and {@code SEA}, and its text is always the same. The function goes into formatted text, so it
     * holds no '%'.
     */
    private String pickerFunction() {
        if (pickerIntervals == null) {
            return "FS picker(int y) { FS f; if (y < PICK_LAVA_Y) { f.level = -54; f.type = ID_LAVA; } else { f.level = SEA; f.type = ID_WATER; } return f; }";
        }
        StringBuilder b = new StringBuilder("FS picker(int y) { FS f;");
        for (int i = pickerIntervals.size() - 1; i > 0; i--) {
            int[] iv = pickerIntervals.get(i);
            b.append(" if (y >= ").append(iv[0]).append(") { f.level = ").append(iv[1]).append("; f.type = ").append(iv[2]).append("; return f; }");
        }
        int[] first = pickerIntervals.get(0);
        return b.append(" f.level = ").append(first[1]).append("; f.type = ").append(first[2]).append("; return f; }").toString();
    }

    /** Aquifer statuses, the per-block aquifer and ore vein rules, the climate tree walk. */
    private String decisionCode() {
        String R = R();
        return ("""
            #define ID_NONE 255
            #define ID_AIR %d
            #define ID_WATER %d
            #define ID_LAVA %d
            #define SEA %d
            #define PICK_LAVA_Y %d
            #define WAY_BELOW_MIN_Y (-32512)
            #define LQ_STRIDE 257
            #define LQ_NONE (-2147483647 - 1)
            #define MINGY %d
            #define SURF_PER_CHUNK SPC_VALUE
            #define FLUID_LAVA FLUID_LAVA_VALUE
            #define IS_LAVA(t) ((t) == ID_LAVA || (FLUID_LAVA && (t) == ID_WATER))
            #define IS_WATER(t) (!FLUID_LAVA && (t) == ID_WATER)
            typedef struct { int level; int type; } FS;
            PICKER_FN
            int fs_at(FS f, int y) { return y < f.level ? f.type : ID_AIR; }
            int jfloordiv(int a, int b) { int q = a / b; if ((a %% b != 0) && ((a < 0) != (b < 0))) q--; return q; }
            int iwrap_add(int a, int b) { return (int) ((uint) a + (uint) b); }
            int iwrap_sub(int a, int b) { return (int) ((uint) a - (uint) b); }
            double jmap(double v, double a, double b, double c, double d) { return c + ((v - a) / (b - a)) * (d - c); }
            int surf_at(Ctx* c, __global const int* surf, __global const int* surfIn, int b, int sqx0, int sqz0, int nsz, int x, int z) {
              if (SURF_PER_CHUNK) { int ix = (x >> 2) - c->cqx0, iz = (z >> 2) - c->cqz0; if ((unsigned) ix < 5u && (unsigned) iz < 5u) return surfIn[b * 25 + ix * 5 + iz]; }
              return surf[((x >> 2) - sqx0) * nsz + ((z >> 2) - sqz0)];
            }
            /* NoiseBasedAquifer.computeFluid with its computeSurfaceLevel, computeRandomizedFluidSurfaceLevel and computeFluidType */
            NI FS compute_fluid(Ctx* c, int x, int y, int z, __global const int* surf, __global const int* surfIn, int b, int sqx0, int sqz0, int nsz) {
              FS gp = picker(y);
              int mn = 0x7fffffff;
              int j = y + 12;
              int k = y - 12;
              int flag = 0;
              const int OX[13] = { 0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1 };
              const int OZ[13] = { 0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1 };
              for (int o = 0; o < 13; o++) {
                int l = x + OX[o] * 16;
                int i1 = z + OZ[o] * 16;
                int j1 = surf_at(c, surf, surfIn, b, sqx0, sqz0, nsz, l, i1);
                int k1 = iwrap_add(j1, 8);
                int flag1 = OX[o] == 0 && OZ[o] == 0;
                if (flag1 && k > k1) return gp;
                int flag2 = j > k1;
                if (flag2 || flag1) {
                  FS s1 = picker(k1);
                  if (fs_at(s1, k1) != ID_AIR) {
                    if (flag1) flag = 1;
                    if (flag2) return s1;
                  }
                }
                mn = min(mn, j1);
              }
              /* computeSurfaceLevel */
              c->bx = x; c->by = y; c->bz = z; reset_APOINT(c);
              double d0, d1;
              if ((double) ap_erosion(c) < (double) -0.225f && (double) ap_depth(c) > (double) 0.9f) {
                d0 = -1.0; d1 = -1.0;
              } else {
                int i = iwrap_sub(iwrap_add(mn, 8), y);
                double d2 = flag ? jclampedMap((double) i, 0.0, 64.0, 1.0, 0.0) : 0.0;
                double v = (double) ap_fluid_level_floodedness(c);
                double d3 = v < -1.0 ? -1.0 : jmind(v, 1.0);
                double d4 = jmap(d2, 1.0, 0.0, -0.3, 0.8);
                double d5 = jmap(d2, 1.0, 0.0, -0.8, 0.4);
                d0 = d3 - d5;
                d1 = d3 - d4;
              }
              int level;
              if (d1 > 0.0) {
                level = gp.level;
              } else if (d0 > 0.0) {
                int kq = jfloordiv(x, 16), lq = jfloordiv(y, 40), iq = jfloordiv(z, 16);
                int j1 = lq * 40 + 20;
                c->bx = kq; c->by = lq; c->bz = iq; reset_APOINT(c);
                double s = (double) ap_fluid_level_spread(c) * 10.0;
                int l1 = jfloor(s / 3.0) * 3;
                level = min(mn, j1 + l1);
              } else {
                level = WAY_BELOW_MIN_Y;
              }
              /* computeFluidType */
              int type = gp.type;
              if (level <= -10 && level != WAY_BELOW_MIN_Y && !IS_LAVA(gp.type)) {
                c->bx = jfloordiv(x, 64); c->by = jfloordiv(y, 40); c->bz = jfloordiv(z, 64); reset_APOINT(c);
                double lv = (double) ap_lava(c);
                if (fabs(lv) > 0.3) type = ID_LAVA;
              }
              FS out; out.level = level; out.type = type;
              return out;
            }
            double similarity(int a, int b) { return 1.0 - (double) abs(b - a) / 25.0; }
            double pressure(Ctx* c, double* barrier, FS a, FS b, int y) {
              int ba = fs_at(a, y), bb = fs_at(b, y);
              if ((IS_LAVA(ba) && IS_WATER(bb)) || (IS_WATER(ba) && IS_LAVA(bb))) return 2.0;
              int j = abs(a.level - b.level);
              if (j == 0) return 0.0;
              double d0 = 0.5 * (double) (a.level + b.level);
              double d1 = (double) y + 0.5 - d0;
              double d2 = (double) j / 2.0;
              double d9 = d2 - fabs(d1);
              double d10;
              if (d1 > 0.0) { double d11 = 0.0 + d9; d10 = d11 > 0.0 ? d11 / 1.5 : d11 / 2.5; }
              else { double d15 = 3.0 + d9; d10 = d15 > 0.0 ? d15 / 3.0 : d15 / 10.0; }
              double d12;
              if (!(d10 < -2.0) && !(d10 > 2.0)) {
                if (isnan(*barrier)) *barrier = (double) bk_barrier(c);
                d12 = *barrier;
              } else d12 = 0.0;
              return 2.0 * (d12 + d10);
            }
            FS cell_fs(__global const int* cells, int cbase, int ci) { FS f; f.level = cells[(cbase + ci) * 5 + 3]; f.type = cells[(cbase + ci) * 5 + 4]; return f; }
            /* NoiseBasedAquifer.computeSubstance: a block id, or ID_NONE for null (the block stays solid) */
            NI int aquifer_decide(Ctx* c, double density, __global const int* cells, int cbase, int bcx, int bcz, int* flag) {
              int x = c->bx, y = c->by, z = c->bz;
              if (density > 0.0) { *flag = 0; return ID_NONE; }
              if (IS_LAVA(fs_at(picker(y), y))) { *flag = 0; return ID_LAVA; }
              int l = jfloordiv(x - 5, 16), i1 = jfloordiv(y + 1, 12), j1 = jfloordiv(z - 5, 16);
              int k1 = 0x7fffffff, l1 = 0x7fffffff, i2 = 0x7fffffff;
              int a1 = 0, a2 = 0, a3 = 0;
              for (int i3 = 0; i3 <= 1; i3++) {
                for (int j3 = -1; j3 <= 1; j3++) {
                  for (int k3 = 0; k3 <= 1; k3++) {
                    int ci = ((i1 + j3 - MINGY) * 3 + (j1 + k3 - (bcz - 1))) * 3 + (l + i3 - (bcx - 1));
                    __global const int* cl = cells + (cbase + ci) * 5;
                    int ex = cl[0] - x, ey = cl[1] - y, ez = cl[2] - z;
                    int l5 = ex * ex + ey * ey + ez * ez;
                    if (k1 >= l5) { a3 = a2; a2 = a1; a1 = ci; i2 = l1; l1 = k1; k1 = l5; }
                    else if (l1 >= l5) { a3 = a2; a2 = ci; i2 = l1; l1 = l5; }
                    else if (i2 >= l5) { a3 = ci; i2 = l5; }
                  }
                }
              }
              FS s1 = cell_fs(cells, cbase, a1);
              double d1 = similarity(k1, l1);
              int bs = fs_at(s1, y);
              if (d1 <= 0.0) { *flag = d1 >= similarity(100, 144) ? 1 : 0; return bs; }
              if (IS_WATER(bs) && IS_LAVA(fs_at(picker(y - 1), y - 1))) { *flag = 1; return bs; }
              double barrier = NAN;
              FS s2 = cell_fs(cells, cbase, a2);
              double d2 = d1 * pressure(c, &barrier, s1, s2, y);
              if (density + d2 > 0.0) { *flag = 0; return ID_NONE; }
              FS s3 = cell_fs(cells, cbase, a3);
              double d0 = similarity(k1, i2);
              if (d0 > 0.0) {
                double d3 = d1 * d0 * pressure(c, &barrier, s1, s3, y);
                if (density + d3 > 0.0) { *flag = 0; return ID_NONE; }
              }
              double d4 = similarity(l1, i2);
              if (d4 > 0.0) {
                double d5 = d1 * d4 * pressure(c, &barrier, s2, s3, y);
                if (density + d5 > 0.0) { *flag = 0; return ID_NONE; }
              }
              *flag = 1;
              return bs;
            }
            /* Aquifer.createDisabled: the global fluid picker below zero density, never a fluid update */
            int aquifer_disabled(Ctx* c, double density, int* flag) { *flag = 0; if (density > 0.0) return ID_NONE; return fs_at(picker(c->by), c->by); }
            /* OreVeinifier's rule */
            NI int vein_rule(Ctx* c, ulong oreLo, ulong oreHi) {
              double d0 = (double) vn_toggle(c);
              int y = c->by;
              int copper = d0 > 0.0;
              int maxY = copper ? 50 : -8, minY = copper ? 0 : -60;
              double d1 = fabs(d0);
              int j = maxY - y, k = y - minY;
              if (k < 0 || j < 0) return ID_NONE;
              int l = min(j, k);
              double d2 = jclampedMap((double) l, 0.0, 20.0, -0.2, 0.0);
              if (d1 + d2 < (double) 0.4f) return ID_NONE;
              Xo r = xo_at(oreLo, oreHi, c->bx, y, c->bz);
              if (xo_nextFloat(&r) > 0.7f) return ID_NONE;
              if ((double) vn_ridged(c) >= 0.0) return ID_NONE;
              double d3 = jclampedMap(d1, (double) 0.4f, (double) 0.6f, (double) 0.1f, (double) 0.3f);
              if ((double) xo_nextFloat(&r) < d3 && (double) vn_gap(c) > (double) -0.3f) {
                return xo_nextFloat(&r) < 0.02f ? (copper ? %d : %d) : (copper ? %d : %d);
              }
              return copper ? %d : %d;
            }
            /* Climate.Parameter.distance and the R-tree node distance (offset dimension precomputed) */
            long pdist(long mn, long mx, long q) { long above = q - mx; long below = mn - q; return above > 0 ? above : max(below, 0L); }
            long node_dist(__global const long* tb, __global const long* toff, int id, long q0, long q1, long q2, long q3, long q4, long q5) {
              __global const long* b = tb + id * 12;
              long s = toff[id], t;
              t = pdist(b[0], b[1], q0); s += t * t;
              t = pdist(b[2], b[3], q1); s += t * t;
              t = pdist(b[4], b[5], q2); s += t * t;
              t = pdist(b[6], b[7], q3); s += t * t;
              t = pdist(b[8], b[9], q4); s += t * t;
              t = pdist(b[10], b[11], q5); s += t * t;
              return s;
            }
            /*
             * vanilla's SubTree.search walk without a last-result seed (GA2's FlatClimateTree.search with last = -1), once for the leaf
             * nearest the point and, in *ties, how many leaves lie at its distance (more than one: vanilla's answer depends on the thread's
             * last result, and the leaf returned is only the first the walk met): subtrees as near as the best so far are walked too. The
             * stack holds each level's end and next child. The walk starts at node root. A tree of one leaf is that leaf.
             */
            NI int tree_search(__global const long* tb, __global const long* toff, __global const int* first, __global const int* count, int root,
                            long q0, long q1, long q2, long q3, long q4, long q5, int* ties) {
              if (count[root] == 0) { *ties = 1; return root; }
              int stack[64];
              int sp = 0; stack[0] = first[root] + count[root]; stack[1] = first[root];
              int best = 0, n = 0; long bd = 0x7fffffffffffffffL;
              while (sp >= 0) {
                int child = stack[2 * sp + 1];
                if (child == stack[2 * sp]) { sp--; continue; }
                stack[2 * sp + 1] = child + 1;
                long d = node_dist(tb, toff, child, q0, q1, q2, q3, q4, q5);
                if (d <= bd) {
                  int cc = count[child];
                  if (cc == 0) { if (d < bd) { bd = d; best = child; n = 1; } else n++; }
                  else { int fc = first[child]; sp++; stack[2 * sp] = fc + cc; stack[2 * sp + 1] = fc; }
                }
              }
              *ties = n;
              return best;
            }
            /*
             * The nearest leaf whose biome (key) differs from excl's, walked as tree_search walks, with how many such leaves lie at its
             * distance in *ties. -1 when the tree has no such leaf.
             */
            NI int tree_other(__global const long* tb, __global const long* toff, __global const int* first, __global const int* count,
                              __global const int* key, int root, int excl, long q0, long q1, long q2, long q3, long q4, long q5, int* ties) {
              if (count[root] == 0) { *ties = key[root] != excl ? 1 : 0; return key[root] != excl ? root : -1; }
              int stack[64];
              int sp = 0; stack[0] = first[root] + count[root]; stack[1] = first[root];
              int best = -1, n = 0; long bd = 0x7fffffffffffffffL;
              while (sp >= 0) {
                int child = stack[2 * sp + 1];
                if (child == stack[2 * sp]) { sp--; continue; }
                stack[2 * sp + 1] = child + 1;
                long d = node_dist(tb, toff, child, q0, q1, q2, q3, q4, q5);
                if (d <= bd) {
                  int cc = count[child];
                  if (cc == 0) { if (key[child] != excl) { if (d < bd) { bd = d; best = child; n = 1; } else n++; } }
                  else { int fc = first[child]; sp++; stack[2 * sp] = fc + cc; stack[2 * sp + 1] = fc; }
                }
              }
              *ties = n;
              return best;
            }
            /*
             * The handed second answer for nearest leaf u: the nearest leaf of another biome when it is the only one at its distance,
             * -2 when the tree has no leaf of another biome, -1 when several share the distance (the CPU searches itself).
             */
            int hand_other(__global const long* tb, __global const long* toff, __global const int* first, __global const int* count,
                           __global const int* key, int root, int u, long q0, long q1, long q2, long q3, long q4, long q5) {
              int t;
              int p = tree_other(tb, toff, first, count, key, root, key[u], q0, q1, q2, q3, q4, q5, &t);
              return p < 0 ? -2 : t == 1 ? p : -1;
            }
            """.replace("SPC_VALUE", surfacePerChunk ? "1" : "0").replace("FLUID_LAVA_VALUE", fluidLava ? "1" : "0").replace("PICKER_FN", pickerFunction()))
            .formatted(ID_AIR, ID_WATER, ID_LAVA, seaLevel,
                Math.min(-54, seaLevel), minGridY,
                ID_RAW_COPPER, ID_RAW_IRON, ID_COPPER_ORE, ID_DEEPSLATE_IRON_ORE, ID_GRANITE, ID_TUFF);
    }

    // ---- per-function emission -------------------------------------------------------------------------------------

    private final class Fn {
        final Mode mode;
        final Set<Node> shared;
        final Map<Node, Integer> memo = new IdentityHashMap<>();
        /** The walk's cache_2d slots, by vanilla marker (equal markers are one wrapper in a NoiseChunk). */
        final Map<Object, Integer> walkMemo = new HashMap<>();
        int tmp;

        Fn(Mode mode, List<Node> fnRoots) {
            this.mode = mode;
            this.shared = shared(mode, fnRoots);
        }

        private String t() {
            return "t" + (tmp++);
        }

        private String tmp(StringBuilder sb, String expr) {
            String n = t();
            sb.append("  R ").append(n).append(" = ").append(expr).append(";\n");
            return n;
        }

        /** Emits statements computing {@code n} into {@code sb}; returns an R-typed expression (a name or a literal). */
        String ev(Node n, StringBuilder sb) {
            if (n instanceof Node.Const c) {
                return lit(c.value());
            }
            if (mode == Mode.BLOCK) {
                Integer bh = blockHoistIndex.get(n);
                if (bh != null) {
                    return "c->bh[c->bhbase + " + bh + "]";
                }
            }
            if (mode == Mode.CORNER) {
                Integer h = hoistIndex.get(n);
                if (h != null) {
                    return "c->hz[c->hbase + " + h + "]";
                }
            }
            if (shared.contains(n)) {
                return tmp(sb, sharedCall(n));
            }
            return compute(n, sb);
        }

        private String sharedCall(Node n) {
            Integer id = memo.get(n);
            String name;
            if (id == null) {
                id = memoCounts.getOrDefault(mode, 0); // the walk's cache_2d slots take ids from the same count (walkMemo)
                memo.put(n, id);
                memoCounts.merge(mode, 1, Integer::sum);
                if (mode == Mode.APOINT) {
                    apointXz.add(pointDeps.of(n).isXzOrConst());
                }
                name = "s" + mode.name().charAt(0) + "_" + id;
                helperProtos.append("R ").append(name).append("(Ctx* c);\n");
                StringBuilder b = new StringBuilder();
                int saved = tmp;
                String v = compute(n, b);
                tmp = saved;
                String slot = "MB_" + mode.name() + " + " + id;
                helpers.append("NI R ").append(name).append("(Ctx* c) {\n  if (c->mk[").append(slot).append("]) return c->mv[").append(slot).append("];\n");
                helpers.append(b);
                helpers.append("  c->mv[").append(slot).append("] = ").append(v).append("; c->mk[").append(slot).append("] = 1; return c->mv[").append(slot).append("];\n}\n");
            } else {
                name = "s" + mode.name().charAt(0) + "_" + id;
            }
            return name + "(c)";
        }

        /**
         * A cache_2d whose child reads y and that only the preliminary surface walk evaluates ({@link C2d#WALK}). Vanilla's wrapper
         * keeps the value of the first call at the walk's column. Every call of a walk is at the walk's column, and each walk has a
         * column of its own. So the slot is kept across the walk's steps. Equal markers share the wrapper in a NoiseChunk, so they
         * share the slot too.
         */
        private String walkMemo(Node.Marker m) {
            Object key = m.vanilla() != null ? m.vanilla() : m;
            Integer id = walkMemo.get(key);
            if (id == null) {
                id = memoCounts.getOrDefault(mode, 0);
                walkMemo.put(key, id);
                memoCounts.merge(mode, 1, Integer::sum);
                apointXz.add(true);
                String name = "sW_" + id;
                helperProtos.append("R ").append(name).append("(Ctx* c);\n");
                StringBuilder b = new StringBuilder();
                int saved = tmp;
                String v = ev(m.child(), b);
                tmp = saved;
                String slot = "MB_" + mode.name() + " + " + id;
                helpers.append("NI R ").append(name).append("(Ctx* c) {\n  if (c->mk[").append(slot).append("]) return c->mv[").append(slot).append("];\n");
                helpers.append(b);
                helpers.append("  c->mv[").append(slot).append("] = ").append(v).append("; c->mk[").append(slot).append("] = 1; return c->mv[").append(slot).append("];\n}\n");
            }
            return "sW_" + id + "(c)";
        }

        private String compute(Node n, StringBuilder sb) {
            return switch (n) {
                case Node.Const c -> lit(c.value());
                case Node.Ap2 a -> ap2(a, sb);
                case Node.MulOrAdd m -> tmp(sb, ev(m.input(), sb) + (m.mul() ? " * " : " + ") + lit(m.argument()));
                case Node.Mapped m -> {
                    String v = ev(m.input(), sb);
                    yield tmp(sb, switch (m.type()) {
                        case ABS -> "fabs(" + v + ")";
                        case SQUARE -> v + " * " + v;
                        case CUBE -> v + " * " + v + " * " + v;
                        case HALF_NEGATIVE -> v + " > 0 ? " + v + " : " + v + " * " + lit(0.5);
                        case QUARTER_NEGATIVE -> v + " > 0 ? " + v + " : " + v + " * " + lit(0.25);
                        case SQUEEZE -> "jsqueeze(" + v + ")";
                    });
                }
                case Node.Clamp c -> tmp(sb, "jclamp(" + ev(c.input(), sb) + ", " + lit(c.lo()) + ", " + lit(c.hi()) + ")");
                case Node.RangeChoice r -> {
                    String in = ev(r.input(), sb);
                    String out = t();
                    sb.append("  R ").append(out).append(";\n");
                    sb.append("  if (").append(in).append(" >= ").append(lit(r.minInclusive())).append(" && ").append(in).append(" < ").append(lit(r.maxExclusive())).append(") {\n");
                    String a = ev(r.inRange(), sb);
                    sb.append("  ").append(out).append(" = ").append(a).append(";\n  } else {\n");
                    String b = ev(r.outRange(), sb);
                    sb.append("  ").append(out).append(" = ").append(b).append(";\n  }\n");
                    yield out;
                }
                case Node.YGradient y -> tmp(sb, "(R) jclampedMap((double) c->by, " + dlit(y.fromY()) + ", " + dlit(y.toY()) + ", " + dlit(y.fromValue()) + ", " + dlit(y.toValue()) + ")");
                case Node.Noise no -> tmp(sb, noise(no.spec(), "(double) c->bx * " + dlit(no.xzScale()), "(double) c->by * " + dlit(no.yScale()), "(double) c->bz * " + dlit(no.xzScale())));
                case Node.ShiftedNoise s -> {
                    String sx = ev(s.shiftX(), sb);
                    String sy = ev(s.shiftY(), sb);
                    String sz = ev(s.shiftZ(), sb);
                    yield tmp(sb, noise(s.spec(), "(double) c->bx * " + dlit(s.xzScale()) + " + (double) " + sx,
                        "(double) c->by * " + dlit(s.yScale()) + " + (double) " + sy, "(double) c->bz * " + dlit(s.xzScale()) + " + (double) " + sz));
                }
                case Node.ShiftA s -> tmp(sb, noise(s.spec(), "(double) c->bx * 0.25", "0.0", "(double) c->bz * 0.25") + " * " + lit(4.0));
                case Node.ShiftB s -> tmp(sb, noise(s.spec(), "(double) c->bz * 0.25", "(double) c->bx * 0.25", "0.0") + " * " + lit(4.0));
                case Node.Shift s -> tmp(sb, noise(s.spec(), "(double) c->bx * 0.25", "(double) c->by * 0.25", "(double) c->bz * 0.25") + " * " + lit(4.0));
                case Node.Weird w -> {
                    String in = ev(w.input(), sb);
                    String r = t();
                    sb.append("  double ").append(r).append(" = ").append(w.type1() ? "rarity3D" : "rarity2D").append("((double) ").append(in).append(");\n");
                    if (w.spec() < 0) {
                        yield tmp(sb, "(R) " + r + " * " + lit(0.0));
                    }
                    yield tmp(sb, "(R) " + r + " * fabs(" + noise(w.spec(), "(double) c->bx / " + r, "(double) c->by / " + r, "(double) c->bz / " + r) + ")");
                }
                case Node.Spline s -> spline(s, sb);
                case Node.Blended b -> {
                    usedBlended.set(b.spec());
                    yield tmp(sb, (tables ? "bn(c, " + b.spec() + ", " : "bn_" + b.spec() + "(c, ") + "c->bx, c->by, c->bz)");
                }
                case Node.BlendDensity b -> ev(b.input(), sb);     // Blender.empty(): the input unchanged
                case Node.BlendAlpha a -> lit(1.0);
                case Node.BlendOffset o -> lit(0.0);
                case Node.Beardifier b -> {
                    if (mode != Mode.BLOCK && mode != Mode.XZBLOCK && !(mode == Mode.CORNER && perChunkCorners)) {
                        throw new Unsupported("beardifier outside the block tree");
                    }
                    yield tmp(sb, "(R) beard(c)"); // the chunk's structure pieces and jigsaw junctions (Beardifier.compute)
                }
                case Node.EndIslands e -> throw new Unsupported("end islands (the End generates fast on the CPU; a GPU port differed near the islands' bottom)");
                case Node.Marker m -> marker(m, sb);
                case Node.Unsupported u -> throw new Unsupported("uncompiled density function " + Names.mojangName(u.vanilla().getClass()) + ": " + u.reason());
                case Node.Select s -> {
                    String cond = cond(s.cond(), sb);
                    String out = t();
                    sb.append("  R ").append(out).append(";\n  if (").append(cond).append(") {\n");
                    String a = ev(s.whenJump(), sb);
                    sb.append("  ").append(out).append(" = ").append(a).append(";\n  } else {\n");
                    String b = ev(s.whenFall(), sb);
                    sb.append("  ").append(out).append(" = ").append(b).append(";\n  }\n");
                    yield out;
                }
                case Node.StaticCall c -> staticCall(c, sb);
                case Node.NoiseAt na -> {
                    String x = ev(na.x(), sb);
                    String y = ev(na.y(), sb);
                    String z = ev(na.z(), sb);
                    yield tmp(sb, noise(na.spec(), "(double) " + x, "(double) " + y, "(double) " + z));
                }
                case Node.IntToDouble i -> tmp(sb, "(R) (double) (" + evInt(i.value(), sb) + ")");
                case Node.RoundFloat r -> tmp(sb, "(R) (float) " + ev(r.value(), sb));
            };
        }

        private String ap2(Node.Ap2 a, StringBuilder sb) {
            if (a.type() == Node.Ap2Type.ADD || a.type() == Node.Ap2Type.MUL) {
                // inside a NoiseChunk vanilla evaluates the wrapped tree, where a constant operand is folded into MulOrAdd
                Node.Const k = a.a() instanceof Node.Const c1 ? c1 : (a.b() instanceof Node.Const c2 ? c2 : null);
                if (k != null) {
                    Node other = a.a() == k ? a.b() : a.a();
                    return tmp(sb, ev(other, sb) + (a.type() == Node.Ap2Type.MUL ? " * " : " + ") + lit(k.value()));
                }
            }
            String d0 = ev(a.a(), sb);
            if (a.type() == Node.Ap2Type.ADD) {
                return tmp(sb, d0 + " + " + ev(a.b(), sb));
            }
            String out = t();
            sb.append("  R ").append(out).append(";\n");
            switch (a.type()) {
                case MUL -> sb.append("  if (").append(d0).append(" == 0) ").append(out).append(" = ").append(lit(0.0)).append("; else {\n");
                case MIN -> sb.append("  if (").append(d0).append(" < ").append(lit(a.b().min())).append(") ").append(out).append(" = ").append(d0).append("; else {\n");
                case MAX -> sb.append("  if (").append(d0).append(" > ").append(lit(a.b().max())).append(") ").append(out).append(" = ").append(d0).append("; else {\n");
                default -> throw new IllegalStateException();
            }
            String b = ev(a.b(), sb);
            String op = switch (a.type()) {
                case MUL -> d0 + " * " + b;
                case MIN -> "jmin(" + d0 + ", " + b + ")";
                default -> "jmax(" + d0 + ", " + b + ")";
            };
            sb.append("  ").append(out).append(" = ").append(op).append(";\n  }\n");
            return out;
        }

        private String marker(Node.Marker m, StringBuilder sb) {
            switch (m.type()) {
                case CACHE_ONCE:
                    return ev(m.child(), sb);
                case CACHE_2D:
                    // a point outside the chunk's flat-cache range (APOINT: aquifer cells, the walk's neighbour columns) reads a flat cache's
                    // child at the point's own y. There the child reads y as the point sees it, not as the chunk's fill does.
                    if (mode == Mode.FLAT || (mode == Mode.APOINT ? pointDeps : deps).of(m.child()).isXzOrConst()) {
                        return ev(m.child(), sb);
                    }
                    // the chunk's wrapper answers with the value of the last (x, z) asked. Vanilla's call order decides that value, unless
                    // only the walk or only the aquifer's floodedness calls the wrapper (classifyCache2d).
                    if (mode == Mode.APOINT && c2dKind == C2d.WALK) {
                        return tmp(sb, walkMemo(m));
                    }
                    if (mode == Mode.APOINT && c2dKind == C2d.FLOODED) {
                        String v = ev(m.child(), sb);
                        sb.append("  c->c2h = 1; c->c2v = ").append(v).append(";\n");
                        return v;
                    }
                    throw new Unsupported("cache_2d over a y-dependent function (order-sensitive): " + deps.firstReadingY(m.child()) + ", reached from "
                        + c2dFields + c2dPaths);
                case FLAT_CACHE: {
                    if (mode == Mode.FLAT) {
                        return ev(m.child(), sb);
                    }
                    Integer f = flatIndex.get(m);
                    if (f == null) {
                        throw new Unsupported("flat cache not collected");
                    }
                    String read = "c->flat[" + f + " * c->fstride + ((c->bx >> 2) - c->qx0) * c->nqz + ((c->bz >> 2) - c->qz0)]";
                    if (mode != Mode.APOINT) {
                        return tmp(sb, read);
                    }
                    // a SinglePointContext on the chunk's wrapper: its array inside the chunk's quart range, the child elsewhere
                    String out = t();
                    sb.append("  R ").append(out).append(";\n");
                    sb.append("  if ((unsigned) ((c->bx >> 2) - c->cqx0) < 5u && (unsigned) ((c->bz >> 2) - c->cqz0) < 5u) ").append(out).append(" = ").append(read)
                        .append("; else {\n");
                    String v = tmp(sb, sharedCall(m.child())); // memoized: the surface walk keeps x/z-only values across its steps
                    sb.append("  ").append(out).append(" = ").append(v).append(";\n  }\n");
                    return out;
                }
                case INTERPOLATED: {
                    if (mode == Mode.FLAT || mode == Mode.APOINT) {
                        return ev(m.child(), sb); // not the NoiseChunk as context: the interpolator computes its child
                    }
                    if (mode != Mode.BLOCK && mode != Mode.VEIN && mode != Mode.XZBLOCK) {
                        throw new Unsupported("interpolated inside an interpolated root");
                    }
                    Integer r = lerpIndex.get(m);
                    if (r == null) {
                        throw new Unsupported("interpolated marker without a column root");
                    }
                    return tmp(sb, (mode == Mode.VEIN ? "lerpvc(c, " : "lerp3c(c, ") + r + ")");
                }
                default:
                    if (mode == Mode.FLAT || mode == Mode.APOINT) {
                        return ev(m.child(), sb);
                    }
                    throw new Unsupported("cache_all_in_cell");
            }
        }

        private String spline(Node.Spline s, StringBuilder sb) {
            if (s.spline() instanceof Node.SplineConst k) {
                return lit((double) k.value());
            }
            // every coordinate of the spline tree first (GA2's programs do the same), then the float evaluation
            Map<Node, Boolean> coords = new LinkedHashMap<>();
            splineCoords(s.spline(), coords);
            for (Node c : coords.keySet()) {
                Integer idx = coordIndex.computeIfAbsent(c, k -> coordIndex.size());
                String v = ev(c, sb);
                sb.append("  c->cv[").append(idx).append("] = (float) ").append(v).append(";\n");
            }
            int id = registerSpline((Node.SplineMulti) s.spline());
            return tmp(sb, "(R) " + splineFn("spl_", id) + "(c, " + id + ")");
        }

        private String cond(Node.Cond c, StringBuilder sb) {
            String cmp;
            if (c.kind() == Node.CmpKind.ICMP) {
                String a = evInt(c.intA(), sb);
                String b = evInt(c.intB(), sb);
                cmp = "((" + a + ") < (" + b + ") ? -1 : ((" + a + ") == (" + b + ") ? 0 : 1))";
            } else {
                String a = ev(c.a(), sb);
                String b = ev(c.b(), sb);
                String nan = c.kind() == Node.CmpKind.DCMPL ? "-1" : "1";
                cmp = "((isnan(" + a + ") || isnan(" + b + ")) ? " + nan + " : ((" + a + ") < (" + b + ") ? -1 : ((" + a + ") == (" + b + ") ? 0 : 1)))";
            }
            String r = t();
            sb.append("  int ").append(r).append(" = ").append(cmp).append(";\n");
            return r + switch (c.op()) {
                case EQ -> " == 0";
                case NE -> " != 0";
                case LT -> " < 0";
                case GE -> " >= 0";
                case GT -> " > 0";
                case LE -> " <= 0";
            };
        }

        private String staticCall(Node.StaticCall c, StringBuilder sb) {
            String extern = extern(c);
            if (extern != null) {
                return tmp(sb, "(R) (double) " + extern + "(" + externTables(c) + ", (double) " + ev((Node) c.args().get(1), sb) + ", (double) "
                    + ev((Node) c.args().get(2), sb) + ", (double) " + ev((Node) c.args().get(3), sb) + ")");
            }
            List<String> args = new ArrayList<>();
            for (Object a : c.args()) {
                args.add(a instanceof Node n ? ev(n, sb) : "(" + evInt((INode) a, sb) + ")");
            }
            if (c.owner().equals("$op")) {
                String op = switch (c.name()) {
                    case "dadd" -> " + ";
                    case "dsub" -> " - ";
                    case "dmul" -> " * ";
                    case "ddiv" -> " / ";
                    default -> null;
                };
                return tmp(sb, op != null ? args.get(0) + op + args.get(1) : "fmod(" + args.get(0) + ", " + args.get(1) + ")");
            }
            String key = staticKey(c.owner(), c.name(), c.desc());
            return tmp(sb, switch (key) {
                case "java/lang/Math.abs(D)D" -> "fabs(" + args.get(0) + ")";
                case "java/lang/Math.min(DD)D" -> "jmin(" + args.get(0) + ", " + args.get(1) + ")";
                case "java/lang/Math.max(DD)D" -> "jmax(" + args.get(0) + ", " + args.get(1) + ")";
                case "java/lang/Math.floor(D)D" -> "floor(" + args.get(0) + ")";
                case "java/lang/Math.ceil(D)D" -> "ceil(" + args.get(0) + ")";
                case "java/lang/Math.sqrt(D)D" -> "sqrt(" + args.get(0) + ")";
                case "net/minecraft/util/Mth.clamp(DDD)D" -> "jclamp(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
                // y_clamped_gradient at another position (dfc.ir.CoordSubstitution)
                case "net/minecraft/util/Mth.clampedMap(DDDDD)D" -> "jclampedMap(" + String.join(", ", args) + ")";
                default -> throw new Unsupported("static call " + key + " (not bit-exact on the GPU)");
            });
        }

        private String evInt(INode n, StringBuilder sb) {
            return switch (n) {
                case INode.IntConst c -> Integer.toString(c.value());
                case INode.IntCoord c -> c.axis() == 0 ? "c->bx" : c.axis() == 1 ? "c->by" : "c->bz";
                case INode.IntBin b -> {
                    String x = evInt(b.a(), sb);
                    String y = evInt(b.b(), sb);
                    yield switch (b.op()) {
                        case ADD -> "(" + x + " + " + y + ")";
                        case SUB -> "(" + x + " - " + y + ")";
                        case MUL -> "(" + x + " * " + y + ")";
                        case DIV -> "(" + x + " / " + y + ")";
                        case REM -> "(" + x + " % " + y + ")";
                        case SHL -> "(" + x + " << (" + y + " & 31))";
                        case SHR -> "(" + x + " >> (" + y + " & 31))";
                        case USHR -> "(int) ((uint) " + x + " >> (" + y + " & 31))";
                        case AND -> "(" + x + " & " + y + ")";
                        case OR -> "(" + x + " | " + y + ")";
                        case XOR -> "(" + x + " ^ " + y + ")";
                    };
                }
                case INode.IntNeg g -> "(-" + evInt(g.a(), sb) + ")";
                case INode.DoubleToInt d -> "convert_int_sat_rtz((double) " + ev(d.value(), sb) + ")";
                case INode.IntStaticCall c -> {
                    String key = staticKey(c.owner(), c.name(), c.desc());
                    if (key.equals("net/minecraft/util/Mth.floor(D)I")) {
                        yield "jfloor((double) " + ev((Node) c.args().get(0), sb) + ")";
                    }
                    throw new Unsupported("int static call " + key);
                }
                case INode.SelectInt s -> {
                    String cond = cond(s.cond(), sb);
                    String a = evInt(s.whenJump(), sb);
                    String b = evInt(s.whenFall(), sb);
                    yield "((" + cond + ") ? (" + a + ") : (" + b + "))";
                }
            };
        }
    }

    // ---- certified fp32 ---------------------------------------------------------------------------------------------

    /**
     * Certified float evaluation of the corner fill, the cell fill and the vein reads. Every value is computed in float exactly as
     * the F32 variant computes it. Each value carries a bound on its distance from the value GA2's double evaluation gives.
     *
     * <p>The bound is a running error bound. Each rounding is bounded by its result's magnitude. Each input error is carried with the
     * operation's sensitivity. Noise octaves, splines and lerps get bounds of their own (see the prelude's {@code _e} functions). A
     * spline input whose bound reaches into a neighbouring segment takes the steeper slope, because the spline is continuous.
     *
     * <p>A discontinuous operation is a range choice, a rarity step, or a comparison, floor or cast in lifted code. When its input
     * bound straddles its step, it sets the context's {@code unc} flag instead, and the cell (or corner) is recomputed in double. Any
     * node this does not cover (end islands, a static call without a certified form) makes the whole certified part
     * {@link Unsupported}.
     */
    private record V(String v, String e) {}

    private static final String FF_ZERO = "((ff) (0.0f, 0.0f))";

    /** Certified modes and their memo offsets ({@code MF_*}). */
    private enum CMode { CORNER, BLOCK, VEIN }

    private final Map<CMode, Integer> cmemoCounts = new EnumMap<>(CMode.class);
    private final StringBuilder chelperProtos = new StringBuilder();
    private final StringBuilder chelpers = new StringBuilder();

    private static Mode baseMode(CMode m) {
        return switch (m) {
            case CORNER -> Mode.CORNER;
            case BLOCK -> Mode.BLOCK;
            case VEIN -> Mode.VEIN;
        };
    }

    /** The smallest float at or above {@code v}. */
    static float ceilF(double v) {
        float f = (float) v;
        return (double) f < v ? Math.nextUp(f) : f;
    }

    /** The largest float at or below {@code v}. */
    static float floorF(double v) {
        float f = (float) v;
        return (double) f > v ? Math.nextDown(f) : f;
    }

    /** A float literal at or above the (non-negative) bound {@code v}. */
    static String up(double v) {
        if (!(v >= 0.0) || Double.isInfinite(v)) {
            throw new Unsupported("unbounded certified error term " + v);
        }
        return v == 0.0 ? "0.0f" : flit(ceilF(v));
    }

    /** A float constant's literal and its distance from the double constant. */
    private static V constant(double c) {
        float f = (float) c;
        if (Double.isNaN(c) || Double.isInfinite(c)) {
            throw new Unsupported("non-finite constant " + c);
        }
        return new V(flit(f), up(Math.abs(c - (double) f)));
    }

    private void certifiedFunctions(StringBuilder out, StringBuilder protos) {
        List<Node> blockRoots = List.of(cellRoot, routerRoot("barrier"));
        List<Node> veinRoots = List.of(routerRoot("vein_toggle"), routerRoot("vein_ridged"), routerRoot("vein_gap"));
        certifiedRoots(CMode.CORNER, "cr32_", roots, null, out, protos);
        certifiedRoots(CMode.BLOCK, "bk32_", blockRoots, new String[] { "final", "barrier" }, out, protos);
        certifiedRoots(CMode.VEIN, "vn32_", veinRoots, new String[] { "toggle", "ridged", "gap" }, out, protos);
    }

    private void certifiedRoots(CMode mode, String prefix, List<Node> group, String[] names, StringBuilder out, StringBuilder protos) {
        CFn fn = new CFn(mode, group);
        for (int i = 0; i < group.size(); i++) {
            String name = prefix + (names == null ? Integer.toString(i) : names[i]);
            fn.tmp = 0;
            StringBuilder body = new StringBuilder();
            V v = fn.ev(group.get(i), body);
            protos.append("float ").append(name).append("(Ctx* c, float* e);\n");
            out.append("NI float ").append(name).append("(Ctx* c, float* e) {\n").append(body).append("  *e = ").append(v.e()).append(";\n  return ").append(v.v())
                .append(";\n}\n");
        }
    }

    private final class CFn {
        final CMode mode;
        final Mode base;
        final Set<Node> shared;
        final Map<Node, Integer> memo = new IdentityHashMap<>();
        int tmp;

        CFn(CMode mode, List<Node> fnRoots) {
            this.mode = mode;
            this.base = baseMode(mode);
            this.shared = shared(base, fnRoots);
        }

        private V tmp(StringBuilder sb, String v, String e) {
            String n = "t" + tmp;
            String en = "e" + tmp;
            tmp++;
            sb.append("  float ").append(n).append(" = ").append(v).append(";\n");
            sb.append("  float ").append(en).append(" = ").append(e).append(";\n");
            return new V(n, en);
        }

        /** A declared, unassigned value and error (assigned in both branches of an if). */
        private V decl(StringBuilder sb) {
            String n = "t" + tmp;
            String en = "e" + tmp;
            tmp++;
            sb.append("  float ").append(n).append(", ").append(en).append(";\n");
            return new V(n, en);
        }

        V ev(Node n, StringBuilder sb) {
            if (n instanceof Node.Const c) {
                return constant(c.value());
            }
            if (mode == CMode.CORNER) {
                Integer h = hoistIndex.get(n);
                if (h != null) {
                    return readRounded(sb, "c->hz[c->hbase + " + h + "]");
                }
            }
            if (mode == CMode.BLOCK) {
                Integer bh = blockHoistIndex.get(n);
                if (bh != null) {
                    return readRounded(sb, "c->bh[c->bhbase + " + bh + "]");
                }
            }
            if (shared.contains(n)) {
                return sharedCall(n, sb);
            }
            return compute(n, sb);
        }

        /** A double read rounded to float: its error is that rounding. */
        private V readRounded(StringBuilder sb, String doubleExpr) {
            String n = "t" + tmp;
            String en = "e" + tmp;
            String d = "d" + tmp;
            tmp++;
            // that rounding exactly (0 for a value a float holds, as an integer y)
            sb.append("  double ").append(d).append(" = ").append(doubleExpr).append(";\n");
            sb.append("  float ").append(n).append(" = (float) ").append(d).append(";\n");
            sb.append("  float ").append(en).append(" = rerr(").append(d).append(", ").append(n).append(");\n");
            return new V(n, en);
        }

        private V sharedCall(Node n, StringBuilder sb) {
            Integer id = memo.get(n);
            String name = "sc" + mode.name().charAt(0) + "_" + (id == null ? memo.size() : id);
            if (id == null) {
                id = memo.size();
                memo.put(n, id);
                cmemoCounts.merge(mode, 1, Integer::sum);
                chelperProtos.append("float ").append(name).append("(Ctx* c, float* e);\n");
                StringBuilder b = new StringBuilder();
                int saved = tmp;
                V v = compute(n, b);
                tmp = saved;
                String slot = "MF_" + mode.name() + " + " + id;
                chelpers.append("NI float ").append(name).append("(Ctx* c, float* e) {\n  if (c->fk[").append(slot).append("]) { *e = c->fe[").append(slot)
                    .append("]; return c->fv[").append(slot).append("]; }\n");
                chelpers.append(b);
                chelpers.append("  c->fv[").append(slot).append("] = ").append(v.v()).append("; c->fe[").append(slot).append("] = ").append(v.e()).append("; c->fk[")
                    .append(slot).append("] = 1; *e = ").append(v.e()).append("; return ").append(v.v()).append(";\n}\n");
            }
            String r = "t" + tmp;
            String er = "e" + tmp;
            tmp++;
            sb.append("  float ").append(er).append(";\n  float ").append(r).append(" = ").append(name).append("(c, &").append(er).append(");\n");
            return new V(r, er);
        }

        private V compute(Node n, StringBuilder sb) {
            return switch (n) {
                case Node.Const c -> constant(c.value());
                case Node.Ap2 a -> ap2(a, sb);
                case Node.MulOrAdd m -> mulOrAdd(ev(m.input(), sb), m.mul(), m.argument(), sb);
                case Node.Mapped m -> mapped(m, sb);
                case Node.Clamp c -> {
                    V a = ev(c.input(), sb);
                    float lo = (float) c.lo(), hi = (float) c.hi();
                    String eb = up(Math.max(Math.abs(c.lo() - lo), Math.abs(c.hi() - hi)));
                    // an input clearly outside the range gives the bound itself
                    yield tmp(sb, "(" + a.v() + " < " + flit(lo) + " ? " + flit(lo) + " : jminf(" + a.v() + ", " + flit(hi) + "))", "((" + a.v() + " - " + flit(hi) + " > SAFE("
                        + a.e() + ") || " + flit(lo) + " - " + a.v() + " > SAFE(" + a.e() + ")) ? " + eb + " : fmax(" + a.e() + ", " + eb + "))");
                }
                case Node.RangeChoice r -> rangeChoice(r, sb);
                case Node.YGradient y -> readRounded(sb, "jclampedMap((double) c->by, " + dlit(y.fromY()) + ", " + dlit(y.toY()) + ", " + dlit(y.fromValue()) + ", "
                    + dlit(y.toValue()) + ")");
                case Node.Noise no -> noise32(no.spec(), ffCoord("c->bx", no.xzScale()), ffCoord("c->by", no.yScale()), ffCoord("c->bz", no.xzScale()), "0.0f", "0.0f",
                    "0.0f", sb);
                case Node.ShiftedNoise s -> {
                    V sx = ev(s.shiftX(), sb);
                    V sy = ev(s.shiftY(), sb);
                    V sz = ev(s.shiftZ(), sb);
                    // the coordinate is vanilla's double sum. The shift's float error moves it by at most that error. The sums' roundings
                    // are accounted for by the lattice (see lat).
                    String x = "ff_add(" + ffCoord("c->bx", s.xzScale()) + ", (ff) (" + sx.v() + ", 0.0f))";
                    String y = "ff_add(" + ffCoord("c->by", s.yScale()) + ", (ff) (" + sy.v() + ", 0.0f))";
                    String z = "ff_add(" + ffCoord("c->bz", s.xzScale()) + ", (ff) (" + sz.v() + ", 0.0f))";
                    yield noise32(s.spec(), x, y, z, sx.e(), sy.e(), sz.e(), sb);
                }
                case Node.ShiftA s -> scale4(noise32(s.spec(), ffCoord("c->bx", 0.25), FF_ZERO, ffCoord("c->bz", 0.25), "0.0f", "0.0f", "0.0f", sb), sb);
                case Node.ShiftB s -> scale4(noise32(s.spec(), ffCoord("c->bz", 0.25), ffCoord("c->bx", 0.25), FF_ZERO, "0.0f", "0.0f", "0.0f", sb), sb);
                case Node.Shift s -> scale4(noise32(s.spec(), ffCoord("c->bx", 0.25), ffCoord("c->by", 0.25), ffCoord("c->bz", 0.25), "0.0f", "0.0f", "0.0f", sb), sb);
                case Node.Weird w -> weird(w, sb);
                case Node.Spline s -> spline32(s, sb);
                case Node.Blended b -> {
                    usedBlended.set(b.spec());
                    String n0 = "t" + tmp;
                    String e0 = "e" + tmp;
                    tmp++;
                    sb.append("  float ").append(e0).append(";\n  float ").append(n0).append(" = bn32(c, ").append(b.spec()).append(", c->bx, c->by, c->bz, &").append(e0)
                        .append(");\n");
                    yield new V(n0, e0);
                }
                case Node.BlendDensity b -> ev(b.input(), sb);
                case Node.BlendAlpha a -> new V("1.0f", "0.0f");
                case Node.BlendOffset o -> new V("0.0f", "0.0f");
                case Node.Beardifier b -> {
                    if (mode != CMode.BLOCK && !(mode == CMode.CORNER && perChunkCorners)) {
                        throw new Unsupported("beardifier outside the block tree");
                    }
                    yield readRounded(sb, "(double) beard(c)");
                }
                case Node.Marker m -> marker(m, sb);
                case Node.StaticCall c -> staticCall32(c, sb);
                case Node.Select s -> {
                    String cond = cond32(s.cond(), sb);
                    V out = decl(sb);
                    sb.append("  if (").append(cond).append(") {\n");
                    V a = ev(s.whenJump(), sb);
                    sb.append("  ").append(out.v()).append(" = ").append(a.v()).append("; ").append(out.e()).append(" = ").append(a.e()).append(";\n  } else {\n");
                    V b = ev(s.whenFall(), sb);
                    sb.append("  ").append(out.v()).append(" = ").append(b.v()).append("; ").append(out.e()).append(" = ").append(b.e()).append(";\n  }\n");
                    yield out;
                }
                case Node.IntToDouble i -> {
                    // an exact int. Its float conversion rounds only beyond 2^24.
                    String iv = evInt32(i.value(), sb);
                    String n0 = "t" + tmp;
                    V v = tmp(sb, "(float) (" + iv + ")", "0.0f");
                    sb.append("  if (fabs(").append(n0).append(") >= 0x1p24f) ").append(v.e()).append(" = RND(").append(n0).append(");\n");
                    yield v;
                }
                case Node.RoundFloat r -> {
                    // (float) of the double value. Exact when the float value is exact (no error). Otherwise, the error plus one rounding.
                    V a = ev(r.value(), sb);
                    yield tmp(sb, a.v(), "(" + a.e() + " > 0.0f ? " + a.e() + " + RND(" + a.v() + ") : 0.0f)");
                }
                case Node.NoiseAt na -> {
                    String[] x = ffCoordinate(na.x(), sb), y = ffCoordinate(na.y(), sb), z = ffCoordinate(na.z(), sb);
                    yield noise32(na.spec(), x[0], y[0], z[0], x[1], y[1], z[1], sb);
                }
                default -> throw new Unsupported("certified fp32 does not cover " + n.getClass().getSimpleName());
            };
        }

        /** Lifted code's static calls: double arithmetic and the {@code Math}/{@code Mth} functions GA2's emitter knows to be exact. */
        private V staticCall32(Node.StaticCall c, StringBuilder sb) {
            String extern = extern(c);
            if (extern != null) {
                // a coordinate made of block coordinates and constants is computed in double as Java computes it (exact). Any other
                // coordinate is the certified value. It is exact when its error is 0. Otherwise the cell is rechecked in fp64.
                String[] xyz = new String[3];
                StringBuilder doubt = new StringBuilder();
                for (int a = 0; a < 3; a++) {
                    Node arg = (Node) c.args().get(a + 1);
                    String d = exactDouble(arg);
                    if (d == null) {
                        V v = ev(arg, sb);
                        d = "(double) " + v.v();
                        doubt.append(doubt.length() == 0 ? "" : " || ").append(v.e()).append(" != 0.0f");
                    }
                    xyz[a] = d;
                }
                if (doubt.length() > 0) {
                    sb.append("  if (").append(doubt).append(") c->unc |= 256;\n");
                }
                return tmp(sb, extern + "(" + externTables(c) + ", " + xyz[0] + ", " + xyz[1] + ", " + xyz[2] + ")", "0.0f");
            }
            if (!c.owner().equals("$op") && staticKey(c.owner(), c.name(), c.desc()).equals("net/minecraft/util/Mth.clampedMap(DDDDD)D")
                && c.args().get(0) instanceof Node.IntToDouble yi && c.args().subList(1, 5).stream().allMatch(o -> o instanceof Node.Const)) {
                // a y_clamped_gradient at another position: of an exact int, computed in double as Java computes it, then rounded once
                StringBuilder lits = new StringBuilder();
                for (Object o : c.args().subList(1, 5)) {
                    lits.append(", ").append(dlit(((Node.Const) o).value()));
                }
                return readRounded(sb, "jclampedMap((double) (" + evInt32(yi.value(), sb) + ")" + lits + ")");
            }
            List<V> a = new ArrayList<>();
            for (Object o : c.args()) {
                if (!(o instanceof Node n)) {
                    throw new Unsupported("certified fp32: static call " + c.name() + " with an int argument");
                }
                a.add(ev(n, sb));
            }
            String key = c.owner().equals("$op") ? "$op." + c.name() : staticKey(c.owner(), c.name(), c.desc());
            String n0 = "t" + tmp;
            switch (key) {
                case "$op.dadd", "$op.dsub" -> {
                    V v = tmp(sb, a.get(0).v() + (key.equals("$op.dadd") ? " + " : " - ") + a.get(1).v(), "0.0f");
                    sb.append("  ").append(v.e()).append(" = ").append(a.get(0).e()).append(" + ").append(a.get(1).e()).append(" + RND(").append(n0).append(");\n");
                    return v;
                }
                case "$op.dmul" -> {
                    V x = a.get(0), y = a.get(1);
                    V v = tmp(sb, x.v() + " * " + y.v(), "0.0f");
                    sb.append("  ").append(v.e()).append(" = fabs(").append(x.v()).append(") * ").append(y.e()).append(" + fabs(").append(y.v()).append(") * ").append(x.e())
                        .append(" + ").append(x.e()).append(" * ").append(y.e()).append(" + RND(").append(n0).append(");\n");
                    return v;
                }
                case "$op.ddiv" -> {
                    // a quotient's error over a denominator bounded away from 0 (else the cell is rechecked)
                    V x = a.get(0), y = a.get(1);
                    sb.append("  if (!(fabs(").append(y.v()).append(") > 2.0f * ").append(y.e()).append(")) c->unc |= 256;\n");
                    V v = tmp(sb, "fdivx(" + x.v() + ", " + y.v() + ")", "0.0f");
                    sb.append("  ").append(v.e()).append(" = (").append(x.e()).append(" + fabs(").append(n0).append(") * ").append(y.e()).append(") / fmax(fabs(").append(y.v())
                        .append(") - ").append(y.e()).append(", 0x1p-126f) + RND(").append(n0).append(");\n");
                    return v;
                }
                case "java/lang/Math.abs(D)D" -> {
                    return tmp(sb, "fabs(" + a.get(0).v() + ")", a.get(0).e());
                }
                case "java/lang/Math.min(DD)D", "java/lang/Math.max(DD)D" -> {
                    String args = a.get(0).v() + ", " + a.get(1).v();
                    return tmp(sb, (key.contains("min") ? "jminf(" : "jmaxf(") + args + ")", (key.contains("min") ? "MINE(" : "MAXE(") + args + ", " + a.get(0).e() + ", "
                        + a.get(1).e() + ")");
                }
                case "java/lang/Math.floor(D)D", "java/lang/Math.ceil(D)D" -> {
                    // a step at every integer. Exact unless the error reaches across one.
                    // The interval's ends are computed in double, so their own rounding cannot hide a step.
                    String f = key.contains("floor") ? "floor" : "ceil";
                    V x = a.get(0);
                    sb.append("  if (").append(f).append("((double) ").append(x.v()).append(" - (double) ").append(x.e()).append(") != ").append(f).append("((double) ")
                        .append(x.v()).append(" + (double) ").append(x.e()).append(")) c->unc |= 256;\n");
                    return tmp(sb, f + "(" + x.v() + ")", "0.0f");
                }
                case "java/lang/Math.sqrt(D)D" -> {
                    // |sqrt x - sqrt y| = |x - y| / (sqrt x + sqrt y). Near 0 (or below), the exact input may be negative.
                    V x = a.get(0);
                    sb.append("  if (!(").append(x.v()).append(" > ").append(x.e()).append(")) c->unc |= 256;\n");
                    V v = tmp(sb, "sqrt(" + x.v() + ")", "0.0f");
                    sb.append("  ").append(v.e()).append(" = ").append(x.e()).append(" / fmax(").append(n0).append(" + sqrt(fmax(").append(x.v()).append(" - ").append(x.e())
                        .append(", 0.0f)), 0x1p-126f) + RND(").append(n0).append(");\n");
                    return v;
                }
                case "net/minecraft/util/Mth.clamp(DDD)D" -> {
                    // 1-Lipschitz in each argument while lo <= hi. With lo > hi, it steps from lo to hi at x = lo.
                    V x = a.get(0), lo = a.get(1), hi = a.get(2);
                    sb.append("  if (").append(lo.v()).append(" + ").append(lo.e()).append(" > ").append(hi.v()).append(" - ").append(hi.e()).append(" && fabs(").append(x.v())
                        .append(" - ").append(lo.v()).append(") <= SAFE(").append(x.e()).append(" + ").append(lo.e()).append(")) c->unc |= 256;\n");
                    return tmp(sb, "(" + x.v() + " < " + lo.v() + " ? " + lo.v() + " : jminf(" + x.v() + ", " + hi.v() + "))",
                        "fmax(" + x.e() + ", fmax(" + lo.e() + ", " + hi.e() + "))");
                }
                default -> throw new Unsupported("certified fp32: static call " + key);
            }
        }

        /** A comparison as lifted code makes it. A comparison that the operands' errors could flip marks the cell. */
        private String cond32(Node.Cond c, StringBuilder sb) {
            String cmp;
            if (c.kind() == Node.CmpKind.ICMP) {
                String a = evInt32(c.intA(), sb);
                String b = evInt32(c.intB(), sb);
                cmp = "((" + a + ") < (" + b + ") ? -1 : ((" + a + ") == (" + b + ") ? 0 : 1))";
            } else if (c.a() == c.b()) {
                // a value against itself (a NaN test): equal unless NaN, whatever its error. A NaN float, or an unbounded error, is
                // rechecked, since the float and the exact value could then disagree about NaN
                V a = ev(c.a(), sb);
                sb.append("  if (isnan(").append(a.v()).append(") || !isfinite(").append(a.e()).append(")) c->unc |= 256;\n");
                cmp = "(isnan(" + a.v() + ") ? " + (c.kind() == Node.CmpKind.DCMPL ? "-1" : "1") + " : 0)";
            } else {
                V a = ev(c.a(), sb);
                V b = ev(c.b(), sb);
                sb.append("  if (fabs((double) ").append(a.v()).append(" - (double) ").append(b.v()).append(") <= (double) SAFE(").append(a.e()).append(" + ")
                    .append(b.e()).append(") && (").append(a.e()).append(" > 0.0f || ").append(b.e()).append(" > 0.0f)) c->unc |= 256;\n");
                String nan = c.kind() == Node.CmpKind.DCMPL ? "-1" : "1";
                cmp = "((isnan(" + a.v() + ") || isnan(" + b.v() + ")) ? " + nan + " : ((" + a.v() + ") < (" + b.v() + ") ? -1 : ((" + a.v() + ") == (" + b.v() + ") ? 0 : 1)))";
            }
            String r = "q" + tmp++;
            sb.append("  int ").append(r).append(" = ").append(cmp).append(";\n");
            return r + switch (c.op()) {
                case EQ -> " == 0";
                case NE -> " != 0";
                case LT -> " < 0";
                case GE -> " >= 0";
                case GT -> " > 0";
                case LE -> " <= 0";
            };
        }

        /** Int expressions are exact. A cast or floor of a float value marks the cell when the value's error reaches across a step. */
        private String evInt32(INode n, StringBuilder sb) {
            return switch (n) {
                case INode.IntConst c -> Integer.toString(c.value());
                case INode.IntCoord c -> c.axis() == 0 ? "c->bx" : c.axis() == 1 ? "c->by" : "c->bz";
                case INode.IntBin b -> {
                    String x = evInt32(b.a(), sb);
                    String y = evInt32(b.b(), sb);
                    yield switch (b.op()) {
                        case ADD -> "(" + x + " + " + y + ")";
                        case SUB -> "(" + x + " - " + y + ")";
                        case MUL -> "(" + x + " * " + y + ")";
                        case DIV -> "(" + x + " / " + y + ")";
                        case REM -> "(" + x + " % " + y + ")";
                        case SHL -> "(" + x + " << (" + y + " & 31))";
                        case SHR -> "(" + x + " >> (" + y + " & 31))";
                        case USHR -> "(int) ((uint) " + x + " >> (" + y + " & 31))";
                        case AND -> "(" + x + " & " + y + ")";
                        case OR -> "(" + x + " | " + y + ")";
                        case XOR -> "(" + x + " ^ " + y + ")";
                    };
                }
                case INode.IntNeg g -> "(-" + evInt32(g.a(), sb) + ")";
                case INode.DoubleToInt d when d.value() instanceof Node.StaticCall s && s.owner().equals("$op") && s.name().equals("dadd")
                    && s.args().get(0) instanceof Node.IntToDouble i && s.args().get(1) instanceof Node shift -> {
                    // (int) ((double) coordinate + shift), a shifted position: the int part stays exact in double. Only the shift's error
                    // can move the result across an integer
                    String base = "(double) (" + evInt32(i.value(), sb) + ")";
                    V v = ev(shift, sb);
                    String r = "q" + tmp++;
                    sb.append("  int ").append(r).append(" = convert_int_sat_rtz(").append(base).append(" + (double) ").append(v.v()).append(");\n");
                    sb.append("  if (convert_int_sat_rtz(").append(base).append(" + ((double) ").append(v.v()).append(" - (double) ").append(v.e())
                        .append(")) != convert_int_sat_rtz(").append(base).append(" + ((double) ").append(v.v()).append(" + (double) ").append(v.e())
                        .append("))) c->unc |= 256;\n");
                    yield r;
                }
                case INode.DoubleToInt d -> {
                    V v = ev(d.value(), sb);
                    String r = "q" + tmp++;
                    sb.append("  int ").append(r).append(" = convert_int_sat_rtz((double) ").append(v.v()).append(");\n");
                    sb.append("  if (convert_int_sat_rtz((double) ").append(v.v()).append(" - (double) ").append(v.e()).append(") != convert_int_sat_rtz((double) ").append(v.v())
                        .append(" + (double) ").append(v.e()).append(")) c->unc |= 256;\n");
                    yield r;
                }
                case INode.IntStaticCall c -> {
                    String key = staticKey(c.owner(), c.name(), c.desc());
                    if (!key.equals("net/minecraft/util/Mth.floor(D)I")) {
                        throw new Unsupported("certified fp32: int static call " + key);
                    }
                    V v = ev((Node) c.args().get(0), sb);
                    String r = "q" + tmp++;
                    sb.append("  int ").append(r).append(" = jfloor((double) ").append(v.v()).append(");\n");
                    sb.append("  if (jfloor((double) ").append(v.v()).append(" - (double) ").append(v.e()).append(") != jfloor((double) ").append(v.v()).append(" + (double) ")
                        .append(v.e()).append(")) c->unc |= 256;\n");
                    yield r;
                }
                case INode.SelectInt s -> {
                    String cond = cond32(s.cond(), sb);
                    String a = evInt32(s.whenJump(), sb);
                    String b = evInt32(s.whenFall(), sb);
                    yield "((" + cond + ") ? (" + a + ") : (" + b + "))";
                }
            };
        }

        /**
         * A noise coordinate as a double-float and its error. An int coordinate, alone or times a constant, is the same exact
         * double-float a plain noise reads ({@link #ffCoord}). Plus another value, it carries that value's error. That is the shape
         * of a function evaluated at another position ({@code dfc.ir.CoordSubstitution}). Anything else is its certified float.
         */
        private String[] ffCoordinate(Node n, StringBuilder sb) {
            String exact = ffExact(n, sb);
            if (exact != null) {
                return new String[] { exact, "0.0f" };
            }
            if (n instanceof Node.StaticCall s && s.owner().equals("$op") && s.name().equals("dadd") && s.args().get(0) instanceof Node a
                && s.args().get(1) instanceof Node b) {
                String fa = ffExact(a, sb);
                if (fa != null) {
                    V v = ev(b, sb);
                    return new String[] { "ff_add(" + fa + ", (ff) (" + v.v() + ", 0.0f))", v.e() };
                }
            }
            V v = ev(n, sb);
            return new String[] { "((ff) (" + v.v() + ", 0.0f))", v.e() };
        }

        /** {@code n} as an exact double-float when it is an int, or an int times a constant (see {@link #ffCoordinate}), else null. */
        private String ffExact(Node n, StringBuilder sb) {
            if (n instanceof Node.Const k && k.value() == 0.0 && Double.doubleToRawLongBits(k.value()) == 0L) {
                return FF_ZERO;
            }
            if (n instanceof Node.IntToDouble i) {
                return ffCoord("(" + evInt32(i.value(), sb) + ")", 1.0);
            }
            if (n instanceof Node.StaticCall s && s.owner().equals("$op") && s.name().equals("dmul") && s.args().get(0) instanceof Node.IntToDouble i
                && s.args().get(1) instanceof Node.Const k) {
                return ffCoord("(" + evInt32(i.value(), sb) + ")", k.value());
            }
            return null;
        }

        /** A block coordinate times a double scale as float-float. Exact for the usual scales, otherwise within the lattice's bound. */
        private static String ffCoord(String axis, double scale) {
            if (scale == 0.0) {
                return FF_ZERO;
            }
            if (scale == 1.0) {
                return "ff_int(" + axis + ")";
            }
            float hi = (float) scale;
            if ((double) hi == scale) {
                return "ff_mulf(ff_int(" + axis + "), " + flit(hi) + ")";
            }
            return "ff_mulc(ff_int(" + axis + "), " + flit(hi) + ", " + flit((float) (scale - (double) hi)) + ")";
        }

        private V scale4(V n, StringBuilder sb) {
            return tmp(sb, n.v() + " * 4.0f", n.e() + " * 4.0f");
        }

        private V mulOrAdd(V a, boolean mul, double arg, StringBuilder sb) {
            V k = constant(arg);
            String n0 = "t" + tmp;
            if (mul) {
                V v = tmp(sb, a.v() + " * " + k.v(), "0.0f");
                sb.append("  ").append(v.e()).append(" = ").append(a.e()).append(" * fabs(").append(k.v()).append(") + fabs(").append(a.v()).append(") * ").append(k.e())
                    .append(" + ").append(a.e()).append(" * ").append(k.e()).append(" + RND(").append(n0).append(");\n");
                return v;
            }
            V v = tmp(sb, a.v() + " + " + k.v(), "0.0f");
            sb.append("  ").append(v.e()).append(" = ").append(a.e()).append(" + ").append(k.e()).append(" + RND(").append(n0).append(");\n");
            return v;
        }

        private V ap2(Node.Ap2 a, StringBuilder sb) {
            if (a.type() == Node.Ap2Type.ADD || a.type() == Node.Ap2Type.MUL) {
                // inside a NoiseChunk vanilla evaluates the wrapped tree, where a constant operand is folded into MulOrAdd
                Node.Const k = a.a() instanceof Node.Const c1 ? c1 : (a.b() instanceof Node.Const c2 ? c2 : null);
                if (k != null) {
                    Node other = a.a() == k ? a.b() : a.a();
                    return mulOrAdd(ev(other, sb), a.type() == Node.Ap2Type.MUL, k.value(), sb);
                }
            }
            V x = ev(a.a(), sb);
            if (a.type() == Node.Ap2Type.ADD) {
                V y = ev(a.b(), sb);
                String n0 = "t" + tmp;
                V v = tmp(sb, x.v() + " + " + y.v(), "0.0f");
                sb.append("  ").append(v.e()).append(" = ").append(x.e()).append(" + ").append(y.e()).append(" + RND(").append(n0).append(");\n");
                return v;
            }
            V out = decl(sb);
            switch (a.type()) {
                case MUL -> {
                    // vanilla skips b when a is 0. Then the exact a is within its error of 0, and the exact product is within error * |b|.
                    // When b has no known range (as in lifted code), an inexact 0 marks the cell.
                    double bmax = Math.max(Math.abs(a.b().min()), Math.abs(a.b().max()));
                    sb.append("  if (").append(x.v()).append(" == 0.0f) { ").append(out.v()).append(" = 0.0f; ");
                    if (bmax < 0x1p100) {
                        sb.append(out.e()).append(" = ").append(x.e()).append(" * ").append(up(bmax)).append("; } else {\n");
                    } else {
                        sb.append(out.e()).append(" = 0.0f; if (").append(x.e()).append(" > 0.0f) c->unc |= 256; } else {\n");
                    }
                    V y = ev(a.b(), sb);
                    sb.append("  ").append(out.v()).append(" = ").append(x.v()).append(" * ").append(y.v()).append(";\n");
                    sb.append("  ").append(out.e()).append(" = fabs(").append(x.v()).append(") * ").append(y.e()).append(" + fabs(").append(y.v()).append(") * ").append(x.e())
                        .append(" + ").append(x.e()).append(" * ").append(y.e()).append(" + RND(").append(out.v()).append(");\n  }\n");
                }
                case MIN -> {
                    // the short cut gives a either way (b >= its minimum > a). Otherwise min is 1-Lipschitz.
                    sb.append("  if (").append(x.v()).append(" < ").append(flit(ceilF(a.b().min()))).append(") { ").append(out.v()).append(" = ").append(x.v()).append("; ")
                        .append(out.e()).append(" = ").append(x.e()).append("; } else {\n");
                    V y = ev(a.b(), sb);
                    sb.append("  ").append(out.v()).append(" = jminf(").append(x.v()).append(", ").append(y.v()).append("); ").append(out.e()).append(" = MINE(").append(x.v())
                        .append(", ").append(y.v()).append(", ").append(x.e()).append(", ").append(y.e()).append(");\n  }\n");
                }
                case MAX -> {
                    sb.append("  if (").append(x.v()).append(" > ").append(flit(floorF(a.b().max()))).append(") { ").append(out.v()).append(" = ").append(x.v()).append("; ")
                        .append(out.e()).append(" = ").append(x.e()).append("; } else {\n");
                    V y = ev(a.b(), sb);
                    sb.append("  ").append(out.v()).append(" = jmaxf(").append(x.v()).append(", ").append(y.v()).append("); ").append(out.e()).append(" = MAXE(").append(x.v())
                        .append(", ").append(y.v()).append(", ").append(x.e()).append(", ").append(y.e()).append(");\n  }\n");
                }
                default -> throw new IllegalStateException();
            }
            return out;
        }

        private V mapped(Node.Mapped m, StringBuilder sb) {
            V a = ev(m.input(), sb);
            String x = a.v(), ex = a.e();
            return switch (m.type()) {
                case ABS -> tmp(sb, "fabs(" + x + ")", ex);
                case SQUARE -> {
                    String n0 = "t" + tmp;
                    V v = tmp(sb, x + " * " + x, "0.0f");
                    sb.append("  ").append(v.e()).append(" = 2.0f * fabs(").append(x).append(") * ").append(ex).append(" + ").append(ex).append(" * ").append(ex)
                        .append(" + RND(").append(n0).append(");\n");
                    yield v;
                }
                case CUBE -> {
                    // x * x * x: two roundings
                    String sq = "q" + tmp;
                    sb.append("  float ").append(sq).append(" = ").append(x).append(" * ").append(x).append(";\n");
                    String n0 = "t" + tmp;
                    V v = tmp(sb, sq + " * " + x, "0.0f");
                    sb.append("  ").append(v.e()).append(" = 3.0f * ").append(sq).append(" * ").append(ex).append(" + 3.0f * fabs(").append(x).append(") * ").append(ex)
                        .append(" * ").append(ex).append(" + ").append(ex).append(" * ").append(ex).append(" * ").append(ex).append(" + RND(").append(sq).append(") * fabs(")
                        .append(x).append(") + RND(").append(n0).append(");\n");
                    yield v;
                }
                // continuous at 0 and 1-Lipschitz. The halving or quartering is exact.
                case HALF_NEGATIVE -> tmp(sb, x + " > 0 ? " + x + " : " + x + " * 0.5f", ex);
                case QUARTER_NEGATIVE -> tmp(sb, x + " > 0 ? " + x + " : " + x + " * 0.25f", ex);
                case SQUEEZE -> {
                    // d0 / 2 - d0^3 / 24 over the clamped input, in double and rounded once: slope at most 1/2
                    String n0 = "t" + tmp;
                    V v = tmp(sb, "(float) jsqueeze((double) " + x + ")", "0.0f");
                    sb.append("  ").append(v.e()).append(" = 0.5f * ").append(ex).append(" + RND(").append(n0).append(");\n");
                    yield v;
                }
            };
        }

        private V rangeChoice(Node.RangeChoice r, StringBuilder sb) {
            V in = ev(r.input(), sb);
            float lo = ceilF(r.minInclusive());
            float hi = ceilF(r.maxExclusive());
            // for a float in: in >= min <=> in >= ceilF(min), and in < max <=> in < ceilF(max). The choice may differ from the exact
            // input's choice when a bound lies within the input's error.
            String elo = up(Math.abs(r.minInclusive() - lo));
            String elh = up(Math.abs(r.maxExclusive() - hi));
            // then both branches are computed. The exact choice gives one of them, so their midpoint is within half their distance plus
            // the larger error of either. A downstream decision that tolerates that bound needs no recheck.
            String u = "q" + tmp, pick = "q" + (tmp + 1);
            tmp += 2;
            // (an exact input decides exactly: a float in >= ceilF(min) iff in >= min, in < ceilF(max) iff in < max)
            sb.append("  int ").append(u).append(" = ").append(in.e()).append(" > 0.0f && (fabs(").append(in.v()).append(" - ").append(flit(lo)).append(") <= SAFE(")
                .append(in.e()).append(" + ").append(elo).append(") || fabs(").append(in.v()).append(" - ").append(flit(hi)).append(") <= SAFE(").append(in.e()).append(" + ")
                .append(elh).append("));\n");
            sb.append("  int ").append(pick).append(" = ").append(in.v()).append(" >= ").append(flit(lo)).append(" && ").append(in.v()).append(" < ").append(flit(hi)).append(";\n");
            V va = decl(sb), vb = decl(sb), out = decl(sb);
            sb.append("  if (").append(pick).append(" || ").append(u).append(") {\n");
            V a = ev(r.inRange(), sb);
            sb.append("  ").append(va.v()).append(" = ").append(a.v()).append("; ").append(va.e()).append(" = ").append(a.e()).append(";\n  }\n");
            sb.append("  if (!").append(pick).append(" || ").append(u).append(") {\n");
            V b = ev(r.outRange(), sb);
            sb.append("  ").append(vb.v()).append(" = ").append(b.v()).append("; ").append(vb.e()).append(" = ").append(b.e()).append(";\n  }\n");
            sb.append("  if (").append(u).append(") { HULL(").append(va.v()).append(", ").append(va.e()).append(", ").append(vb.v()).append(", ").append(vb.e()).append(", ")
                .append(out.v()).append(", ").append(out.e()).append("); } else if (").append(pick).append(") { ").append(out.v()).append(" = ").append(va.v()).append("; ")
                .append(out.e()).append(" = ").append(va.e()).append("; } else { ").append(out.v()).append(" = ").append(vb.v()).append("; ").append(out.e()).append(" = ")
                .append(vb.e()).append("; }\n");
            return out;
        }

        private V weird(Node.Weird w, StringBuilder sb) {
            V in = ev(w.input(), sb);
            if (w.spec() < 0) {
                return new V("0.0f", "0.0f");
            }
            // the rarity is a monotone step function of the input. The exact input's rarity is the rarity at one end of the input's error
            // interval. Where the two ends differ, both values are computed. Two steps within the error mark the cell.
            String fn = w.type1() ? "rarity3D" : "rarity2D";
            String r = "q" + tmp, rl = "q" + (tmp + 1), rh = "q" + (tmp + 2), r2 = "q" + (tmp + 3);
            tmp += 4;
            sb.append("  double ").append(r).append(" = ").append(fn).append("((double) ").append(in.v()).append(");\n");
            sb.append("  double ").append(rl).append(" = ").append(fn).append("((double) ").append(in.v()).append(" - (double) SAFE(").append(in.e()).append("));\n");
            sb.append("  double ").append(rh).append(" = ").append(fn).append("((double) ").append(in.v()).append(" + (double) SAFE(").append(in.e()).append("));\n");
            sb.append("  if (").append(rl).append(" != ").append(r).append(" && ").append(rh).append(" != ").append(r).append(") c->unc |= 1;\n");
            sb.append("  double ").append(r2).append(" = ").append(rl).append(" != ").append(r).append(" ? ").append(rl).append(" : ").append(rh).append(";\n");
            V n = noise32(w.spec(), "ff_divf(ff_int(c->bx), (float) " + r + ")", "ff_divf(ff_int(c->by), (float) " + r + ")", "ff_divf(ff_int(c->bz), (float) " + r + ")", "0.0f",
                "0.0f", "0.0f", sb);
            String n0 = "t" + tmp;
            V v = tmp(sb, "(float) " + r + " * fabs(" + n.v() + ")", "0.0f");
            sb.append("  ").append(v.e()).append(" = (float) ").append(r).append(" * ").append(n.e()).append(" + RND(").append(n0).append(");\n");
            V out = decl(sb);
            sb.append("  if (").append(r2).append(" != ").append(r).append(") {\n");
            V m = noise32(w.spec(), "ff_divf(ff_int(c->bx), (float) " + r2 + ")", "ff_divf(ff_int(c->by), (float) " + r2 + ")", "ff_divf(ff_int(c->bz), (float) " + r2 + ")",
                "0.0f", "0.0f", "0.0f", sb);
            String m0 = "t" + tmp;
            V v2 = tmp(sb, "(float) " + r2 + " * fabs(" + m.v() + ")", "0.0f");
            sb.append("  ").append(v2.e()).append(" = (float) ").append(r2).append(" * ").append(m.e()).append(" + RND(").append(m0).append(");\n");
            sb.append("  HULL(").append(v.v()).append(", ").append(v.e()).append(", ").append(v2.v()).append(", ").append(v2.e()).append(", ").append(out.v()).append(", ")
                .append(out.e()).append(");\n  } else { ").append(out.v()).append(" = ").append(v.v()).append("; ").append(out.e()).append(" = ").append(v.e()).append("; }\n");
            return out;
        }

        private V marker(Node.Marker m, StringBuilder sb) {
            switch (m.type()) {
                case CACHE_ONCE:
                    return ev(m.child(), sb);
                case CACHE_2D:
                    if (!deps.of(m.child()).isXzOrConst()) {
                        throw new Unsupported("cache_2d over a y-dependent function (order-sensitive): " + deps.firstReadingY(m.child()));
                    }
                    return ev(m.child(), sb);
                case FLAT_CACHE: {
                    Integer f = flatIndex.get(m);
                    if (f == null) {
                        throw new Unsupported("flat cache not collected");
                    }
                    return readRounded(sb, "c->flat[" + f + " * c->fstride + ((c->bx >> 2) - c->qx0) * c->nqz + ((c->bz >> 2) - c->qz0)]");
                }
                case INTERPOLATED: {
                    if (mode == CMode.CORNER) {
                        throw new Unsupported("interpolated inside an interpolated root");
                    }
                    Integer r = lerpIndex.get(m);
                    if (r == null) {
                        throw new Unsupported("interpolated marker without a column root");
                    }
                    String n0 = "t" + tmp;
                    String e0 = "e" + tmp;
                    tmp++;
                    sb.append("  float ").append(e0).append(";\n  float ").append(n0).append(" = ").append(mode == CMode.VEIN ? "lerpvc32" : "lerp3c32").append("(c, ")
                        .append(r).append(", &").append(e0).append(");\n");
                    return new V(n0, e0);
                }
                default:
                    throw new Unsupported("cache_all_in_cell");
            }
        }

        private V spline32(Node.Spline s, StringBuilder sb) {
            if (s.spline() instanceof Node.SplineConst k) {
                return new V(flit(k.value()), "0.0f");
            }
            Map<Node, Boolean> coords = new LinkedHashMap<>();
            splineCoords(s.spline(), coords);
            for (Node c : coords.keySet()) {
                Integer idx = coordIndex.computeIfAbsent(c, k -> coordIndex.size());
                V v = ev(c, sb);
                sb.append("  c->cv[").append(idx).append("] = ").append(v.v()).append("; c->ce[").append(idx).append("] = ").append(v.e()).append(";\n");
            }
            int id = registerSpline((Node.SplineMulti) s.spline());
            certifiedSplines = true;
            String n0 = "t" + tmp;
            String e0 = "e" + tmp;
            tmp++;
            sb.append("  float ").append(e0).append(";\n  float ").append(n0).append(" = ").append(splineFn("sp32_", id)).append("(c, ").append(id).append(", &").append(e0).append(");\n");
            return new V(n0, e0);
        }

        private V noise32(int spec, String x, String y, String z, String ex, String ey, String ez, StringBuilder sb) {
            usedSpecs.set(spec);
            String n0 = "t" + tmp;
            String e0 = "e" + tmp;
            tmp++;
            sb.append("  float ").append(e0).append(";\n  float ").append(n0).append(" = nn32(c, ").append(spec).append(", ").append(x).append(", ").append(y).append(", ")
                .append(z).append(", ").append(ex).append(", ").append(ey).append(", ").append(ez).append(", &").append(e0).append(");\n");
            return new V(n0, e0);
        }
    }

    /**
     * How the dimension's cache_2d markers whose child reads y are evaluated. A NoiseChunk's wrapper returns the value computed at the
     * last (x, z) it was asked for, whatever the y. Which y that was depends on the order of the calls, so the value is exact only where
     * that order is known.
     *
     * <p>{@code WALK}: only {@code initial_density_without_jaggedness} reaches the markers. A NoiseChunk evaluates that field only in
     * its preliminary surface walks (one per column, top down). The first value at the column holds for the whole walk.
     *
     * <p>{@code FLOODED}: only {@code fluid_level_floodedness} reaches a marker. The aquifer evaluates that field once per cell, at the
     * cell's centre. Every call returns its own value when the cells of the chunk's grid at the same column agree. k_status checks
     * this. A chunk where they disagree goes to the CPU.
     *
     * <p>Null: no field reaches them, or other fields do (the dimension stays on the CPU).
     */
    public enum C2d { WALK, FLOODED }

    public C2d c2dKind;

    /**
     * Router fields that a NoiseChunk only evaluates at points inside its flat-cache range (the fill's corners and blocks). Their flat
     * caches answer from the array the cache filled at y = 0 when the NoiseChunk was made. So a cache_2d below such a flat cache is
     * only called by that fill, once per column, and the flat fill reproduces it. The aquifer's fields, the surface walk and the
     * climate are also asked elsewhere.
     */
    private static final Set<String> INSIDE_ONLY = Set.of("final_density", "vein_toggle", "vein_ridged", "vein_gap", "barrier");

    /**
     * Whether every flat cache in a router field holds a function of x and z alone. Inside its range, a NoiseChunk's flat cache
     * answers from its fill at y = 0. When this holds, the flat cache gives the field's value at the point wherever it is asked.
     */
    public boolean flatCachesXz(String field) {
        Node root = router.get(field);
        if (root == null) {
            return true;
        }
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        seen.add(root);
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (n instanceof Node.Marker m && m.type() == Node.MarkerType.FLAT_CACHE && !pointDeps.of(m.child()).isXzOrConst()) {
                return false;
            }
            for (Node c : RefCount.children(n)) {
                if (seen.add(c)) {
                    stack.push(c);
                }
            }
        }
        return true;
    }

    /** Sets {@link #c2dKind} from which router fields reach a cache_2d whose child reads y. */
    private void classifyCache2d() {
        Set<String> fields = new TreeSet<>();
        Set<Object> markers = new HashSet<>();
        StringBuilder paths = new StringBuilder();
        for (Map.Entry<String, Node> e : router.entrySet()) {
            boolean inside = INSIDE_ONLY.contains(e.getKey());
            Map<Node, Node> parent = new IdentityHashMap<>();
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.push(e.getValue());
            parent.put(e.getValue(), e.getValue());
            boolean shown = false;
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                // fields asked outside the chunk see a flat cache's child at the point's own y (point dependencies)
                if (n instanceof Node.Marker m && m.type() == Node.MarkerType.CACHE_2D && !(inside ? deps : pointDeps).of(m.child()).isXzOrConst()) {
                    fields.add(e.getKey());
                    markers.add(m.vanilla() != null ? m.vanilla() : m);
                    if (!shown) {
                        // the first path in the field down to such a cache_2d (for the reason a dimension stays on the CPU)
                        shown = true;
                        List<String> chain = new ArrayList<>();
                        for (Node p = n; p != e.getValue(); p = parent.get(p)) {
                            chain.add(0, p instanceof Node.Marker mm ? mm.type().name().toLowerCase(Locale.ROOT) : p.getClass().getSimpleName());
                        }
                        String v = String.valueOf(m.vanilla());
                        paths.append("; ").append(e.getKey()).append(": ").append(String.join(" > ", chain)).append(" (")
                            .append(v.length() > 160 ? v.substring(0, 160) + "..." : v).append(')');
                    }
                }
                if (inside && n instanceof Node.Marker m && m.type() == Node.MarkerType.FLAT_CACHE) {
                    continue; // only the flat cache's fill (y = 0) evaluates the child
                }
                for (Node c : RefCount.children(n)) {
                    if (!parent.containsKey(c)) {
                        parent.put(c, n);
                        stack.push(c);
                    }
                }
            }
        }
        c2dPaths = paths.toString();
        if (fields.equals(Set.of("initial_density_without_jaggedness"))) {
            c2dKind = C2d.WALK;
        } else if (fields.equals(Set.of("fluid_level_floodedness")) && markers.size() == 1) {
            c2dKind = C2d.FLOODED;
        }
        c2dFields = fields + (markers.size() > 1 ? " (" + markers.size() + " of them)" : "");
    }

    /** The router fields that reach a cache_2d whose child reads y, and a path in each (for the reason a dimension stays on the CPU). */
    private String c2dFields = "", c2dPaths = "";

    /** Whether certified spline functions ({@code sp32_*}) are needed. */
    private boolean certifiedSplines;

    // ---- certified fp32: OpenCL C ------------------------------------------------------------------------------------

    /** The bound of one rounding of {@code w} (twice the unit roundoff, and a floor for the subnormal range). */
    private static final String CERT_PRELUDE = """
        #define RND(w) (fabs(w) * 0x1p-23f + 0x1p-126f)
        /* safety factor applied where bounds meet decisions: the bounds' own float roundings and second-order terms */
        #define SAFE(e) ((e) * 1.0005f + 0x1p-126f)
        /* min and max carry the larger error, or only the winner's when the order is certain */
        #define MINE(x, y, ex, ey) (fabs((x) - (y)) > SAFE((ex) + (ey)) ? ((x) < (y) ? (ex) : (ey)) : fmax((ex), (ey)))
        #define MAXE(x, y, ex, ey) (fabs((x) - (y)) > SAFE((ex) + (ey)) ? ((x) > (y) ? (ex) : (ey)) : fmax((ex), (ey)))
        /* float-float (hi + lo, about 44 bits): the certified noise lattice; the products' low parts exact through fma */
        typedef float2 ff;
        ff ff_fast(float a, float b) { float s = a + b; return (ff) (s, b - (s - a)); }
        ff ff_two(float a, float b) { float s = a + b; float bb = s - a; return (ff) (s, (a - (s - bb)) + (b - bb)); }
        ff ff_add(ff a, ff b) { ff s = ff_two(a.x, b.x); return ff_fast(s.x, s.y + (a.y + b.y)); }
        ff ff_int(int i) { float h = (float) i; return (ff) (h, (float) (i - (int) h)); }
        ff ff_mulf(ff a, float b) { float p = a.x * b; return ff_fast(p, fma(a.x, b, -p) + a.y * b); }
        ff ff_mulc(ff a, float ch, float cl) { float p = a.x * ch; return ff_fast(p, fma(a.x, ch, -p) + (a.x * cl + a.y * ch)); }
        ff ff_divf(ff a, float r) { float q = a.x / r; return ff_fast(q, (fma(-q, r, a.x) + a.y) / r); }
        /* the distance of a double from its float rounding, rounded up */
        float rerr(double d, float f) { double x = fabs(d - (double) f); float y = (float) x; return (double) y < x ? nextafter(y, INFINITY) : y; }
        /* one of two values (either may be the exact one): their midpoint, within half their distance plus the larger error */
        #define HULL(a, ea, b, eb, o, eo) { float hd_ = (a) - (b); (o) = 0.5f * (a) + 0.5f * (b); (eo) = 0.5f * fabs(hd_) + fmax((ea), (eb)) + RND(hd_) + RND(o); }
        /* |d noise / d lattice coordinate| per axis: interpolated gradients (at most 1) plus smoothstep slope (1.875) times corner differences (4) */
        #define NOISE_LIP 8.5f
        float gradf_e(int h, float x, float y, float z, float ex, float ey, float ez, float* e) {
          uint s = (uint) (h & 15) * 2u;
          uint cx = (GXC >> s) & 3u, cy = (GYC >> s) & 3u, cz = (GZC >> s) & 3u;
          float a = gmf(cx, x), b = gmf(cy, y), d = gmf(cz, z);
          float t = a + b; float r = t + d;
          *e = (cx ? ex : 0.0f) + (cy ? ey : 0.0f) + (cz ? ez : 0.0f) + RND(t) + RND(r);
          return r;
        }
        /* smoothf (d^3 (d (6 d - 15) + 10)) with each rounding carried to the result by its sensitivity; slope at most 1.875 on [0, 1] */
        float smoothf_e(float d, float ed, float* e) {
          float d2 = d * d; float d3 = d2 * d; float a = d * 6.0f; float b = a - 15.0f; float cc = d * b; float q = cc + 10.0f; float s = d3 * q;
          float ad = fabs(d), ad3 = fabs(d3), aq = fabs(q);
          *e = 1.875f * ed + (RND(a) + RND(b)) * ad3 * ad + (RND(cc) + RND(q)) * ad3 + RND(d2) * ad * aq + RND(d3) * aq + RND(s);
          return s;
        }
        /* lerpf with t's, a's and b's errors (t in [0, 1]) */
        float lerpf_e(float t, float et, float a, float ea, float b, float eb, float* e) {
          float d = b - a; float m = t * d; float w = a + m;
          float at = fabs(t);
          *e = fabs(1.0f - t) * ea + at * eb + fabs(d) * et + RND(d) * at + RND(m) + RND(w);
          return w;
        }
        float cornersf_e(__global const uchar* p, int ix, int iy, int iz, float x, float y, float z, float ys, float ex, float ey, float ez, float eys, float* eo) {
          int X = ix & 255, Y = iy & 255, Z = iz & 255;
          int A = p[X], B = p[X + 1];
          int AA = p[A + Y], AB = p[A + Y + 1], BA = p[B + Y], BB = p[B + Y + 1];
          float x1 = x - 1.0f, y1 = y - 1.0f, z1 = z - 1.0f;
          float ex1 = ex + RND(x1), ey1 = ey + RND(y1), ez1 = ez + RND(z1);
          float e0, e1, e2, e3, e4, e5, e6, e7;
          float d0 = gradf_e(p[AA + Z], x, y, z, ex, ey, ez, &e0);
          float d1 = gradf_e(p[BA + Z], x1, y, z, ex1, ey, ez, &e1);
          float d2 = gradf_e(p[AB + Z], x, y1, z, ex, ey1, ez, &e2);
          float d3 = gradf_e(p[BB + Z], x1, y1, z, ex1, ey1, ez, &e3);
          float d4 = gradf_e(p[AA + Z + 1], x, y, z1, ex, ey, ez1, &e4);
          float d5 = gradf_e(p[BA + Z + 1], x1, y, z1, ex1, ey, ez1, &e5);
          float d6 = gradf_e(p[AB + Z + 1], x, y1, z1, ex, ey1, ez1, &e6);
          float d7 = gradf_e(p[BB + Z + 1], x1, y1, z1, ex1, ey1, ez1, &e7);
          float esx, esy, esz;
          float sx = smoothf_e(x, ex, &esx), sy = smoothf_e(ys, eys, &esy), sz = smoothf_e(z, ez, &esz);
          float el0, el1, el2, el3, ek0, ek1;
          float l0 = lerpf_e(sx, esx, d0, e0, d1, e1, &el0), l1 = lerpf_e(sx, esx, d2, e2, d3, e3, &el1);
          float l2 = lerpf_e(sx, esx, d4, e4, d5, e5, &el2), l3 = lerpf_e(sx, esx, d6, e6, d7, e7, &el3);
          float k0 = lerpf_e(sy, esy, l0, el0, l1, el1, &ek0), k1 = lerpf_e(sy, esy, l2, el2, l3, el3, &ek1);
          return lerpf_e(sz, esz, k0, ek0, k1, ek1, eo);
        }
        /* a / (rh + rl): the quotient's residual exact through fma, the low parts' terms in float */
        ff ff_divc(ff a, float rh, float rl) { float q = a.x / rh; float r = fma(-q, rh, a.x); r = (r + a.y) - q * rl; return ff_fast(q, r / rh); }
        /* a times a power of two (exact) */
        ff ff_scale(ff a, float p2) { return (ff) (a.x * p2, a.y * p2); }
        """;

    private String certifiedNoiseFunctions() {
        if (!tables) {
            throw new Unsupported("certified fp32 needs the noise tables");
        }
        if (!exactPow2) {
            throw new Unsupported("a noise octave's input factor is not a float");
        }
        // |improved noise| < 1.04: the limit noises of blended noise are bounded by 1.1 * (2^16 - 1) / 512
        return """
            #define BL_LIM 141.0f
            /* one lattice coordinate of an octave: vanilla's wrap(x * f) + offset (in double) as float-float, its cell and fraction; the noise is
               continuous across cells, so a cell other than vanilla's (a coordinate within its error of an integer) only moves the point by that
               error, which the caller bounds; a coordinate within its error of the wrap's step (vanilla's own discontinuity) marks the cell */
            int lat2(ff x, float f, float oh, float ol, float ein, ff* frac, float* el, int* unc) {
              ff d = ff_mulf(x, f);
              float k = floor(d.x * 0x1p-25f + 0.5f);
              float ea = (fabs(d.x) + 256.0f) * 0x1p-40f;
              if (k != 0.0f || fabs(d.x) > 0x1.fffp23f) {
                /* beyond 2^24: vanilla subtracts k * 2^25 exactly; at its steps (k +- 1/2) * 2^25 the exact k may differ */
                if (fabs(fabs(d.x * 0x1p-25f - k) - 0.5f) * 0x1p25f <= 2.0f * (ea + ein) + 4.0f) *unc |= 1;
                d = ff_add(d, (ff) (-k * 0x1p25f, 0.0f));
              }
              ff p = ff_add(d, (ff) (oh, ol));
              float fl = floor(p.x);
              if (p.x == fl && p.y < 0.0f) fl -= 1.0f;
              *frac = ff_fast(p.x - fl, p.y);
              *el = ea;
              return (int) fl;
            }
            /* lat2 with the fraction rounded to a float */
            int lat(ff x, float f, float oh, float ol, float ein, float* frac, float* el, int* unc) {
              ff fr;
              int i = lat2(x, f, oh, ol, ein, &fr, el, unc);
              *frac = fr.x;
              return i;
            }
            NI float perlin_sum32(Ctx* c, int start, int count, ff x, ff y, ff z, float ec, float* e) {
              float sum = 0.0f, es = 0.0f;
              for (int i = start; i < start + count; i++) {
                __global const float* o = c->of + OF_OCT + i * OF_STRIDE;
                float A = o[0], VF = o[1], f = o[2];
                float fx, fy, fz, elx, ely, elz;
                float ein = f * ec;
                int ix = lat(x, f, o[3], o[4], ein, &fx, &elx, &c->unc), iy = lat(y, f, o[5], o[6], ein, &fy, &ely, &c->unc);
                int iz = lat(z, f, o[7], o[8], ein, &fz, &elz, &c->unc);
                float ev;
                float v = cornersf_e(c->pm + c->oi[OI_OCT + i], ix, iy, iz, fx, fy, fz, fy, RND(fx), RND(fy), RND(fz), RND(fy), &ev);
                /* the lattice point's distance from vanilla's: the float-float coordinates' (and vanilla's double) rounding, and the input's error */
                ev += NOISE_LIP * 1.0001f * (elx + ely + elz + f * ec);
                float t = A * v; float u2 = t * VF; float s2 = sum + u2;
                es += fabs(A * VF) * ev + fabs(v) * (fabs(VF) * RND(A) + fabs(A) * RND(VF)) + RND(t) * fabs(VF) + RND(u2) + RND(s2);
                sum = s2;
              }
              *e = es;
              return sum;
            }
            NI float nn32(Ctx* c, int s, ff x, ff y, ff z, float ex, float ey, float ez, float* e) {
              __global const int* r = c->oi + s * 4;
              float ec = ex + ey + ez;
              float ea, eb;
              float a = perlin_sum32(c, r[0], r[1], x, y, z, ec, &ea);
              ff x2 = ff_mulc(x, INFH, INFL), y2 = ff_mulc(y, INFH, INFL), z2 = ff_mulc(z, INFH, INFL);
              float b = perlin_sum32(c, r[2], r[3], x2, y2, z2, ec * INFUP, &eb);
              float SVF = c->of[s];
              float sm = a + b; float out = sm * SVF;
              *e = (ea + eb + RND(sm)) * fabs(SVF) + fabs(sm) * RND(SVF) + RND(out);
              return out;
            }
            /*
             * ImprovedNoise.noise(wrap(x * f), wrap(y * f), wrap(z * f), yScale, yMax) of a blended octave, certified, with no double: the
             * lattice in float-float as lat's (o: the octave's x, y, z offsets as hi, lo); ys, ym: vanilla's yScale and yMax. The smear's
             * step floor(d7 / yScale + 1e-7) is decided in float-float (its 1e-7 decides it when yMax / yScale is a whole number, as at
             * every corner of a cell height that is a multiple of the smear); the cell is marked when the step is in doubt, or the point is
             * near the cell's y edge or yMax near the fraction (where a smeared octave jumps). An octave whose yScale exceeds 1 keeps the
             * step at 0 (d7 < 1): continuous in y, as an octave of a normal noise.
             */
            NI float bl_oct(Ctx* c, __global const uchar* pm, __global const float* o, ff x, ff y, ff z, float f, ff ys, ff ym, float* e) {
              float fx, fz, elx, ely, elz;
              ff fyf;
              int ix = lat(x, f, o[0], o[1], 0.0f, &fx, &elx, &c->unc);
              int iy = lat2(y, f, o[2], o[3], 0.0f, &fyf, &ely, &c->unc);
              int iz = lat(z, f, o[4], o[5], 0.0f, &fz, &elz, &c->unc);
              float fy = fyf.x, fys = fy, ey6 = 0.0f;
              if (ys.x <= 1.0001f && ys.x != 0.0f) {
                if (fy <= ely || fy >= 1.0f - ely) c->unc |= 1;
                /* d7 = yMax when 0 <= yMax < fy, else fy; yMax's sign is vanilla's (a product and quotient of the same signs) */
                float em = fabs(ym.x) * 0x1p-38f;
                ff dm = ff_add(ym, (ff) (-fyf.x, -fyf.y));
                if (fabs(dm.x) <= em + ely) c->unc |= 1;
                int useMax = ym.x >= 0.0f && dm.x < 0.0f;
                ff d7 = useMax ? ym : fyf;
                float e7 = useMax ? em : ely;
                ff q = ff_add(ff_divc(d7, ys.x, ys.y), (ff) (1.0E-7f, 0.0f));
                float n = floor(q.x);
                if (q.x == n && q.y < 0.0f) n -= 1.0f;
                float qf = (q.x - n) + q.y;
                float eq = e7 / ys.x + fabs(q.x) * 0x1p-40f + 0x1p-60f;
                if (qf <= eq || qf >= 1.0f - eq) c->unc |= 1;
                ff d6 = ff_mulf(ys, n);
                fys = ff_add(fyf, (ff) (-d6.x, -d6.y)).x;
                ey6 = fabs(d6.x) * 0x1p-40f + 0x1p-60f;
              }
              float ev;
              float v = cornersf_e(pm, ix, iy, iz, fx, fys, fz, fy, RND(fx), RND(fys) + ey6, RND(fz), RND(fy), &ev);
              *e = ev + NOISE_LIP * 1.0001f * (elx + ely + elz);
              return v;
            }
            NI float bn32(Ctx* c, int s, int bx, int by, int bz, float* eo) {
              __global const float* k = c->of + OF_BL + s * OF_BL_STRIDE;
              __global const int* p = c->oi + OI_BL + s * 40;
              /* vanilla's doubles d0 = bx * xzMultiplier, d1, d2, d3 = d0 / xzFactor, d4, d5 in float-float (within 2^-44 of them, inside
                 lat's budget), its d6 and d7 from the table */
              ff d0 = ff_mulc(ff_int(bx), k[0], k[1]), d1 = ff_mulc(ff_int(by), k[2], k[3]), d2 = ff_mulc(ff_int(bz), k[0], k[1]);
              ff d3 = ff_divc(d0, k[4], k[5]), d4 = ff_divc(d1, k[6], k[7]), d5 = ff_divc(d2, k[4], k[5]);
              ff d6 = (ff) (k[8], k[9]), d7 = (ff) (k[10], k[11]);
              float d8 = 0.0f, d9 = 0.0f, d10 = 0.0f, e8 = 0.0f, e9 = 0.0f, e10 = 0.0f;
              float d11 = 1.0f;
              for (int i = 0; i < 8; i++) {
                if (p[i] >= 0) {
                  float ev;
                  float v = bl_oct(c, c->pm + p[i], k + 12 + i * 6, d3, d4, d5, d11, ff_scale(d7, d11), ff_scale(d4, d11), &ev);
                  float q = v / d11; float s2 = d10 + q;
                  e10 += ev / d11 + RND(q) + RND(s2);
                  d10 = s2;
                }
                d11 /= 2.0f;
              }
              float tq = d10 / 10.0f; float tp = tq + 1.0f; float d16 = tp / 2.0f;
              float e16 = (e10 / 10.0f + RND(tq) + RND(tp)) / 2.0f + RND(d16);
              int flag1 = d16 >= 1.0f; int flag2 = d16 <= 0.0f;
              d11 = 1.0f;
              for (int j = 0; j < 16; j++) {
                ff ys = ff_scale(d6, d11), ym = ff_scale(d1, d11);
                int a = 8 + j, b = 24 + j;
                if (!flag1 && p[a] >= 0) {
                  float ev; float v = bl_oct(c, c->pm + p[a], k + 12 + a * 6, d0, d1, d2, d11, ys, ym, &ev);
                  float q = v / d11; float s2 = d8 + q; e8 += ev / d11 + RND(q) + RND(s2); d8 = s2;
                }
                if (!flag2 && p[b] >= 0) {
                  float ev; float v = bl_oct(c, c->pm + p[b], k + 12 + b * 6, d0, d1, d2, d11, ys, ym, &ev);
                  float q = v / d11; float s2 = d9 + q; e9 += ev / d11 + RND(q) + RND(s2); d9 = s2;
                }
                d11 /= 2.0f;
              }
              float lo = d8 / 512.0f, hi = d9 / 512.0f, elo = e8 / 512.0f + RND(lo), ehi = e9 / 512.0f + RND(hi);
              float r, er;
              if (d16 < 0.0f) {
                r = lo; er = elo + (d16 + e16 >= 0.0f ? e16 * (fabs(lo) + BL_LIM) : 0.0f);
              } else if (d16 > 1.0f) {
                r = hi; er = ehi + (d16 - e16 <= 1.0f ? e16 * (fabs(hi) + BL_LIM) : 0.0f);
              } else {
                float df = hi - lo; float m = d16 * df; r = lo + m;
                er = (1.0f - d16) * elo + d16 * ehi + fabs(df) * e16 + RND(df) * d16 + RND(m) + RND(r);
                /* at d16 = 0 (1) vanilla never computed hi (lo); the exact d16 may lie inside, where the missing side counts */
                if (flag1 || flag2) er += e16 * (fabs(lo) + fabs(hi) + BL_LIM);
              }
              float out = r / 128.0f;
              *eo = er / 128.0f + RND(out);
              return out;
            }
            """.replace("OD_OCT", Integer.toString(odOct)).replace("OI_OCT", Integer.toString(oiOct)).replace("OF_OCT", Integer.toString(ofOct))
            .replace("OF_BL_STRIDE", Integer.toString(OF_BL_STRIDE)).replace("OF_BL", Integer.toString(ofBl))
            .replace("OD_BL", Integer.toString(odBl)).replace("OI_BL", Integer.toString(oiBl)).replace("INFUP", flit(ceilF(NoiseSpec.INPUT_FACTOR)))
            .replace("INFH", flit((float) NoiseSpec.INPUT_FACTOR)).replace("INFL", flit((float) (NoiseSpec.INPUT_FACTOR - (double) (float) NoiseSpec.INPUT_FACTOR))).replace("OF_STRIDE", Integer.toString(OF_STRIDE));
    }

    /**
     * Certified spline functions on the spline tables, one set per depth as in {@link #splineFunctions}. Each runs vanilla's float
     * evaluation and bounds its distance from the evaluation of the exact input.
     */
    private String certifiedSplineFunctions() {
        splineTables();
        StringBuilder sb = new StringBuilder();
        for (int d = 1; d <= maxSplineDepth; d++) {
            sb.append("NI float sv32_").append(d).append("(Ctx* c, int s, int k, float* e) {\n  __global const int* hd = c->oi + SPL_I + 4 * s;\n");
            if (d > 1) {
                sb.append("  int ch = c->oi[SPL_I + hd[3] + k];\n  if (ch >= 0) return sp32_").append(d - 1).append("(c, ch, e);\n");
            }
            sb.append("  *e = 0.0f;\n  return c->of[SPL_F + hd[2] + 2 * hd[1] + k];\n}\n");
            sb.append(CERT_SPLINE.replace("@SG@", "sg32_" + d).replace("@SP@", "sp32_" + d).replace("@SV@", "sv32_" + d));
        }
        return sb.toString();
    }

    private static final String CERT_SPLINE = """
                /* segment i of spline s (-1 and n - 1: the linear ends) at f in vanilla's float evaluation; *L its slope bound, *R one
                   evaluation's rounding, *ec the children's errors carried */
                NI float @SG@(Ctx* c, int s, int i, float f, float* L, float* R, float* ec) {
                  __global const int* hd = c->oi + SPL_I + 4 * s;
                  int n = hd[1];
                  __global const float* loc = c->of + SPL_F + hd[2];
                  __global const float* der = loc + n;
                  if (i < 0 || i == n - 1) {
                    int j = i < 0 ? 0 : n - 1;
                    float ev; float v = @SV@(c, s, j, &ev);
                    float d = der[j];
                    *ec = ev;
                    if (d == 0.0f) { *L = 0.0f; *R = 0.0f; return v; }
                    float dd = f - loc[j]; float m = d * dd; float r = v + m;
                    *L = fabs(d); *R = RND(dd) * fabs(d) + RND(m) + RND(r);
                    return r;
                  }
                  float f1 = loc[i], f2 = loc[i + 1];
                  float q1 = f - f1, q2 = f2 - f1; float f3 = fdivx(q1, q2);
                  float f4 = der[i], f5 = der[i + 1];
                  float e6, e7; float f6 = @SV@(c, s, i, &e6); float f7 = @SV@(c, s, i + 1, &e7);
                  float df = f7 - f6;
                  float a8 = f4 * q2; float f8 = a8 - df;
                  float a9 = -f5 * q2; float f9 = a9 + df;
                  float m1 = f3 * df; float l1 = f6 + m1;
                  float d98 = f9 - f8; float m2 = f3 * d98; float il = f8 + m2;
                  float omf = 1.0f - f3; float g = f3 * omf; float h = g * il; float out = l1 + h;
                  float Lt = fabs(df) + fmax(fabs(f8), fabs(f9)) + 0.25f * fabs(d98);
                  float e3 = RND(q1) / q2 + fabs(f3) * RND(q2) / q2 + RND(f3);
                  /* the slope with the exact children: df, f8 and f9 move by at most e6 + e7, d98 by twice that */
                  *L = (Lt + 2.5f * (e6 + e7)) / q2;
                  *R = 1.25f * RND(df) + RND(m1) + RND(l1) + 0.25f * (RND(a8) + RND(f8) + RND(a9) + RND(f9) + RND(d98) + RND(m2) + RND(il))
                    + fabs(il) * (RND(omf) + RND(g)) + RND(h) + RND(out) + Lt * e3;
                  *ec = 1.25f * (e6 + e7);
                  return out;
                }
                NI float @SP@(Ctx* c, int s, float* e) {
                  __global const int* hd = c->oi + SPL_I + 4 * s;
                  int n = hd[1];
                  __global const float* loc = c->of + SPL_F + hd[2];
                  float f = c->cv[hd[0]]; float ef = c->ce[hd[0]];
                  /* vanilla rounds the exact coordinate to float: ours differs from that by at most our error plus that rounding */
                  float din = ef == 0.0f ? 0.0f : ef + RND(f);
                  int i = sfindg(loc, n, f);
                  float L, R, ec;
                  float out = @SG@(c, s, i, f, &L, &R, &ec);
                  /* the same float evaluation of the same inputs */
                  if (din == 0.0f && ec == 0.0f) { *e = 0.0f; return out; }
                  if (din > 0.0f) {
                    /* vanilla's input may lie in a neighbouring segment: the exact spline is continuous at the knots (and at the linear
                       ends), so the input's error moves it by at most the steepest segment's slope, and vanilla's evaluation rounds as
                       that segment's does */
                    float w = din * 1.001f + 0x1p-126f;
                    int lo = sfindg(loc, n, f - w), hi = sfindg(loc, n, f + w);
                    for (int j = lo; j <= hi; j++) {
                      if (j == i) continue;
                      float fj = j < 0 ? loc[0] : j == n - 1 ? loc[n - 1] : fmin(fmax(f, loc[j]), loc[j + 1]);
                      float Lj, Rj, ecj; @SG@(c, s, j, fj, &Lj, &Rj, &ecj);
                      L = fmax(L, Lj); R = fmax(R, Rj);
                    }
                  }
                  *e = L * din + ec + 2.01f * R;
                  return out;
                }
                """;

    private static final String LERP3C32 = """
        /* the cell fill's lerp3 over float corners: a convex combination (the weights exact), so the corners' errors interpolated with
           the same weights, plus the lerps' roundings (the error lerps' own roundings are within SAFE's margin) */
        float lerpr(float t, float a, float b, float* r) { float d = b - a; float m = t * d; float w = a + m; *r += RND(d) * fabs(t) + RND(m) + RND(w); return w; }
        float cerr8(Ctx* c, __global const float* q, float x, float y, float z, int* unc) {
          float n = fmin(fmin(fmin(q[0], q[c->csx]), fmin(q[1], q[c->csx + 1])), fmin(fmin(q[c->csz], q[c->csx + c->csz]), fmin(q[c->csz + 1], q[c->csx + c->csz + 1])));
          if (n < 0.0f) {
            *unc |= 512 | (int) -n; /* an uncertain corner (and the largest reason a corner had) */
            return fmax(fmax(fmax(q[0], q[c->csx]), fmax(q[1], q[c->csx + 1])), fmax(fmax(q[c->csz], q[c->csx + c->csz]), fmax(q[c->csz + 1], q[c->csx + c->csz + 1])));
          }
          float a = q[0] + x * (q[c->csx] - q[0]), b = q[1] + x * (q[c->csx + 1] - q[1]);
          float a2 = q[c->csz] + x * (q[c->csx + c->csz] - q[c->csz]), b2 = q[c->csz + 1] + x * (q[c->csx + c->csz + 1] - q[c->csz + 1]);
          float k0 = a + y * (b - a), k1 = a2 + y * (b2 - a2);
          return k0 + z * (k1 - k0);
        }
        float lerp3c32(Ctx* c, int ri, float* e) {
          __global const float* p = c->cr32 + c->cbase + ri * c->rstride;
          __global const float* q = c->cre32 + c->cbase + ri * c->rstride;
          float x = (float) c->dx, y = (float) c->dy, z = (float) c->dz;
          float r = 0.0f;
          float a = lerpr(x, p[0], p[c->csx], &r), b = lerpr(x, p[1], p[c->csx + 1], &r);
          float a2 = lerpr(x, p[c->csz], p[c->csx + c->csz], &r), b2 = lerpr(x, p[c->csz + 1], p[c->csx + c->csz + 1], &r);
          float k0 = lerpr(y, a, b, &r), k1 = lerpr(y, a2, b2, &r);
          float w = lerpr(z, k0, k1, &r);
          *e = cerr8(c, q, x, y, z, &c->unc) + r;
          return w;
        }
        float lerpvc32(Ctx* c, int ri, float* e) {
          __global const float* p = c->cr32 + c->cbase + ri * c->rstride;
          __global const float* q = c->cre32 + c->cbase + ri * c->rstride;
          float x = (float) c->dx, y = (float) c->dy, z = (float) c->dz;
          float r = 0.0f;
          float xz00 = lerpr(y, p[0], p[1], &r);
          float xz10 = lerpr(y, p[c->csx], p[c->csx + 1], &r);
          float xz01 = lerpr(y, p[c->csz], p[c->csz + 1], &r);
          float xz11 = lerpr(y, p[c->csx + c->csz], p[c->csx + c->csz + 1], &r);
          float z0 = lerpr(x, xz00, xz10, &r), z1 = lerpr(x, xz01, xz11, &r);
          float w = lerpr(z, z0, z1, &r);
          *e = cerr8(c, q, x, y, z, &c->unc) + r;
          return w;
        }
        """;

    /** The aquifer and vein decisions on certified values. A decision that the inputs' errors could flip sets {@code unc}. */
    private String certifiedDecisionCode() {
        return ("""
            /* a double comparison's margin: the inputs' error and the comparison's own roundings */
            #define CLOSE(v, err) (fabs(v) <= (err) + 1e-12 * fabs(v) + 1e-300)
            double pressure32(Ctx* c, double* barrier, double* eb, FS a, FS b, int y, double* ep) {
              *ep = 0.0;
              int ba = fs_at(a, y), bb = fs_at(b, y);
              if ((IS_LAVA(ba) && IS_WATER(bb)) || (IS_WATER(ba) && IS_LAVA(bb))) return 2.0;
              int j = abs(a.level - b.level);
              if (j == 0) return 0.0;
              double d0 = 0.5 * (double) (a.level + b.level);
              double d1 = (double) y + 0.5 - d0;
              double d2 = (double) j / 2.0;
              double d9 = d2 - fabs(d1);
              double d10;
              if (d1 > 0.0) { double d11 = 0.0 + d9; d10 = d11 > 0.0 ? d11 / 1.5 : d11 / 2.5; }
              else { double d15 = 3.0 + d9; d10 = d15 > 0.0 ? d15 / 3.0 : d15 / 10.0; }
              double d12;
              if (!(d10 < -2.0) && !(d10 > 2.0)) {
                if (isnan(*barrier)) { float e; *barrier = (double) bk32_barrier(c, &e); *eb = (double) SAFE(e); if (!(e < 1e30f)) c->unc |= 4; }
                d12 = *barrier;
                *ep = 2.0 * *eb;
              } else d12 = 0.0;
              return 2.0 * (d12 + d10);
            }
            NI int aquifer_decide32(Ctx* c, double density, double ed, __global const int* cells, int cbase, int bcx, int bcz, int lmax, int lqOn, int* flag) {
              int x = c->bx, y = c->by, z = c->bz;
              if (CLOSE(density, ed)) c->unc |= 2;
              if (density > 0.0) { *flag = 0; return ID_NONE; }
              if (IS_LAVA(fs_at(picker(y), y))) { *flag = 0; return ID_LAVA; }
              /* 6 or more above every fluid level of the cells the chunk's blocks can see (lmax): each of them is air at y, and each pressure
                 is 2 * d10 with d10 <= -2.6 (the barrier unread), never raising the density: vanilla returns air (no fluid, no mark). Not
                 where Better Caves' liquid regions apply (lqOn): the air becomes liquid there, and the mark needs vanilla's flag */
              if (!lqOn && y >= lmax + 6) { *flag = 0; return ID_AIR; }
              int l = jfloordiv(x - 5, 16), i1 = jfloordiv(y + 1, 12), j1 = jfloordiv(z - 5, 16);
              int k1 = 0x7fffffff, l1 = 0x7fffffff, i2 = 0x7fffffff;
              int a1 = 0, a2 = 0, a3 = 0;
              for (int i3 = 0; i3 <= 1; i3++) {
                for (int j3 = -1; j3 <= 1; j3++) {
                  for (int k3 = 0; k3 <= 1; k3++) {
                    int ci = ((i1 + j3 - MINGY) * 3 + (j1 + k3 - (bcz - 1))) * 3 + (l + i3 - (bcx - 1));
                    __global const int* cl = cells + (cbase + ci) * 5;
                    int ex = cl[0] - x, ey = cl[1] - y, ez = cl[2] - z;
                    int l5 = ex * ex + ey * ey + ez * ez;
                    if (k1 >= l5) { a3 = a2; a2 = a1; a1 = ci; i2 = l1; l1 = k1; k1 = l5; }
                    else if (l1 >= l5) { a3 = a2; a2 = ci; i2 = l1; l1 = l5; }
                    else if (i2 >= l5) { a3 = ci; i2 = l5; }
                  }
                }
              }
              FS s1 = cell_fs(cells, cbase, a1);
              double d1 = similarity(k1, l1);
              int bs = fs_at(s1, y);
              if (d1 <= 0.0) { *flag = d1 >= similarity(100, 144) ? 1 : 0; return bs; }
              if (IS_WATER(bs) && IS_LAVA(fs_at(picker(y - 1), y - 1))) { *flag = 1; return bs; }
              double barrier = NAN, eb = 0.0, ep;
              FS s2 = cell_fs(cells, cbase, a2);
              double d2 = d1 * pressure32(c, &barrier, &eb, s1, s2, y, &ep);
              if (CLOSE(density + d2, ed + d1 * ep)) c->unc |= 4;
              if (density + d2 > 0.0) { *flag = 0; return ID_NONE; }
              FS s3 = cell_fs(cells, cbase, a3);
              double d0 = similarity(k1, i2);
              if (d0 > 0.0) {
                double d3 = d1 * d0 * pressure32(c, &barrier, &eb, s1, s3, y, &ep);
                if (CLOSE(density + d3, ed + d1 * d0 * ep)) c->unc |= 4;
                if (density + d3 > 0.0) { *flag = 0; return ID_NONE; }
              }
              double d4 = similarity(l1, i2);
              if (d4 > 0.0) {
                double d5 = d1 * d4 * pressure32(c, &barrier, &eb, s2, s3, y, &ep);
                if (CLOSE(density + d5, ed + d1 * d4 * ep)) c->unc |= 4;
                if (density + d5 > 0.0) { *flag = 0; return ID_NONE; }
              }
              *flag = 1;
              return bs;
            }
            int aquifer_disabled32(Ctx* c, double density, double ed, int* flag) {
              *flag = 0;
              if (CLOSE(density, ed)) c->unc |= 2;
              if (density > 0.0) return ID_NONE;
              return fs_at(picker(c->by), c->by);
            }
            NI int vein_rule32(Ctx* c, ulong oreLo, ulong oreHi) {
              float et; float tg = vn32_toggle(c, &et);
              double d0 = (double) tg, e0 = (double) SAFE(et);
              if (!(et < 1e30f)) c->unc |= 8;
              int y = c->by;
              /* the sign picks copper or iron; it only matters where either type's y range admits y (outside both, both return null) */
              if (CLOSE(d0, e0) && ((y >= 0 && y <= 50) || (y >= -60 && y <= -8))) c->unc |= 8;
              int copper = d0 > 0.0;
              int maxY = copper ? 50 : -8, minY = copper ? 0 : -60;
              double d1 = fabs(d0);
              int j = maxY - y, k = y - minY;
              if (k < 0 || j < 0) return ID_NONE;
              int l = min(j, k);
              double d2 = jclampedMap((double) l, 0.0, 20.0, -0.2, 0.0);
              if (CLOSE(d1 + d2 - (double) 0.4f, e0)) c->unc |= 16;
              if (d1 + d2 < (double) 0.4f) return ID_NONE;
              Xo r = xo_at(oreLo, oreHi, c->bx, y, c->bz);
              if (xo_nextFloat(&r) > 0.7f) return ID_NONE;
              float er; float rg = vn32_ridged(c, &er);
              if (!(er < 1e30f) || CLOSE((double) rg, (double) SAFE(er))) c->unc |= 32;
              if ((double) rg >= 0.0) return ID_NONE;
              double d3 = jclampedMap(d1, (double) 0.4f, (double) 0.6f, (double) 0.1f, (double) 0.3f);
              double nf = (double) xo_nextFloat(&r);
              /* clampedMap's slope here is 1 (with float-rounded ends: within 1.0001) */
              if (CLOSE(nf - d3, e0 * 1.0001)) c->unc |= 64;
              if (nf < d3) {
                float eg; float gp = vn32_gap(c, &eg);
                if (!(eg < 1e30f) || CLOSE((double) gp + 0.30000001192092896, (double) SAFE(eg))) c->unc |= 128;
                if ((double) gp > (double) -0.3f) {
                  return xo_nextFloat(&r) < 0.02f ? (copper ? %d : %d) : (copper ? %d : %d);
                }
              }
              return copper ? %d : %d;
            }
            """).formatted(ID_RAW_COPPER, ID_RAW_IRON, ID_COPPER_ORE, ID_DEEPSLATE_IRON_ORE, ID_GRANITE, ID_TUFF);
    }

    /**
     * Certified kernels. {@code k_corner32} computes region corners in float with their errors. A negative error marks an uncertain
     * corner. {@code k_corner32_pc} computes per-chunk corners. {@code k_block32} computes blocks, and an uncertain decision lists its
     * cell once. Listed cells get a double recheck. {@code k_recheck_mark} lists their corners once each. {@code k_recheck_corners}
     * computes those corners in double into a per-chunk corner grid. {@code k_recheck_blocks} decides the cells' blocks over that grid.
     */
    private String certifiedKernels() {
        StringBuilder k = new StringBuilder();
        int cw = cellWidth;
        int per = 16 / cw;
        int pc = (per + 1) * (per + 1);
        int nh = Math.max(1, hoisted.size());
        int nb = Math.max(1, blockHoisted.size());
        int h = height;
        int nr = roots.size();
        int cells = per * per * cellCountY;
        int cellBlocks = cw * cw * cellHeight;
        k.append("#define CELLS ").append(cells).append("\n#define CELL_BLOCKS ").append(cellBlocks).append("\n#define RECHECK_REASONS ").append(RECHECK_REASONS.length)
            .append("\n");
        // a batch's recheck state cleared in one launch: the cell flags, the corner marks, the counts
        k.append("__kernel void k_zero(__global int* a, int na, __global int* b, int nb, __global int* c, int nc) {\n"
            + "  int g = get_global_id(0); if (g < na) a[g] = 0; if (g < nb) b[g] = 0; if (g < nc) c[g] = 0;\n}\n");
        // region corners
        k.append("__kernel void k_corner32(").append(COMMON_ARGS).append(", __global const R* hz, __global float* corners, __global float* cornersE, int bx0, int bz0, int ncx, int ncz, int ncy) {\n");
        k.append("  int g = get_global_id(0); if (g >= ncx * ncz * ncy) return;\n");
        k.append("  int col = g / ncy; int cy = g % ncy;\n").append(ctxInit()).append(COMMON_INIT).append("  reset32(c);\n");
        k.append("  c->bps = 0; c->bpn = 0; c->bjs = 0; c->bjn = 0; c->epn = 0; c->ovr = 0;\n");
        k.append("  c->hz = hz; c->hbase = col * ").append(nh).append(";\n");
        k.append("  c->bx = bx0 + (col / ncz) * ").append(cw).append("; c->bz = bz0 + (col % ncz) * ").append(cw)
            .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
        if (!perChunkCorners) {
            for (int i = 0; i < nr; i++) {
                k.append("  float e").append(i).append("; float v").append(i).append(" = cr32_").append(i).append("(c, &e").append(i).append(");\n");
            }
            for (int i = 0; i < nr; i++) {
                k.append("  corners[").append(i).append(" * ncx * ncz * ncy + g] = v").append(i).append("; cornersE[").append(i).append(" * ncx * ncz * ncy + g] = c->unc ? -(float) c->unc : !(e")
                    .append(i).append(" < 1e30f) ? -1.0f : SAFE(e").append(i).append(");\n");
            }
        }
        k.append("}\n");
        // per-chunk corners
        k.append("__kernel void k_corner32_pc(").append(COMMON_ARGS).append(", __global const R* hz, __global float* corners, __global float* cornersE, int bx0, int bz0, int ncz, int ncy,"
            + " int hchunks, int n, __global const int* lst, __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const int* ek) {\n");
        k.append("  int g = get_global_id(0); if (g >= n * ").append(pc).append(" * ncy) return;\n");
        k.append("  int li = g / (").append(pc).append(" * ncy); int rem = g % (").append(pc).append(" * ncy); int col = rem / ncy; int cy = rem % ncy;\n");
        k.append("  int b = lst[li]; int lcx = col / ").append(per + 1).append(", lcz = col % ").append(per + 1).append("; int ix = b / hchunks, iz = b % hchunks;\n");
        k.append(ctxInit()).append(COMMON_INIT).append("  reset32(c);\n");
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  c->hz = hz; c->hbase = ((ix * ").append(per).append(" + lcx) * ncz + iz * ").append(per).append(" + lcz) * ").append(nh).append(";\n");
        k.append("  c->bx = bx0 + ix * 16 + lcx * ").append(cw).append("; c->bz = bz0 + iz * 16 + lcz * ").append(cw)
            .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
        if (perChunkCorners) {
            for (int i = 0; i < nr; i++) {
                k.append("  float e").append(i).append("; float v").append(i).append(" = cr32_").append(i).append("(c, &e").append(i).append(");\n");
            }
            for (int i = 0; i < nr; i++) {
                String idx = "((li * " + nr + " + " + i + ") * " + pc + " + col) * ncy + cy";
                k.append("  corners[").append(idx).append("] = v").append(i).append("; cornersE[").append(idx).append("] = c->unc ? -(float) c->unc : !(e").append(i)
                    .append(" < 1e30f) ? -1.0f : SAFE(e").append(i).append(");\n");
            }
        }
        k.append("}\n");
        // blocks
        k.append("__kernel void k_block32(").append(COMMON_ARGS)
            .append(", __global const float* corners, __global const float* cornersE, __global uchar* out, __global float* dens, int bx0, int bz0, int nbz, int ncx, int ncz, int ncy, int n,"
                + " __global const int* lst, __global const int* cells, int cx0, int cz0, int hchunks, ulong oreLo, ulong oreHi,"
                + " __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const int* ek, __global const R* bh,"
                + " __global int* cflag, __global int* rlist, __global int* rcount, int rcap, __global const int* levels" + lqArg() + ") {\n");
        k.append("  int g = get_global_id(0); if (g >= n * ").append(256 * h).append(") return;\n");
        k.append("  int li = g / ").append(256 * h).append("; int r = g % ").append(256 * h).append("; int Y = r % ").append(h).append("; int t = r / ").append(h)
            .append("; int lz = t & 15; int lx = t >> 4;\n");
        k.append("  int b = lst[li]; int X = (b / hchunks) * 16 + lx; int Z = (b % hchunks) * 16 + lz;\n");
        k.append(ctxInit()).append(COMMON_INIT).append("  reset32(c);\n");
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  int cellX = X / ").append(cw).append(", cellY = Y / ").append(cellHeight).append(", cellZ = Z / ").append(cw).append(";\n");
        if (!perChunkCorners) {
            k.append("  c->cr32 = corners; c->cre32 = cornersE; c->rstride = ncx * ncz * ncy; c->csx = ncz * ncy; c->csz = ncy; c->cbase = (cellX * ncz + cellZ) * ncy + cellY;\n");
        } else {
            k.append("  c->cr32 = corners; c->cre32 = cornersE; c->rstride = ").append(pc).append(" * ncy; c->csx = ").append(per + 1).append(" * ncy; c->csz = ncy;\n");
            k.append("  c->cbase = li * ").append(nr * pc).append(" * ncy + ((lx / ").append(cw).append(") * ").append(per + 1).append(" + lz / ").append(cw)
                .append(") * ncy + cellY;\n");
        }
        k.append("  c->dx = (R) ((double) (X % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->dy = (R) ((double) (Y % ").append(cellHeight).append(") / (double) ").append(cellHeight).append(");\n");
        k.append("  c->dz = (R) ((double) (Z % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->bx = bx0 + X; c->by = ").append(minY).append(" + Y; c->bz = bz0 + Z;\n");
        k.append("  c->bh = bh; c->bhbase = (X * nbz + Z) * ").append(nb).append(";\n");
        k.append("  float ed; float d = bk32_final(c, &ed);\n");
        k.append("  if (!(ed < 1e30f)) c->unc |= 2;\n");
        k.append("#ifdef PERTURB\n  d += (Y & 1 ? 0.999f : -0.999f) * ed;\n#endif\n");
        k.append("#ifdef WRITE_DENSITY\n  long di = (long) b * (256 * H_) + (lx * 16 + lz) * H_ + Y; dens[3 * di] = d; dens[3 * di + 1] = c->unc ? INFINITY : SAFE(ed);\n#endif\n"
            .replace("H_", Integer.toString(h)));
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  int flag = 0;\n");
        if (aquifers) {
            k.append("  int id = aquifer_decide32(c, (double) d, (double) SAFE(ed), cells, b * ").append(cellsPerChunk).append(", bcx, bcz, levels[b], ")
                .append(liquidRegions ? "lq[li * LQ_STRIDE] != LQ_NONE && c->by <= lq[li * LQ_STRIDE]" : "0").append(", &flag);\n");
        } else {
            k.append("  int id = aquifer_disabled32(c, (double) d, (double) SAFE(ed), &flag);\n");
        }
        k.append(lqLine());
        if (veins) {
            k.append("  if (id == ID_NONE) { id = vein_rule32(c, oreLo, oreHi); if (id == ID_NONE) id = ").append(ID_STONE).append("; }\n");
        } else {
            k.append("  if (id == ID_NONE) id = ").append(ID_STONE).append(";\n");
        }
        k.append(OVERRIDE_LINE);
        k.append("#ifdef WRITE_DENSITY\n  dens[3 * di + 2] = as_float(c->unc); /* why the block's cell is rechecked (bits: 1 value, 2 density sign, 4 aquifer, 8+ veins) */\n#endif\n");
        k.append("  if (c->unc) {\n");
        k.append("    int ci = ((li * ").append(per).append(" + lx / ").append(cw).append(") * ").append(per).append(" + lz / ").append(cw).append(") * ").append(cellCountY)
            .append(" + cellY;\n");
        k.append("    if (atomic_cmpxchg(&cflag[ci], 0, 1) == 0) { int s = atomic_inc(rcount); if (s < rcap) rlist[s] = ci;"
            + " for (int rb = 0; rb < RECHECK_REASONS; rb++) if ((c->unc >> rb) & 1) atomic_inc(&rcount[2 + rb]); }\n");
        k.append("  }\n");
        k.append(canonLine()).append("  out[g] = (uchar) (id | (flag << 7));\n}\n");
        // recheck: the listed cells' corners, each corner once, because neighbouring cells share corners. Each corner is marked in the
        // batch's per-chunk corner grid and listed in clist (count in rcount[1]).
        k.append("__kernel void k_recheck_mark(__global const int* rlist, __global int* rcount, int rcap, __global int* cmark, __global int* clist, int ncy) {\n");
        k.append("  int g = get_global_id(0); int kk = g / 8; if (kk >= min(rcount[0], rcap)) return; int corner = g % 8;\n");
        k.append("  int ci = rlist[kk]; int cellY = ci % ").append(cellCountY).append("; int t = ci / ").append(cellCountY).append("; int cellZ = t % ").append(per)
            .append("; t /= ").append(per).append("; int cellX = t % ").append(per).append("; int li = t / ").append(per).append(";\n");
        k.append("  int idx = (li * ").append(pc).append(" + (cellX + ((corner >> 2) & 1)) * ").append(per + 1).append(" + cellZ + ((corner >> 1) & 1)) * ncy + cellY + (corner & 1);\n");
        k.append("  if (atomic_cmpxchg(&cmark[idx], 0, 1) == 0) clist[atomic_inc(&rcount[1])] = idx;\n}\n");
        // then in double, one thread per listed corner and root, a root per work-group (the roots are different functions)
        k.append("__kernel void k_recheck_corners(").append(COMMON_ARGS).append(", __global const R* hz, __global R* cval, int bx0, int bz0, int ncz, int ncy, int hchunks,"
            + " __global const int* lst, __global const int* clist, __global const int* rcount, int ccap, __global const int* bp, __global const int* bj, __global const int* bidx,"
            + " __global const float* bk, __global const int* ek) {\n");
        k.append("  int g = get_global_id(0); int ri = g / ccap; int kk = g % ccap; if (ri >= ").append(nr).append(" || kk >= rcount[1]) return;\n");
        k.append("  int idx = clist[kk]; int cy = idx % ncy; int t = idx / ncy; int col = t % ").append(pc).append("; int li = t / ").append(pc).append(";\n");
        k.append("  int b = lst[li]; int ix = b / hchunks, iz = b % hchunks;\n");
        k.append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  int colX = ix * ").append(per).append(" + col / ").append(per + 1).append(", colZ = iz * ").append(per).append(" + col % ").append(per + 1).append(";\n");
        k.append("  c->hz = hz; c->hbase = (colX * ncz + colZ) * ").append(nh).append(";\n");
        k.append("  c->bx = bx0 + colX * ").append(cw).append("; c->bz = bz0 + colZ * ").append(cw).append("; c->by = (cy + ").append(cellNoiseMinY)
            .append(") * ").append(cellHeight).append(";\n");
        k.append("  R v = 0;\n  switch (ri) {\n");
        for (int i = 0; i < nr; i++) {
            k.append("    case ").append(i).append(": v = cr_").append(i).append("(c); break;\n");
        }
        k.append("  }\n  cval[((long) (li * ").append(nr).append(" + ri) * ").append(pc).append(" + col) * ncy + cy] = v;\n");
        k.append("}\n");
        // recheck: the listed cells' blocks in double over those corners
        k.append("__kernel void k_recheck_blocks(").append(COMMON_ARGS).append(", __global const R* cval, __global uchar* out, int bx0, int bz0, int nbz, int ncy, int hchunks,"
            + " __global const int* lst, __global const int* rlist, __global const int* rcount, int rcap, __global const int* cells, int cx0, int cz0, ulong oreLo, ulong oreHi,"
            + " __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const int* ek, __global const R* bh" + lqArg() + ") {\n");
        k.append("  int g = get_global_id(0); int kk = g / CELL_BLOCKS; if (kk >= min(*rcount, rcap)) return; int q = g % CELL_BLOCKS;\n");
        k.append("  int iy = q % ").append(cellHeight).append("; int t = q / ").append(cellHeight).append("; int izc = t % ").append(cw).append("; int ixc = t / ").append(cw).append(";\n");
        k.append("  int ci = rlist[kk]; int cellY = ci % ").append(cellCountY).append("; t = ci / ").append(cellCountY).append("; int cellZ = t % ").append(per)
            .append("; t /= ").append(per).append("; int cellX = t % ").append(per).append("; int li = t / ").append(per).append(";\n");
        k.append("  int b = lst[li]; int lx = cellX * ").append(cw).append(" + ixc, lz = cellZ * ").append(cw).append(" + izc; int X = (b / hchunks) * 16 + lx, Z = (b % hchunks) * 16 + lz;")
            .append(" int Y = cellY * ").append(cellHeight).append(" + iy;\n");
        k.append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[li * 8]; c->bpn = bidx[li * 8 + 1]; c->bjs = bidx[li * 8 + 2]; c->bjn = bidx[li * 8 + 3];"
            + " c->ek = ek; c->epn = bidx[li * 8 + 4]; c->ovr = 0;\n");
        k.append("  c->cr = cval; c->rstride = ").append(pc).append(" * ncy; c->csx = ").append(per + 1).append(" * ncy; c->csz = ncy; c->cbase = li * ").append(nr * pc)
            .append(" * ncy + (cellX * ").append(per + 1).append(" + cellZ) * ncy + cellY;\n");
        k.append("  c->dx = (R) ((double) ixc / (double) ").append(cw).append("); c->dy = (R) ((double) iy / (double) ").append(cellHeight)
            .append("); c->dz = (R) ((double) izc / (double) ").append(cw).append(");\n");
        k.append("  c->bx = bx0 + X; c->by = ").append(minY).append(" + Y; c->bz = bz0 + Z;\n");
        k.append("  c->bh = bh; c->bhbase = (X * nbz + Z) * ").append(nb).append(";\n");
        k.append("  R d = bk_final(c);\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  int flag = 0;\n");
        if (aquifers) {
            k.append("  int id = aquifer_decide(c, (double) d, cells, b * ").append(cellsPerChunk).append(", bcx, bcz, &flag);\n");
        } else {
            k.append("  int id = aquifer_disabled(c, (double) d, &flag);\n");
        }
        k.append(lqLine());
        if (veins) {
            k.append("  if (id == ID_NONE) { id = vein_rule(c, oreLo, oreHi); if (id == ID_NONE) id = ").append(ID_STONE).append("; }\n");
        } else {
            k.append("  if (id == ID_NONE) id = ").append(ID_STONE).append(";\n");
        }
        k.append(OVERRIDE_LINE);
        k.append(canonLine()).append("  out[(long) li * ").append(256 * h).append(" + (lx * 16 + lz) * ").append(h).append(" + Y] = (uchar) (id | (flag << 7));\n}\n");
        return k.toString();
    }

    private static void splineCoords(Node.SplineSpec s, Map<Node, Boolean> out) {
        if (s instanceof Node.SplineMulti m) {
            out.put(m.coordinate(), Boolean.TRUE);
            for (Node.SplineSpec v : m.values()) {
                splineCoords(v, out);
            }
        }
    }

    private int registerSpline(Node.SplineMulti m) {
        Integer id = splineIndex.get(m);
        if (id != null) {
            return id;
        }
        int depth = 1;
        for (Node.SplineSpec v : m.values()) {
            if (v instanceof Node.SplineMulti mm) {
                depth = Math.max(depth, splineDepths.get(registerSpline(mm)) + 1);
            }
        }
        id = splines.size();
        splines.add(m);
        splineIndex.put(m, id);
        splineDepths.add(depth);
        maxSplineDepth = Math.max(maxSplineDepth, depth);
        return id;
    }

    /** The evaluator of a spline: the generic function of its depth (the levels of nested splines below it, itself included). */
    private String splineFn(String prefix, int id) {
        return prefix + splineDepths.get(id);
    }

    // ---- literals ------------------------------------------------------------------------------------------------

    /** An R-typed literal of the double value (F32: the value rounded to float). */
    String lit(double v) {
        return prec == Prec.F64 ? dlit(v) : flit((float) v);
    }

    static String dlit(double v) {
        if (Double.isNaN(v)) {
            return "((double) NAN)";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "((double) INFINITY)" : "(-(double) INFINITY)";
        }
        return "(" + Double.toHexString(v) + ")";
    }

    static String flit(float v) {
        if (Float.isNaN(v)) {
            return "NAN";
        }
        if (Float.isInfinite(v)) {
            return v > 0 ? "INFINITY" : "(-INFINITY)";
        }
        return "(" + Float.toHexString(v) + "f)";
    }

    // ---- noise ---------------------------------------------------------------------------------------------------

    private String noise(int spec, String x, String y, String z) {
        usedSpecs.set(spec);
        return tables ? "nn(c, " + spec + ", " + x + ", " + y + ", " + z + ")" : "nn_" + spec + "(c, " + x + ", " + y + ", " + z + ")";
    }

    private int permOf(int[] p) {
        Integer off = permOffset.get(p);
        if (off == null) {
            off = perm.size();
            for (int i = 0; i < 512; i++) {
                perm.write(p[i] & 255);
            }
            permOffset.put(p, off);
        }
        return off;
    }

    /** {@code v} as the float pair hi + lo at {@code out[at]}, {@code out[at + 1]}. */
    private static void splitFloat(double v, float[] out, int at) {
        float hi = (float) v;
        out[at] = hi;
        out[at + 1] = (float) (v - (double) hi);
    }

    /**
     * {@link #tables}: every noise spec's octaves as data, with one generic loop over them. One record per live octave holds the
     * offsets, amplitude, input and value factor, and permutation offset. BlendedNoise likewise. The loop runs the same operations in
     * the same order as the unrolled functions, so it gives the same values.
     */
    private String tableNoiseFunctions() {
        int nspecs = specs.size();
        List<Integer> octPerm = new ArrayList<>();
        List<double[]> octD = new ArrayList<>();
        int[] specRec = new int[nspecs * 4];
        for (int s = 0; s < nspecs; s++) {
            NoiseSpec spec = specs.get(s);
            NoiseSpec.Perlin[] branches = { spec.first, spec.second };
            for (int b = 0; b < 2; b++) {
                NoiseSpec.Perlin p = branches[b];
                specRec[s * 4 + b * 2] = octD.size();
                specRec[s * 4 + b * 2 + 1] = p.count;
                for (int i = 0; i < p.count; i++) {
                    octD.add(new double[] { p.xo[i], p.yo[i], p.zo[i], p.amp[i], p.inputFactor[i], p.valueFactor[i] });
                    octPerm.add(permOf(p.perm[i]));
                }
            }
        }
        int nOct = octD.size();
        int nBl = blendedSpecs.size();
        oiOct = nspecs * 4;
        oiBl = oiOct + nOct;
        odOct = nspecs;
        odBl = odOct + nOct * 6;
        ofOct = nspecs;
        ofBl = ofOct + nOct * OF_STRIDE;
        oi = new int[Math.max(4, oiBl + nBl * 40)];
        od = new double[Math.max(4, odBl + nBl * 125)];
        of = new float[Math.max(4, ofBl + nBl * OF_BL_STRIDE)];
        System.arraycopy(specRec, 0, oi, 0, specRec.length);
        for (int s = 0; s < nspecs; s++) {
            od[s] = specs.get(s).valueFactor;
            of[s] = (float) specs.get(s).valueFactor;
        }
        for (int o = 0; o < nOct; o++) {
            oi[oiOct + o] = octPerm.get(o);
            double[] d = octD.get(o);
            System.arraycopy(d, 0, od, odOct + o * 6, 6);
            int k = ofOct + o * OF_STRIDE;
            of[k] = (float) d[3];
            of[k + 1] = (float) d[5];
            // the certified lattice in float-float: the input factor (a power of two, so a float exactly) and the offsets as hi + lo
            of[k + 2] = (float) d[4];
            exactPow2 &= (double) (float) d[4] == d[4];
            for (int a = 0; a < 3; a++) {
                float hi = (float) d[a];
                of[k + 3 + a * 2] = hi;
                of[k + 4 + a * 2] = (float) (d[a] - (double) hi);
            }
        }
        for (int b = 0; b < nBl; b++) {
            BlendedSpec bs = blendedSpecs.get(b);
            int k = odBl + b * 125;
            od[k] = bs.xzMultiplier;
            od[k + 1] = bs.yMultiplier;
            od[k + 2] = bs.xzFactor;
            od[k + 3] = bs.yFactor;
            od[k + 4] = bs.smearScaleMultiplier;
            int[][][] perms = { bs.mainPerm, bs.minPerm, bs.maxPerm };
            double[][][] offs = { { bs.mainXo, bs.mainYo, bs.mainZo }, { bs.minXo, bs.minYo, bs.minZo }, { bs.maxXo, bs.maxYo, bs.maxZo } };
            // the certified blended noise in float-float: vanilla's doubles as hi + lo
            int f = ofBl + b * OF_BL_STRIDE;
            double d6 = bs.yMultiplier * bs.smearScaleMultiplier;
            double[] consts = { bs.xzMultiplier, bs.yMultiplier, bs.xzFactor, bs.yFactor, d6, d6 / bs.yFactor };
            for (int a = 0; a < consts.length; a++) {
                splitFloat(consts[a], of, f + a * 2);
            }
            int i = 0;
            for (int g = 0; g < 3; g++) {
                for (int n = 0; n < perms[g].length; n++, i++) {
                    oi[oiBl + b * 40 + i] = perms[g][n] == null ? -1 : permOf(perms[g][n]);
                    od[k + 5 + i * 3] = offs[g][0][n];
                    od[k + 5 + i * 3 + 1] = offs[g][1][n];
                    od[k + 5 + i * 3 + 2] = offs[g][2][n];
                    for (int a = 0; a < 3; a++) {
                        splitFloat(offs[g][a][n], of, f + 12 + i * 6 + a * 2);
                    }
                }
            }
        }
        boolean f64 = prec == Prec.F64;
        String imp = f64 ? "imp_noise" : "imp_noise_f";
        String impY = f64 ? "imp_noiseY" : "imp_noiseY_f";
        String amp = f64 ? "o[3]" : "c->of[" + ofOct + " + i * " + OF_STRIDE + "]";
        String vf = f64 ? "o[5]" : "c->of[" + ofOct + " + i * " + OF_STRIDE + " + 1]";
        String svf = f64 ? "c->od[s]" : "c->of[s]";
        String z = lit(0.0);
        return """
            NI R perlin_sum(Ctx* c, int start, int count, double x, double y, double z) {
              R sum = ZERO;
              for (int i = start; i < start + count; i++) {
                __global const double* o = c->od + OD_OCT + i * 6;
                double f = o[4];
                R v = IMP(c->pm + c->oi[OI_OCT + i], o[0], o[1], o[2], jwrap(x * f), jwrap(y * f), jwrap(z * f));
                sum += AMP * v * VF;
              }
              return sum;
            }
            NI R nn(Ctx* c, int s, double x, double y, double z) {
              __global const int* r = c->oi + s * 4;
              R a = perlin_sum(c, r[0], r[1], x, y, z);
              double x2 = x * INF, y2 = y * INF, z2 = z * INF;
              R b = perlin_sum(c, r[2], r[3], x2, y2, z2);
              return (a + b) * SVF;
            }
            NI R bn(Ctx* c, int s, int bx, int by, int bz) {
              __global const double* k = c->od + OD_BL + s * 125;
              __global const int* p = c->oi + OI_BL + s * 40;
              double d0 = (double) bx * k[0];
              double d1 = (double) by * k[1];
              double d2 = (double) bz * k[0];
              double d3 = d0 / k[2]; double d4 = d1 / k[3]; double d5 = d2 / k[2];
              double d6 = k[1] * k[4]; double d7 = d6 / k[3];
              R d8 = ZERO, d9 = ZERO, d10 = ZERO;
              double d11 = 1.0;
              for (int i = 0; i < 8; i++) {
                if (p[i] >= 0) {
                  d10 += IMPY(c->pm + p[i], k[5 + i * 3], k[6 + i * 3], k[7 + i * 3], jwrap(d3 * d11), jwrap(d4 * d11), jwrap(d5 * d11), d7 * d11, d4 * d11) / (R) d11;
                }
                d11 /= 2.0;
              }
              R d16 = (d10 / TEN + ONE) / TWO;
              int flag1 = d16 >= 1; int flag2 = d16 <= 0;
              d11 = 1.0;
              for (int j = 0; j < 16; j++) {
                double d12 = jwrap(d0 * d11); double d13 = jwrap(d1 * d11); double d14 = jwrap(d2 * d11); double d15 = d6 * d11; double ym = d1 * d11;
                int a = 8 + j, b = 24 + j;
                if (!flag1 && p[a] >= 0) d8 += IMPY(c->pm + p[a], k[5 + a * 3], k[6 + a * 3], k[7 + a * 3], d12, d13, d14, d15, ym) / (R) d11;
                if (!flag2 && p[b] >= 0) d9 += IMPY(c->pm + p[b], k[5 + b * 3], k[6 + b * 3], k[7 + b * 3], d12, d13, d14, d15, ym) / (R) d11;
                d11 /= 2.0;
              }
              R lo = d8 / F512; R hi = d9 / F512; R r;
              if (d16 < 0) r = lo; else if (d16 > 1) r = hi; else r = lo + d16 * (hi - lo);
              return r / F128;
            }
            """.replace("ZERO", z).replace("TEN", lit(10.0)).replace("ONE", lit(1.0)).replace("TWO", lit(2.0)).replace("F512", lit(512.0)).replace("F128", lit(128.0))
            .replace("IMPY", impY).replace("IMP", imp).replace("AMP", amp).replace("SVF", svf).replace("VF", vf).replace("INF", dlit(NoiseSpec.INPUT_FACTOR))
            .replace("OD_OCT", Integer.toString(odOct)).replace("OI_OCT", Integer.toString(oiOct)).replace("OD_BL", Integer.toString(odBl))
            .replace("OI_BL", Integer.toString(oiBl));
    }

    private String noiseFunctions() {
        if (tables) {
            return tableNoiseFunctions();
        }
        StringBuilder sb = new StringBuilder();
        String R = R();
        for (int s = usedSpecs.nextSetBit(0); s >= 0; s = usedSpecs.nextSetBit(s + 1)) {
            NoiseSpec spec = specs.get(s);
            sb.append("NI ").append(R).append(" nn_").append(s).append("(Ctx* c, double x, double y, double z) {\n");
            perlin(sb, spec.first, "x", "y", "z", "a");
            sb.append("  double x2 = x * ").append(dlit(NoiseSpec.INPUT_FACTOR)).append("; double y2 = y * ").append(dlit(NoiseSpec.INPUT_FACTOR))
                .append("; double z2 = z * ").append(dlit(NoiseSpec.INPUT_FACTOR)).append(";\n");
            perlin(sb, spec.second, "x2", "y2", "z2", "b");
            sb.append("  return (a + b) * ").append(lit(spec.valueFactor)).append(";\n}\n");
        }
        for (int s = usedBlended.nextSetBit(0); s >= 0; s = usedBlended.nextSetBit(s + 1)) {
            blended(sb, s, blendedSpecs.get(s));
        }
        return sb.toString();
    }

    private void perlin(StringBuilder sb, NoiseSpec.Perlin p, String x, String y, String z, String sum) {
        String R = R();
        String fn = prec == Prec.F64 ? "imp_noise" : "imp_noise_f";
        sb.append("  ").append(R).append(" ").append(sum).append(" = ").append(lit(0.0)).append(";\n");
        for (int i = 0; i < p.count; i++) {
            double f = p.inputFactor[i];
            sb.append("  ").append(sum).append(" += ").append(lit(p.amp[i])).append(" * ").append(fn).append("(c->pm + ").append(permOf(p.perm[i])).append(", ")
                .append(dlit(p.xo[i])).append(", ").append(dlit(p.yo[i])).append(", ").append(dlit(p.zo[i])).append(", jwrap(").append(x).append(" * ").append(dlit(f))
                .append("), jwrap(").append(y).append(" * ").append(dlit(f)).append("), jwrap(").append(z).append(" * ").append(dlit(f)).append(")) * ")
                .append(lit(p.valueFactor[i])).append(";\n");
        }
    }

    /** {@code BlendedSpec.compute}, unrolled with every per-octave constant inline (powers of two are exact literals). */
    private void blended(StringBuilder sb, int s, BlendedSpec b) {
        String R = R();
        String fn = prec == Prec.F64 ? "imp_noiseY" : "imp_noiseY_f";
        sb.append("NI ").append(R).append(" bn_").append(s).append("(Ctx* c, int bx, int by, int bz) {\n");
        sb.append("  double d0 = (double) bx * ").append(dlit(b.xzMultiplier)).append(";\n");
        sb.append("  double d1 = (double) by * ").append(dlit(b.yMultiplier)).append(";\n");
        sb.append("  double d2 = (double) bz * ").append(dlit(b.xzMultiplier)).append(";\n");
        sb.append("  double d3 = d0 / ").append(dlit(b.xzFactor)).append("; double d4 = d1 / ").append(dlit(b.yFactor)).append("; double d5 = d2 / ").append(dlit(b.xzFactor)).append(";\n");
        sb.append("  double d6 = ").append(dlit(b.yMultiplier)).append(" * ").append(dlit(b.smearScaleMultiplier)).append("; double d7 = d6 / ").append(dlit(b.yFactor)).append(";\n");
        sb.append("  ").append(R).append(" d8 = ").append(lit(0.0)).append(", d9 = ").append(lit(0.0)).append(", d10 = ").append(lit(0.0)).append(";\n");
        double d11 = 1.0;
        for (int i = 0; i < 8; i++) {
            if (b.mainPerm[i] != null) {
                String k = dlit(d11);
                sb.append("  d10 += ").append(fn).append("(c->pm + ").append(permOf(b.mainPerm[i])).append(", ").append(dlit(b.mainXo[i])).append(", ").append(dlit(b.mainYo[i]))
                    .append(", ").append(dlit(b.mainZo[i])).append(", jwrap(d3 * ").append(k).append("), jwrap(d4 * ").append(k).append("), jwrap(d5 * ").append(k)
                    .append("), d7 * ").append(k).append(", d4 * ").append(k).append(") / ").append(lit(d11)).append(";\n");
            }
            d11 /= 2.0;
        }
        sb.append("  ").append(R).append(" d16 = (d10 / ").append(lit(10.0)).append(" + ").append(lit(1.0)).append(") / ").append(lit(2.0)).append(";\n");
        sb.append("  int flag1 = d16 >= 1; int flag2 = d16 <= 0;\n");
        d11 = 1.0;
        for (int j = 0; j < 16; j++) {
            String k = dlit(d11);
            boolean min = b.minPerm[j] != null;
            boolean max = b.maxPerm[j] != null;
            if (min || max) {
                sb.append("  { double d12 = jwrap(d0 * ").append(k).append("); double d13 = jwrap(d1 * ").append(k).append("); double d14 = jwrap(d2 * ").append(k)
                    .append("); double d15 = d6 * ").append(k).append("; double ym = d1 * ").append(k).append(";\n");
                if (min) {
                    sb.append("    if (!flag1) d8 += ").append(fn).append("(c->pm + ").append(permOf(b.minPerm[j])).append(", ").append(dlit(b.minXo[j])).append(", ")
                        .append(dlit(b.minYo[j])).append(", ").append(dlit(b.minZo[j])).append(", d12, d13, d14, d15, ym) / ").append(lit(d11)).append(";\n");
                }
                if (max) {
                    sb.append("    if (!flag2) d9 += ").append(fn).append("(c->pm + ").append(permOf(b.maxPerm[j])).append(", ").append(dlit(b.maxXo[j])).append(", ")
                        .append(dlit(b.maxYo[j])).append(", ").append(dlit(b.maxZo[j])).append(", d12, d13, d14, d15, ym) / ").append(lit(d11)).append(";\n");
                }
                sb.append("  }\n");
            }
            d11 /= 2.0;
        }
        sb.append("  ").append(R).append(" lo = d8 / ").append(lit(512.0)).append("; ").append(R).append(" hi = d9 / ").append(lit(512.0)).append("; ").append(R).append(" r;\n");
        sb.append("  if (d16 < 0) r = lo; else if (d16 > 1) r = hi; else r = lo + d16 * (hi - lo);\n");
        sb.append("  return r / ").append(lit(128.0)).append(";\n}\n");
    }

    // ---- splines (always float, as CubicSpline) --------------------------------------------------------------------

    /**
     * Appends the spline tables to oi and of, once. The noise tables are built by now. Splines are laid out children first. Per
     * spline, oi gets 4 ints: the coordinate's slot in cv, the point count n, the offset of the spline's floats from SPL_F, and the
     * offset of its children from SPL_I. of gets n floats of locations, n of derivatives and n of constant values. oi also gets n ints
     * of children: a child spline's index, or -1 where the value is the constant.
     */
    private void splineTables() {
        if (splOi >= 0) {
            return;
        }
        int ns = splines.size();
        int ints = 4 * ns, floats = 0;
        for (Node.SplineMulti m : splines) {
            ints += m.locations().length;
            floats += 3 * m.locations().length;
        }
        int io = oi.length, fo = of.length;
        oi = Arrays.copyOf(oi, io + ints);
        of = Arrays.copyOf(of, fo + floats);
        int ic = 4 * ns, fc = 0;
        for (int i = 0; i < ns; i++) {
            Node.SplineMulti m = splines.get(i);
            int n = m.locations().length;
            oi[io + 4 * i] = coordIndex.get(m.coordinate());
            oi[io + 4 * i + 1] = n;
            oi[io + 4 * i + 2] = fc;
            oi[io + 4 * i + 3] = ic;
            for (int k = 0; k < n; k++) {
                of[fo + fc + k] = m.locations()[k];
                of[fo + fc + n + k] = m.derivatives()[k];
                Node.SplineSpec v = m.values().get(k);
                if (v instanceof Node.SplineConst c) {
                    of[fo + fc + 2 * n + k] = c.value();
                    oi[io + ic + k] = -1;
                } else {
                    oi[io + ic + k] = splineIndex.get((Node.SplineMulti) v);
                }
            }
            fc += 3 * n;
            ic += n;
        }
        splOi = io;
        splOf = fo;
    }

    /**
     * Splines as data: vanilla's float evaluation (CubicSpline.Multipoint) on the spline tables, with one generic evaluator per depth.
     * {@code spl_d} evaluates any spline of depth d or less, and evaluates its child splines with {@code spl_(d-1)}. OpenCL has no
     * recursion.
     */
    private String splineFunctions() {
        splineTables();
        StringBuilder sb = new StringBuilder();
        sb.append("#define SPL_I ").append(splOi).append("\n#define SPL_F ").append(splOf).append('\n');
        // CubicSpline's findIntervalStart (Mth.binarySearch), with selects instead of branches
        sb.append("""
            int sfindg(__global const float* loc, int n, float f) {
              int min = 0; int i = n;
              while (i > 0) { int j = i >> 1; int k = min + j; int lt = f < loc[k]; min = lt ? min : k + 1; i = lt ? j : i - j - 1; }
              return min - 1;
            }
            """);
        for (int d = 1; d <= maxSplineDepth; d++) {
            String value = d == 1 ? "val[K]" : "(ch[K] < 0 ? val[K] : spl_" + (d - 1) + "(c, ch[K]))";
            sb.append("NI float spl_").append(d).append("(Ctx* c, int s) {\n");
            sb.append("""
                  __global const int* h = c->oi + SPL_I + 4 * s;
                  int n = h[1];
                  __global const float* loc = c->of + SPL_F + h[2];
                  __global const float* der = loc + n;
                  __global const float* val = der + n;
                  __global const int* ch = c->oi + SPL_I + h[3];
                  float f = c->cv[h[0]];
                  int i = sfindg(loc, n, f);
                  if (i < 0) return lin_ext(f, loc[0], @V0@, der[0]);
                  if (i == n - 1) return lin_ext(f, loc[n - 1], @VN@, der[n - 1]);
                  float f1 = loc[i]; float f2 = loc[i + 1];
                  float f3 = fdivx(f - f1, f2 - f1);
                  float f4 = der[i]; float f5 = der[i + 1];
                  float f6 = @VI@; float f7 = @VI1@;
                  float f8 = f4 * (f2 - f1) - (f7 - f6);
                  float f9 = -f5 * (f2 - f1) + (f7 - f6);
                  return lerpf(f3, f6, f7) + f3 * (1.0f - f3) * lerpf(f3, f8, f9);
                }
                """.replace("@V0@", value.replace("K", "0")).replace("@VN@", value.replace("K", "n - 1")).replace("@VI@", value.replace("K", "i"))
                .replace("@VI1@", value.replace("K", "i + 1")));
        }
        return sb.toString();
    }

    // ---- prelude -------------------------------------------------------------------------------------------------

    private String prelude() {
        // gradient components of SimplexNoise.GRADIENT, two bits each: 0 -> 0, 1 -> +1, 2 -> -1
        int[][] g = { { 1, 1, 0 }, { -1, 1, 0 }, { 1, -1, 0 }, { -1, -1, 0 }, { 1, 0, 1 }, { -1, 0, 1 }, { 1, 0, -1 }, { -1, 0, -1 },
            { 0, 1, 1 }, { 0, -1, 1 }, { 0, 1, -1 }, { 0, -1, -1 }, { 1, 1, 0 }, { 0, -1, 1 }, { -1, 1, 0 }, { 0, -1, -1 } };
        long[] packed = new long[3];
        for (int i = 0; i < 16; i++) {
            for (int a = 0; a < 3; a++) {
                long code = g[i][a] == 0 ? 0 : g[i][a] > 0 ? 1 : 2;
                packed[a] |= code << (2 * i);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (nonce != null) {
            sb.append("// nonce ").append(nonce).append("\n");
        }
        sb.append("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n#pragma OPENCL FP_CONTRACT OFF\n");
        sb.append(noinline ? "#define NI __attribute__((noinline))\n" : "#define NI\n");
        sb.append("typedef ").append(R()).append(" R;\n");
        sb.append("#define GXC ").append(packed[0]).append("u\n#define GYC ").append(packed[1]).append("u\n#define GZC ").append(packed[2]).append("u\n");
        sb.append(JFLOOR).append("""
            long jlfloor(double d) { long i = (long) d; return d < (double) i ? i - 1L : i; }
            double jwrap(double v) { if (v > -1.6E7 && v < 1.6E7) return v; return v - (double) jlfloor(v / 3.3554432E7 + 0.5) * 3.3554432E7; }
            double jmind(double a, double b) { if (a != a) return a; if (a == 0.0 && b == 0.0 && as_long(b) == (long) 0x8000000000000000UL) return b; return a <= b ? a : b; }
            double jmaxd(double a, double b) { if (a != a) return a; if (a == 0.0 && b == 0.0 && as_long(a) == (long) 0x8000000000000000UL) return b; return a >= b ? a : b; }
            float jminf(float a, float b) { if (a != a) return a; if (a == 0.0f && b == 0.0f && as_int(b) == (int) 0x80000000u) return b; return a <= b ? a : b; }
            float jmaxf(float a, float b) { if (a != a) return a; if (a == 0.0f && b == 0.0f && as_int(a) == (int) 0x80000000u) return b; return a >= b ? a : b; }
            """);
        if (prec == Prec.F64) {
            sb.append("#define jmin jmind\n#define jmax jmaxd\n");
        } else {
            sb.append("#define jmin jminf\n#define jmax jmaxf\n");
        }
        sb.append("""
            R jclamp(R v, R lo, R hi) { return v < lo ? lo : jmin(v, hi); }
            R jsqueeze(R v) { R d0 = jclamp(v, (R) -1.0, (R) 1.0); return d0 / (R) 2.0 - d0 * d0 * d0 / (R) 24.0; }
            double jclampedMap(double v, double a, double b, double from, double to) {
              double t = (v - a) / (b - a);
              if (t < 0.0) return from;
              return t > 1.0 ? to : from + t * (to - from);
            }
            double rarity3D(double v) { if (v < -0.5) return 0.75; else if (v < 0.0) return 1.0; else return v < 0.5 ? 1.5 : 2.0; }
            double rarity2D(double v) { if (v < -0.75) return 0.5; else if (v < -0.5) return 0.75; else if (v < 0.5) return 1.0; else return v < 0.75 ? 2.0 : 3.0; }
            double smoothd(double d) { return d * d * d * (d * (d * 6.0 - 15.0) + 10.0); }
            double lerpd(double t, double a, double b) { return a + t * (b - a); }
            float smoothf(float d) { return d * d * d * (d * (d * 6.0f - 15.0f) + 10.0f); }
            float lerpf(float t, float a, float b) { return a + t * (b - a); }
            /* g * v for a gradient component g in {-1, 0, 1}: exactly vanilla's (double) g * v, sign of zero included */
            double gmd(uint code, double v) { return code == 1u ? v : (code == 2u ? -v : copysign(0.0, v)); }
            float gmf(uint code, float v) { return code == 1u ? v : (code == 2u ? -v : copysign(0.0f, v)); }
            double gradd(int h, double x, double y, double z) {
              uint s = (uint) (h & 15) * 2u;
              return gmd((GXC >> s) & 3u, x) + gmd((GYC >> s) & 3u, y) + gmd((GZC >> s) & 3u, z);
            }
            float gradf(int h, float x, float y, float z) {
              uint s = (uint) (h & 15) * 2u;
              return gmf((GXC >> s) & 3u, x) + gmf((GYC >> s) & 3u, y) + gmf((GZC >> s) & 3u, z);
            }
            double cornersd(__global const uchar* p, int ix, int iy, int iz, double x, double y, double z, double ys) {
              int X = ix & 255, Y = iy & 255, Z = iz & 255;
              int A = p[X], B = p[X + 1];
              int AA = p[A + Y], AB = p[A + Y + 1], BA = p[B + Y], BB = p[B + Y + 1];
              double x1 = x - 1.0, y1 = y - 1.0, z1 = z - 1.0;
              double d0 = gradd(p[AA + Z], x, y, z);
              double d1 = gradd(p[BA + Z], x1, y, z);
              double d2 = gradd(p[AB + Z], x, y1, z);
              double d3 = gradd(p[BB + Z], x1, y1, z);
              double d4 = gradd(p[AA + Z + 1], x, y, z1);
              double d5 = gradd(p[BA + Z + 1], x1, y, z1);
              double d6 = gradd(p[AB + Z + 1], x, y1, z1);
              double d7 = gradd(p[BB + Z + 1], x1, y1, z1);
              double sx = smoothd(x), sy = smoothd(ys), sz = smoothd(z);
              double l0 = lerpd(sx, d0, d1), l1 = lerpd(sx, d2, d3), l2 = lerpd(sx, d4, d5), l3 = lerpd(sx, d6, d7);
              return lerpd(sz, lerpd(sy, l0, l1), lerpd(sy, l2, l3));
            }
            float cornersf(__global const uchar* p, int ix, int iy, int iz, float x, float y, float z, float ys) {
              int X = ix & 255, Y = iy & 255, Z = iz & 255;
              int A = p[X], B = p[X + 1];
              int AA = p[A + Y], AB = p[A + Y + 1], BA = p[B + Y], BB = p[B + Y + 1];
              float x1 = x - 1.0f, y1 = y - 1.0f, z1 = z - 1.0f;
              float d0 = gradf(p[AA + Z], x, y, z);
              float d1 = gradf(p[BA + Z], x1, y, z);
              float d2 = gradf(p[AB + Z], x, y1, z);
              float d3 = gradf(p[BB + Z], x1, y1, z);
              float d4 = gradf(p[AA + Z + 1], x, y, z1);
              float d5 = gradf(p[BA + Z + 1], x1, y, z1);
              float d6 = gradf(p[AB + Z + 1], x, y1, z1);
              float d7 = gradf(p[BB + Z + 1], x1, y1, z1);
              float sx = smoothf(x), sy = smoothf(ys), sz = smoothf(z);
              float l0 = lerpf(sx, d0, d1), l1 = lerpf(sx, d2, d3), l2 = lerpf(sx, d4, d5), l3 = lerpf(sx, d6, d7);
              return lerpf(sz, lerpf(sy, l0, l1), lerpf(sy, l2, l3));
            }
            double imp_noise(__global const uchar* p, double xo, double yo, double zo, double x, double y, double z) {
              double dx = x + xo, dy = y + yo, dz = z + zo;
              int ix = jfloor(dx), iy = jfloor(dy), iz = jfloor(dz);
              double fx = dx - (double) ix, fy = dy - (double) iy, fz = dz - (double) iz;
              return cornersd(p, ix, iy, iz, fx, fy, fz, fy);
            }
            float imp_noise_f(__global const uchar* p, double xo, double yo, double zo, double x, double y, double z) {
              double dx = x + xo, dy = y + yo, dz = z + zo;
              int ix = jfloor(dx), iy = jfloor(dy), iz = jfloor(dz);
              float fx = (float) (dx - (double) ix), fy = (float) (dy - (double) iy), fz = (float) (dz - (double) iz);
              return cornersf(p, ix, iy, iz, fx, fy, fz, fy);
            }
            /* a call: the blended noise asks it at three places, inlined in each it made a tenth of the program (same device time) */
            NI double imp_noiseY(__global const uchar* p, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
              double dx = x + xo, dy = y + yo, dz = z + zo;
              int ix = jfloor(dx), iy = jfloor(dy), iz = jfloor(dz);
              double fx = dx - (double) ix, fy = dy - (double) iy, fz = dz - (double) iz;
              double d6;
              if (yScale != 0.0) { double d7 = (yMax >= 0.0 && yMax < fy) ? yMax : fy; d6 = (double) jfloor(d7 / yScale + (double) 1.0E-7f) * yScale; }
              else d6 = 0.0;
              return cornersd(p, ix, iy, iz, fx, fy - d6, fz, fy);
            }
            float imp_noiseY_f(__global const uchar* p, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
              double dx = x + xo, dy = y + yo, dz = z + zo;
              int ix = jfloor(dx), iy = jfloor(dy), iz = jfloor(dz);
              double fx = dx - (double) ix, fy = dy - (double) iy, fz = dz - (double) iz;
              double d6;
              if (yScale != 0.0) { double d7 = (yMax >= 0.0 && yMax < fy) ? yMax : fy; d6 = (double) jfloor(d7 / yScale + (double) 1.0E-7f) * yScale; }
              else d6 = 0.0;
              return cornersf(p, ix, iy, iz, (float) fx, (float) (fy - d6), (float) fz, (float) fy);
            }
            int sfind(__constant const float* loc, int n, float f) {
              int min = 0; int i = n;
              while (i > 0) { int j = i / 2; int k = min + j; if (f < loc[k]) { i = j; } else { min = k + 1; i -= j + 1; } }
              return min - 1;
            }
            float lin_ext(float f, float loc, float value, float d) { return d == 0.0f ? value : value + d * (f - loc); }
            """);
        // float division: correctly rounded through double when the device has it (exact for 24-bit operands), plain otherwise
        sb.append(prec == Prec.F64
            ? "float fdivx(float a, float b) { return (float) ((double) a / (double) b); }\n"
            : "float fdivx(float a, float b) { return a / b; }\n");
        sb.append("""
            double lerp3d(double x, double y, double z, double v000, double v100, double v010, double v110, double v001, double v101, double v011, double v111) {
              return lerpd(z, lerpd(y, lerpd(x, v000, v100), lerpd(x, v010, v110)), lerpd(y, lerpd(x, v001, v101), lerpd(x, v011, v111)));
            }
            float lerp3f(float x, float y, float z, float v000, float v100, float v010, float v110, float v001, float v101, float v011, float v111) {
              return lerpf(z, lerpf(y, lerpf(x, v000, v100), lerpf(x, v010, v110)), lerpf(y, lerpf(x, v001, v101), lerpf(x, v011, v111)));
            }
            """);
        sb.append(prec == Prec.F64 ? "#define lerp3 lerp3d\n#define lerpR lerpd\n" : "#define lerp3 lerp3f\n#define lerpR lerpf\n");
        sb.append(XO).append(ExternSamplers.gpuSource());
        return sb.toString();
    }


    static final String JFLOOR = "int jfloor(double d) { int i = (int) d; return d < (double) i ? i - 1 : i; }\n";

    /**
     * XoroshiroRandomSource through XoroshiroPositionalRandomFactory.at (Mth.getSeed), with vanilla's bounded nextInt and nextFloat.
     * All arithmetic is unsigned, so Java's wrapping is defined here too.
     */
    static final String XO = """
            typedef struct { ulong lo, hi; } Xo;
            Xo xo_at(ulong seedLo, ulong seedHi, int x, int y, int z) {
              long a = (long) (int) ((uint) x * 3129871u);
              ulong i = (ulong) a ^ ((ulong) (long) z * 116129781UL) ^ (ulong) (long) y;
              ulong m = i * i * 42317861UL + i * 11UL;
              ulong s = (m >> 16) | ((m & 0x8000000000000000UL) ? 0xFFFF000000000000UL : 0UL);
              Xo r; r.lo = s ^ seedLo; r.hi = seedHi;
              if ((r.lo | r.hi) == 0UL) { r.lo = (ulong) -7046029254386353131L; r.hi = 7640891576956012809UL; }
              return r;
            }
            ulong xo_next(Xo* r) {
              ulong i = r->lo, j = r->hi;
              ulong k = rotate(i + j, (ulong) 17) + i;
              j ^= i;
              r->lo = rotate(i, (ulong) 49) ^ j ^ (j << 21);
              r->hi = rotate(j, (ulong) 28);
              return k;
            }
            int xo_nextInt(Xo* r, int bound) {
              ulong i = (ulong) (uint) xo_next(r);
              ulong j = i * (ulong) bound;
              ulong k = j & 0xFFFFFFFFUL;
              if (k < (ulong) bound) {
                uint l = (uint) (-bound) % (uint) bound;
                while (k < (ulong) l) { i = (ulong) (uint) xo_next(r); j = i * (ulong) bound; k = j & 0xFFFFFFFFUL; }
              }
              return (int) (j >> 32);
            }
            float xo_nextFloat(Xo* r) { return (float) (xo_next(r) >> 40) * 5.9604645E-8f; }
            """;

    /**
     * A double expression for {@code n} when it is built only from block coordinates, constants and double add, subtract and multiply
     * (the same operations Java does, so the same value), or null.
     */
    static String exactDouble(Node n) {
        return switch (n) {
            case Node.Const k -> dlit(k.value());
            case Node.IntToDouble i when i.value() instanceof INode.IntCoord ic ->
                "(double) c->" + (ic.axis() == 0 ? "bx" : ic.axis() == 1 ? "by" : "bz");
            case Node.StaticCall s when s.owner().equals("$op") && s.args().size() == 2 && (s.name().equals("dadd") || s.name().equals("dsub") || s.name().equals("dmul")) -> {
                String a = s.args().get(0) instanceof Node na ? exactDouble(na) : null, b = s.args().get(1) instanceof Node nb ? exactDouble(nb) : null;
                yield a == null || b == null ? null : "(" + a + (s.name().equals("dadd") ? " + " : s.name().equals("dsub") ? " - " : " * ") + b + ")";
            }
            default -> null;
        };
    }

    /** The OpenCL function of a call to an extern sampler the GPU computes ({@code ExternSamplers.GpuForm}), or null. */
    static String extern(Node.StaticCall c) {
        if (!c.owner().equals(ExternSamplers.OWNER) || c.args().size() != 4
            || !(c.args().get(0) instanceof INode.IntConst i)) {
            return null;
        }
        var form = ExternSamplers.gpu(i.value());
        if (form == null) {
            return null;
        }
        return switch (form.kind()) {
            case ExternSamplers.GpuForm.FNL_CELLULAR -> "fnl_cellular";
            case ExternSamplers.GpuForm.LITHO_CELLULAR -> "litho_cellular";
            default -> null;
        };
    }

    private final Map<Integer, String> externArgs = new LinkedHashMap<>();
    /** Per extern sampler the program computes: its ints', floats' and table's offsets in oi and of. */
    public final Map<Integer, int[]> externOffsets = new LinkedHashMap<>();
    private final Map<float[], Integer> externTablesAt = new IdentityHashMap<>();

    /**
     * The table pointers an extern's function takes: its ints and floats, then its shared table. This is a placeholder until the
     * source is done, because the noise tables are built after the root functions ({@link #resolveExterns}).
     */
    String externTables(Node.StaticCall c) {
        int index = ((INode.IntConst) c.args().get(0)).value();
        externArgs.putIfAbsent(index, null);
        return "@EXTERN" + index + "@";
    }

    /** Appends each extern's data to oi and of (the tables are final now). Replaces each extern's placeholder with its offsets. */
    private String resolveExterns(String src) {
        for (int k : externArgs.keySet()) {
            var form = ExternSamplers.gpu(k);
            int io = oi.length;
            oi = Arrays.copyOf(oi, io + form.ints().length);
            System.arraycopy(form.ints(), 0, oi, io, form.ints().length);
            int fo = of.length;
            of = Arrays.copyOf(of, fo + form.floats().length);
            System.arraycopy(form.floats(), 0, of, fo, form.floats().length);
            Integer to = externTablesAt.get(form.table());
            if (to == null) {
                to = of.length;
                of = Arrays.copyOf(of, to + form.table().length);
                System.arraycopy(form.table(), 0, of, to, form.table().length);
                externTablesAt.put(form.table(), to);
            }
            externOffsets.put(k, new int[] { io, fo, to });
            src = src.replace("@EXTERN" + k + "@", "c->oi + " + io + ", c->of + " + fo + ", c->of + " + to);
        }
        return src;
    }

    /**
     * {@code Beardifier.compute}: the pieces in list order, then the junctions, each contribution as vanilla computes it. The beard
     * kernel is vanilla's own table ({@code Math.pow} is not reproducible on a GPU). Pieces: minX, minY, minZ, maxX, maxY, maxZ,
     * terrain adjustment ordinal, ground level delta. Junctions: source x, ground y, z.
     */
    static final String BEARD = """
        double bury(double a, double b, double cz) { double d = sqrt(a * a + b * b + cz * cz); return jclampedMap(d, 0.0, 6.0, 1.0, 0.0); }
        double fast_inv_sqrt(double n) { double d0 = 0.5 * n; long i = as_long(n); i = 6910469410427058090L - (i >> 1); double x = as_double(i); return x * (1.5 - d0 * x * x); }
        /* EnhancedTerrainAdaptation.computeDensityFactor of YUNG's API (t[7] == 0) and Integrated API, over the batch's adaptation table */
        double enh_factor(__global const int* ek, __global const int* t, int dx, int dy, int dz, int yRel) {
          int size = t[0]; int rad = size / 2;
          int i = dx + rad, j = dy + rad, k = dz + rad;
          if (!(i >= 0 && i < size && j >= 0 && j < size && k >= 0 && k < size)) return 0.0;
          float kv = as_float(ek[t[1] + (k * size * size + i * size + j)]);
          double d0 = (double) yRel + 0.5;
          double d1 = (double) dx * (double) dx + d0 * d0 + (double) dz * (double) dz;
          if (t[7] == 0) {
            double d2 = fabs((d0 * fast_inv_sqrt(d1 / 2.0)) / 2.0);
            int mod = d0 > 0.0 ? t[2] : t[3];
            return (d2 * (double) kv) * (double) mod;
          }
          double d2 = ((-d0) * fast_inv_sqrt(d1 / 2.0)) / 2.0;
          if (d2 > 0.0 && !t[5]) return 0.0;
          if (d2 < 0.0 && !t[4]) return 0.0;
          return d2 * (double) kv;
        }
        double beard_c(__global const float* bk, int a, int b, int cz, int l1) {
          int i = a + 12, j = b + 12, k = cz + 12;
          if (i >= 0 && i < 24 && j >= 0 && j < 24 && k >= 0 && k < 24) {
            double d0 = (double) l1 + 0.5;
            double d1 = (double) a * (double) a + d0 * d0 + (double) cz * (double) cz;
            double d2 = -d0 * fast_inv_sqrt(d1 / 2.0) / 2.0;
            return d2 * (double) bk[k * 576 + i * 24 + j];
          }
          return 0.0;
        }
        NI double beard(Ctx* c) {
          int x = c->bx, y = c->by, z = c->bz;
          double d0 = 0.0;
          for (int p = c->bps; p < c->bps + c->bpn; p++) {
            __global const int* r = c->bp + p * 8;
            int i1 = max(0, max(r[0] - x, x - r[3]));
            int j1 = max(0, max(r[2] - z, z - r[5]));
            int k1 = r[1] + r[7];
            int l1 = y - k1;
            int adj = r[6];
            int i2 = adj == 0 ? 0 : (adj == 1 || adj == 2) ? l1 : adj == 3 ? max(0, max(k1 - y, y - r[4])) : max(0, max(r[1] - y, y - r[4]));
            double v;
            if (adj == 0) v = 0.0;
            else if (adj == 1) v = bury((double) i1, (double) i2 / 2.0, (double) j1);
            else if (adj == 2 || adj == 3) v = beard_c(c->bk, i1, i2, j1, l1) * 0.8;
            else v = bury((double) i1 / 2.0, (double) i2 / 2.0, (double) j1 / 2.0) * 0.8;
            d0 += v;
          }
          for (int q = c->bjs; q < c->bjs + c->bjn; q++) {
            __global const int* r = c->bj + q * 3;
            int j2 = x - r[0], k2 = y - r[1], l2 = z - r[2];
            d0 += beard_c(c->bk, j2, k2, l2, k2) * 0.4;
          }
          /* other mods' pieces and junctions after the vanilla pieces (beardifier.EnhancedBeard): their adaptation's kernel, scaled as
             vanilla's beard, YUNG's API with its top and bottom actions, Integrated API with its carve/beard switches and direction */
          for (int p = c->bps + c->bpn; p < c->bps + c->bpn + c->epn; p++) {
            __global const int* r = c->bp + p * 8;
            int a = r[6];
            if (a < 0) continue;
            __global const int* t = c->ek + a * 10;
            int inv = r[7] & 2;
            double v;
            if (r[7] & 1) {
              int j2 = x - r[0], k2 = inv ? r[1] - y : y - r[1], l2 = z - r[2];
              v = enh_factor(c->ek, t, j2, k2, l2, k2) * 0.4;
            } else {
              int i1 = max(0, max(r[0] - x, x - r[3]));
              int j1 = max(0, max(r[1] - y, y - r[4]));
              int k1 = max(0, max(r[2] - z, z - r[5]));
              int yRel = inv ? r[4] - y : y - r[1];
              v = enh_factor(c->ek, t, i1, j1, k1, yRel) * 0.8;
            }
            d0 += v;
            if (v != 0.0 && t[8] != 0) c->ovr = 1;
          }
          return d0;
        }
        """;


    static final String LERP3C = """
        R lerp3c(Ctx* c, int r) {
          __global const R* p = c->cr + c->cbase + r * c->rstride;
          return lerp3(c->dx, c->dy, c->dz, p[0], p[c->csx], p[1], p[c->csx + 1], p[c->csz], p[c->csx + c->csz], p[c->csz + 1], p[c->csx + c->csz + 1]);
        }
        /* an interpolator read outside the cell fill: NoiseInterpolator.updateForY, updateForX, updateForZ */
        R lerpvc(Ctx* c, int r) {
          __global const R* p = c->cr + c->cbase + r * c->rstride;
          R xz00 = lerpR(c->dy, p[0], p[1]);
          R xz10 = lerpR(c->dy, p[c->csx], p[c->csx + 1]);
          R xz01 = lerpR(c->dy, p[c->csz], p[c->csz + 1]);
          R xz11 = lerpR(c->dy, p[c->csx + c->csz], p[c->csx + c->csz + 1]);
          R z0 = lerpR(c->dx, xz00, xz10);
          R z1 = lerpR(c->dx, xz01, xz11);
          return lerpR(c->dz, z0, z1);
        }
        """;

    /**
     * YUNG's aquifer override at the end of the fill: water and lava at a block some overriding piece marked become the chunk's
     * override block ({@code bidx[li * 8 + 5]}, the mask's one override), as {@code yungsapi_dontFillMarkedPositions} does.
     */
    private static final String OVERRIDE_LINE = "  if (c->ovr && (id == ID_WATER || id == ID_LAVA)) id = bidx[li * 8 + 5];\n";

    /** The block kernels' extra argument when the program applies Better Caves' liquid regions. */
    private String lqArg() {
        return liquidRegions ? ", __global const int* lq" : "";
    }

    /**
     * Better Caves' step on the aquifer's answer, before the vein rule, as its handler runs at {@code computeSubstance}'s return: at or
     * below the chunk's liquid altitude a block the aquifer did not leave solid becomes the column's liquid (or solid where the column
     * has none), unless it is of that block already ({@code aquifer.LiquidRegions.apply}). The entry's bits say which of the
     * aquifer's answers are of the liquid's block.
     */
    private String lqLine() {
        if (!liquidRegions) {
            return "";
        }
        return "  if (lq[li * LQ_STRIDE] != LQ_NONE && id != ID_NONE && c->by <= lq[li * LQ_STRIDE]) { int lqe = lq[li * LQ_STRIDE + 1 + (lx << 4 | lz)]; int liq = lqe & 0xff;"
            + " if (liq == 0xff) id = ID_NONE; else if (!((id == ID_AIR && (lqe & 1024)) || (id == ID_WATER && (lqe & 256)) || (id == ID_LAVA && (lqe & 512)))) id = liq; }\n";
    }

    /** A lifted static call's key in the tables above, in Mojang names. The IR keeps the running game's names ({@link Names}). */
    private static String staticKey(String owner, String name, String desc) {
        return Names.mojangClass(owner) + "." + Names.mojangMethod(name) + Names.mojangDesc(desc);
    }
}
