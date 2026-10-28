package dev.denismasterherobrine.generatoraccelerator2.dfc.program;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.lift.StaticCalls;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Reference evaluator over the IR. Mirrors vanilla {@code compute} operation by operation and is
 * the oracle every code generator is tested against. It evaluates markers transparently, which is
 * what vanilla does for the RandomState-level router and for the marker-stripped climate sampler.
 */
public final class Interpreter {
    private final NoiseSpec[] specs;
    private final BlendedSpec[] blended;

    public Interpreter(List<NoiseSpec> specs, List<BlendedSpec> blended) {
        this.specs = specs.toArray(new NoiseSpec[0]);
        this.blended = blended.toArray(new BlendedSpec[0]);
    }

    public NoiseSpec spec(int index) {
        return specs[index];
    }

    public double eval(Node node, DensityFunction.FunctionContext ctx) {
        if (node instanceof Node.Const c) {
            return c.value();
        } else if (node instanceof Node.Ap2 a) {
            double d0 = eval(a.a(), ctx);
            return switch (a.type()) {
                case ADD -> d0 + eval(a.b(), ctx);
                case MUL -> d0 == 0.0 ? 0.0 : d0 * eval(a.b(), ctx);
                case MIN -> d0 < a.b().min() ? d0 : Math.min(d0, eval(a.b(), ctx));
                case MAX -> d0 > a.b().max() ? d0 : Math.max(d0, eval(a.b(), ctx));
            };
        } else if (node instanceof Node.MulOrAdd m) {
            double v = eval(m.input(), ctx);
            return m.mul() ? v * m.argument() : v + m.argument();
        } else if (node instanceof Node.Mapped m) {
            return mapped(m.type(), eval(m.input(), ctx));
        } else if (node instanceof Node.Clamp c) {
            return clamp(eval(c.input(), ctx), c.lo(), c.hi());
        } else if (node instanceof Node.RangeChoice r) {
            double d0 = eval(r.input(), ctx);
            return d0 >= r.minInclusive() && d0 < r.maxExclusive() ? eval(r.inRange(), ctx) : eval(r.outRange(), ctx);
        } else if (node instanceof Node.YGradient y) {
            return clampedMap((double) ctx.blockY(), (double) y.fromY(), (double) y.toY(), y.fromValue(), y.toValue());
        } else if (node instanceof Node.Noise n) {
            return specs[n.spec()].getValue((double) ctx.blockX() * n.xzScale(), (double) ctx.blockY() * n.yScale(), (double) ctx.blockZ() * n.xzScale());
        } else if (node instanceof Node.ShiftedNoise s) {
            double d0 = (double) ctx.blockX() * s.xzScale() + eval(s.shiftX(), ctx);
            double d1 = (double) ctx.blockY() * s.yScale() + eval(s.shiftY(), ctx);
            double d2 = (double) ctx.blockZ() * s.xzScale() + eval(s.shiftZ(), ctx);
            return specs[s.spec()].getValue(d0, d1, d2);
        } else if (node instanceof Node.ShiftA s) {
            return specs[s.spec()].getValue((double) ctx.blockX() * 0.25, 0.0, (double) ctx.blockZ() * 0.25) * 4.0;
        } else if (node instanceof Node.ShiftB s) {
            return specs[s.spec()].getValue((double) ctx.blockZ() * 0.25, (double) ctx.blockX() * 0.25, 0.0) * 4.0;
        } else if (node instanceof Node.Shift s) {
            return specs[s.spec()].getValue((double) ctx.blockX() * 0.25, (double) ctx.blockY() * 0.25, (double) ctx.blockZ() * 0.25) * 4.0;
        } else if (node instanceof Node.Weird w) {
            double input = eval(w.input(), ctx);
            double d0 = w.type1() ? rarity3D(input) : rarity2D(input);
            double noise = w.spec() < 0 ? 0.0 : specs[w.spec()].getValue((double) ctx.blockX() / d0, (double) ctx.blockY() / d0, (double) ctx.blockZ() / d0);
            return d0 * Math.abs(noise);
        } else if (node instanceof Node.Spline s) {
            return (double) spline(s.spline(), ctx);
        } else if (node instanceof Node.Blended b) {
            return blended[b.spec()].compute(ctx.blockX(), ctx.blockY(), ctx.blockZ());
        } else if (node instanceof Node.BlendDensity b) {
            return ctx.getBlender().blendDensity(ctx, eval(b.input(), ctx));
        } else if (node instanceof Node.BlendAlpha a) {
            return 1.0;
        } else if (node instanceof Node.BlendOffset o) {
            return 0.0;
        } else if (node instanceof Node.Beardifier b) {
            return b.vanilla().compute(ctx);
        } else if (node instanceof Node.EndIslands e) {
            return e.vanilla().compute(ctx);
        } else if (node instanceof Node.Marker m) {
            return eval(m.child(), ctx);
        } else if (node instanceof Node.Unsupported u) {
            return u.vanilla().compute(ctx);
        } else if (node instanceof Node.Select s) {
            return jumps(s.cond(), ctx) ? eval(s.whenJump(), ctx) : eval(s.whenFall(), ctx);
        } else if (node instanceof Node.StaticCall c) {
            if (c.owner().equals("$op")) {
                double a = eval((Node) c.args().get(0), ctx);
                double b = eval((Node) c.args().get(1), ctx);
                return switch (c.name()) {
                    case "dadd" -> a + b;
                    case "dsub" -> a - b;
                    case "dmul" -> a * b;
                    case "ddiv" -> a / b;
                    default -> a % b;
                };
            }
            return (double) StaticCalls.invoke(c.owner(), c.name(), c.desc(), callArgs(c.args(), ctx));
        } else if (node instanceof Node.NoiseAt n) {
            return specs[n.spec()].getValue(eval(n.x(), ctx), eval(n.y(), ctx), eval(n.z(), ctx));
        } else if (node instanceof Node.IntToDouble i) {
            return (double) evalInt(i.value(), ctx);
        } else if (node instanceof Node.RoundFloat r) {
            return (double) (float) eval(r.value(), ctx);
        } else {
            throw new IllegalStateException(String.valueOf(node));
        }
    }

    public int evalInt(INode node, DensityFunction.FunctionContext ctx) {
        if (node instanceof INode.IntConst c) {
            return c.value();
        } else if (node instanceof INode.IntCoord c) {
            return c.axis() == 0 ? ctx.blockX() : c.axis() == 1 ? ctx.blockY() : ctx.blockZ();
        } else if (node instanceof INode.IntBin b) {
            int x = evalInt(b.a(), ctx);
            int y = evalInt(b.b(), ctx);
            return switch (b.op()) {
                case ADD -> x + y;
                case SUB -> x - y;
                case MUL -> x * y;
                case DIV -> x / y;
                case REM -> x % y;
                case SHL -> x << y;
                case SHR -> x >> y;
                case USHR -> x >>> y;
                case AND -> x & y;
                case OR -> x | y;
                case XOR -> x ^ y;
            };
        } else if (node instanceof INode.IntNeg g) {
            return -evalInt(g.a(), ctx);
        } else if (node instanceof INode.DoubleToInt d) {
            return (int) eval(d.value(), ctx);
        } else if (node instanceof INode.IntStaticCall c) {
            return (int) StaticCalls.invoke(c.owner(), c.name(), c.desc(), callArgs(c.args(), ctx));
        } else if (node instanceof INode.SelectInt s) {
            return jumps(s.cond(), ctx) ? evalInt(s.whenJump(), ctx) : evalInt(s.whenFall(), ctx);
        } else {
            throw new IllegalStateException(String.valueOf(node));
        }
    }

    private Object[] callArgs(List<Object> args, DensityFunction.FunctionContext ctx) {
        Object[] out = new Object[args.size()];
        for (int i = 0; i < out.length; i++) {
            Object a = args.get(i);
            out[i] = a instanceof Node n ? (Object) eval(n, ctx) : (Object) evalInt((INode) a, ctx);
        }
        return out;
    }

    /** Evaluates a lifted condition exactly like the JVM: compare, then apply the jump opcode to the result. */
    public boolean jumps(Node.Cond c, DensityFunction.FunctionContext ctx) {
        int r;
        if (c.kind() == Node.CmpKind.ICMP) {
            int a = evalInt(c.intA(), ctx);
            int b = evalInt(c.intB(), ctx);
            r = Integer.compare(a, b);
        } else {
            double a = eval(c.a(), ctx);
            double b = eval(c.b(), ctx);
            if (Double.isNaN(a) || Double.isNaN(b)) {
                r = c.kind() == Node.CmpKind.DCMPL ? -1 : 1;
            } else {
                r = a < b ? -1 : (a == b ? 0 : 1);
            }
        }
        return switch (c.op()) {
            case EQ -> r == 0;
            case NE -> r != 0;
            case LT -> r < 0;
            case GE -> r >= 0;
            case GT -> r > 0;
            case LE -> r <= 0;
        };
    }

    /** {@code DensityFunctions.Mapped.transform}. */
    public static double mapped(Node.MappedType type, double v) {
        return switch (type) {
            case ABS -> Math.abs(v);
            case SQUARE -> v * v;
            case CUBE -> v * v * v;
            case HALF_NEGATIVE -> v > 0.0 ? v : v * 0.5;
            case QUARTER_NEGATIVE -> v > 0.0 ? v : v * 0.25;
            case SQUEEZE -> {
                double d0 = clamp(v, -1.0, 1.0);
                yield d0 / 2.0 - d0 * d0 * d0 / 24.0;
            }
        };
    }

    /** {@code Mth.clamp(double, double, double)}. */
    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** {@code Mth.clampedMap} = {@code clampedLerp(from, to, inverseLerp(v, a, b))}. */
    public static double clampedMap(double v, double a, double b, double from, double to) {
        double t = (v - a) / (b - a);
        if (t < 0.0) {
            return from;
        }
        return t > 1.0 ? to : from + t * (to - from);
    }

    /** {@code NoiseRouterData.QuantizedSpaghettiRarity.getSpaghettiRarity3D}. */
    public static double rarity3D(double v) {
        if (v < -0.5) {
            return 0.75;
        } else if (v < 0.0) {
            return 1.0;
        } else {
            return v < 0.5 ? 1.5 : 2.0;
        }
    }

    /** {@code NoiseRouterData.QuantizedSpaghettiRarity.getSphaghettiRarity2D}. */
    public static double rarity2D(double v) {
        if (v < -0.75) {
            return 0.5;
        } else if (v < -0.5) {
            return 0.75;
        } else if (v < 0.5) {
            return 1.0;
        } else {
            return v < 0.75 ? 2.0 : 3.0;
        }
    }

    /** {@code CubicSpline.Multipoint.apply}, in float. */
    private float spline(Node.SplineSpec spec, DensityFunction.FunctionContext ctx) {
        if (spec instanceof Node.SplineConst c) {
            return c.value();
        }
        Node.SplineMulti m = (Node.SplineMulti) spec;
        float[] locations = m.locations();
        float f = (float) eval(m.coordinate(), ctx);
        int i = findIntervalStart(locations, f);
        int j = locations.length - 1;
        if (i < 0) {
            return linearExtend(f, locations, spline(m.values().get(0), ctx), m.derivatives(), 0);
        } else if (i == j) {
            return linearExtend(f, locations, spline(m.values().get(j), ctx), m.derivatives(), j);
        } else {
            float f1 = locations[i];
            float f2 = locations[i + 1];
            float f3 = (f - f1) / (f2 - f1);
            float f4 = m.derivatives()[i];
            float f5 = m.derivatives()[i + 1];
            float f6 = spline(m.values().get(i), ctx);
            float f7 = spline(m.values().get(i + 1), ctx);
            float f8 = f4 * (f2 - f1) - (f7 - f6);
            float f9 = -f5 * (f2 - f1) + (f7 - f6);
            return lerp(f3, f6, f7) + f3 * (1.0F - f3) * lerp(f3, f8, f9);
        }
    }

    public static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    public static float linearExtend(float f, float[] locations, float value, float[] derivatives, int index) {
        float d = derivatives[index];
        return d == 0.0F ? value : value + d * (f - locations[index]);
    }

    /** {@code Mth.binarySearch(0, locations.length, i -> f < locations[i]) - 1}. */
    public static int findIntervalStart(float[] locations, float f) {
        int min = 0;
        int i = locations.length;
        while (i > 0) {
            int j = i / 2;
            int k = min + j;
            if (f < locations[k]) {
                i = j;
            } else {
                min = k + 1;
                i -= j + 1;
            }
        }
        return min - 1;
    }
}
