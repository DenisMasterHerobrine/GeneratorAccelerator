package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.DfcCompileException;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.Emitter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ProgramCompiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Bounds of the interpolated roots over a stretch of one corner column, so the noise fill can leave out corners that only
 * all-air cells touch (the open sky).
 *
 * <p>A root's value at a corner is a pure function of the position. Over the corners of one column from some height up, it
 * lies within the interval this class computes: the root's (x, z)-only subtrees exactly (a compiled point program, read
 * once per column), the y gradients over the stretch's heights, and every other y-dependent leaf (3D noises, old blended
 * noise, splines of such) between its own bounds. The operations above them are bounded as {@link CellBounds} bounds
 * them, from their arguments' bounds with the same floating point operations, whose rounding is monotone. So the interval
 * holds for the values the column program computes. It is widened by a margin on top.
 *
 * <p>Taking a smaller stretch (from a higher corner) only shrinks every interval. So "the cells above corner j are all air"
 * holds from some corner up and can be searched for.
 *
 * <p>{@code -Dgeneratoraccelerator2.dfc.skyCorners=false} turns it off.
 */
public final class SkyBounds {
    /** Not final so a bench and the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.dfc.skyCorners"));

    /** An interval program node. */
    private sealed interface Op permits Leaf, Fixed, YGrad, Bin, Affine, MapOp, Clamp, Range {}

    /** An (x, z)-only subtree, computed exactly per column ({@code leaves[index]}). */
    private record Leaf(int index) implements Op {}

    private record Fixed(double lo, double hi) implements Op {}

    private record YGrad(int fromY, int toY, double fromValue, double toValue) implements Op {}

    private record Bin(Node.Ap2Type type, Op a, Op b) implements Op {}

    private record Affine(boolean mul, Op input, double argument) implements Op {}

    private record MapOp(Node.MappedType type, Op input) implements Op {}

    private record Clamp(Op input, double lo, double hi) implements Op {}

    private record Range(Op input, double minInclusive, double maxExclusive, Op inRange, Op outRange) implements Op {}

    /** Per column root index: its interval program, or null when the final density's per-block tree does not read it. */
    private final Op[] roots;
    /** The (x, z)-only leaves' program, or null when there are none. */
    @Nullable private final PointTemplate leaves;
    private final int leafCount;
    /** The final density's bounds from interval corners ({@link CellBounds#classifyIntervals}). */
    private final CellBounds perBlock;

    private SkyBounds(Op[] roots, @Nullable PointTemplate leaves, int leafCount, CellBounds perBlock) {
        this.roots = roots;
        this.leaves = leaves;
        this.leafCount = leafCount;
        this.perBlock = perBlock;
    }

    /**
     * The bounds for the column program's roots ({@code rootMarkers}, in root order) that the final density's per-block tree
     * ({@code finalDensity}) reads, or null when some node of those roots is not covered (a nested interpolated or cell cache,
     * the beardifier, another mod's function). Compiles the (x, z)-only leaves into one point program.
     */
    public static Result of(List<Node.Marker> rootMarkers, CellBounds finalDensity, List<NoiseSpec> specs, List<BlendedSpec> blended) {
        int[] read = finalDensity.interpolatedRoots();
        if (read.length == 0) {
            return new Result(null, "the final density reads no interpolated root");
        }
        Builder b = new Builder(blended);
        Op[] roots = new Op[rootMarkers.size()];
        for (int r : read) {
            if (r < 0 || r >= roots.length) {
                return new Result(null, "root " + r + " is not a column root");
            }
            Op op = b.build(rootMarkers.get(r).child());
            if (op == null) {
                return new Result(null, "root " + r + " has " + b.uncovered);
            }
            roots[r] = op;
        }
        PointTemplate program = null;
        if (!b.leafNodes.isEmpty()) {
            try {
                program = ProgramCompiler.compilePointTemplate("sky-xz", b.leafNodes, specs, blended, Emitter.Mode.COLUMN);
            } catch (DfcCompileException e) {
                return new Result(null, "the x/z program failed: " + e.getMessage());
            }
        }
        return new Result(new SkyBounds(roots, program, b.leafNodes.size(), finalDensity), null);
    }

    /** {@link #of}'s answer: the bounds, or why there are none. */
    public record Result(@Nullable SkyBounds bounds, @Nullable String reason) {}

    /** The objects each chunk resolves through its own cache wrappers. */
    public DensityFunction[] externTemplates() {
        return leaves == null ? new DensityFunction[0] : leaves.externTemplates;
    }

    /** An evaluator for one chunk (one thread), with the leaves program bound to the chunk's own wrappers. */
    public Column forChunk(DensityFunction[] externs) {
        return new Column(leaves == null ? null : leaves.instantiate(externs), leaves == null ? 0 : leaves.scratchSize());
    }

    private static final class Builder {
        final List<BlendedSpec> blended;

        Builder(List<BlendedSpec> blended) {
            this.blended = blended;
        }

        final CoordDeps deps = new CoordDeps(true);
        final List<Node> leafNodes = new ArrayList<>();
        final Map<Node, Integer> leafIndex = new IdentityHashMap<>();
        final Map<Node, Op> built = new IdentityHashMap<>();
        /** The first node not covered, for the log. */
        String uncovered = "a node the bounds do not cover";

        @Nullable
        Op build(Node n) {
            Op op = built.get(n);
            if (op == null) {
                op = make(n);
                if (op == null) {
                    return null;
                }
                built.put(n, op);
            }
            return op;
        }

        @Nullable
        private Op make(Node n) {
            if (n instanceof Node.Const c) {
                return new Fixed(c.value(), c.value());
            }
            if (deps.of(n).isXzOrConst()) {
                Integer i = leafIndex.get(n);
                if (i == null) {
                    i = leafNodes.size();
                    leafNodes.add(n);
                    leafIndex.put(n, i);
                }
                return new Leaf(i);
            }
            return switch (n) {
                case Node.Ap2 a -> {
                    Op x = build(a.a());
                    Op y = x == null ? null : build(a.b());
                    yield y == null ? null : new Bin(a.type(), x, y);
                }
                case Node.MulOrAdd m -> {
                    Op x = build(m.input());
                    yield x == null ? null : new Affine(m.mul(), x, m.argument());
                }
                case Node.Mapped m -> {
                    Op x = build(m.input());
                    yield x == null ? null : new MapOp(m.type(), x);
                }
                case Node.Clamp c -> {
                    Op x = build(c.input());
                    yield x == null ? null : new Clamp(x, c.lo(), c.hi());
                }
                case Node.RangeChoice r -> {
                    Op x = build(r.input());
                    Op in = x == null ? null : build(r.inRange());
                    Op out = in == null ? null : build(r.outRange());
                    yield out == null ? null : new Range(x, r.minInclusive(), r.maxExclusive(), in, out);
                }
                case Node.YGradient y -> new YGrad(y.fromY(), y.toY(), y.fromValue(), y.toValue());
                // vanilla's own bounds of the y-dependent leaves, which its MIN/MAX short cuts rely on too
                case Node.Noise x -> bounded(x.min(), x.max());
                case Node.ShiftedNoise x -> bounded(x.min(), x.max());
                case Node.Shift x -> bounded(x.min(), x.max());
                case Node.Weird x -> bounded(x.min(), x.max());
                // vanilla's bound of the old blended noise is far too wide to decide anything
                case Node.Blended x -> bounded(-blended.get(x.spec()).bound(), blended.get(x.spec()).bound());
                // computed in the reals from the control points, and the float evaluation can land a float ulp outside
                case Node.Spline x -> bounded(Math.nextDown(Math.nextDown((float) x.min())), Math.nextUp(Math.nextUp((float) x.max())));
                // the noise fill only plans sky corners without old-chunk blending, where blend_density returns its input
                case Node.BlendDensity x -> build(x.input());
                case Node.Marker m -> switch (m.type()) {
                    case CACHE_ONCE -> build(m.child());
                    // a y-dependent 2D cache holds its child's value at some earlier height of the column
                    case CACHE_2D -> bounded(m.child().min(), m.child().max());
                    default -> missing("a " + m.type().name().toLowerCase(Locale.ROOT) + " inside");
                };
                default -> missing(n.getClass().getSimpleName());
            };
        }

        @Nullable
        private Op missing(String what) {
            uncovered = what;
            return null;
        }

        @Nullable
        private Op bounded(double lo, double hi) {
            return Double.isFinite(lo) && Double.isFinite(hi) ? new Fixed(lo, hi) : missing("a leaf without finite bounds");
        }
    }

    /** A {@link DensityFunction.FunctionContext} that moves, for the leaves program. No old-chunk blending. */
    private static final class At implements DensityFunction.FunctionContext {
        int x;
        int y;
        int z;

        @Override
        public int blockX() {
            return x;
        }

        @Override
        public int blockY() {
            return y;
        }

        @Override
        public int blockZ() {
            return z;
        }

        @Override
        public Blender getBlender() {
            return Blender.empty();
        }
    }

    /** Bounds over the columns of one chunk. Not thread-safe: one chunk is filled by one thread. */
    public final class Column {
        @Nullable private final PointProgram program;
        private final double[] scratch;
        private final double[] leafValues;
        private final At at = new At();
        /** The chunk's own copy: a classification keeps its state in the instance. */
        private final CellBounds cell = perBlock.fresh();
        private int y0;
        private int y1;
        private double lo;
        private double hi;

        private Column(@Nullable PointProgram program, int scratchSize) {
            this.program = program;
            this.scratch = new double[Math.max(1, scratchSize)];
            this.leafValues = new double[Math.max(1, leafCount)];
        }

        /** Moves to the column at block {@code x, z}: computes its (x, z)-only values. {@code y} is any height of the column. */
        public void column(int x, int y, int z) {
            if (program != null) {
                at.x = x;
                at.y = y;
                at.z = z;
                program.computeAll(at, scratch, leafValues);
            }
        }

        /**
         * Bounds of every read root over the column's corners at block heights {@code [fromY, toY]}, into {@code rootLo} and
         * {@code rootHi} (unread roots are left as they are).
         */
        public void roots(int fromY, int toY, double[] rootLo, double[] rootHi) {
            this.y0 = fromY;
            this.y1 = toY;
            for (int r = 0; r < roots.length; r++) {
                Op op = roots[r];
                if (op != null) {
                    eval(op);
                    // a margin far above the floating point error, far below anything that decides a cell
                    rootLo[r] = lo - (Math.abs(lo) * 1e-9 + 1e-9);
                    rootHi[r] = hi + (Math.abs(hi) * 1e-9 + 1e-9);
                }
            }
        }

        /** Whether the final density is non-positive over the box with each read root within its interval. */
        public boolean sky(double[] rootLo, double[] rootHi, int x0, int yFrom, int z0, int w, int yTo) {
            return cell.classifyIntervals(rootLo, rootHi, x0, yFrom, z0, w, yTo) == CellBounds.SKY;
        }

        private void eval(Op op) {
            switch (op) {
                case Leaf l -> set(leafValues[l.index()], leafValues[l.index()]);
                case Fixed f -> set(f.lo(), f.hi());
                case YGrad g -> {
                    double a = Interpreter.clampedMap((double) y0, (double) g.fromY(), (double) g.toY(), g.fromValue(), g.toValue());
                    double b = Interpreter.clampedMap((double) y1, (double) g.fromY(), (double) g.toY(), g.fromValue(), g.toValue());
                    set(Math.min(a, b), Math.max(a, b));
                }
                case Bin b -> {
                    eval(b.a());
                    double alo = lo;
                    double ahi = hi;
                    eval(b.b());
                    switch (b.type()) {
                        case ADD -> set(alo + lo, ahi + hi);
                        case MUL -> mul(alo, ahi, lo, hi);
                        case MIN -> set(Math.min(alo, lo), Math.min(ahi, hi));
                        case MAX -> set(Math.max(alo, lo), Math.max(ahi, hi));
                    }
                }
                case Affine a -> {
                    eval(a.input());
                    if (a.mul()) {
                        mul(lo, hi, a.argument(), a.argument());
                    } else {
                        set(lo + a.argument(), hi + a.argument());
                    }
                }
                case MapOp m -> {
                    eval(m.input());
                    mapped(m.type());
                }
                case Clamp c -> {
                    eval(c.input());
                    set(Interpreter.clamp(lo, c.lo(), c.hi()), Interpreter.clamp(hi, c.lo(), c.hi()));
                }
                case Range r -> {
                    eval(r.input());
                    double ilo = lo;
                    double ihi = hi;
                    boolean canIn = ihi >= r.minInclusive() && ilo < r.maxExclusive();
                    boolean canOut = ilo < r.minInclusive() || ihi >= r.maxExclusive();
                    if (canIn && !canOut) {
                        eval(r.inRange());
                    } else if (canOut && !canIn) {
                        eval(r.outRange());
                    } else {
                        eval(r.inRange());
                        double inLo = lo;
                        double inHi = hi;
                        eval(r.outRange());
                        set(Math.min(inLo, lo), Math.max(inHi, hi));
                    }
                }
            }
        }

        private void set(double lo, double hi) {
            this.lo = lo;
            this.hi = hi;
        }

        private void mul(double alo, double ahi, double blo, double bhi) {
            double p = alo * blo;
            double q = alo * bhi;
            double r = ahi * blo;
            double s = ahi * bhi;
            set(Math.min(Math.min(p, q), Math.min(r, s)), Math.max(Math.max(p, q), Math.max(r, s)));
        }

        /** {@link CellBounds}' bounds of a mapped value. */
        private void mapped(Node.MappedType type) {
            double a = lo;
            double b = hi;
            switch (type) {
                case ABS -> {
                    if (a >= 0.0) {
                        set(a, b);
                    } else if (b <= 0.0) {
                        set(-b, -a);
                    } else {
                        set(0.0, Math.max(-a, b));
                    }
                }
                case SQUARE -> {
                    double p = a * a;
                    double q = b * b;
                    set(a <= 0.0 && b >= 0.0 ? 0.0 : Math.min(p, q), Math.max(p, q));
                }
                case CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE -> set(Interpreter.mapped(type, a), Interpreter.mapped(type, b));
                // c / 2 - c^3 / 24 is monotone in the reals but a difference of two rounded terms in floating point
                case SQUEEZE -> set(Math.nextDown(Math.nextDown(Math.nextDown(Math.nextDown(Interpreter.mapped(type, a))))),
                    Math.nextUp(Math.nextUp(Math.nextUp(Math.nextUp(Interpreter.mapped(type, b))))));
            }
        }
    }
}
