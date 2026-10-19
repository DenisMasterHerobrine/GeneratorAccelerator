package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.CoordSubstitution;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import java.util.List;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Lithostitched's density functions besides {@code fast_noise} ({@link LithostitchedDensity}), compiled. Each lowering mirrors the
 * function's {@code compute} instruction for instruction. The compiler then checks it against the object itself.
 *
 * <p>{@code shift} evaluates its input at {@code ((int) (x + shiftX), (int) (y + shiftY), (int) (z + shiftZ))}. The input is
 * rewritten to read those coordinates ({@link CoordSubstitution}). {@code select} walks its ranges in order. A range holds a value
 * as {@code Double.compareTo} decides, which differs from {@code <=} at NaN and at zero. {@code axis}, {@code mix}, the math
 * functions and {@code merged} map directly.
 *
 * <p>Every lowering gives the bounds the function itself reports, so vanilla's {@code min} and {@code max} skip their second
 * argument exactly when they would. Recognised by class name and record shape, with no dependency on the mod.
 */
public final class LithostitchedFunctions {
    private LithostitchedFunctions() {}

    private static final String PKG = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.";
    static final String SHIFT = PKG + "ShiftDensityFunction";
    static final String AXIS = PKG + "AxisDensityFunction";
    static final String SELECT = PKG + "SelectDensityFunction";
    static final String SELECTION = PKG + "SelectDensityFunction$Selection";
    static final String MIX = PKG + "MixDensityFunction";
    static final String MERGED = PKG + "marker.MergedDensityFunction";
    private static final String MATH = "java/lang/Math";
    private static final String D_D = "(D)D";

    public static void register() {
        add(SHIFT, LithostitchedFunctions::shift);
        add(AXIS, LithostitchedFunctions::axis);
        add(SELECT, LithostitchedFunctions::select);
        add(MIX, LithostitchedFunctions::mix);
        add(MERGED, (fn, ctx) -> {
            // MergedDensityFunction.compute: full.compute(context)
            Object[] c = Reflect.components(fn, MERGED, "original", "wrapped", "full");
            return c != null && c[2] instanceof DensityFunction full ? ctx.lower(full) : null;
        });
        add(PKG + "FloorDensityFunction", (fn, ctx) -> transform(fn, ctx, a -> call(ctx, "floor", a)));
        add(PKG + "CeilDensityFunction", (fn, ctx) -> transform(fn, ctx, a -> call(ctx, "ceil", a)));
        add(PKG + "SinDensityFunction", (fn, ctx) -> transform(fn, ctx, a -> call(ctx, "sin", a)));
        add(PKG + "CosDensityFunction", (fn, ctx) -> transform(fn, ctx, a -> call(ctx, "cos", a)));
        add(PKG + "SqrtDensityFunction", (fn, ctx) -> transform(fn, ctx, a -> sqrt(ctx, a)));
        add(LithostitchedCellular.CELLULAR, LithostitchedCellular::lower);
        dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers.addGpuSource(LithostitchedCellular.CL);
    }

    @SuppressWarnings("unchecked")
    private static void add(String name, DensityFunctionLowering lowering) {
        Class<?> type = Reflect.type(name);
        if (type != null && DensityFunction.class.isAssignableFrom(type)) {
            DensityFunctionLowering.register((Class<? extends DensityFunction>) type, lowering);
        }
    }

    /** {@code node} with the bounds {@code fn} reports ({@code node * 1.0}, the identity, when they differ). */
    private static Node bounded(Node node, DensityFunction fn, DensityFunctionLowering.Context ctx) {
        double min = fn.minValue(), max = fn.maxValue();
        if (Double.doubleToRawLongBits(node.min()) == Double.doubleToRawLongBits(min) && Double.doubleToRawLongBits(node.max()) == Double.doubleToRawLongBits(max)) {
            return node;
        }
        return ctx.intern(new Node.MulOrAdd(true, node, 1.0, min, max));
    }

    // ---- shift ---------------------------------------------------------------------------------

    private static Node shift(DensityFunction fn, DensityFunctionLowering.Context ctx) {
        Object[] c = Reflect.components(fn, SHIFT, "input", "shiftX", "shiftY", "shiftZ");
        if (c == null || !(c[0] instanceof DensityFunction input) || !(c[1] instanceof DensityFunction sx) || !(c[2] instanceof DensityFunction sy)
            || !(c[3] instanceof DensityFunction sz)) {
            return null;
        }
        // SimpleContext.of((double) blockX + shiftX.compute(ctx), ...), in x, y, z order, then input.compute at that context
        INode x = coordinate(ctx, 0, ctx.lower(sx));
        INode y = coordinate(ctx, 1, ctx.lower(sy));
        INode z = coordinate(ctx, 2, ctx.lower(sz));
        try {
            return bounded(CoordSubstitution.at(ctx.lower(input), x, y, z, ctx), fn, ctx);
        } catch (CoordSubstitution.Declined e) {
            ctx.decline("input at a shifted position: " + e.getMessage());
            return null;
        }
    }

    /** {@code (int) ((double) coordinate + shift)}, which is the coordinate itself for a shift of +0.0 or -0.0. */
    private static INode coordinate(DensityFunctionLowering.Context ctx, int axis, Node shift) {
        INode coordinate = new INode.IntCoord(axis);
        if (shift instanceof Node.Const k && k.value() == 0.0) {
            return coordinate;
        }
        Node sum = ctx.intern(new Node.StaticCall("$op", "dadd", "(DD)D", List.of(ctx.intern(new Node.IntToDouble(coordinate)), shift)));
        return new INode.DoubleToInt(sum);
    }

    // ---- axis ----------------------------------------------------------------------------------

    private static Node axis(DensityFunction fn, DensityFunctionLowering.Context ctx) {
        Object[] c = Reflect.components(fn, AXIS, "axis");
        if (c == null || !(c[0] instanceof Enum<?> axis)) {
            return null;
        }
        // (double) context.blockX(), blockY() or blockZ()
        int a = switch (axis.name()) {
            case "X" -> 0;
            case "Y" -> 1;
            case "Z" -> 2;
            default -> -1;
        };
        return a < 0 ? null : bounded(ctx.intern(new Node.IntToDouble(new INode.IntCoord(a))), fn, ctx);
    }

    // ---- mix -----------------------------------------------------------------------------------

    private static Node mix(DensityFunction fn, DensityFunctionLowering.Context ctx) {
        Object[] c = Reflect.components(fn, MIX, "input", "argument1", "argument2");
        if (c == null || !(c[0] instanceof DensityFunction inputFn) || !(c[1] instanceof DensityFunction a1Fn) || !(c[2] instanceof DensityFunction a2Fn)) {
            return null;
        }
        Node input = ctx.lower(inputFn), a1 = ctx.lower(a1Fn), a2 = ctx.lower(a2Fn);
        // argument1 * (1.0 - input) + argument2 * input
        Node blend = op(ctx, "dadd", op(ctx, "dmul", a1, op(ctx, "dsub", constant(ctx, 1.0), input)), op(ctx, "dmul", a2, input));
        // if (input <= 0.0) argument1, else if (input >= 1.0) argument2, else the blend. NaN takes the blend
        Node upper = ctx.intern(new Node.Select(cmp(Node.CmpKind.DCMPL, Node.JumpOp.GE, input, constant(ctx, 1.0)), a2, blend));
        return bounded(ctx.intern(new Node.Select(cmp(Node.CmpKind.DCMPG, Node.JumpOp.LE, input, constant(ctx, 0.0)), a1, upper)), fn, ctx);
    }

    // ---- select --------------------------------------------------------------------------------

    private static Node select(DensityFunction fn, DensityFunctionLowering.Context ctx) {
        Object[] c = Reflect.components(fn, SELECT, "input", "fallback", "selections", "min", "max");
        if (c == null || !(c[0] instanceof DensityFunction inputFn) || !(c[1] instanceof DensityFunction fallbackFn) || !(c[2] instanceof List<?> selections)) {
            return null;
        }
        Node value = ctx.lower(inputFn);
        Object[][] parts = new Object[selections.size()][];
        for (int i = 0; i < parts.length; i++) {
            Object[] s = Reflect.components(selections.get(i), SELECTION, "range", "function");
            if (s == null || !(s[0] instanceof InclusiveRange<?> range) || !(range.minInclusive() instanceof Double lo) || !(range.maxInclusive() instanceof Double hi)
                || !(s[1] instanceof DensityFunction f)) {
                return null;
            }
            parts[i] = new Object[] { lo, hi, f };
        }
        // the first selection whose range holds the value, else the fallback
        Node result = ctx.lower(fallbackFn);
        for (int i = parts.length - 1; i >= 0; i--) {
            Pred inRange = new And(atLeast(ctx, value, (Double) parts[i][0]), atMost(ctx, value, (Double) parts[i][1]));
            result = choose(ctx, inRange, ctx.lower((DensityFunction) parts[i][2]), result);
        }
        return bounded(result, fn, ctx);
    }

    /** A condition on doubles, built from JVM comparisons. Evaluated left to right, the right side only when it decides. */
    private sealed interface Pred permits Atom, And, Or, True {}

    private record Atom(Node.Cond cond) implements Pred {}

    private record And(Pred a, Pred b) implements Pred {}

    private record Or(Pred a, Pred b) implements Pred {}

    private record True() implements Pred {}

    private static Node choose(DensityFunctionLowering.Context ctx, Pred p, Node whenTrue, Node whenFalse) {
        return switch (p) {
            case True t -> whenTrue;
            case Atom a -> ctx.intern(new Node.Select(a.cond(), whenTrue, whenFalse));
            case And a -> choose(ctx, a.a(), choose(ctx, a.b(), whenTrue, whenFalse), whenFalse);
            case Or o -> choose(ctx, o.a(), whenTrue, choose(ctx, o.b(), whenTrue, whenFalse));
        };
    }

    /** {@code Double.compare(v, c) >= 0}: NaN is above every value, and -0.0 is below +0.0. */
    private static Pred atLeast(DensityFunctionLowering.Context ctx, Node v, double c) {
        if (Double.isNaN(c)) {
            return isNaN(v);
        }
        if (c == 0.0 && Double.doubleToRawLongBits(c) == 0L) {
            // above +0.0: positive values, +0.0 itself and NaN
            return new Or(atom(Node.CmpKind.DCMPL, Node.JumpOp.GT, v, constant(ctx, 0.0)), new Or(positiveZero(ctx, v), isNaN(v)));
        }
        // c == -0.0 included: v >= -0.0 holds at both zeros
        return new Or(atom(Node.CmpKind.DCMPL, Node.JumpOp.GE, v, constant(ctx, c)), isNaN(v));
    }

    /** {@code Double.compare(v, c) <= 0}. */
    private static Pred atMost(DensityFunctionLowering.Context ctx, Node v, double c) {
        if (Double.isNaN(c)) {
            return new True();
        }
        if (c == 0.0 && Double.doubleToRawLongBits(c) != 0L) {
            // below -0.0: negative values and -0.0 itself
            return new Or(atom(Node.CmpKind.DCMPG, Node.JumpOp.LT, v, constant(ctx, 0.0)), negativeZero(ctx, v));
        }
        // c == +0.0 included: v <= +0.0 holds at both zeros. NaN fails
        return atom(Node.CmpKind.DCMPG, Node.JumpOp.LE, v, constant(ctx, c));
    }

    private static Pred isNaN(Node v) {
        // dcmpl of v with itself is -1 only for NaN
        return atom(Node.CmpKind.DCMPL, Node.JumpOp.NE, v, v);
    }

    /** {@code v == 0 && 1.0 / v > 0}: +0.0. */
    private static Pred positiveZero(DensityFunctionLowering.Context ctx, Node v) {
        return new And(atom(Node.CmpKind.DCMPL, Node.JumpOp.EQ, v, constant(ctx, 0.0)),
            atom(Node.CmpKind.DCMPL, Node.JumpOp.GT, op(ctx, "ddiv", constant(ctx, 1.0), v), constant(ctx, 0.0)));
    }

    /** {@code v == 0 && 1.0 / v < 0}: -0.0. */
    private static Pred negativeZero(DensityFunctionLowering.Context ctx, Node v) {
        return new And(atom(Node.CmpKind.DCMPL, Node.JumpOp.EQ, v, constant(ctx, 0.0)),
            atom(Node.CmpKind.DCMPG, Node.JumpOp.LT, op(ctx, "ddiv", constant(ctx, 1.0), v), constant(ctx, 0.0)));
    }

    private static Atom atom(Node.CmpKind kind, Node.JumpOp op, Node a, Node b) {
        return new Atom(cmp(kind, op, a, b));
    }

    private static Node.Cond cmp(Node.CmpKind kind, Node.JumpOp op, Node a, Node b) {
        return new Node.Cond(kind, op, a, b, null, null);
    }

    // ---- math functions --------------------------------------------------------------------------

    /** {@code TransformerDensityFunction.compute}: {@code transform(argument.compute(context))}. */
    private static Node transform(DensityFunction fn, DensityFunctionLowering.Context ctx, java.util.function.UnaryOperator<Node> transform) {
        Object argument;
        try {
            argument = fn.getClass().getMethod("argument").invoke(fn);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return argument instanceof DensityFunction a ? bounded(transform.apply(ctx.lower(a)), fn, ctx) : null;
    }

    /** {@code value == 0.0 ? 0.0 : value > 0.0 ? Math.sqrt(value) : -Math.sqrt(-value)}. NaN takes the last arm. */
    private static Node sqrt(DensityFunctionLowering.Context ctx, Node v) {
        Node negative = negate(ctx, call(ctx, "sqrt", negate(ctx, v)));
        Node nonZero = ctx.intern(new Node.Select(cmp(Node.CmpKind.DCMPL, Node.JumpOp.GT, v, constant(ctx, 0.0)), call(ctx, "sqrt", v), negative));
        return ctx.intern(new Node.Select(cmp(Node.CmpKind.DCMPL, Node.JumpOp.EQ, v, constant(ctx, 0.0)), constant(ctx, 0.0), nonZero));
    }

    /** {@code dneg}, as the bytecode lifter writes it: {@code -0.0 - v}. */
    private static Node negate(DensityFunctionLowering.Context ctx, Node v) {
        return op(ctx, "dsub", constant(ctx, -0.0), v);
    }

    private static Node call(DensityFunctionLowering.Context ctx, String name, Node argument) {
        return ctx.intern(new Node.StaticCall(MATH, name, D_D, List.of(argument)));
    }

    private static Node op(DensityFunctionLowering.Context ctx, String name, Node a, Node b) {
        return ctx.intern(new Node.StaticCall("$op", name, "(DD)D", List.of(a, b)));
    }

    private static Node constant(DensityFunctionLowering.Context ctx, double v) {
        return ctx.intern(new Node.Const(v));
    }
}
