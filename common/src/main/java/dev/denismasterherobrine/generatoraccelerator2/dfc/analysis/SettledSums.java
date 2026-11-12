package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sums of a part and the old blended noise whose exact value no column root needs once the sum is large enough.
 *
 * <p>Vanilla's {@code sloped_cheese} is a y gradient term plus the blended noise. Its final density takes the cave branch of a
 * range choice once {@code sloped_cheese} reaches 1.5625, and there reads it only through {@code clamp(1.5 - 0.64 *
 * sloped_cheese, 0, 0.5)}, which is 0 from 2.34375 up. Deep underground the corner's value is then the same whatever the
 * noise. The blended noise lies within {@link BlendedSpec#bound}, so the column program computes the other part first and,
 * when that part minus the bound reaches the threshold, uses the threshold as the sum and skips the noise
 * ({@code Emitter}).
 *
 * <p>The threshold comes from an interval walk over every root. The sum lies in {@code [K, +inf]} and every other leaf
 * between its bounds. A clamp whose input lies wholly below or above its range gives that end of the range. A range choice
 * whose input lies wholly in one branch takes that branch, so neither its input nor the other branch matters. Everything
 * else passes the dependence on. The rules are {@link SkyBounds}' rules with the same floating point operations, whose
 * rounding is monotone. So when no root depends on the sum for some {@code K}, the program's own values from any sum at or
 * above {@code K} are the same. The smallest such {@code K} is searched for and then widened by a margin.
 *
 * <p>{@code -Dgeneratoraccelerator2.dfc.settledSums=false} turns it off.
 */
public final class SettledSums {
    private SettledSums() {}

    /** Not final so the exactness tests and benches can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.dfc.settledSums"));

    /** The sum stands at {@code threshold} when its other part minus {@code bound} reaches {@code threshold}. */
    public record Guard(double threshold, double bound) {}

    /** The guarded sums of the column program's {@code roots}, by their {@link Node.Ap2} node. Empty when none qualifies. */
    public static Map<Node, Guard> of(List<Node> roots, List<BlendedSpec> blended) {
        Map<Node, Guard> out = new IdentityHashMap<>();
        if (!ENABLED) {
            return out;
        }
        RefCount rc = RefCount.of(roots);
        CoordDeps deps = new CoordDeps(true);
        for (Node n : rc.postOrder()) {
            if (!(n instanceof Node.Ap2 a) || a.type() != Node.Ap2Type.ADD) {
                continue;
            }
            Node noise = a.a() instanceof Node.Blended ? a.a() : a.b() instanceof Node.Blended ? a.b() : null;
            // a noise read elsewhere too is computed anyway
            if (noise == null || a.a() == a.b() || rc.count(noise) != 1) {
                continue;
            }
            double bound = blended.get(((Node.Blended) noise).spec()).bound();
            if (!Double.isFinite(bound)) {
                continue;
            }
            Walk walk = new Walk(a, roots, blended);
            if (walk.touchesState(deps)) {
                continue;
            }
            double k = walk.smallest();
            if (Double.isFinite(k)) {
                out.put(a, new Guard(k + Math.abs(k) * 1e-9 + 1e-9, bound));
            }
        }
        return out;
    }

    /** A value's interval, and whether it can change with the sum. */
    private record Iv(boolean dep, double lo, double hi) {}

    private static final Iv ANY = new Iv(false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

    private static final class Walk {
        final Node sum;
        final List<Node> roots;
        final List<BlendedSpec> blended;
        final Map<Node, Boolean> holds = new IdentityHashMap<>();
        final Map<Node, Iv> memo = new IdentityHashMap<>();
        double k;

        Walk(Node sum, List<Node> roots, List<BlendedSpec> blended) {
            this.sum = sum;
            this.roots = roots;
            this.blended = blended;
        }

        /** The smallest {@code K} for which no root depends on the sum, or NaN when even a huge one does not settle them. */
        double smallest() {
            double high = 1e300;
            if (!free(high)) {
                return Double.NaN;
            }
            if (free(-high)) {
                return -high;
            }
            // over the doubles in order: their bits, with the negative ones flipped
            long lo = order(-high), hi = order(high);
            // the ends lie far apart, so the midpoint is taken without overflow
            while (lo < hi - 1) {
                long mid = (lo & hi) + ((lo ^ hi) >> 1);
                if (free(value(mid))) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return value(hi);
        }

        private static long order(double d) {
            long bits = Double.doubleToLongBits(d);
            return bits < 0 ? bits ^ Long.MAX_VALUE : bits;
        }

        private static double value(long order) {
            return Double.longBitsToDouble(order < 0 ? order ^ Long.MAX_VALUE : order);
        }

        /**
         * Whether a root holding the sum holds something whose value depends on what was evaluated before: another mod's
         * function, the beardifier, a cache whose value is the first height's. The sum's value can change which nodes a
         * corner evaluates, so such a root keeps the noise.
         */
        boolean touchesState(CoordDeps deps) {
            Map<Node, Boolean> seen = new IdentityHashMap<>();
            for (Node r : roots) {
                if (holds(r) && stateful(r, deps, seen)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean stateful(Node n, CoordDeps deps, Map<Node, Boolean> seen) {
            if (seen.put(n, Boolean.TRUE) != null) {
                return false;
            }
            boolean own = switch (n) {
                case Node.Unsupported x -> true;
                case Node.Beardifier x -> true;
                case Node.EndIslands x -> true;
                case Node.Marker m -> switch (m.type()) {
                    case CACHE_ONCE, FLAT_CACHE -> false;
                    case CACHE_2D -> !deps.of(m.child()).isXzOrConst();
                    case INTERPOLATED, CACHE_ALL_IN_CELL -> true;
                };
                default -> false;
            };
            if (own) {
                return true;
            }
            for (Node c : RefCount.children(n)) {
                if (stateful(c, deps, seen)) {
                    return true;
                }
            }
            return false;
        }

        /** Whether no root depends on the sum while it is at least {@code k}. */
        boolean free(double k) {
            this.k = k;
            memo.clear();
            for (Node r : roots) {
                if (holds(r) && iv(r).dep) {
                    return false;
                }
            }
            return true;
        }

        /** Whether the sum is below {@code n}. */
        boolean holds(Node n) {
            Boolean known = holds.get(n);
            if (known == null) {
                boolean found = n == sum;
                if (!found) {
                    for (Node c : RefCount.children(n)) {
                        if (holds(c)) {
                            found = true;
                            break;
                        }
                    }
                }
                holds.put(n, found);
                known = found;
            }
            return known;
        }

        Iv iv(Node n) {
            Iv known = memo.get(n);
            if (known == null) {
                known = make(n);
                if (Double.isNaN(known.lo) || Double.isNaN(known.hi)) {
                    known = new Iv(known.dep, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                }
                memo.put(n, known);
            }
            return known;
        }

        private Iv make(Node n) {
            if (n == sum) {
                return new Iv(true, k, Double.POSITIVE_INFINITY);
            }
            return switch (n) {
                case Node.Const c -> new Iv(false, c.value(), c.value());
                case Node.Ap2 a -> {
                    Iv x = iv(a.a());
                    Iv y = iv(a.b());
                    boolean dep = x.dep || y.dep;
                    yield switch (a.type()) {
                        case ADD -> new Iv(dep, x.lo + y.lo, x.hi + y.hi);
                        case MUL -> mul(dep, x.lo, x.hi, y.lo, y.hi);
                        case MIN -> new Iv(dep, Math.min(x.lo, y.lo), Math.min(x.hi, y.hi));
                        case MAX -> new Iv(dep, Math.max(x.lo, y.lo), Math.max(x.hi, y.hi));
                    };
                }
                case Node.MulOrAdd m -> {
                    Iv x = iv(m.input());
                    yield m.mul() ? mul(x.dep, x.lo, x.hi, m.argument(), m.argument()) : new Iv(x.dep, x.lo + m.argument(), x.hi + m.argument());
                }
                case Node.Mapped m -> mapped(m.type(), iv(m.input()));
                case Node.Clamp c -> {
                    Iv x = iv(c.input());
                    if (x.hi < c.lo()) {
                        yield new Iv(false, c.lo(), c.lo());
                    }
                    if (x.lo > c.hi()) {
                        yield new Iv(false, c.hi(), c.hi());
                    }
                    yield new Iv(x.dep, Interpreter.clamp(x.lo, c.lo(), c.hi()), Interpreter.clamp(x.hi, c.lo(), c.hi()));
                }
                case Node.RangeChoice r -> {
                    Iv x = iv(r.input());
                    boolean canIn = x.hi >= r.minInclusive() && x.lo < r.maxExclusive();
                    boolean canOut = x.lo < r.minInclusive() || x.hi >= r.maxExclusive();
                    if (canIn && !canOut) {
                        yield iv(r.inRange());
                    }
                    if (canOut && !canIn) {
                        yield iv(r.outRange());
                    }
                    Iv in = iv(r.inRange());
                    Iv out = iv(r.outRange());
                    yield new Iv(x.dep || in.dep || out.dep, Math.min(in.lo, out.lo), Math.max(in.hi, out.hi));
                }
                case Node.YGradient y -> new Iv(false, y.min(), y.max());
                // vanilla's own bounds of the noises, which its MIN/MAX short cuts rely on too
                case Node.Noise x -> bounded(n, x.min(), x.max());
                case Node.ShiftedNoise x -> bounded(n, x.min(), x.max());
                case Node.Shift x -> bounded(n, x.min(), x.max());
                case Node.ShiftA x -> bounded(n, x.min(), x.max());
                case Node.ShiftB x -> bounded(n, x.min(), x.max());
                case Node.Weird x -> bounded(n, x.min(), x.max());
                case Node.Blended x -> new Iv(false, -blended.get(x.spec()).bound(), blended.get(x.spec()).bound());
                // computed in the reals from the control points, and the float evaluation can land a float ulp outside
                case Node.Spline x -> bounded(n, Math.nextDown(Math.nextDown((float) x.min())), Math.nextUp(Math.nextUp((float) x.max())));
                // a cache_once holds its child's value
                case Node.Marker m when m.type() == Node.MarkerType.CACHE_ONCE -> iv(m.child());
                default -> opaque(n);
            };
        }

        /** A leaf between its bounds, which follows the sum only through its inputs. */
        private Iv bounded(Node n, double lo, double hi) {
            return new Iv(anyDep(n), lo, hi);
        }

        /** Any other node: unknown bounds, and it follows the sum when any input does. */
        private Iv opaque(Node n) {
            return anyDep(n) ? new Iv(true, ANY.lo, ANY.hi) : ANY;
        }

        private boolean anyDep(Node n) {
            for (Node c : RefCount.children(n)) {
                if (holds(c) && iv(c).dep) {
                    return true;
                }
            }
            return false;
        }

        private static Iv mul(boolean dep, double alo, double ahi, double blo, double bhi) {
            double p = alo * blo;
            double q = alo * bhi;
            double r = ahi * blo;
            double s = ahi * bhi;
            return new Iv(dep, Math.min(Math.min(p, q), Math.min(r, s)), Math.max(Math.max(p, q), Math.max(r, s)));
        }

        /** {@link SkyBounds}' bounds of a mapped value. */
        private static Iv mapped(Node.MappedType type, Iv x) {
            double a = x.lo;
            double b = x.hi;
            return switch (type) {
                case ABS -> a >= 0.0 ? new Iv(x.dep, a, b) : b <= 0.0 ? new Iv(x.dep, -b, -a) : new Iv(x.dep, 0.0, Math.max(-a, b));
                case SQUARE -> new Iv(x.dep, a <= 0.0 && b >= 0.0 ? 0.0 : Math.min(a * a, b * b), Math.max(a * a, b * b));
                case CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE -> new Iv(x.dep, Interpreter.mapped(type, a), Interpreter.mapped(type, b));
                // c / 2 - c^3 / 24 is monotone in the reals but a difference of two rounded terms in floating point
                case SQUEEZE -> new Iv(x.dep, Math.nextDown(Math.nextDown(Math.nextDown(Math.nextDown(Interpreter.mapped(type, a))))),
                    Math.nextUp(Math.nextUp(Math.nextUp(Math.nextUp(Interpreter.mapped(type, b))))));
            };
        }
    }
}
