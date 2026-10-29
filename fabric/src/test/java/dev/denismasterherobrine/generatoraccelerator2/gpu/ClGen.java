package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IR to OpenCL C for the GPU go/no-go harness. Mirrors GA2's chunk-mode code generator (the {@code COLUMN} and {@code CELL}
 * modes of {@code dfc.codegen.Emitter}) operation by operation, so the F64 output can be compared bit for bit with GA2's own
 * NoiseChunk, noise fill and biomes. The F32 variant keeps the noise lattice (coordinates, floor, fraction) in double and runs
 * everything else in float. The F32 variant is not exact and is measured against the F64 output.
 *
 * <p>Kernels, all over one rectangle of W x H chunks ("region"), so neighbouring chunks share their border corner columns:
 * <ul>
 *   <li>{@code k_flat}: every {@code flat_cache} at every quart column of the region (y = 0, as vanilla's FlatCache fills it);</li>
 *   <li>{@code k_hoist}: the x/z-only parts of the interpolated roots that sample something, once per corner column;</li>
 *   <li>{@code k_corner}: every interpolated root at every cell corner;</li>
 *   <li>{@code k_surface}: the preliminary surface level ({@code NoiseChunk.preliminarySurfaceLevel}) of every quart column the
 *   region's aquifers ask for;</li>
 *   <li>{@code k_status}: the randomized centre and the {@code FluidStatus} ({@code NoiseBasedAquifer.computeFluid}) of each of
 *   every chunk's 315 aquifer cells, with the chunk's own flat-cache view;</li>
 *   <li>{@code k_block}: per block, the final density cell cache (lerp3 of the corners, as vanilla's cell fill). With
 *   {@code -D FULL}, also the block the noise fill writes, from the aquifer's {@code computeSubstance}, the ore vein rule and the
 *   default block. The ore vein rule reads interpolators in the per-block Y, X, Z order. One byte per block. Bit 7 holds the
 *   aquifer's fluid update flag;</li>
 *   <li>{@code k_biome}: the chunk climate sampler at every quart point and the climate R-tree search (vanilla's walk, no
 *   last-result seed). Bit 31 marks a tie. In vanilla, a tie's answer depends on the searching thread's last result.</li>
 * </ul>
 */
final class ClGen {
    enum Prec { F64, F32 }

    /**
     * Evaluation contexts. FLAT: a flat cache's own fill (markers transparent). HOIST and CORNER: the corner fill (flat caches
     * read, hoisted values read in CORNER). BLOCK: the cell fill (interpolators lerp3). VEIN: a block's interpolator reads
     * outside the cell fill (Y, X, Z order). APOINT: a {@code SinglePointContext} on the chunk-wrapped router. There a flat
     * cache gives its own value only inside the chunk's quart range, and its child's value elsewhere. Every other marker is
     * transparent.
     */
    enum Mode { FLAT, HOIST, CORNER, BLOCK, VEIN, APOINT, XZBLOCK }

    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message);
        }
    }

    /** Block ids of the noise fill's output. */
    static final int ID_AIR = 0, ID_STONE = 1, ID_WATER = 2, ID_LAVA = 3, ID_COPPER_ORE = 4, ID_RAW_COPPER = 5, ID_GRANITE = 6,
        ID_DEEPSLATE_IRON_ORE = 7, ID_RAW_IRON = 8, ID_TUFF = 9;

    /** Aquifer cells per chunk, computed from the layout. 3 x 35 x 3 for the overworld's height. */
    final int cellsX = 3, cellsZ = 3;
    final int cellsY;
    final int minGridY;
    final int cellsPerChunk;

    final Prec prec;
    final boolean hoist;
    /**
     * Compile-time switches. {@code tables}: noise octaves read from data tables by one generic loop instead of unrolled code with
     * every constant inline. {@code noinline}: the large functions (noise, splines, shared values, roots, aquifer, climate search) are
     * kept as calls instead of being inlined into every kernel. {@code nonce}: a comment that makes the driver's compile cache miss.
     */
    boolean tables;
    boolean noinline;
    /** The interpolated roots read the beardifier (Terralith): each chunk gets its own corners, set by {@link #source()}. */
    boolean perChunkCorners;
    /** Some flat cache reads y: each chunk walks its own quart columns' surface levels too ({@code k_surface_in}). */
    boolean surfacePerChunk;
    String nonce;
    /** Noise tables ({@link #tables}): ints (spec records, octave and blended permutation offsets), doubles, floats. */
    int[] oi = new int[4];
    double[] od = new double[4];
    float[] of = new float[4];
    private int oiOct, oiBl, odOct, odBl, ofOct;
    final List<NoiseSpec> specs;
    final List<BlendedSpec> blendedSpecs;
    final List<Node> roots = new ArrayList<>();
    final Map<Node.Marker, Integer> lerpIndex = new IdentityHashMap<>();
    final Node cellRoot;
    final Map<String, Node> router;
    final List<Node.Marker> flats = new ArrayList<>();
    final Map<Node.Marker, Integer> flatIndex = new IdentityHashMap<>();
    final List<Node> hoisted = new ArrayList<>();
    final Map<Node, Integer> hoistIndex = new IdentityHashMap<>();
    final List<Node> blockHoisted = new ArrayList<>();
    final Map<Node, Integer> blockHoistIndex = new IdentityHashMap<>();
    private final CoordDeps deps = new CoordDeps(true);
    private final Map<Node, Boolean> expensive = new IdentityHashMap<>();

    final int cellWidth;
    final int cellHeight;
    final int cellNoiseMinY;
    final int cellCountY;
    final int minY;
    final int height;
    final int seaLevel;
    /** The router's surface level as a {@code find_top_surface}, or null for any other function. */
    SurfaceLevels.Top surfaceTop;

    /** The APOINT roots after the surface level's, in this order. Their functions are named {@code ap_<name>}. */
    static final String[] APOINT_ROOTS = { "erosion", "depth", "fluid_level_floodedness", "fluid_level_spread",
        "lava", "temperature", "vegetation", "continents", "ridges" };

    private final List<Node.SplineMulti> splines = new ArrayList<>();
    private final Map<Node.SplineMulti, Integer> splineIndex = new IdentityHashMap<>();
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

    ClGen(Prec prec, boolean hoist, List<NoiseSpec> specs, List<BlendedSpec> blendedSpecs, List<Node.Marker> rootMarkers, Node cellRoot,
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

    /** The OpenCL body that sets {@code res} to the column's preliminary surface level, as {@code ClEmitter#surfaceWalk} does. */
    private String surfaceWalk() {
        if (surfaceTop == null) {
            return "  c->by = 0; reset_APOINT_Y(c);\n  int res = jfloor((double) ap_surface_level(c));\n";
        }
        int ch = surfaceTop.cellHeight(), lb = surfaceTop.lowerBound();
        return "  c->by = 0; reset_APOINT_Y(c);\n"
            + "  int top = jfloor((double) ap_surface_upper(c) / " + ch + ".0) * " + ch + ";\n"
            + "  int res = " + lb + ";\n"
            + "  for (int y = top; top > " + lb + " && y >= " + lb + "; y -= " + ch + ") {\n"
            + "    c->by = y; reset_APOINT_Y(c);\n"
            + "    if ((double) ap_surface_density(c) > 0.0) { res = y; break; }\n"
            + "  }\n";
    }

    private Node routerRoot(String name) {
        Node n = router.get(name);
        if (n == null) {
            throw new Unsupported("router has no " + name);
        }
        return n;
    }

    /** Builds the whole program. Throws {@link Unsupported} naming the first node the GPU path does not cover. */
    String source() {
        if (source != null) {
            return source;
        }
        List<Node> blockRoots = List.of(cellRoot, routerRoot("barrier"));
        List<Node> veinRoots = List.of(routerRoot("vein_toggle"), routerRoot("vein_ridged"), routerRoot("vein_gap"));
        List<Node> apointRoots = new ArrayList<>();
        List<String> apointNames = new ArrayList<>();
        if (surfaceTop != null) {
            apointRoots.add(routerRoot(SurfaceLevels.DENSITY));
            apointNames.add("surface_density");
            apointRoots.add(routerRoot(SurfaceLevels.UPPER_BOUND));
            apointNames.add("surface_upper");
        } else {
            apointRoots.add(routerRoot(SurfaceLevels.WHOLE));
            apointNames.add("surface_level");
        }
        for (String name : APOINT_ROOTS) {
            apointRoots.add(routerRoot(name));
            apointNames.add(name);
        }
        // every flat cache any context reads (a flat cache inside another is read transparently by the flat fill)
        Map<Node, Boolean> seen = new IdentityHashMap<>();
        for (List<Node> group : List.of(roots, blockRoots, veinRoots, apointRoots)) {
            for (Node r : group) {
                collectFlats(r, seen);
            }
        }
        // the shared surface level walk evaluates flat caches' children at the column itself, as outside any chunk. When a child reads
        // y, a chunk's own quart columns are walked again per chunk. Inside the chunk's range, a flat cache gives its value at y = 0
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

        StringBuilder src = new StringBuilder();
        src.append(prelude());
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
            } Ctx;
            """);
        src.append(resets);
        src.append(LERP3C);
        src.append(BEARD);
        src.append(noiseFunctions());
        src.append(splineFunctions());
        src.append(helperProtos);
        src.append(rootProtos);
        src.append(helpers);
        src.append(rootFns);
        src.append(decisionCode());
        src.append(kernels());
        source = src.toString();
        return source;
    }

    byte[] permTable() {
        source();
        return perm.toByteArray();
    }

    int hoistCount() {
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
        // flat caches
        k.append("__kernel void k_flat(").append(COMMON_ARGS).append(") {\n");
        k.append("  int g = get_global_id(0); if (g >= nqx * nqz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bx = (qx0 + g / nqz) << 2; c->by = 0; c->bz = (qz0 + g % nqz) << 2;\n");
        k.append("  __global R* out = (__global R*) flat;\n");
        for (int i = 0; i < flats.size(); i++) {
            k.append("  out[").append(i).append(" * nqx * nqz + g] = fl_").append(i).append("(c);\n");
        }
        k.append("}\n");
        // hoisted x/z values
        int nh = Math.max(1, hoisted.size());
        k.append("__kernel void k_hoist(").append(COMMON_ARGS).append(", __global R* hz, int bx0, int bz0, int ncx, int ncz) {\n");
        k.append("  int g = get_global_id(0); if (g >= ncx * ncz) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  c->bx = bx0 + (g / ncz) * ").append(cw).append("; c->by = 0; c->bz = bz0 + (g % ncz) * ").append(cw).append(";\n");
        for (int i = 0; i < hoisted.size(); i++) {
            k.append("  hz[g * ").append(nh).append(" + ").append(i).append("] = hz_").append(i).append("(c);\n");
        }
        k.append("}\n");
        // corners
        k.append("__kernel void k_corner(").append(COMMON_ARGS).append(", __global const R* hz, __global R* corners, int bx0, int bz0, int ncx, int ncz, int ncy,"
            + " int hchunks, int nchunks, __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk) {\n");
        int per = 16 / cw;
        int pc = (per + 1) * (per + 1);
        if (!perChunkCorners) {
            k.append("  int g = get_global_id(0); if (g >= ncx * ncz * ncy) return;\n");
            k.append("  int col = g / ncy; int cy = g % ncy;\n").append(ctxInit()).append(COMMON_INIT);
            k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = 0; c->bpn = 0; c->bjs = 0; c->bjn = 0;\n");
            k.append("  c->hz = hz; c->hbase = col * ").append(nh).append(";\n");
            k.append("  c->bx = bx0 + (col / ncz) * ").append(cw).append("; c->bz = bz0 + (col % ncz) * ").append(cw)
                .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
            for (int i = 0; i < roots.size(); i++) {
                k.append("  corners[").append(i).append(" * ncx * ncz * ncy + g] = cr_").append(i).append("(c);\n");
            }
        } else {
            // the router reads the chunk's beardifier at its corners: every chunk its own corners (border corners differ by chunk)
            k.append("  int g = get_global_id(0); if (g >= nchunks * ").append(pc).append(" * ncy) return;\n");
            k.append("  int b = g / (").append(pc).append(" * ncy); int rem = g % (").append(pc).append(" * ncy); int col = rem / ncy; int cy = rem % ncy;\n");
            k.append("  int lcx = col / ").append(per + 1).append(", lcz = col % ").append(per + 1).append("; int ix = b / hchunks, iz = b % hchunks;\n");
            k.append(ctxInit()).append(COMMON_INIT);
            k.append("  c->bp = bp; c->bj = bj; c->bk = bk; c->bps = bidx[b * 4]; c->bpn = bidx[b * 4 + 1]; c->bjs = bidx[b * 4 + 2]; c->bjn = bidx[b * 4 + 3];\n");
            k.append("  c->hz = hz; c->hbase = ((ix * ").append(per).append(" + lcx) * ncz + iz * ").append(per).append(" + lcz) * ").append(nh).append(";\n");
            k.append("  c->bx = bx0 + ix * 16 + lcx * ").append(cw).append("; c->bz = bz0 + iz * 16 + lcz * ").append(cw)
                .append("; c->by = (cy + ").append(cellNoiseMinY).append(") * ").append(cellHeight).append(";\n");
            for (int i = 0; i < roots.size(); i++) {
                k.append("  corners[((b * ").append(roots.size()).append(" + ").append(i).append(") * ").append(pc).append(" + col) * ncy + cy] = cr_").append(i).append("(c);\n");
            }
        }
        k.append("}\n");
        // the block tree's x/z-only parts, once per block column
        int nb = Math.max(1, blockHoisted.size());
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
        k.append(surfaceWalk());
        k.append("  surf[g] = res;\n}\n");
        // each chunk's own 5x5 quart columns, with the chunk's flat cache view (only used when a flat cache reads y)
        k.append("__kernel void k_surface_in(").append(COMMON_ARGS).append(", __global int* surfIn, int cx0, int cz0, int hchunks, int nchunks) {\n");
        k.append("  int g = get_global_id(0); if (g >= nchunks * 25) return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / 25; int col = g % 25; int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  c->bx = (bcx * 4 + col / 5) << 2; c->bz = (bcz * 4 + col % 5) << 2;\n");
        k.append(surfaceWalk());
        k.append("  surfIn[g] = res;\n}\n");
        // aquifer cells
        k.append("__kernel void k_status(").append(COMMON_ARGS)
            .append(", __global const int* surf, __global const int* surfIn, int sqx0, int sqz0, int nsz, __global int* cells, int cx0, int cz0, int hchunks, int nchunks, ulong aqLo, ulong aqHi) {\n");
        k.append("  int g = get_global_id(0); if (g >= nchunks * ").append(cellsPerChunk).append(") return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / ").append(cellsPerChunk).append("; int ci = g % ").append(cellsPerChunk).append(";\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  int ii = ci % 3; int t = ci / 3; int kk = t % 3; int jj = t / 3;\n");
        k.append("  int gx = bcx - 1 + ii; int gy = ").append(minGridY).append(" + jj; int gz = bcz - 1 + kk;\n");
        k.append("  Xo r = xo_at(aqLo, aqHi, gx, gy, gz);\n");
        k.append("  int lx = gx * 16 + xo_nextInt(&r, 10); int ly = gy * 12 + xo_nextInt(&r, 9); int lz = gz * 16 + xo_nextInt(&r, 10);\n");
        k.append("  FS s = compute_fluid(c, lx, ly, lz, surf, surfIn, b, sqx0, sqz0, nsz);\n");
        k.append("  cells[g * 5] = lx; cells[g * 5 + 1] = ly; cells[g * 5 + 2] = lz; cells[g * 5 + 3] = s.level; cells[g * 5 + 4] = s.type;\n}\n");
        // blocks
        int h = height;
        k.append("__kernel void k_block(").append(COMMON_ARGS)
            .append(", __global const R* corners, __global uchar* out, __global R* dens, int bx0, int bz0, int nbz, int ncx, int ncz, int ncy, int count,"
                + " __global const int* cells, int cx0, int cz0, int hchunks, ulong oreLo, ulong oreHi,"
                + " __global const int* bp, __global const int* bj, __global const int* bidx, __global const float* bk, __global const R* bh) {\n");
        k.append("  int g = get_global_id(0); if (g >= count) return;\n");
        k.append("  int Y = g % ").append(h).append("; int t = g / ").append(h).append("; int Z = t % nbz; int X = t / nbz;\n");
        k.append(ctxInit()).append(COMMON_INIT);
        k.append("  { int b0 = (X >> 4) * hchunks + (Z >> 4); c->bp = bp; c->bj = bj; c->bk = bk;"
            + " c->bps = bidx[b0 * 4]; c->bpn = bidx[b0 * 4 + 1]; c->bjs = bidx[b0 * 4 + 2]; c->bjn = bidx[b0 * 4 + 3]; }\n");
        k.append("  int cellX = X / ").append(cw).append(", cellY = Y / ").append(cellHeight).append(", cellZ = Z / ").append(cw).append(";\n");
        if (!perChunkCorners) {
            k.append("  c->cr = corners; c->rstride = ncx * ncz * ncy; c->csx = ncz * ncy; c->csz = ncy; c->cbase = (cellX * ncz + cellZ) * ncy + cellY;\n");
        } else {
            k.append("  c->cr = corners; c->rstride = ").append(pc).append(" * ncy; c->csx = ").append(per + 1).append(" * ncy; c->csz = ncy;\n");
            k.append("  c->cbase = ((X >> 4) * hchunks + (Z >> 4)) * ").append(roots.size() * pc).append(" * ncy + ((cellX % ").append(per).append(") * ").append(per + 1)
                .append(" + cellZ % ").append(per).append(") * ncy + cellY;\n");
        }
        k.append("  c->dx = (R) ((double) (X % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->dy = (R) ((double) (Y % ").append(cellHeight).append(") / (double) ").append(cellHeight).append(");\n");
        k.append("  c->dz = (R) ((double) (Z % ").append(cw).append(") / (double) ").append(cw).append(");\n");
        k.append("  c->bx = bx0 + X; c->by = ").append(minY).append(" + Y; c->bz = bz0 + Z;\n");
        k.append("  c->bh = bh; c->bhbase = (X * nbz + Z) * ").append(Math.max(1, blockHoisted.size())).append(";\n");
        k.append("  R d = bk_final(c);\n");
        k.append("#ifdef WRITE_DENSITY\n  dens[g] = d;\n#endif\n");
        k.append("#ifdef FULL\n");
        k.append("  int bcx = cx0 + (X >> 4); int bcz = cz0 + (Z >> 4); int b = (X >> 4) * hchunks + (Z >> 4);\n");
        k.append("  int flag = 0;\n");
        k.append("  int id = aquifer_decide(c, (double) d, cells, b * ").append(cellsPerChunk).append(", bcx, bcz, &flag);\n");
        k.append("  if (id == ID_NONE) { id = vein_rule(c, oreLo, oreHi); if (id == ID_NONE) id = ").append(ID_STONE).append("; }\n");
        k.append("  out[g] = (uchar) (id | (flag << 7));\n");
        k.append("#else\n  out[g] = d > 0 ? 1 : 0;\n#endif\n}\n");
        // biomes
        k.append("__kernel void k_biome(").append(COMMON_ARGS)
            .append(", __global const long* tb, __global const long* toff, __global const int* tfirst, __global const int* tcount, __global const int* tleaf,"
                + " __global int* biomes, int cx0, int cz0, int hchunks, int nchunks) {\n");
        int quartsY = height / 4;
        k.append("  int g = get_global_id(0); if (g >= nchunks * ").append(16 * quartsY).append(") return;\n").append(ctxInit()).append(COMMON_INIT);
        k.append("  int b = g / ").append(16 * quartsY).append("; int r = g % ").append(16 * quartsY).append("; int qy = r / 16; int qx = (r % 16) / 4; int qz = r % 4;\n");
        k.append("  int bcx = cx0 + b / hchunks; int bcz = cz0 + b % hchunks;\n");
        k.append("  c->cqx0 = bcx * 4; c->cqz0 = bcz * 4;\n");
        k.append("  c->bx = bcx * 16 + qx * 4; c->by = ").append(minY).append(" + qy * 4; c->bz = bcz * 16 + qz * 4;\n");
        k.append("  long q0 = (long) ((float) ap_temperature(c) * 10000.0f);\n");
        k.append("  long q1 = (long) ((float) ap_vegetation(c) * 10000.0f);\n");
        k.append("  long q2 = (long) ((float) ap_continents(c) * 10000.0f);\n");
        k.append("  long q3 = (long) ((float) ap_erosion(c) * 10000.0f);\n");
        k.append("  long q4 = (long) ((float) ap_depth(c) * 10000.0f);\n");
        k.append("  long q5 = (long) ((float) ap_ridges(c) * 10000.0f);\n");
        k.append("  long best;\n  int leaf = tree_search(tb, toff, tfirst, tcount, q0, q1, q2, q3, q4, q5, &best);\n");
        k.append("  int ties = tree_ties(tb, toff, tfirst, tcount, q0, q1, q2, q3, q4, q5, best);\n");
        k.append("  biomes[g] = tleaf[leaf] | (ties > 1 ? (int) 0x80000000 : 0);\n}\n");
        k.append(packKernels());
        return k.toString();
    }

    /** Ints per section in k_pack's meta output: [0] palette size (0: all air), [1..15] block ids in palette order, [16..31] ids' counts. */
    static final int META = 32;

    /**
     * The noise fill's result in the form the chunk keeps it. Per section: vanilla's linear palette (air, then each block id in the
     * order {@code doFill} first writes it), the 4-bit packed storage, and a bitmask of the fluid update marks. Per column: the tops
     * of the two worldgen heightmaps. One work-group of 256 per section.
     */
    private String packKernels() {
        return """
            /* doFill's order inside a section: cell x, cell z, y descending, then x and z inside the cell */
            int fill_rank(int x, int y, int z) { return ((((x >> 2) * 4 + (z >> 2)) * 16 + (15 - y)) * 16) + (x & 3) * 4 + (z & 3); }
            int blocks_motion(int id) { return id != ID_AIR && id != ID_WATER && id != ID_LAVA; }
            __kernel __attribute__((reqd_work_group_size(256, 1, 1)))
            void k_pack(__global const uchar* out, int nbz, int hchunks, __global long* storage, __global int* meta, __global long* marks) {
              int grp = get_group_id(0); int lid = get_local_id(0);
              int b = grp / NSEC; int sec = grp % NSEC;
              int bx = (b / hchunks) * 16, bz = (b % hchunks) * 16;
              __local int hist[16]; __local int first[16]; __local int map[16];
              if (lid < 16) { hist[lid] = 0; first[lid] = 0x7fffffff; map[lid] = 0; }
              barrier(CLK_LOCAL_MEM_FENCE);
              for (int k = 0; k < 16; k++) {
                int i = lid * 16 + k; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int id = out[((bx + x) * nbz + bz + z) * HEIGHT + sec * 16 + y] & 0x7f;
                atomic_inc(&hist[id]);
                if (id != ID_AIR) atomic_min(&first[id], fill_rank(x, y, z));
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              if (lid == 0) {
                int n = 0; int order[16];
                for (int id = 1; id < 16; id++) if (hist[id] > 0) order[n++] = id;
                for (int a = 1; a < n; a++) { int v = order[a]; int c = a - 1; while (c >= 0 && first[order[c]] > first[v]) { order[c + 1] = order[c]; c--; } order[c + 1] = v; }
                for (int a = 0; a < n; a++) map[order[a]] = a + 1;
                __global int* m = meta + grp * 32;
                m[0] = n == 0 ? 0 : n + 1;
                for (int a = 0; a < 15; a++) m[1 + a] = a < n ? order[a] : -1;
                for (int a = 0; a < 16; a++) m[16 + a] = hist[a];
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              ulong word = 0;
              for (int j = 0; j < 16; j++) {
                int i = lid * 16 + j; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int id = out[((bx + x) * nbz + bz + z) * HEIGHT + sec * 16 + y] & 0x7f;
                word |= (ulong) map[id] << (4 * j);
              }
              storage[grp * 256 + lid] = (long) word;
              if (lid < 64) {
                ulong mk = 0;
                for (int j = 0; j < 64; j++) {
                  int i = lid * 64 + j; int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                  int g = out[((bx + x) * nbz + bz + z) * HEIGHT + sec * 16 + y];
                  int id = g & 0x7f;
                  if ((g & 0x80) && (id == ID_WATER || id == ID_LAVA)) mk |= 1UL << j;
                }
                marks[grp * 64 + lid] = (long) mk;
              }
            }
            /* per column: the highest non-air block (WORLD_SURFACE_WG) and the highest block that blocks motion (OCEAN_FLOOR_WG) */
            __kernel void k_heights(__global const uchar* out, int nbz, int hchunks, int nchunks, __global int* tops) {
              int g = get_global_id(0); if (g >= nchunks * 256) return;
              int b = g / 256; int col = g % 256; int x = col & 15, z = col >> 4;
              int bx = (b / hchunks) * 16 + x, bz = (b % hchunks) * 16 + z;
              __global const uchar* p = out + (bx * nbz + bz) * HEIGHT;
              int surf = INT_MIN, ocean = INT_MIN;
              for (int Y = HEIGHT - 1; Y >= 0; Y--) {
                int id = p[Y] & 0x7f;
                if (surf == INT_MIN && id != ID_AIR) surf = MINY_B + Y;
                if (blocks_motion(id)) { ocean = MINY_B + Y; break; }
              }
              tops[g * 2] = surf; tops[g * 2 + 1] = ocean;
            }
            """.replace("NSEC", Integer.toString(height / 16)).replace("HEIGHT", Integer.toString(height)).replace("MINY_B", Integer.toString(minY));
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
            #define MINGY %d
            #define SURF_PER_CHUNK SPC_VALUE
            typedef struct { int level; int type; } FS;
            FS picker(int y) { FS f; if (y < PICK_LAVA_Y) { f.level = -54; f.type = ID_LAVA; } else { f.level = SEA; f.type = ID_WATER; } return f; }
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
              if (level <= -10 && level != WAY_BELOW_MIN_Y && gp.type != ID_LAVA) {
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
              if ((ba == ID_LAVA && bb == ID_WATER) || (ba == ID_WATER && bb == ID_LAVA)) return 2.0;
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
              if (fs_at(picker(y), y) == ID_LAVA) { *flag = 0; return ID_LAVA; }
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
              if (bs == ID_WATER && fs_at(picker(y - 1), y - 1) == ID_LAVA) { *flag = 1; return bs; }
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
            /* vanilla's SubTree.search walk without a last-result seed (GA2's FlatClimateTree.search with last = -1) */
            NI int tree_search(__global const long* tb, __global const long* toff, __global const int* first, __global const int* count,
                            long q0, long q1, long q2, long q3, long q4, long q5, long* bestOut) {
              int stack[64];
              int sp = 0; stack[0] = 0; stack[1] = first[0];
              int best = 0; long bd = 0x7fffffffffffffffL;
              while (sp >= 0) {
                int node = stack[2 * sp], child = stack[2 * sp + 1];
                if (child == first[node] + count[node]) { sp--; continue; }
                stack[2 * sp + 1] = child + 1;
                long d = node_dist(tb, toff, child, q0, q1, q2, q3, q4, q5);
                if (bd > d) {
                  if (count[child] == 0) { bd = d; best = child; }
                  else { sp++; stack[2 * sp] = child; stack[2 * sp + 1] = first[child]; }
                }
              }
              *bestOut = bd;
              return best;
            }
            /* how many leaves lie at the best distance (more than one: vanilla's answer depends on the thread's last result) */
            NI int tree_ties(__global const long* tb, __global const long* toff, __global const int* first, __global const int* count,
                          long q0, long q1, long q2, long q3, long q4, long q5, long bd) {
              int stack[64];
              int sp = 0; stack[0] = 0; stack[1] = first[0];
              int n = 0;
              while (sp >= 0) {
                int node = stack[2 * sp], child = stack[2 * sp + 1];
                if (child == first[node] + count[node]) { sp--; continue; }
                stack[2 * sp + 1] = child + 1;
                long d = node_dist(tb, toff, child, q0, q1, q2, q3, q4, q5);
                if (d <= bd) {
                  if (count[child] == 0) { if (d == bd) n++; }
                  else { sp++; stack[2 * sp] = child; stack[2 * sp + 1] = first[child]; }
                }
              }
              return n;
            }
            """.replace("SPC_VALUE", surfacePerChunk ? "1" : "0")).formatted(ID_AIR, ID_WATER, ID_LAVA, seaLevel, Math.min(-54, seaLevel), minGridY,
                ID_RAW_COPPER, ID_RAW_IRON, ID_COPPER_ORE, ID_DEEPSLATE_IRON_ORE, ID_GRANITE, ID_TUFF);
    }

    // ---- per-function emission -------------------------------------------------------------------------------------

    private final class Fn {
        final Mode mode;
        final Set<Node> shared;
        final Map<Node, Integer> memo = new IdentityHashMap<>();
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

        /** Emits statements computing {@code n} into {@code sb}. Returns an R-typed expression (a name or a literal). */
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
                id = memo.size();
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
                        case INVERT -> lit(1.0) + " / " + v;
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
                case Node.EndIslands e -> throw new Unsupported("end islands (not ported yet)");
                case Node.Marker m -> marker(m, sb);
                case Node.Unsupported u -> throw new Unsupported("uncompiled density function " + u.vanilla().getClass().getName() + ": " + u.reason());
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
                    if (mode != Mode.FLAT && !deps.of(m.child()).isXzOrConst()) {
                        throw new Unsupported("cache_2d over a y-dependent function (order-sensitive)");
                    }
                    return ev(m.child(), sb);
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
            return tmp(sb, "(R) sp_" + id + "(c->cv)");
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
            String key = c.owner() + "." + c.name() + c.desc();
            return tmp(sb, switch (key) {
                case "java/lang/Math.abs(D)D" -> "fabs(" + args.get(0) + ")";
                case "java/lang/Math.min(DD)D" -> "jmin(" + args.get(0) + ", " + args.get(1) + ")";
                case "java/lang/Math.max(DD)D" -> "jmax(" + args.get(0) + ", " + args.get(1) + ")";
                case "java/lang/Math.floor(D)D" -> "floor(" + args.get(0) + ")";
                case "java/lang/Math.ceil(D)D" -> "ceil(" + args.get(0) + ")";
                case "java/lang/Math.sqrt(D)D" -> "sqrt(" + args.get(0) + ")";
                case "net/minecraft/util/Mth.clamp(DDD)D" -> "jclamp(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
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
                    String key = c.owner() + "." + c.name() + c.desc();
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
        for (Node.SplineSpec v : m.values()) {
            if (v instanceof Node.SplineMulti mm) {
                registerSpline(mm);
            }
        }
        id = splines.size();
        splines.add(m);
        splineIndex.put(m, id);
        return id;
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

    /**
     * {@link #tables}: every noise spec's octaves as data, and one generic loop over them. Each live octave has one record: offsets,
     * amplitude, input and value factor, permutation offset. BlendedNoise is handled the same way. The loop runs the same operations
     * in the same order as the unrolled functions, so it gives the same values.
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
        oi = new int[Math.max(4, oiBl + nBl * 40)];
        od = new double[Math.max(4, odBl + nBl * 125)];
        of = new float[Math.max(4, ofOct + nOct * 2)];
        System.arraycopy(specRec, 0, oi, 0, specRec.length);
        for (int s = 0; s < nspecs; s++) {
            od[s] = specs.get(s).valueFactor;
            of[s] = (float) specs.get(s).valueFactor;
        }
        for (int o = 0; o < nOct; o++) {
            oi[oiOct + o] = octPerm.get(o);
            System.arraycopy(octD.get(o), 0, od, odOct + o * 6, 6);
            of[ofOct + o * 2] = (float) octD.get(o)[3];
            of[ofOct + o * 2 + 1] = (float) octD.get(o)[5];
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
            int i = 0;
            for (int g = 0; g < 3; g++) {
                for (int n = 0; n < perms[g].length; n++, i++) {
                    oi[oiBl + b * 40 + i] = perms[g][n] == null ? -1 : permOf(perms[g][n]);
                    od[k + 5 + i * 3] = offs[g][0][n];
                    od[k + 5 + i * 3 + 1] = offs[g][1][n];
                    od[k + 5 + i * 3 + 2] = offs[g][2][n];
                }
            }
        }
        boolean f64 = prec == Prec.F64;
        String imp = f64 ? "imp_noise" : "imp_noise_f";
        String impY = f64 ? "imp_noiseY" : "imp_noiseY_f";
        String amp = f64 ? "o[3]" : "c->of[" + ofOct + " + i * 2]";
        String vf = f64 ? "o[5]" : "c->of[" + ofOct + " + i * 2 + 1]";
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

    private String splineFunctions() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < splines.size(); i++) {
            sb.append("float sp_").append(i).append("(const float* cv);\n");
        }
        for (int i = 0; i < splines.size(); i++) {
            Node.SplineMulti m = splines.get(i);
            int n = m.locations().length;
            int ci = coordIndex.get(m.coordinate());
            sb.append("__constant float sl_").append(i).append("[").append(n).append("] = {");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ", ").append(flit(m.locations()[k]));
            }
            sb.append("};\n__constant float sd_").append(i).append("[").append(n).append("] = {");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ", ").append(flit(m.derivatives()[k]));
            }
            sb.append("};\n");
            sb.append("NI float sv_").append(i).append("(int k, const float* cv) {\n  switch (k) {\n");
            for (int k = 0; k < n; k++) {
                Node.SplineSpec v = m.values().get(k);
                String e = v instanceof Node.SplineConst c ? flit(c.value()) : "sp_" + splineIndex.get((Node.SplineMulti) v) + "(cv)";
                sb.append("    case ").append(k).append(": return ").append(e).append(";\n");
            }
            sb.append("  }\n  return 0.0f;\n}\n");
            sb.append("NI float sp_").append(i).append("(const float* cv) {\n");
            sb.append("  float f = cv[").append(ci).append("];\n");
            sb.append("  int i = sfind(sl_").append(i).append(", ").append(n).append(", f);\n");
            sb.append("  if (i < 0) return lin_ext(f, sl_").append(i).append("[0], sv_").append(i).append("(0, cv), sd_").append(i).append("[0]);\n");
            sb.append("  if (i == ").append(n - 1).append(") return lin_ext(f, sl_").append(i).append("[").append(n - 1).append("], sv_").append(i).append("(")
                .append(n - 1).append(", cv), sd_").append(i).append("[").append(n - 1).append("]);\n");
            sb.append("  float f1 = sl_").append(i).append("[i]; float f2 = sl_").append(i).append("[i + 1];\n");
            sb.append("  float f3 = fdivx(f - f1, f2 - f1);\n");
            sb.append("  float f4 = sd_").append(i).append("[i]; float f5 = sd_").append(i).append("[i + 1];\n");
            sb.append("  float f6 = sv_").append(i).append("(i, cv); float f7 = sv_").append(i).append("(i + 1, cv);\n");
            sb.append("  float f8 = f4 * (f2 - f1) - (f7 - f6);\n");
            sb.append("  float f9 = -f5 * (f2 - f1) + (f7 - f6);\n");
            sb.append("  return lerpf(f3, f6, f7) + f3 * (1.0f - f3) * lerpf(f3, f8, f9);\n}\n");
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
        sb.append("""
            int jfloor(double d) { int i = (int) d; return d < (double) i ? i - 1 : i; }
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
            double imp_noiseY(__global const uchar* p, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
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
        // XoroshiroRandomSource through XoroshiroPositionalRandomFactory.at (Mth.getSeed), with vanilla's bounded nextInt and
        // nextFloat. All arithmetic is unsigned so Java's wrapping is defined here too
        sb.append("""
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
            """);
        return sb.toString();
    }

    /**
     * {@code Beardifier.compute}: the pieces in list order, then the junctions, each contribution as vanilla computes it. The beard
     * kernel is vanilla's own table ({@code Math.pow} is not reproducible on a GPU). Pieces: minX, minY, minZ, maxX, maxY, maxZ,
     * terrain adjustment ordinal, ground level delta. Junctions: source x, ground y, z.
     */
    static final String BEARD = """
        double bury(double a, double b, double cz) { double d = sqrt(a * a + b * b + cz * cz); return jclampedMap(d, 0.0, 6.0, 1.0, 0.0); }
        double fast_inv_sqrt(double n) { double d0 = 0.5 * n; long i = as_long(n); i = 6910469410427058090L - (i >> 1); double x = as_double(i); return x * (1.5 - d0 * x * x); }
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
}
