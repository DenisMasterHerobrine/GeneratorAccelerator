package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellCacheOnce;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.lift.StaticCalls;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits IR expressions as JVM bytecode.
 *
 * <p>Evaluation model: a node that is used more than once (or that a spline reads as a
 * coordinate) gets a scratch slot. Slots are reset to NaN at the start of an evaluation and
 * filled lazily at first use, so every node is computed at most once per evaluation and only on
 * the control-flow paths vanilla would also take. Single-use nodes are emitted inline on the
 * operand stack. All arithmetic follows the vanilla operation order exactly, with no rewrites.
 *
 * <p>Large expressions are split into private helper methods on the generated class so every
 * method stays under HotSpot's huge-method limit. Helpers share the caller's locals layout
 * through parameters.
 */
public final class Emitter {
    /** Keep generated methods comfortably under HotSpot's 8000-byte HugeMethodLimit. */
    /**
     * Method size budgets (estimated bytes) per compaction level. If a program's generated methods exceed the JIT's
     * limit, the program is generated again at the next level, with smaller methods and every root in its own helper.
     */
    private static final int[] METHOD_BUDGETS = { 5500, 2500, 1200 };
    private static final int[] SPLIT_MINS = { 900, 300, 120 };
    public static final int MAX_COMPACT = METHOD_BUDGETS.length - 1;
    private final int methodBudget;
    private final int splitMin;

    // Vanilla classes and members called by the generated code, under the running game's names ({@link Names}).
    // On Fabric these are intermediary names.
    private static final String MOJ_FUNCTION_CONTEXT = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String MOJ_DENSITY_FUNCTION = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String MOJ_BLENDER = "net/minecraft/world/level/levelgen/blending/Blender";
    private static final String FUNCTION_CONTEXT = Names.runtimeClass(MOJ_FUNCTION_CONTEXT);
    private static final String DENSITY_FUNCTION = Names.runtimeClass(MOJ_DENSITY_FUNCTION);
    private static final String BLENDER = Names.runtimeClass(MOJ_BLENDER);
    private static final String BLOCK_X = Names.runtimeMethod(MOJ_FUNCTION_CONTEXT, "blockX", "()I");
    private static final String BLOCK_Y = Names.runtimeMethod(MOJ_FUNCTION_CONTEXT, "blockY", "()I");
    private static final String BLOCK_Z = Names.runtimeMethod(MOJ_FUNCTION_CONTEXT, "blockZ", "()I");
    private static final String GET_BLENDER = Names.runtimeMethod(MOJ_FUNCTION_CONTEXT, "getBlender", "()L" + MOJ_BLENDER + ";");
    private static final String BLEND_DENSITY = Names.runtimeMethod(MOJ_BLENDER, "blendDensity", "(L" + MOJ_FUNCTION_CONTEXT + ";D)D");
    private static final String COMPUTE = Names.runtimeMethod(MOJ_DENSITY_FUNCTION, "compute", "(L" + MOJ_FUNCTION_CONTEXT + ";)D");
    private static final String NOISE_SPEC = "dev/denismasterherobrine/generatoraccelerator2/dfc/ir/NoiseSpec";
    private static final String BLENDED_SPEC = "dev/denismasterherobrine/generatoraccelerator2/dfc/ir/BlendedSpec";

    /** Local variable layout of the method being emitted. The last five are CELL-mode only (-1 otherwise). */
    public record Locals(int ctx, int scratch, int x, int y, int z, int tmp, int tmp2, int next,
                         int corners, int dx, int dy, int dz, int beard) {
        public Locals(int ctx, int scratch, int x, int y, int z, int tmp, int tmp2, int next) {
            this(ctx, scratch, x, y, z, tmp, tmp2, next, -1, -1, -1, -1, -1);
        }
    }

    public static final String HELPER_DESC = "(L" + FUNCTION_CONTEXT + ";[DIII)D";
    public static final String CELL_HELPER_DESC = "(L" + FUNCTION_CONTEXT + ";[DIII[DDDDI)D";

    /**
     * POINT: markers are transparent (RandomState-level evaluation). COLUMN: chunk-aware marker policy
     * and XZ hoisting for cell-corner fills. CELL: per-block fills where interpolated markers are
     * trilinear reads of the current cell's corners and the beardifier is skipped when culled.
     */
    public enum Mode { POINT, COLUMN, CELL }

    /** Root index of each {@code interpolated} marker in the column program (CELL mode only). */
    private Map<Node.Marker, Integer> lerpIndex = new IdentityHashMap<>();

    public void setLerpIndex(Map<Node.Marker, Integer> lerpIndex) {
        this.lerpIndex = lerpIndex;
    }

    /** First scratch slot of the per-row interpolation halves (CELL mode), or -1 to emit plain {@code lerp3}. */
    private int cellRowBase = -1;

    public void setCellRowBase(int base) {
        this.cellRowBase = base;
    }

    /** Values per column cache: one per column of the cell, indexed by {@code (x & 15) << 4 | (z & 15)}. */
    public static final int COLUMN_CACHE_SIZE = 256;

    /**
     * CELL mode: {@code cache_2d} markers whose child reads only x and z and holds no {@code cache_once}, mapped to the
     * index of their column cache. Vanilla evaluates such a child for every block of a cell. The wrapper only remembers
     * the last column, and the cell fill moves to another column at every block. The child gives the same value at every
     * y, so the program keeps one value per column of the cell column. It reuses that value in every cell above or below.
     */
    private final Map<Node.Marker, Integer> columnCaches = new IdentityHashMap<>();
    /**
     * CELL mode: the other subtrees that a cell program evaluates per block and that read only x and z, mapped to the
     * index of their column table. These tables come after the column caches' tables. Examples: noises with
     * {@code y_scale} 0, splines over them, flat cache reads. Vanilla evaluates these subtrees for every block. They give
     * the same bits at every y of a column. A noise sampled at {@code y * 0.0} gets a signed zero, which the octave's y
     * offset absorbs. So the program computes each subtree once per column of the cell column, the first time a block
     * needs it, like a column cache. Subtrees that hold a {@code cache_once} or a wrapper the program keeps stay per block.
     */
    private final Map<Node, Integer> columnValues = new IdentityHashMap<>();
    /** First scratch slot of the column tables (two key slots, then {@link #COLUMN_CACHE_SIZE} values per table), or -1. */
    private int columnCacheBase = -1;
    /** CELL mode: the nodes a cell program evaluates (the column cache walk goes exactly where emission goes). */
    private Map<Node, Boolean> walked = Map.of();

    /** Column values in cell programs; off with -Dgeneratoraccelerator2.dfc.columnValues=false (not final for the exactness test). */
    public static boolean COLUMN_VALUES = !"false".equals(System.getProperty("generatoraccelerator2.dfc.columnValues"));

    public int columnCacheCount() {
        return columnCaches.size();
    }

    public int columnValueCount() {
        return columnValues.size();
    }

    /** The column tables after the two key slots: one per column cache, then one per column value. */
    public int columnTableCount() {
        return columnCaches.size() + columnValues.size();
    }

    public void setColumnCacheBase(int base) {
        this.columnCacheBase = base;
    }

    /** First scratch slot of the splines' interval hints ({@link SplineRt}), or -1 for none. */
    private int splineHintBase = -1;
    private int splineHints;

    public void setSplineHintBase(int base) {
        this.splineHintBase = base;
    }

    public int splineHintCount() {
        return splineHints;
    }

    private final ClassWriter cw;
    private final String className;
    private final List<NoiseSpec> specs;
    private final List<BlendedSpec> blended;
    private final Mode mode;
    /** Cell programs: shared slots carry a per-block stamp instead of being reset to NaN before every block ({@link CellProgram#stamps}). */
    public boolean stampedSlots;
    private final CoordDeps deps;
    private final List<DensityFunction> externs = new ArrayList<>();
    private final Map<DensityFunction, Integer> externIndex = new IdentityHashMap<>();
    private final List<SplineRt> splines = new ArrayList<>();
    private final Map<Node, Integer> slot = new IdentityHashMap<>();
    private final Map<Node, Integer> size = new IdentityHashMap<>();
    private final Map<Node, String> helper = new IdentityHashMap<>();
    private final Map<Node, Integer> splineIndex = new IdentityHashMap<>();
    private final List<Node.Marker> statefulMarkers = new ArrayList<>();
    /** Column kernel states (COLUMN mode): one per 3D noise leaf, with the hoisted slot that flags "prepared for this column". */
    private final List<StateDesc> states = new ArrayList<>();
    private final Map<Node, Integer> stateIndex = new IdentityHashMap<>();
    /** Shared nodes with an unconditional use: computed up front in the prologue, read back without the NaN check. */
    private final Map<Node, Boolean> eager = new IdentityHashMap<>();
    private final List<Node> eagerHoisted = new ArrayList<>();
    private final List<Node> eagerPerPoint = new ArrayList<>();
    private int slotCount;
    private int hoistEnd;
    private int helperCount;

    public StateDesc[] stateDescriptors() {
        return states.toArray(new StateDesc[0]);
    }

    public Emitter(ClassWriter cw, String className, List<NoiseSpec> specs, List<BlendedSpec> blended, Mode mode, CoordDeps deps) {
        this(cw, className, specs, blended, mode, deps, 0);
    }

    public Emitter(ClassWriter cw, String className, List<NoiseSpec> specs, List<BlendedSpec> blended, Mode mode, CoordDeps deps, int compact) {
        this.methodBudget = METHOD_BUDGETS[compact];
        this.splitMin = SPLIT_MINS[compact];
        this.cw = cw;
        this.className = className;
        this.specs = specs;
        this.blended = blended;
        this.mode = mode;
        this.deps = deps;
    }

    public int slotCount() {
        return slotCount;
    }

    /** Slots {@code [0, hoistEnd)} hold values that depend on (x, z) only and survive the whole column. */
    public int hoistEnd() {
        return hoistEnd;
    }

    /** Markers that had to stay opaque chunk state (y-dependent cache_2d, nested interpolated, nested cell caches). */
    public List<Node.Marker> statefulMarkers() {
        return statefulMarkers;
    }

    public List<DensityFunction> externs() {
        return externs;
    }

    public List<SplineRt> splines() {
        return splines;
    }

    public Integer slotOf(Node n) {
        return slot.get(n);
    }

    /** Assigns scratch slots to shared nodes and spline coordinates, and plans helper methods. */
    public void plan(List<Node> roots) {
        RefCount rc = RefCount.of(roots);
        if (mode == Mode.CELL) {
            Map<Node, Boolean> seen = new IdentityHashMap<>();
            for (Node r : roots) {
                collectColumnCaches(r, seen);
            }
            walked = seen;
            if (COLUMN_VALUES) {
                Map<Node, Boolean> perBlock = new IdentityHashMap<>();
                for (Node r : roots) {
                    collectColumnValues(r, perBlock);
                }
            }
        }
        if (mode == Mode.COLUMN) {
            // Hoisted slots first: anything (x,z)-only that is shared or worth caching across the 49 corners,
            // plus one "prepared" flag slot per column kernel state.
            for (Node n : rc.postOrder()) {
                if (n instanceof Node.Const) {
                    continue;
                }
                boolean shared = rc.count(n) > 1;
                if (deps.of(n).isXzOrConst() && (shared || expensive(n))) {
                    allocSlot(n);
                }
                if (columnKernelCandidate(n)) {
                    boolean blendedKind = n instanceof Node.Blended;
                    int spec;
                    if (n instanceof Node.Noise x) {
                        spec = x.spec();
                    } else if (n instanceof Node.ShiftedNoise x) {
                        spec = x.spec();
                    } else if (n instanceof Node.NoiseAt x) {
                        spec = x.spec();
                    } else if (n instanceof Node.Blended x) {
                        spec = x.spec();
                    } else {
                        throw new IllegalStateException();
                    }
                    stateIndex.put(n, states.size());
                    states.add(new StateDesc(blendedKind, spec, slotCount++));
                }
            }
            hoistEnd = slotCount;
        }
        for (Node n : rc.postOrder()) {
            boolean shared = rc.count(n) > 1 && !(n instanceof Node.Const);
            if (shared) {
                allocSlot(n);
            }
            if (n instanceof Node.Spline s) {
                reserveSplineCoords(s.spline());
            }
        }
        // Shared nodes that are always needed are computed eagerly in dependency order and read back without checks.
        Map<Node, Boolean> unconditional = new IdentityHashMap<>();
        for (Node r : roots) {
            markUnconditional(r, unconditional, new IdentityHashMap<>());
        }
        for (Node n : rc.postOrder()) {
            if (slot.containsKey(n) && unconditional.containsKey(n) && !(n instanceof Node.Const)) {
                eager.put(n, Boolean.TRUE);
                if (mode == Mode.COLUMN && slot.get(n) < hoistEnd) {
                    eagerHoisted.add(n);
                } else {
                    eagerPerPoint.add(n);
                }
            }
        }
        // Bottom-up: size every subtree with current slot/helper knowledge and extract helpers where needed.
        for (Node n : rc.postOrder()) {
            planSplits(n);
        }
    }

    /**
     * Collects the {@code cache_2d} markers that a cell program evaluates and whose child reads only x and z and holds no
     * {@code cache_once}. Markers below the markers the program reads are skipped. The read markers are interpolated
     * corners, flat and cell caches, and wrappers the program keeps.
     */
    private void collectColumnCaches(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (n instanceof Node.Marker m) {
            switch (m.type()) {
                case CACHE_ONCE -> collectColumnCaches(m.child(), seen);
                case CACHE_2D -> {
                    // a cache_once below would be reached point by point when vanilla asks the wrapper, one value per cell
                    if (!(m.child() instanceof Node.Const) && deps.of(m.child()).isXzOrConst() && !CellCacheOnce.reachesCacheOnce(m.child())) {
                        columnCaches.putIfAbsent(m, columnCaches.size());
                        collectColumnCaches(m.child(), seen);
                    }
                }
                case INTERPOLATED, FLAT_CACHE, CACHE_ALL_IN_CELL -> {
                }
            }
            return;
        }
        for (Node c : RefCount.children(n)) {
            collectColumnCaches(c, seen);
        }
    }

    /**
     * Collects the column values. The walk goes down from the roots through what a block evaluates. On each path, the
     * first subtree that reads only x and z and is worth a table becomes a column value. Below a column value or a column
     * cache, everything is evaluated once per column, so the walk stops there.
     */
    private void collectColumnValues(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (columnValueCandidate(n)) {
            columnValues.put(n, columnCaches.size() + columnValues.size());
            return;
        }
        if (n instanceof Node.Marker m) {
            if (m.type() == Node.MarkerType.CACHE_ONCE) {
                collectColumnValues(m.child(), seen);
            }
            // Column caches compute their child once per column. The program does not evaluate wrappers or reads
            // (corners, flat and cell caches).
            return;
        }
        for (Node c : RefCount.children(n)) {
            collectColumnValues(c, seen);
        }
    }

    private boolean columnValueCandidate(Node n) {
        if (n instanceof Node.Const || !deps.of(n).isXzOrConst()) {
            return false;
        }
        if (n instanceof Node.Marker m && m.type() == Node.MarkerType.CACHE_2D) {
            return false; // a column cache already, or a constant, or a wrapper kept for its cache_once
        }
        return columnWorth(n) && columnSafe(n);
    }

    private final Map<Node, Boolean> columnWorthMemo = new IdentityHashMap<>();

    /** True when computing the subtree samples something (noise, spline, flat cache, a modded sampler) beyond column caches. */
    private boolean columnWorth(Node n) {
        Boolean known = columnWorthMemo.get(n);
        if (known != null) {
            return known;
        }
        boolean worth;
        if (n instanceof Node.Noise x) {
            worth = true;
        } else if (n instanceof Node.ShiftedNoise x) {
            worth = true;
        } else if (n instanceof Node.ShiftA x) {
            worth = true;
        } else if (n instanceof Node.ShiftB x) {
            worth = true;
        } else if (n instanceof Node.NoiseAt x) {
            worth = true;
        } else if (n instanceof Node.EndIslands x) {
            worth = true;
        } else if (n instanceof Node.Spline x) {
            worth = true;
        } else if (n instanceof Node.StaticCall c && !c.owner().equals("$op")) {
            worth = true;
        } else if (n instanceof Node.Marker m) {
            worth = switch (m.type()) {
            case FLAT_CACHE -> true;
            case CACHE_ONCE -> columnWorth(m.child());
            case CACHE_2D, INTERPOLATED, CACHE_ALL_IN_CELL -> false;
        };
        } else {
            boolean any = false;
            for (Node c : RefCount.children(n)) {
                if (columnWorth(c)) {
                    any = true;
                    break;
                }
            }
            worth = any;
        }
        columnWorthMemo.put(n, worth);
        return worth;
    }

    private final Map<Node, Boolean> columnSafeMemo = new IdentityHashMap<>();

    /**
     * True when the subtree is a pure function of the block position, as the cell program evaluates it. The subtree holds
     * no {@code cache_once}, no wrapper the program keeps, and no extern call. A {@code cache_once} value depends on how
     * vanilla reaches it.
     */
    private boolean columnSafe(Node n) {
        Boolean known = columnSafeMemo.get(n);
        if (known != null) {
            return known;
        }
        boolean safe;
        if (n instanceof Node.Marker m) {
            safe = switch (m.type()) {
            case FLAT_CACHE -> true;   // an array read of the chunk's flat cache inside the chunk
            case CACHE_2D -> m.child() instanceof Node.Const || columnCaches.containsKey(m);
            case CACHE_ONCE, INTERPOLATED, CACHE_ALL_IN_CELL -> false;
        };
        } else if (n instanceof Node.Const x) {
            // pure arithmetic, samplers and lifted calls: safe when everything below is
            safe = true;
        } else if (n instanceof Node.Ap2 x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.MulOrAdd x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.Mapped x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.Clamp x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.RangeChoice x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.Noise x) {
            safe = true;
        } else if (n instanceof Node.ShiftedNoise x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.ShiftA x) {
            safe = true;
        } else if (n instanceof Node.ShiftB x) {
            safe = true;
        } else if (n instanceof Node.Spline x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.EndIslands x) {
            safe = true;
        } else if (n instanceof Node.Select x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.StaticCall x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.NoiseAt x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.IntToDouble x) {
            safe = childrenSafe(n);
        } else if (n instanceof Node.RoundFloat x) {
            safe = childrenSafe(n);
        } else {
            // anything else (externs, the beardifier, blending, y readers, node types added later) stays per block
            safe = false;
        }
        columnSafeMemo.put(n, safe);
        return safe;
    }

    private boolean childrenSafe(Node n) {
        for (Node c : RefCount.children(n)) {
            if (!columnSafe(c)) {
                return false;
            }
        }
        return true;
    }

    /** 3D noise leaves whose x and z inputs depend on (x,z) only can use the column kernel. */
    private boolean columnKernelCandidate(Node n) {
        if (n instanceof Node.Noise x) {
            return x.yScale() != 0.0;
        } else if (n instanceof Node.ShiftedNoise x) {
            return deps.of(x.shiftX()).isXzOrConst() && deps.of(x.shiftZ()).isXzOrConst() && !deps.of(n).isXzOrConst();
        } else if (n instanceof Node.NoiseAt x) {
            return deps.of(x.x()).isXzOrConst() && deps.of(x.z()).isXzOrConst() && !deps.of(n).isXzOrConst();
        } else if (n instanceof Node.Blended x) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Marks every node reachable from a root without passing through a conditional edge (the lazily
     * evaluated operand of MUL/MIN/MAX, range-choice and select arms, the culled beardifier). Such a
     * node is evaluated on every vanilla path, so computing it up front changes nothing.
     */
    private void markUnconditional(Node n, Map<Node, Boolean> out, Map<Node, Boolean> visiting) {
        if (out.containsKey(n) || visiting.containsKey(n)) {
            return;
        }
        visiting.put(n, Boolean.TRUE);
        out.put(n, Boolean.TRUE);
        if (columnValues.containsKey(n)) {
            // Computed once per column, the first time a block needs it. Nothing below it is evaluated per block.
            visiting.remove(n);
            return;
        }
        if (n instanceof Node.Ap2 a) {
            markUnconditional(a.a(), out, visiting);
            if (a.type() == Node.Ap2Type.ADD) {
                markUnconditional(a.b(), out, visiting);
            } else if (mode != Mode.POINT && (a.type() == Node.Ap2Type.MUL) && (a.a() instanceof Node.Const || a.b() instanceof Node.Const)) {
                markUnconditional(a.b(), out, visiting); // folded MulOrAdd form has no short-circuit
            }
        } else if (n instanceof Node.RangeChoice r) {
            markUnconditional(r.input(), out, visiting);
        } else if (n instanceof Node.Select s) {
            if (s.cond().kind() != Node.CmpKind.ICMP) {
                markUnconditional(s.cond().a(), out, visiting);
                markUnconditional(s.cond().b(), out, visiting);
            }
        } else if (n instanceof Node.Marker m) {
            boolean transparent = mode == Mode.POINT || m.type() == Node.MarkerType.CACHE_ONCE
                || (m.type() == Node.MarkerType.CACHE_2D && deps.of(m.child()).isXzOrConst() && mode == Mode.COLUMN);
            if (transparent) {
                markUnconditional(m.child(), out, visiting);
            }
        } else if (n instanceof Node.Spline s) {
            markSplineCoords(s.spline(), out, visiting);
        } else {
            for (Node c : RefCount.children(n)) {
                markUnconditional(c, out, visiting);
            }
        }
        visiting.remove(n);
    }

    private void markSplineCoords(Node.SplineSpec s, Map<Node, Boolean> out, Map<Node, Boolean> visiting) {
        if (s instanceof Node.SplineMulti m) {
            markUnconditional(m.coordinate(), out, visiting); // every coordinate is ensured before the spline runs
            for (Node.SplineSpec v : m.values()) {
                markSplineCoords(v, out, visiting);
            }
        }
    }

    /** Computes the eager nodes of one phase into their slots. Call after the matching reset and before any root. */
    public void emitPrologue(MethodVisitor mv, Locals l, boolean hoistedPhase) {
        for (Node n : hoistedPhase ? eagerHoisted : eagerPerPoint) {
            emitCompute(mv, l, n);
            mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
            mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
            pushInt(mv, slot.get(n));
            mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
            mv.visitInsn(Opcodes.DASTORE);
        }
    }

    private void emitCompute(MethodVisitor mv, Locals l, Node n) {
        if (columnValues.containsKey(n)) {
            emitColumnValue(mv, l, n);
            return;
        }
        String h = helper.get(n);
        if (h != null) {
            emitHelperCall(mv, l, h);
        } else {
            emitInline(mv, l, n);
        }
    }

    private final Map<Node, Boolean> expensiveMemo = new IdentityHashMap<>();

    /** True when evaluating the node involves a noise, spline, blended noise or extern call. */
    private boolean expensive(Node n) {
        Boolean m = expensiveMemo.get(n);
        if (m != null) {
            return m;
        }
        boolean e;
        if (n instanceof Node.Noise x) {
            e = true;
        } else if (n instanceof Node.ShiftedNoise x) {
            e = true;
        } else if (n instanceof Node.ShiftA x) {
            e = true;
        } else if (n instanceof Node.ShiftB x) {
            e = true;
        } else if (n instanceof Node.Shift x) {
            e = true;
        } else if (n instanceof Node.Weird x) {
            e = true;
        } else if (n instanceof Node.Spline x) {
            e = true;
        } else if (n instanceof Node.Blended x) {
            e = true;
        } else if (n instanceof Node.BlendDensity x) {
            e = true;
        } else if (n instanceof Node.BlendAlpha x) {
            e = true;
        } else if (n instanceof Node.BlendOffset x) {
            e = true;
        } else if (n instanceof Node.Beardifier x) {
            e = true;
        } else if (n instanceof Node.EndIslands x) {
            e = true;
        } else if (n instanceof Node.Unsupported x) {
            e = true;
        } else if (n instanceof Node.NoiseAt x) {
            e = true;
        } else if (n instanceof Node.Marker x) {
            e = mode != Mode.POINT && x.type() != Node.MarkerType.CACHE_ONCE || expensive(x.child());
        } else {
            boolean any = false;
            for (Node c : RefCount.children(n)) {
                any |= expensive(c);
            }
            e = any;
        }
        expensiveMemo.put(n, e);
        return e;
    }

    private int childCost(Node c) {
        if (helper.containsKey(c)) {
            return 12;
        }
        if (slot.containsKey(c)) {
            return 30;
        }
        return size.getOrDefault(c, 0);
    }

    private int total(Node n) {
        int t = ownSize(n) + (columnValues.containsKey(n) ? 40 : 0);
        for (Node c : RefCount.children(n)) {
            t += childCost(c);
        }
        return t;
    }

    /** Post-order visitor: children are already planned when this runs. */
    private void planSplits(Node n) {
        int t = total(n);
        if (t > methodBudget) {
            List<Node> kids = new ArrayList<>(RefCount.children(n));
            kids.sort((a, b) -> Integer.compare(childCost(b), childCost(a)));
            for (Node k : kids) {
                if (t <= methodBudget) {
                    break;
                }
                int kc = childCost(k);
                if (kc >= splitMin && !helper.containsKey(k)) {
                    helper.put(k, "h" + helperCount++);
                    t -= kc - 12;
                }
            }
        }
        size.put(n, t);
        // Shared subtrees of non-trivial size get their own method so no first-use site inflates a caller.
        if (slot.containsKey(n) && t >= splitMin && !helper.containsKey(n)) {
            helper.put(n, "h" + helperCount++);
        }
    }

    /** Forces a node to live in a scratch slot (used by loop templates for hoisted values). */
    public int allocSlot(Node n) {
        Integer s = slot.get(n);
        if (s == null) {
            s = slotCount++;
            slot.put(n, s);
        }
        return s;
    }

    private void reserveSplineCoords(Node.SplineSpec s) {
        if (s instanceof Node.SplineMulti m) {
            allocSlot(m.coordinate());
            for (Node.SplineSpec v : m.values()) {
                reserveSplineCoords(v);
            }
        }
    }

    private int ownSize(Node n) {
        if (n instanceof Node.Const c) {
            return 3;
        } else if (n instanceof Node.Ap2 a) {
            return a.type() == Node.Ap2Type.ADD ? 1 : 20;
        } else if (n instanceof Node.MulOrAdd m) {
            return 4;
        } else if (n instanceof Node.Mapped m) {
            return 4;
        } else if (n instanceof Node.Clamp c) {
            return 10;
        } else if (n instanceof Node.RangeChoice r) {
            return 30;
        } else if (n instanceof Node.YGradient y) {
            return 24;
        } else if (n instanceof Node.Noise no) {
            return 30;
        } else if (n instanceof Node.ShiftedNoise s) {
            return 36;
        } else if (n instanceof Node.ShiftA s) {
            return 24;
        } else if (n instanceof Node.ShiftB s) {
            return 24;
        } else if (n instanceof Node.Shift s) {
            return 28;
        } else if (n instanceof Node.Weird w) {
            return 50;
        } else if (n instanceof Node.Spline s) {
            return 14 + 6 * splineCoords(s.spline()).size();
        } else if (n instanceof Node.Blended b) {
            return 14;
        } else if (n instanceof Node.BlendDensity b) {
            return 12;
        } else if (n instanceof Node.BlendAlpha a) {
            return 3;
        } else if (n instanceof Node.BlendOffset o) {
            return 3;
        } else if (n instanceof Node.Beardifier b) {
            return 12;
        } else if (n instanceof Node.EndIslands e) {
            return 12;
        } else if (n instanceof Node.Marker m) {
            return columnCaches.containsKey(m) ? 40 : 0;
        } else if (n instanceof Node.Unsupported u) {
            return 12;
        } else if (n instanceof Node.Select s) {
            return 20;
        } else if (n instanceof Node.StaticCall c) {
            return 6 + 4 * c.args().size();
        } else if (n instanceof Node.NoiseAt na) {
            return 14;
        } else if (n instanceof Node.IntToDouble i) {
            return 6;
        } else if (n instanceof Node.RoundFloat r) {
            return 2;
        } else {
            throw new IllegalStateException(String.valueOf(n));
        }
    }

    /** Puts each of {@code roots} in a helper method of its own, so a method computing several roots stays small. */
    public void forceHelpers(List<Node> roots) {
        for (Node n : roots) {
            if (!(n instanceof Node.Const) && !helper.containsKey(n)) {
                helper.put(n, "h" + helperCount++);
            }
        }
    }

    /** Emits all planned helper methods. Call once after the main method is emitted. */
    public void emitHelpers() {
        for (Map.Entry<Node, String> e : helper.entrySet()) {
            boolean cell = mode == Mode.CELL;
            if (cell && !walked.containsKey(e.getKey())) {
                // Under a marker the cell only reads (interpolated corners, flat and cell caches, kept wrappers).
                // The helper is never called.
                continue;
            }
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, e.getValue(), cell ? CELL_HELPER_DESC : HELPER_DESC, null, null);
            mv.visitCode();
            // helper locals: 0 this, 1 ctx, 2 scratch, 3 x, 4 y, 5 z [, 6 corners, 7-8 dx, 9-10 dy, 11-12 dz, 13 beard]
            Locals l = cell
                ? new Locals(1, 2, 3, 4, 5, 14, 16, 18, 6, 7, 9, 11, 13)
                : new Locals(1, 2, 3, 4, 5, 6, 8, 10);
            emitInline(mv, l, e.getKey());
            mv.visitInsn(Opcodes.DRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    public int helperCount() {
        return helper.size();
    }

    /** Loads the three block coordinates from the context into the int locals. */
    public static void loadCoords(MethodVisitor mv, Locals l) {
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT, BLOCK_X, "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, l.x());
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT, BLOCK_Y, "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, l.y());
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT, BLOCK_Z, "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, l.z());
    }

    /** Resets skip the slots that the next prologue phase writes first. Off with -Dgeneratoraccelerator2.lazyReset=false. */
    static final boolean LAZY_RESET = !"false".equals(System.getProperty("generatoraccelerator2.lazyReset"));

    /**
     * Emits the "not computed" (NaN) reset of slots {@code [from, to)} ahead of {@link #emitPrologue} for the given phase.
     * The prologue writes the slots of that phase's eager nodes before anything can read them. Eager values are read back
     * without a check, so only the other slots need the marker. Short runs are stored inline.
     */
    public void emitResetBeforePrologue(MethodVisitor mv, Locals l, int from, int to, boolean hoistedPhase) {
        if (!LAZY_RESET || to <= from) {
            emitReset(mv, l, from, to);
            return;
        }
        boolean[] written = new boolean[to - from];
        for (Node n : hoistedPhase ? eagerHoisted : eagerPerPoint) {
            int s = slot.get(n);
            if (s >= from && s < to) {
                written[s - from] = true;
            }
        }
        int i = from;
        while (i < to) {
            if (written[i - from]) {
                i++;
                continue;
            }
            int j = i;
            while (j < to && !written[j - from]) {
                j++;
            }
            if (j - i <= 4) {
                for (int s = i; s < j; s++) {
                    mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
                    pushInt(mv, s);
                    mv.visitLdcInsn(Double.NaN);
                    mv.visitInsn(Opcodes.DASTORE);
                }
            } else {
                emitReset(mv, l, i, j);
            }
            i = j;
        }
    }

    /** Emits {@code Ops.reset(scratch, from..count)} for the slot range {@code [from, to)}. */
    public static void emitReset(MethodVisitor mv, Locals l, int from, int to) {
        if (to <= from) {
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, from);
        pushInt(mv, to);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, "resetRange", "([DII)V", false);
    }

    /** Emits code leaving the node's double value on the operand stack. */
    public void emit(MethodVisitor mv, Locals l, Node n) {
        Integer s = slot.get(n);
        if (s != null) {
            if (eager.containsKey(n)) {
                mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
                pushInt(mv, s);
                mv.visitInsn(Opcodes.DALOAD);
                return;
            }
            emitSharedUse(mv, l, n, s);
            return;
        }
        if (columnValues.containsKey(n)) {
            emitColumnValue(mv, l, n);
            return;
        }
        String h = helper.get(n);
        if (h != null) {
            emitHelperCall(mv, l, h);
            return;
        }
        emitInline(mv, l, n);
    }

    private void emitHelperCall(MethodVisitor mv, Locals l, String name) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        mv.visitVarInsn(Opcodes.ILOAD, l.x());
        mv.visitVarInsn(Opcodes.ILOAD, l.y());
        mv.visitVarInsn(Opcodes.ILOAD, l.z());
        if (mode == Mode.CELL) {
            mv.visitVarInsn(Opcodes.ALOAD, l.corners());
            mv.visitVarInsn(Opcodes.DLOAD, l.dx());
            mv.visitVarInsn(Opcodes.DLOAD, l.dy());
            mv.visitVarInsn(Opcodes.DLOAD, l.dz());
            mv.visitVarInsn(Opcodes.ILOAD, l.beard());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, name, CELL_HELPER_DESC, false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, name, HELPER_DESC, false);
        }
    }

    private void emitSharedUse(MethodVisitor mv, Locals l, Node n, int s) {
        if (stampedSlots) {
            emitStampedUse(mv, l, n, s);
            return;
        }
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, s);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFEQ, done); // value == value: already computed
        mv.visitInsn(Opcodes.POP2);
        emitCompute(mv, l, n);
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, s);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitLabel(done);
    }

    /**
     * Emits a use of a shared slot in a cell program ({@link #stampedSlots}). The slot holds the current block's value
     * when the slot's stamp equals the program's epoch. Otherwise the value is computed and stamped. The values match the
     * NaN check. The NaN check needed every per-block slot filled with NaN before every block, and it recomputed a node
     * whose value was NaN.
     */
    private void emitStampedUse(MethodVisitor mv, Locals l, Node n, int s) {
        Label compute = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CellProgram.INTERNAL, "stamps", "[I");
        pushInt(mv, s);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CellProgram.INTERNAL, "epoch", "I");
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, compute);
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, s);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(compute);
        emitCompute(mv, l, n);
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, s);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CellProgram.INTERNAL, "stamps", "[I");
        pushInt(mv, s);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CellProgram.INTERNAL, "epoch", "I");
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitLabel(done);
    }

    /** Ensures a shared node is computed into its slot (value discarded). */
    public void emitEnsure(MethodVisitor mv, Locals l, Node n) {
        emit(mv, l, n);
        mv.visitInsn(Opcodes.POP2);
    }

    private void emitInline(MethodVisitor mv, Locals l, Node n) {
        if (n instanceof Node.Const c) {
            mv.visitLdcInsn(c.value());
        } else if (n instanceof Node.Ap2 a) {
            emitAp2(mv, l, a);
        } else if (n instanceof Node.MulOrAdd m) {
            emit(mv, l, m.input());
            mv.visitLdcInsn(m.argument());
            mv.visitInsn(m.mul() ? Opcodes.DMUL : Opcodes.DADD);
        } else if (n instanceof Node.Mapped m) {
            emit(mv, l, m.input());
            String name = switch (m.type()) {
                case ABS -> "abs";
                case SQUARE -> "square";
                case CUBE -> "cube";
                case HALF_NEGATIVE -> "halfNegative";
                case QUARTER_NEGATIVE -> "quarterNegative";
                case SQUEEZE -> "squeeze";
            };
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, name, "(D)D", false);
        } else if (n instanceof Node.Clamp c) {
            emit(mv, l, c.input());
            mv.visitLdcInsn(c.lo());
            mv.visitLdcInsn(c.hi());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, "clamp", "(DDD)D", false);
        } else if (n instanceof Node.RangeChoice r) {
            emitRangeChoice(mv, l, r);
        } else if (n instanceof Node.YGradient y) {
            mv.visitVarInsn(Opcodes.ILOAD, l.y());
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn((double) y.fromY());
            mv.visitLdcInsn((double) y.toY());
            mv.visitLdcInsn(y.fromValue());
            mv.visitLdcInsn(y.toValue());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, "clampedMap", "(DDDDD)D", false);
        } else if (n instanceof Node.Noise no) {
            Integer st = stateIndex.get(no);
            if (st != null) {
                emitColumnState(mv, l, states.get(st), st,
                    () -> coord(mv, l.x(), no.xzScale()), () -> coord(mv, l.z(), no.xzScale()), () -> coord(mv, l.y(), no.yScale()));
                return;
            }
            loadSpec(mv, no.spec());
            coord(mv, l.x(), no.xzScale());
            coord(mv, l.y(), no.yScale());
            coord(mv, l.z(), no.xzScale());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
        } else if (n instanceof Node.ShiftedNoise s) {
            Integer st = stateIndex.get(s);
            if (st != null) {
                emitColumnState(mv, l, states.get(st), st,
                    () -> { coord(mv, l.x(), s.xzScale()); emit(mv, l, s.shiftX()); mv.visitInsn(Opcodes.DADD); },
                    () -> { coord(mv, l.z(), s.xzScale()); emit(mv, l, s.shiftZ()); mv.visitInsn(Opcodes.DADD); },
                    () -> { coord(mv, l.y(), s.yScale()); emit(mv, l, s.shiftY()); mv.visitInsn(Opcodes.DADD); });
                return;
            }
            loadSpec(mv, s.spec());
            coord(mv, l.x(), s.xzScale());
            emit(mv, l, s.shiftX());
            mv.visitInsn(Opcodes.DADD);
            coord(mv, l.y(), s.yScale());
            emit(mv, l, s.shiftY());
            mv.visitInsn(Opcodes.DADD);
            coord(mv, l.z(), s.xzScale());
            emit(mv, l, s.shiftZ());
            mv.visitInsn(Opcodes.DADD);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
        } else if (n instanceof Node.ShiftA s) {
            loadSpec(mv, s.spec());
            coord(mv, l.x(), 0.25);
            mv.visitLdcInsn(0.0);
            coord(mv, l.z(), 0.25);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        } else if (n instanceof Node.ShiftB s) {
            loadSpec(mv, s.spec());
            coord(mv, l.z(), 0.25);
            coord(mv, l.x(), 0.25);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        } else if (n instanceof Node.Shift s) {
            loadSpec(mv, s.spec());
            coord(mv, l.x(), 0.25);
            coord(mv, l.y(), 0.25);
            coord(mv, l.z(), 0.25);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        } else if (n instanceof Node.Weird w) {
            emitWeird(mv, l, w);
        } else if (n instanceof Node.Spline s) {
            emitSpline(mv, l, s);
        } else if (n instanceof Node.Blended b) {
            Integer st = stateIndex.get(b);
            if (st != null) {
                emitBlendedState(mv, l, states.get(st), st);
                return;
            }
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, CompiledProgram.INTERNAL, "blended", "[L" + BLENDED_SPEC + ";");
            pushInt(mv, b.spec());
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitVarInsn(Opcodes.ILOAD, l.x());
            mv.visitVarInsn(Opcodes.ILOAD, l.y());
            mv.visitVarInsn(Opcodes.ILOAD, l.z());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLENDED_SPEC, "compute", "(III)D", false);
        } else if (n instanceof Node.BlendDensity b) {
            mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT, GET_BLENDER, "()L" + BLENDER + ";", true);
            mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
            emit(mv, l, b.input());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLENDER, BLEND_DENSITY, "(L" + FUNCTION_CONTEXT + ";D)D", false);
        } else if (n instanceof Node.BlendAlpha a) {
            if (mode == Mode.COLUMN) {
                emitExtern(mv, l, DensityFunctions.BlendAlpha.INSTANCE); // the chunk's blend alpha cache, or 1.0
            } else {
                mv.visitLdcInsn(1.0);
            }
        } else if (n instanceof Node.BlendOffset o) {
            if (mode == Mode.COLUMN) {
                emitExtern(mv, l, DensityFunctions.BlendOffset.INSTANCE);
            } else {
                mv.visitLdcInsn(0.0);
            }
        } else if (n instanceof Node.Beardifier b) {
            if (mode == Mode.CELL) {
                // Every contribution is exactly 0.0 when no piece is in range of the cell, so the
                // vanilla loop would sum nothing. Skip the call and use 0.0, the loop's start value.
                Label call = new Label();
                Label end = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, l.beard());
                mv.visitJumpInsn(Opcodes.IFNE, call);
                mv.visitLdcInsn(0.0);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                mv.visitLabel(call);
                emitExtern(mv, l, b.vanilla());
                mv.visitLabel(end);
            } else {
                emitExtern(mv, l, b.vanilla());
            }
        } else if (n instanceof Node.EndIslands e) {
            emitExtern(mv, l, e.vanilla());
        } else if (n instanceof Node.Marker m) {
            emitMarker(mv, l, m);
        } else if (n instanceof Node.Unsupported u) {
            emitExtern(mv, l, u.vanilla());
        } else if (n instanceof Node.Select s) {
            Label jump = new Label();
            Label end = new Label();
            emitCond(mv, l, s.cond(), jump);
            emit(mv, l, s.whenFall());
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(jump);
            emit(mv, l, s.whenJump());
            mv.visitLabel(end);
        } else if (n instanceof Node.StaticCall c) {
            emitStaticCall(mv, l, c.owner(), c.name(), c.desc(), c.args());
        } else if (n instanceof Node.NoiseAt na) {
            Integer st = stateIndex.get(na);
            if (st != null) {
                emitColumnState(mv, l, states.get(st), st, () -> emit(mv, l, na.x()), () -> emit(mv, l, na.z()), () -> emit(mv, l, na.y()));
                return;
            }
            loadSpec(mv, na.spec());
            emit(mv, l, na.x());
            emit(mv, l, na.y());
            emit(mv, l, na.z());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
        } else if (n instanceof Node.IntToDouble i) {
            emitInt(mv, l, i.value());
            mv.visitInsn(Opcodes.I2D);
        } else if (n instanceof Node.RoundFloat r) {
            emit(mv, l, r.value());
            mv.visitInsn(Opcodes.D2F);
            mv.visitInsn(Opcodes.F2D);
        }
    }

    /** Pushes the comparison and jumps to {@code target} exactly like the lifted bytecode did. */
    private void emitCond(MethodVisitor mv, Locals l, Node.Cond c, Label target) {
        if (c.kind() == Node.CmpKind.ICMP) {
            emitInt(mv, l, c.intA());
            emitInt(mv, l, c.intB());
            mv.visitJumpInsn(switch (c.op()) {
                case EQ -> Opcodes.IF_ICMPEQ;
                case NE -> Opcodes.IF_ICMPNE;
                case LT -> Opcodes.IF_ICMPLT;
                case GE -> Opcodes.IF_ICMPGE;
                case GT -> Opcodes.IF_ICMPGT;
                case LE -> Opcodes.IF_ICMPLE;
            }, target);
            return;
        }
        emit(mv, l, c.a());
        emit(mv, l, c.b());
        mv.visitInsn(c.kind() == Node.CmpKind.DCMPL ? Opcodes.DCMPL : Opcodes.DCMPG);
        mv.visitJumpInsn(switch (c.op()) {
            case EQ -> Opcodes.IFEQ;
            case NE -> Opcodes.IFNE;
            case LT -> Opcodes.IFLT;
            case GE -> Opcodes.IFGE;
            case GT -> Opcodes.IFGT;
            case LE -> Opcodes.IFLE;
        }, target);
    }

    private void emitStaticCall(MethodVisitor mv, Locals l, String owner, String name, String desc, List<Object> args) {
        if (owner.equals("$op")) {
            emit(mv, l, (Node) args.get(0));
            emit(mv, l, (Node) args.get(1));
            mv.visitInsn(switch (name) {
                case "dadd" -> Opcodes.DADD;
                case "dsub" -> Opcodes.DSUB;
                case "dmul" -> Opcodes.DMUL;
                case "ddiv" -> Opcodes.DDIV;
                default -> Opcodes.DREM;
            });
            return;
        }
        char[] sorts = StaticCalls.argSorts(desc);
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof Node n) {
                emit(mv, l, n);
                if (sorts[i] == 'F') {
                    mv.visitInsn(Opcodes.D2F);
                }
            } else {
                emitInt(mv, l, (INode) a);
            }
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
    }

    /** Leaves an int on the operand stack. Integer expressions are never shared or spilled. */
    private void emitInt(MethodVisitor mv, Locals l, INode n) {
        if (n instanceof INode.IntConst c) {
            pushInt(mv, c.value());
        } else if (n instanceof INode.IntCoord c) {
            mv.visitVarInsn(Opcodes.ILOAD, c.axis() == 0 ? l.x() : c.axis() == 1 ? l.y() : l.z());
        } else if (n instanceof INode.IntBin b) {
            emitInt(mv, l, b.a());
            emitInt(mv, l, b.b());
            mv.visitInsn(switch (b.op()) {
                case ADD -> Opcodes.IADD;
                case SUB -> Opcodes.ISUB;
                case MUL -> Opcodes.IMUL;
                case DIV -> Opcodes.IDIV;
                case REM -> Opcodes.IREM;
                case SHL -> Opcodes.ISHL;
                case SHR -> Opcodes.ISHR;
                case USHR -> Opcodes.IUSHR;
                case AND -> Opcodes.IAND;
                case OR -> Opcodes.IOR;
                case XOR -> Opcodes.IXOR;
            });
        } else if (n instanceof INode.IntNeg g) {
            emitInt(mv, l, g.a());
            mv.visitInsn(Opcodes.INEG);
        } else if (n instanceof INode.DoubleToInt d) {
            emit(mv, l, d.value());
            mv.visitInsn(Opcodes.D2I);
        } else if (n instanceof INode.IntStaticCall c) {
            char[] sorts = StaticCalls.argSorts(c.desc());
            for (int i = 0; i < c.args().size(); i++) {
                Object a = c.args().get(i);
                if (a instanceof Node nn) {
                    emit(mv, l, nn);
                    if (sorts[i] == 'F') {
                        mv.visitInsn(Opcodes.D2F);
                    }
                } else {
                    emitInt(mv, l, (INode) a);
                }
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, c.owner(), c.name(), c.desc(), false);
        } else if (n instanceof INode.SelectInt s) {
            Label jump = new Label();
            Label end = new Label();
            emitCond(mv, l, s.cond(), jump);
            emitInt(mv, l, s.whenFall());
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(jump);
            emitInt(mv, l, s.whenJump());
            mv.visitLabel(end);
        }
    }

    private void emitMarker(MethodVisitor mv, Locals l, Node.Marker m) {
        if (mode == Mode.POINT) {
            emit(mv, l, m.child());
            return;
        }
        if (mode == Mode.CELL) {
            emitCellMarker(mv, l, m);
            return;
        }
        switch (m.type()) {
            case CACHE_ONCE -> emit(mv, l, m.child());                 // per-corner memo == CSE within one corner
            case CACHE_2D -> {
                if (deps.of(m.child()).isXzOrConst()) {
                    emit(mv, l, m.child());                           // pure per-column value, hoisted by plan()
                } else {
                    if (!statefulMarkers.contains(m)) {
                        statefulMarkers.add(m);
                    }
                    emitExtern(mv, l, m.vanilla());                   // vanilla wrapper keeps its own last-position memo
                }
            }
            case FLAT_CACHE -> emitExtern(mv, l, m.vanilla());         // array read inside the chunk wrapper
            case INTERPOLATED, CACHE_ALL_IN_CELL -> {
                if (!statefulMarkers.contains(m)) {
                    statefulMarkers.add(m);
                }
                emitExtern(mv, l, m.vanilla());
            }
        }
    }

    private void emitCellMarker(MethodVisitor mv, Locals l, Node.Marker m) {
        switch (m.type()) {
            case INTERPOLATED -> {
                Integer r = lerpIndex.get(m);
                if (r == null) {
                    // Not one of the chunk's interpolators (cannot happen for a wrapped router). Keep vanilla behaviour.
                    if (!statefulMarkers.contains(m)) {
                        statefulMarkers.add(m);
                    }
                    emitExtern(mv, l, m.vanilla());
                    return;
                }
                if (cellRowBase >= 0) {
                    // Ops.cellLerp(scratch, rowBase + 2r, corners, 8r, dx, dy, dz): lerp3 with its xy halves kept per cell row
                    mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
                    pushInt(mv, cellRowBase + 2 * r);
                    mv.visitVarInsn(Opcodes.ALOAD, l.corners());
                    pushInt(mv, r * 8);
                    mv.visitVarInsn(Opcodes.DLOAD, l.dx());
                    mv.visitVarInsn(Opcodes.DLOAD, l.dy());
                    mv.visitVarInsn(Opcodes.DLOAD, l.dz());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, "cellLerp", "([DI[DIDDD)D", false);
                    return;
                }
                // Mth.lerp3(inCellX / w, inCellY / h, inCellZ / w, noise000, noise100, noise010, noise110, noise001, noise101, noise011, noise111)
                mv.visitVarInsn(Opcodes.DLOAD, l.dx());
                mv.visitVarInsn(Opcodes.DLOAD, l.dy());
                mv.visitVarInsn(Opcodes.DLOAD, l.dz());
                for (int i = 0; i < 8; i++) {
                    mv.visitVarInsn(Opcodes.ALOAD, l.corners());
                    pushInt(mv, r * 8 + i);
                    mv.visitInsn(Opcodes.DALOAD);
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, "lerp3", "(DDDDDDDDDDD)D", false);
            }
            // Only array-path cache_once markers get here. CellCacheOnce keeps cells with other cache_once markers on
            // vanilla's fill. Vanilla fills array-path markers as arrays, so each block gets its child's own value.
            case CACHE_ONCE -> emit(mv, l, m.child());
            case CACHE_2D, CACHE_ALL_IN_CELL -> {
                if (m.type() == Node.MarkerType.CACHE_2D && m.child() instanceof Node.Const) {
                    // the wrapper gives its child's constant at every block, remembered or not
                    emit(mv, l, m.child());
                    return;
                }
                Integer t = columnCaches.get(m);
                if (t != null && columnCacheBase >= 0) {
                    emitColumnCached(mv, l, m, columnCacheBase + 2 + t * COLUMN_CACHE_SIZE);
                    return;
                }
                // Inside a cell these wrappers carry position state. The wrapper itself reproduces it exactly.
                if (!statefulMarkers.contains(m)) {
                    statefulMarkers.add(m);
                }
                emitExtern(mv, l, m.vanilla());
            }
            case FLAT_CACHE -> emitExtern(mv, l, m.vanilla());
        }
    }

    /**
     * Emits a column cache read. It gives the column's value if this cell column already computed it. Otherwise it
     * computes the child and stores the result for the column.
     */
    private void emitColumnCached(MethodVisitor mv, Locals l, Node.Marker m, int base) {
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        emitColumnIndex(mv, l, base);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFEQ, done); // value == value: computed for this column
        mv.visitInsn(Opcodes.POP2);
        emit(mv, l, m.child());
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        emitColumnIndex(mv, l, base);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitLabel(done);
    }

    /**
     * Emits a column value read. It gives this column's value if the cell column already computed it. Otherwise it
     * computes the subtree and stores the result for the column.
     */
    private void emitColumnValue(MethodVisitor mv, Locals l, Node n) {
        if (columnCacheBase < 0) {
            throw new DfcCompileException("column values planned without a column table base");
        }
        int base = columnCacheBase + 2 + columnValues.get(n) * COLUMN_CACHE_SIZE;
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        emitColumnIndex(mv, l, base);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFEQ, done); // value == value: computed for this column
        mv.visitInsn(Opcodes.POP2);
        String h = helper.get(n);
        if (h != null) {
            emitHelperCall(mv, l, h);
        } else {
            emitInline(mv, l, n);
        }
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        emitColumnIndex(mv, l, base);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitLabel(done);
    }

    /** {@code base + ((x & 15) << 4 | (z & 15))}: distinct for the columns of one cell column (cells are at most 16 wide). */
    private static void emitColumnIndex(MethodVisitor mv, Locals l, int base) {
        mv.visitVarInsn(Opcodes.ILOAD, l.x());
        pushInt(mv, 15);
        mv.visitInsn(Opcodes.IAND);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitInsn(Opcodes.ISHL);
        mv.visitVarInsn(Opcodes.ILOAD, l.z());
        pushInt(mv, 15);
        mv.visitInsn(Opcodes.IAND);
        mv.visitInsn(Opcodes.IOR);
        pushInt(mv, base);
        mv.visitInsn(Opcodes.IADD);
    }

    private static final String NOISE_COLUMN = "dev/denismasterherobrine/generatoraccelerator2/dfc/ir/NoiseSpec$Column";
    private static final String BLENDED_COLUMN = "dev/denismasterherobrine/generatoraccelerator2/dfc/ir/BlendedSpec$Column";

    private void loadState(MethodVisitor mv, int index, String type) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CompiledProgram.INTERNAL, "states", "[Ljava/lang/Object;");
        pushInt(mv, index);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, type);
    }

    /** Emits "prepare the column state if this column has not yet (flag slot is NaN), then evaluate at y". */
    private void emitColumnState(MethodVisitor mv, Locals l, StateDesc d, int index, Runnable emitX, Runnable emitZ, Runnable emitY) {
        Label prepared = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, d.slot());
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFEQ, prepared);
        loadState(mv, index, NOISE_COLUMN);
        emitX.run();
        emitZ.run();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_COLUMN, "prepare", "(DD)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, d.slot());
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitLabel(prepared);
        loadState(mv, index, NOISE_COLUMN);
        emitY.run();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_COLUMN, "value", "(D)D", false);
    }

    private void emitBlendedState(MethodVisitor mv, Locals l, StateDesc d, int index) {
        Label prepared = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, d.slot());
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFEQ, prepared);
        loadState(mv, index, BLENDED_COLUMN);
        mv.visitVarInsn(Opcodes.ILOAD, l.x());
        mv.visitVarInsn(Opcodes.ILOAD, l.z());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLENDED_COLUMN, "prepare", "(II)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        pushInt(mv, d.slot());
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitLabel(prepared);
        loadState(mv, index, BLENDED_COLUMN);
        mv.visitVarInsn(Opcodes.ILOAD, l.y());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLENDED_COLUMN, "value", "(I)D", false);
    }

    private void emitAp2(MethodVisitor mv, Locals l, Node.Ap2 a) {
        if (mode != Mode.POINT && (a.type() == Node.Ap2Type.ADD || a.type() == Node.Ap2Type.MUL)) {
            // Inside a NoiseChunk vanilla evaluates the wrapped tree, where add/mul with a constant argument has been
            // folded into MulOrAdd(x, c) = x + c / x * c (no zero short-circuit). Mirror that here.
            Node.Const c = a.a() instanceof Node.Const k ? k : (a.b() instanceof Node.Const k2 ? k2 : null);
            if (c != null) {
                Node other = a.a() == c ? a.b() : a.a();
                emit(mv, l, other);
                mv.visitLdcInsn(c.value());
                mv.visitInsn(a.type() == Node.Ap2Type.MUL ? Opcodes.DMUL : Opcodes.DADD);
                return;
            }
        }
        emit(mv, l, a.a());
        switch (a.type()) {
            case ADD -> {
                emit(mv, l, a.b());
                mv.visitInsn(Opcodes.DADD);
            }
            case MUL -> {
                // d0 == 0.0 ? 0.0 : d0 * b   (b not evaluated when d0 == 0)
                Label nonZero = new Label();
                Label end = new Label();
                mv.visitInsn(Opcodes.DUP2);
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DCMPL);
                mv.visitJumpInsn(Opcodes.IFNE, nonZero);
                mv.visitInsn(Opcodes.POP2);
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                mv.visitLabel(nonZero);
                emit(mv, l, a.b());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitLabel(end);
            }
            case MIN -> {
                // d0 < b.min ? d0 : Math.min(d0, b)
                Label keep = new Label();
                mv.visitInsn(Opcodes.DUP2);
                mv.visitLdcInsn(a.b().min());
                mv.visitInsn(Opcodes.DCMPG);
                mv.visitJumpInsn(Opcodes.IFLT, keep);
                emit(mv, l, a.b());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
                mv.visitLabel(keep);
            }
            case MAX -> {
                // d0 > b.max ? d0 : Math.max(d0, b)
                Label keep = new Label();
                mv.visitInsn(Opcodes.DUP2);
                mv.visitLdcInsn(a.b().max());
                mv.visitInsn(Opcodes.DCMPL);
                mv.visitJumpInsn(Opcodes.IFGT, keep);
                emit(mv, l, a.b());
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                mv.visitLabel(keep);
            }
        }
    }

    private void emitRangeChoice(MethodVisitor mv, Locals l, Node.RangeChoice r) {
        Label out = new Label();
        Label end = new Label();
        emit(mv, l, r.input());
        mv.visitInsn(Opcodes.DUP2);
        mv.visitLdcInsn(r.minInclusive());
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLT, out);   // d < min (or NaN) -> out of range
        mv.visitInsn(Opcodes.DUP2);
        mv.visitLdcInsn(r.maxExclusive());
        mv.visitInsn(Opcodes.DCMPG);
        mv.visitJumpInsn(Opcodes.IFGE, out);   // d >= max (or NaN) -> out of range
        mv.visitInsn(Opcodes.POP2);
        emit(mv, l, r.inRange());
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(out);
        mv.visitInsn(Opcodes.POP2);
        emit(mv, l, r.outRange());
        mv.visitLabel(end);
    }

    private void emitWeird(MethodVisitor mv, Locals l, Node.Weird w) {
        emit(mv, l, w.input());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Ops.INTERNAL, w.type1() ? "rarity3D" : "rarity2D", "(D)D", false);
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp2());
        if (w.spec() < 0) {
            // rarity * Math.abs(0.0)
            mv.visitVarInsn(Opcodes.DLOAD, l.tmp2());
            mv.visitLdcInsn(0.0);
            mv.visitInsn(Opcodes.DMUL);
            return;
        }
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp2());
        loadSpec(mv, w.spec());
        for (int c : new int[] { l.x(), l.y(), l.z() }) {
            mv.visitVarInsn(Opcodes.ILOAD, c);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, l.tmp2());
            mv.visitInsn(Opcodes.DDIV);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NOISE_SPEC, "getValue", "(DDD)D", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
        mv.visitInsn(Opcodes.DMUL); // rarity * abs(noise), same operand order as vanilla
    }

    private void emitSpline(MethodVisitor mv, Locals l, Node.Spline s) {
        Integer idx = splineIndex.get(s);
        if (idx == null) {
            idx = splines.size();
            splines.add(toRuntime(s.spline()));
            splineIndex.put(s, idx);
        }
        // Make sure every coordinate is in its slot before the runtime walks the spline.
        ensureSplineCoords(mv, l, s.spline());
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CompiledProgram.INTERNAL, "splines", "[L" + SplineRt.INTERNAL + ";");
        pushInt(mv, idx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SplineRt.INTERNAL, "apply", "([D)F", false);
        mv.visitInsn(Opcodes.F2D);
    }

    /**
     * Ensures each distinct coordinate of the spline once. Vanilla's offset spline nests a few hundred points over four coordinates. An
     * ensure per point repeated the same slot check hundreds of times, which kept one method over the JIT's limit at every compaction
     * level (TerraFirmaGreg's moon). A second ensure of a slot computes nothing, so once per coordinate is the same.
     */
    private void ensureSplineCoords(MethodVisitor mv, Locals l, Node.SplineSpec spec) {
        for (Node coordinate : splineCoords(spec)) {
            emitEnsure(mv, l, coordinate);
        }
    }

    /** The spline's distinct coordinates in the order its points first use them. Told apart by identity, as the slots are. */
    private static List<Node> splineCoords(Node.SplineSpec spec) {
        List<Node> coords = new ArrayList<>();
        collectSplineCoords(spec, coords, new IdentityHashMap<>());
        return coords;
    }

    private static void collectSplineCoords(Node.SplineSpec spec, List<Node> coords, Map<Node, Boolean> seen) {
        if (spec instanceof Node.SplineMulti m) {
            if (seen.put(m.coordinate(), Boolean.TRUE) == null) {
                coords.add(m.coordinate());
            }
            for (Node.SplineSpec v : m.values()) {
                collectSplineCoords(v, coords, seen);
            }
        }
    }

    private SplineRt toRuntime(Node.SplineSpec spec) {
        if (spec instanceof Node.SplineConst c) {
            return SplineRt.constant(c.value());
        }
        Node.SplineMulti m = (Node.SplineMulti) spec;
        SplineRt[] values = new SplineRt[m.values().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = toRuntime(m.values().get(i));
        }
        int hint = splineHintBase >= 0 && SplineRt.hintable(m.locations()) ? splineHintBase + splineHints++ : -1;
        return SplineRt.multipoint(slot.get(m.coordinate()), m.locations(), values, m.derivatives(), hint);
    }

    private void emitExtern(MethodVisitor mv, Locals l, DensityFunction fn) {
        Integer idx = externIndex.get(fn);
        if (idx == null) {
            idx = externs.size();
            externs.add(fn);
            externIndex.put(fn, idx);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CompiledProgram.INTERNAL, "externs", "[L" + DENSITY_FUNCTION + ";");
        pushInt(mv, idx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION, COMPUTE, "(L" + FUNCTION_CONTEXT + ";)D", true);
    }

    private void loadSpec(MethodVisitor mv, int spec) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CompiledProgram.INTERNAL, "specs", "[L" + NOISE_SPEC + ";");
        pushInt(mv, spec);
        mv.visitInsn(Opcodes.AALOAD);
    }

    private static void coord(MethodVisitor mv, int local, double scale) {
        mv.visitVarInsn(Opcodes.ILOAD, local);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(scale);
        mv.visitInsn(Opcodes.DMUL);
    }

    public static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }
}
