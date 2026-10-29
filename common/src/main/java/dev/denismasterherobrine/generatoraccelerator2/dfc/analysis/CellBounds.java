package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
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
 *
 * <p>Noise leaves evaluated per block (datapack terrain: Terralith's caves, Tectonic's ridges) are first bounded by the
 * noise's own maximum, as vanilla's MIN/MAX short cuts bound them. That rarely bounds a cell away from zero. A cell that
 * stays mixed is then bounded again with each noise bounded over the cell itself ({@link NoiseBounds}), when the tree
 * has such leaves. The second pass costs about as much as a few blocks of the per-block fill, so it is kept only while it
 * decides cells: after 4096 tries with fewer than 2% decided it pauses for 32768 mixed cells ({@link Gate}).
 * {@code -Dgeneratoraccelerator2.dfc.noiseBounds=false} turns the second pass off.
 */
public final class CellBounds {
    public static final int MIXED = 0;
    public static final int SKY = 1;
    public static final int SOLID = 2;

    /** Not final so a bench can compare both ways. */
    public static boolean TIGHT = !"false".equals(System.getProperty("generatoraccelerator2.dfc.noiseBounds"));
    /** Mixed cells bounded again with the noise leaves bounded over the cell, and those the second pass decided. */
    public static final LongAdder TIGHT_TRIED = new LongAdder();
    public static final LongAdder TIGHT_WON = new LongAdder();
    /** Mixed cells the second pass skipped while paused. */
    public static final LongAdder TIGHT_PAUSED = new LongAdder();

    /** Whether the second pass pays for itself on this program's terrain. Shared by every chunk's instance. Racy counts are fine here. */
    static final class Gate {
        private static final int WINDOW = 4096;
        private static final int PAUSE = 32768;
        private int tried;
        private int won;
        private int paused;
        private volatile boolean off;

        boolean allows() {
            if (!off) {
                return true;
            }
            if (++paused >= PAUSE) {
                paused = 0;
                tried = 0;
                won = 0;
                off = false;
                return true;
            }
            TIGHT_PAUSED.increment();
            return false;
        }

        void record(boolean win) {
            tried++;
            if (win) {
                won++;
            }
            if (tried >= WINDOW) {
                if (won * 50 < tried) {
                    off = true;
                }
                tried = 0;
                won = 0;
            }
        }
    }

    private final Node root;
    private final Map<Node.Marker, Integer> lerpIndex;
    private final NoiseSpec[] specs;
    private final boolean hasNoise;
    private final Gate gate;
    private NoiseBounds noise;
    private double[] corners;
    private int x0;
    private int x1;
    private int y0;
    private int y1;
    private int z0;
    private int z1;
    private boolean blenderEmpty;
    /** Whether noise leaves are bounded over the cell (second pass) or by their maximum (first pass). */
    private boolean tight;
    private double lo;
    private double hi;
    /** The bounds of the last {@link #classify}, for diagnostics. */
    public double lastLo;
    public double lastHi;

    private CellBounds(Node root, Map<Node.Marker, Integer> lerpIndex, NoiseSpec[] specs, boolean hasNoise, Gate gate) {
        this.root = root;
        this.lerpIndex = lerpIndex;
        this.specs = specs;
        this.hasNoise = hasNoise;
        this.gate = gate;
    }

    /**
     * Bounds for {@code root} (the per-block tree of a cell program), or null when some node is not covered. The instance
     * keeps the state of a classification, so a template shares it only as a prototype. Each chunk binding takes its own
     * instance through {@link #fresh()}. {@code specs} are the program's noises, for the second pass.
     */
    @Nullable
    public static CellBounds of(Node root, Map<Node.Marker, Integer> lerpIndex, NoiseSpec[] specs) {
        if (!covered(root, lerpIndex, new IdentityHashMap<>())) {
            return null;
        }
        return new CellBounds(root, lerpIndex, specs, hasNoise(root, new IdentityHashMap<>()), new Gate());
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
            List<Node> children = switch (n) {
                case Node.Ap2 a -> List.of(a.a(), a.b());
                case Node.MulOrAdd m -> List.of(m.input());
                case Node.Mapped m -> List.of(m.input());
                case Node.Clamp c -> List.of(c.input());
                case Node.RangeChoice r -> List.of(r.input(), r.inRange(), r.outRange());
                case Node.ShiftedNoise s -> List.of(s.shiftX(), s.shiftY(), s.shiftZ());
                case Node.Weird w -> List.of(w.input());
                case Node.BlendDensity b -> List.of(b.input());
                case Node.Marker m -> m.type() == Node.MarkerType.INTERPOLATED || m.type() == Node.MarkerType.CACHE_ONCE || m.type() == Node.MarkerType.CACHE_2D
                    ? List.of(m.child()) : List.of();
                default -> List.of();
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
        return new CellBounds(root, lerpIndex, specs, hasNoise, gate);
    }

    /** Whether the tree has noise leaves the second pass could tighten. */
    public boolean hasNoise() {
        return hasNoise;
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

    /** Whether the per-block part of {@code n} (above the interpolated markers) samples a noise the second pass can bound. */
    private static boolean hasNoise(Node n, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return false;
        }
        return switch (n) {
            case Node.Noise x -> true;
            case Node.ShiftedNoise x -> true;
            case Node.ShiftA x -> true;
            case Node.ShiftB x -> true;
            case Node.Shift x -> true;
            case Node.Ap2 a -> hasNoise(a.a(), seen) || hasNoise(a.b(), seen);
            case Node.MulOrAdd m -> hasNoise(m.input(), seen);
            case Node.Mapped m -> hasNoise(m.input(), seen);
            case Node.Clamp c -> hasNoise(c.input(), seen);
            case Node.RangeChoice r -> hasNoise(r.input(), seen) || hasNoise(r.inRange(), seen) || hasNoise(r.outRange(), seen);
            case Node.Weird w -> hasNoise(w.input(), seen);
            case Node.BlendDensity b -> hasNoise(b.input(), seen);
            case Node.Marker m -> (m.type() == Node.MarkerType.CACHE_ONCE || m.type() == Node.MarkerType.CACHE_2D) && hasNoise(m.child(), seen);
            default -> false;
        };
    }

    /**
     * Classifies the cell {@code [x0, x0 + w) x [y0, y0 + h) x [z0, z0 + w)} from the corners ({@code corners[root * 8 + i]}
     * as the cell programs read them) when no structure piece reaches it.
     */
    public int classify(double[] corners, int x0, int y0, int z0, int w, int h, boolean blenderEmpty) {
        this.corners = corners;
        this.x0 = x0;
        this.x1 = x0 + w - 1;
        this.y0 = y0;
        this.y1 = y0 + h - 1;
        this.z0 = z0;
        this.z1 = z0 + w - 1;
        this.blenderEmpty = blenderEmpty;
        this.tight = false;
        int cls = pass();
        if (cls == MIXED && TIGHT && hasNoise && gate.allows()) {
            this.tight = true;
            if (noise == null) {
                noise = new NoiseBounds();
            }
            cls = pass();
            TIGHT_TRIED.increment();
            boolean won = cls != MIXED;
            if (won) {
                TIGHT_WON.increment();
            }
            gate.record(won);
        }
        return cls;
    }

    /** While {@link #classifyIntervals} runs: each interpolated root's bounds, instead of the corners. */
    private double[] rootLo;
    private double[] rootHi;

    /**
     * {@link #classify}'s first pass over the box {@code [x0, x0 + w) x [y0, y1] x [z0, z0 + w)}, with each interpolated root
     * {@link #interpolatedRoots} names within {@code [rootLo[r], rootHi[r]]} instead of within eight corners. No old-chunk
     * blending. The second pass and its statistics are left out.
     */
    public int classifyIntervals(double[] rootLo, double[] rootHi, int x0, int y0, int z0, int w, int y1) {
        this.x0 = x0;
        this.x1 = x0 + w - 1;
        this.y0 = y0;
        this.y1 = y1;
        this.z0 = z0;
        this.z1 = z0 + w - 1;
        this.blenderEmpty = true;
        this.tight = false;
        this.rootLo = rootLo;
        this.rootHi = rootHi;
        try {
            return pass();
        } finally {
            this.rootLo = null;
            this.rootHi = null;
        }
    }

    /** The interpolated roots (their indexes) whose corners the first pass reads. */
    public int[] interpolatedRoots() {
        TreeSet<Integer> out = new TreeSet<>();
        collectRoots(root, out, new IdentityHashMap<>());
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The interpolated markers {@link #eval} reaches when not {@link #tight}, as it reaches them. */
    private void collectRoots(Node n, Set<Integer> out, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        switch (n) {
            case Node.Ap2 a -> {
                collectRoots(a.a(), out, seen);
                collectRoots(a.b(), out, seen);
            }
            case Node.MulOrAdd m -> collectRoots(m.input(), out, seen);
            case Node.Mapped m -> collectRoots(m.input(), out, seen);
            case Node.Clamp c -> collectRoots(c.input(), out, seen);
            case Node.RangeChoice r -> {
                collectRoots(r.input(), out, seen);
                collectRoots(r.inRange(), out, seen);
                collectRoots(r.outRange(), out, seen);
            }
            case Node.BlendDensity b -> collectRoots(b.input(), out, seen);
            case Node.Marker m -> {
                Integer r = m.type() == Node.MarkerType.INTERPOLATED ? lerpIndex.get(m) : null;
                if (r != null) {
                    out.add(r);
                } else if (!(m.type() == Node.MarkerType.CACHE_2D && m.child() instanceof Node.Const)) {
                    collectRoots(m.child(), out, seen);
                }
            }
            default -> {
            }
        }
    }

    private int pass() {
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
            case Node.Noise x -> {
                // noise.getValue(blockX * xzScale, blockY * yScale, blockZ * xzScale)
                if (!tight || !noiseOver(x.spec(), scaled(x0, x1, x.xzScale()), ilo, ihi, scaled(y0, y1, x.yScale()), ilo, ihi,
                    scaled(z0, z1, x.xzScale()), ilo, ihi, 1.0)) {
                    set(-x.max(), x.max());
                }
            }
            case Node.ShiftedNoise x -> {
                if (!tight) {
                    set(-x.max(), x.max());
                } else {
                    // noise.getValue(blockX * xzScale + shiftX, blockY * yScale + shiftY, blockZ * xzScale + shiftZ)
                    if (!eval(x.shiftX())) {
                        return false;
                    }
                    double sxl = lo, sxh = hi;
                    if (!eval(x.shiftY())) {
                        return false;
                    }
                    double syl = lo, syh = hi;
                    if (!eval(x.shiftZ())) {
                        return false;
                    }
                    double szl = lo, szh = hi;
                    scaled(x0, x1, x.xzScale());
                    double xl = ilo + sxl, xh = ihi + sxh;
                    scaled(y0, y1, x.yScale());
                    double yl = ilo + syl, yh = ihi + syh;
                    scaled(z0, z1, x.xzScale());
                    double zl = ilo + szl, zh = ihi + szh;
                    if (!noiseOver(x.spec(), 0, xl, xh, 0, yl, yh, 0, zl, zh, 1.0)) {
                        set(-x.max(), x.max());
                    }
                }
            }
            case Node.ShiftA x -> {
                // noise.getValue(blockX * 0.25, 0.0, blockZ * 0.25) * 4.0
                if (!tight || !noiseOver(x.spec(), scaled(x0, x1, 0.25), ilo, ihi, 0, 0.0, 0.0, scaled(z0, z1, 0.25), ilo, ihi, 4.0)) {
                    set(-x.max(), x.max());
                }
            }
            case Node.ShiftB x -> {
                // noise.getValue(blockZ * 0.25, blockX * 0.25, 0.0) * 4.0
                if (!tight || !noiseOver(x.spec(), scaled(z0, z1, 0.25), ilo, ihi, scaled(x0, x1, 0.25), ilo, ihi, 0, 0.0, 0.0, 4.0)) {
                    set(-x.max(), x.max());
                }
            }
            case Node.Shift x -> {
                // noise.getValue(blockX * 0.25, blockY * 0.25, blockZ * 0.25) * 4.0
                if (!tight || !noiseOver(x.spec(), scaled(x0, x1, 0.25), ilo, ihi, scaled(y0, y1, 0.25), ilo, ihi, scaled(z0, z1, 0.25), ilo, ihi, 4.0)) {
                    set(-x.max(), x.max());
                }
            }
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
                if (r != null && rootLo != null) {
                    set(rootLo[r], rootHi[r]);
                } else if (r != null) {
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

    /** The last {@link #scaled} interval. */
    private double ilo;
    private double ihi;

    /**
     * {@code (double) a * scale} over the integers {@code [a0, a1]}, into {@link #ilo}/{@link #ihi}: the two ends as the
     * programs compute them. Rounding is monotone, so every block between lands between them. Returns 0 so it can sit in an
     * argument list.
     */
    private int scaled(int a0, int a1, double scale) {
        double p = (double) a0 * scale;
        double q = (double) a1 * scale;
        ilo = Math.min(p, q);
        ihi = Math.max(p, q);
        return 0;
    }

    /**
     * Sets the bounds of {@code specs[spec]} over the input box, times {@code factor}. The unused int arguments let the
     * callers compute each axis with {@link #scaled} in place. False when the noise could not be bounded (inputs past the
     * wrap limit).
     */
    private boolean noiseOver(int spec, int unusedX, double xl, double xh, int unusedY, double yl, double yh, int unusedZ, double zl, double zh, double factor) {
        if (!noise.normal(specs[spec], xl, xh, yl, yh, zl, zh)) {
            return false;
        }
        set(noise.lo * factor, noise.hi * factor);
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
            // decreasing on either side of 0. A range that reaches 0 can give either infinity.
            case INVERT -> set(a > 0.0 || b < 0.0 ? 1.0 / b : Double.NEGATIVE_INFINITY, a > 0.0 || b < 0.0 ? 1.0 / a : Double.POSITIVE_INFINITY);
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
