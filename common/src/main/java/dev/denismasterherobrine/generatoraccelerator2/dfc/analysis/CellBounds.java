package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Sound bounds of a cell program's value over a whole cell, from the eight corner values of each interpolated root.
 *
 * <p>Vanilla's per-block density is the per-block part of the tree (everything above the {@code interpolated} markers),
 * applied to trilinear interpolations of the corners. Each {@code lerp(t, a, b) = a + t * (b - a)} with {@code t} in
 * {@code [0, 15/16]} stays within {@code [min(a, b), max(a, b)]}, in floating point too. The step {@code t * (b - a)}
 * has the sign of {@code b - a} and a magnitude below {@code |b - a|}. Rounding a sum that lies strictly between two
 * doubles never leaves them. So every block's interpolated value lies within the corners' range. The per-block
 * operations are monotone in each argument, or have interval forms whose floating point rounding is monotone. So the
 * bounds hold for the values the generated code computes, not only for the real numbers. {@code squeeze} is not exact
 * to the last bit, because it is a difference of two rounded terms. Its bound is widened by a few ulps.
 *
 * <p>A cell whose upper bound is at most 0 holds no solid block ({@code density > 0.0} is false everywhere). A cell
 * whose lower bound is above 0 holds only solid blocks. {@code FastFill} then handles such cells without the per-block
 * density, the aquifer or the ore vein rule. A tree with a node these bounds do not cover has no bounds, and every cell
 * takes the per-block path. Uncovered nodes include another mod's function or a blend near old chunks. A spline is
 * covered by the bounds vanilla computes for it, widened by two ulps on each side.
 */
public final class CellBounds {
    public static final int MIXED = 0;
    public static final int SKY = 1;
    public static final int SOLID = 2;

    private final Node root;
    private final Map<Node.Marker, Integer> lerpIndex;
    private double[] corners;
    private int y0;
    private int y1;
    private boolean blenderEmpty;
    private double lo;
    private double hi;
    /** The bounds of the last {@link #classify}, for diagnostics. */
    public double lastLo;
    public double lastHi;

    private CellBounds(Node root, Map<Node.Marker, Integer> lerpIndex) {
        this.root = root;
        this.lerpIndex = lerpIndex;
    }

    /**
     * Bounds for {@code root} (the per-block tree of a cell program), or null when some node is not covered. The instance
     * keeps the state of a classification, so a template shares it only as a prototype. Each chunk binding takes its own
     * instance through {@link #fresh()}.
     */
    @Nullable
    public static CellBounds of(Node root, Map<Node.Marker, Integer> lerpIndex) {
        return covered(root, lerpIndex, new IdentityHashMap<>()) ? new CellBounds(root, lerpIndex) : null;
    }

    /** The kind of the first node {@link #of} does not cover in {@code root}, or null when all are covered (for the compile log). */
    @Nullable
    public static String uncovered(Node root, Map<Node.Marker, Integer> lerpIndex) {
        return firstUncovered(root, lerpIndex, new IdentityHashMap<>());
    }

    @Nullable
    private static String firstUncovered(Node n, Map<Node.Marker, Integer> lerpIndex, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return null;
        }
        if (!covered(n, lerpIndex, new IdentityHashMap<>())) {
            // descend to name the deepest uncovered node
            java.util.List<Node> children = switch (n) {
                case Node.Ap2 a -> java.util.List.of(a.a(), a.b());
                case Node.MulOrAdd m -> java.util.List.of(m.input());
                case Node.Mapped m -> java.util.List.of(m.input());
                case Node.Clamp c -> java.util.List.of(c.input());
                case Node.RangeChoice r -> java.util.List.of(r.input(), r.inRange(), r.outRange());
                case Node.ShiftedNoise s -> java.util.List.of(s.shiftX(), s.shiftY(), s.shiftZ());
                case Node.Weird w -> java.util.List.of(w.input());
                case Node.BlendDensity b -> java.util.List.of(b.input());
                case Node.Marker m -> m.type() == Node.MarkerType.INTERPOLATED || m.type() == Node.MarkerType.CACHE_ONCE || m.type() == Node.MarkerType.CACHE_2D
                    ? java.util.List.of(m.child()) : java.util.List.of();
                default -> java.util.List.of();
            };
            for (Node c : children) {
                String deeper = firstUncovered(c, lerpIndex, seen);
                if (deeper != null) {
                    return deeper;
                }
            }
            return n instanceof Node.Marker m ? "Marker." + m.type() : n.getClass().getSimpleName();
        }
        return null;
    }

    /** An instance of the same tree for one chunk binding (one thread at a time). */
    public CellBounds fresh() {
        return new CellBounds(root, lerpIndex);
    }

    private static boolean covered(Node n, Map<Node.Marker, Integer> lerpIndex, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return true;
        }
        return switch (n) {
            case Node.Const c -> true;
            case Node.Ap2 a -> covered(a.a(), lerpIndex, seen) && covered(a.b(), lerpIndex, seen);
            case Node.MulOrAdd m -> covered(m.input(), lerpIndex, seen);
            case Node.Mapped m -> covered(m.input(), lerpIndex, seen);
            case Node.Clamp c -> covered(c.input(), lerpIndex, seen);
            case Node.RangeChoice r -> covered(r.input(), lerpIndex, seen) && covered(r.inRange(), lerpIndex, seen) && covered(r.outRange(), lerpIndex, seen);
            case Node.YGradient y -> true;
            // Splines and the End islands carry the bounds vanilla computes for them. Vanilla's MIN/MAX short-circuits
            // rely on those bounds too.
            case Node.Spline x -> true;
            case Node.EndIslands x -> true;
            // Noise leaves evaluated per block are bounded by vanilla's own maxValue. The MIN/MAX short-circuits rely on it too.
            case Node.Noise x -> true;
            case Node.ShiftedNoise s -> covered(s.shiftX(), lerpIndex, seen) && covered(s.shiftY(), lerpIndex, seen) && covered(s.shiftZ(), lerpIndex, seen);
            case Node.ShiftA x -> true;
            case Node.ShiftB x -> true;
            case Node.Shift x -> true;
            case Node.Weird w -> covered(w.input(), lerpIndex, seen);
            case Node.Blended x -> true;
            case Node.BlendDensity b -> covered(b.input(), lerpIndex, seen);
            case Node.BlendAlpha x -> true;
            case Node.BlendOffset x -> true;
            // only cells no structure piece reaches are classified, where the beardifier adds exactly 0.0
            case Node.Beardifier x -> true;
            case Node.Marker m -> switch (m.type()) {
                // an interpolated marker without corners is filled the vanilla way. The cell program never sees that cell
                case INTERPOLATED -> lerpIndex.containsKey(m);
                case CACHE_ONCE, CACHE_2D -> covered(m.child(), lerpIndex, seen);
                case FLAT_CACHE, CACHE_ALL_IN_CELL -> false;
            };
            default -> false;
        };
    }

    /**
     * Classifies the cell {@code [x0, x0 + w) x [y0, y0 + h) x [z0, z0 + w)} from the corners ({@code corners[root * 8 + i]}
     * as the cell programs read them) when no structure piece reaches it.
     */
    public int classify(double[] corners, int y0, int h, boolean blenderEmpty) {
        this.corners = corners;
        this.y0 = y0;
        this.y1 = y0 + h - 1;
        this.blenderEmpty = blenderEmpty;
        if (!eval(root)) {
            lastLo = Double.NaN;
            lastHi = Double.NaN;
            return MIXED;
        }
        lastLo = lo;
        lastHi = hi;
        if (hi <= 0.0) {
            return SKY;
        }
        return lo > 0.0 ? SOLID : MIXED;
    }

    /** Sets {@link #lo}/{@link #hi}. Returns false when the bounds cannot be given (a blend near old chunks). */
    private boolean eval(Node n) {
        switch (n) {
            case Node.Const c -> set(c.value(), c.value());
            case Node.Ap2 a -> {
                if (!eval(a.a())) {
                    return false;
                }
                double alo = lo;
                double ahi = hi;
                if (!eval(a.b())) {
                    return false;
                }
                switch (a.type()) {
                    case ADD -> set(alo + lo, ahi + hi);
                    case MUL -> mul(alo, ahi, lo, hi);
                    case MIN -> set(Math.min(alo, lo), Math.min(ahi, hi));
                    case MAX -> set(Math.max(alo, lo), Math.max(ahi, hi));
                }
            }
            case Node.MulOrAdd m -> {
                if (!eval(m.input())) {
                    return false;
                }
                if (m.mul()) {
                    mul(lo, hi, m.argument(), m.argument());
                } else {
                    set(lo + m.argument(), hi + m.argument());
                }
            }
            case Node.Mapped m -> {
                if (!eval(m.input())) {
                    return false;
                }
                mapped(m.type());
            }
            case Node.Clamp c -> {
                if (!eval(c.input())) {
                    return false;
                }
                set(Interpreter.clamp(lo, c.lo(), c.hi()), Interpreter.clamp(hi, c.lo(), c.hi()));
            }
            case Node.RangeChoice r -> {
                if (!eval(r.input())) {
                    return false;
                }
                double ilo = lo;
                double ihi = hi;
                boolean canIn = ihi >= r.minInclusive() && ilo < r.maxExclusive();
                boolean canOut = ilo < r.minInclusive() || ihi >= r.maxExclusive();
                if (canIn && !canOut) {
                    return eval(r.inRange());
                }
                if (canOut && !canIn) {
                    return eval(r.outRange());
                }
                if (!eval(r.inRange())) {
                    return false;
                }
                double inLo = lo;
                double inHi = hi;
                if (!eval(r.outRange())) {
                    return false;
                }
                set(Math.min(inLo, lo), Math.max(inHi, hi));
            }
            case Node.YGradient y -> {
                double a = Interpreter.clampedMap((double) y0, (double) y.fromY(), (double) y.toY(), y.fromValue(), y.toValue());
                double b = Interpreter.clampedMap((double) y1, (double) y.fromY(), (double) y.toY(), y.fromValue(), y.toValue());
                set(Math.min(a, b), Math.max(a, b));
            }
            case Node.Noise x -> set(-x.max(), x.max());
            case Node.ShiftedNoise x -> set(-x.max(), x.max());
            case Node.ShiftA x -> set(-x.max(), x.max());
            case Node.ShiftB x -> set(-x.max(), x.max());
            case Node.Shift x -> set(-x.max(), x.max());
            case Node.Weird x -> set(x.min(), x.max());
            case Node.Blended x -> set(-x.max(), x.max());
            // Vanilla computes these in the reals from the control points. The float evaluation can land a float ulp outside.
            case Node.Spline x -> set(Math.nextDown(Math.nextDown((float) x.min())), Math.nextUp(Math.nextUp((float) x.max())));
            case Node.EndIslands x -> set(x.min(), x.max());
            case Node.BlendDensity b -> {
                if (!blenderEmpty) {
                    return false;
                }
                return eval(b.input());
            }
            case Node.BlendAlpha x -> {
                if (!blenderEmpty) {
                    return false;
                }
                set(1.0, 1.0);
            }
            case Node.BlendOffset x -> {
                if (!blenderEmpty) {
                    return false;
                }
                set(0.0, 0.0);
            }
            case Node.Beardifier x -> set(0.0, 0.0);
            case Node.Marker m -> {
                Integer r = m.type() == Node.MarkerType.INTERPOLATED ? lerpIndex.get(m) : null;
                if (r != null) {
                    int b = r * 8;
                    double mn = corners[b];
                    double mx = mn;
                    for (int i = 1; i < 8; i++) {
                        double v = corners[b + i];
                        mn = Math.min(mn, v);
                        mx = Math.max(mx, v);
                    }
                    set(mn, mx);
                } else if (m.type() == Node.MarkerType.CACHE_2D && m.child() instanceof Node.Const c) {
                    set(c.value(), c.value());
                } else {
                    return eval(m.child());
                }
            }
            default -> {
                return false;
            }
        }
        return true;
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
            case SQUEEZE -> {
                // c / 2 - c^3 / 24 is monotone in the reals but a difference of two rounded terms in floating point
                double p = Interpreter.mapped(type, a);
                double q = Interpreter.mapped(type, b);
                set(widenDown(p), widenUp(q));
            }
        }
    }

    private static double widenDown(double v) {
        for (int i = 0; i < 4; i++) {
            v = Math.nextDown(v);
        }
        return v;
    }

    private static double widenUp(double v) {
        for (int i = 0; i < 4; i++) {
            v = Math.nextUp(v);
        }
        return v;
    }
}
