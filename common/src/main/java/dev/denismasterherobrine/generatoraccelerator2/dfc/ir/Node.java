package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Compiler IR. Every node mirrors one vanilla {@code DensityFunctions} record and carries the
 * vanilla {@code minValue()/maxValue()} of the object it was built from, because vanilla's
 * {@code Ap2} MIN/MAX short-circuits read those bounds and we must reproduce them exactly.
 *
 * <p>Nodes are immutable records and are interned by {@link IrBuilder}, so structurally
 * identical subtrees are the same instance and identity maps can be used for analyses.
 * No node ever rewrites arithmetic: the only transformations are sharing and hoisting.
 *
 * <p>The last group of nodes ({@link Select}, {@link StaticCall}, {@link NoiseAt},
 * {@link IntToDouble}, {@link RoundFloat}) exists for the bytecode lifter. Each maps to the
 * exact JVM instructions of a modded density function's {@code compute} method.
 */
public sealed interface Node permits
        Node.Const, Node.Ap2, Node.MulOrAdd, Node.Mapped, Node.Clamp, Node.RangeChoice, Node.YGradient,
        Node.Noise, Node.ShiftedNoise, Node.ShiftA, Node.ShiftB, Node.Shift, Node.Weird, Node.Spline,
        Node.Blended, Node.BlendDensity, Node.BlendAlpha, Node.BlendOffset, Node.Beardifier, Node.EndIslands,
        Node.Marker, Node.Unsupported,
        Node.Select, Node.StaticCall, Node.NoiseAt, Node.IntToDouble, Node.RoundFloat {

    double min();

    double max();

    enum Ap2Type { ADD, MUL, MIN, MAX }

    enum MappedType { ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, INVERT, SQUEEZE }

    enum MarkerType { INTERPOLATED, FLAT_CACHE, CACHE_2D, CACHE_ONCE, CACHE_ALL_IN_CELL }

    /** {@code DensityFunctions.Constant}. */
    record Const(double value) implements Node {
        @Override public double min() { return value; }
        @Override public double max() { return value; }
    }

    /** {@code DensityFunctions.Ap2}: ADD, MUL (with the {@code d0 == 0.0} skip), MIN/MAX (with the bounds skip on {@code b}). */
    record Ap2(Ap2Type type, Node a, Node b, double min, double max) implements Node {}

    /** {@code DensityFunctions.MulOrAdd}: {@code input * argument} or {@code input + argument}. */
    record MulOrAdd(boolean mul, Node input, double argument, double min, double max) implements Node {}

    /** {@code DensityFunctions.Mapped}. */
    record Mapped(MappedType type, Node input, double min, double max) implements Node {}

    /** {@code DensityFunctions.Clamp}: {@code Mth.clamp(v, lo, hi)}. */
    record Clamp(Node input, double lo, double hi) implements Node {
        @Override public double min() { return lo; }
        @Override public double max() { return hi; }
    }

    /** {@code DensityFunctions.RangeChoice}. */
    record RangeChoice(Node input, double minInclusive, double maxExclusive, Node inRange, Node outRange, double min, double max) implements Node {}

    /** {@code DensityFunctions.YClampedGradient}: {@code Mth.clampedMap((double) y, fromY, toY, fromValue, toValue)}. */
    record YGradient(int fromY, int toY, double fromValue, double toValue) implements Node {
        @Override public double min() { return Math.min(fromValue, toValue); }
        @Override public double max() { return Math.max(fromValue, toValue); }
    }

    /** {@code DensityFunctions.Noise}: {@code noise.getValue(x * xzScale, y * yScale, z * xzScale)}. */
    record Noise(int spec, double xzScale, double yScale, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.ShiftedNoise}. */
    record ShiftedNoise(Node shiftX, Node shiftY, Node shiftZ, double xzScale, double yScale, int spec, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.ShiftA}: {@code noise.getValue(x * 0.25, 0.0, z * 0.25) * 4.0}. */
    record ShiftA(int spec, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.ShiftB}: {@code noise.getValue(z * 0.25, x * 0.25, 0.0) * 4.0}. */
    record ShiftB(int spec, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.Shift}: {@code noise.getValue(x * 0.25, y * 0.25, z * 0.25) * 4.0}. */
    record Shift(int spec, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.WeirdScaledSampler}. {@code type1} selects the 3D rarity mapper. */
    record Weird(Node input, int spec, boolean type1, double max) implements Node {
        @Override public double min() { return 0.0; }
    }

    /** {@code DensityFunctions.Spline}: evaluated in float exactly like {@code CubicSpline}. */
    record Spline(SplineSpec spline, double min, double max) implements Node {}

    /** {@code BlendedNoise}. */
    record Blended(int spec, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** {@code DensityFunctions.BlendDensity}: {@code ctx.getBlender().blendDensity(ctx, input)}. */
    record BlendDensity(Node input) implements Node {
        @Override public double min() { return Double.NEGATIVE_INFINITY; }
        @Override public double max() { return Double.POSITIVE_INFINITY; }
    }

    /** {@code DensityFunctions.BlendAlpha.INSTANCE} (1.0 outside a NoiseChunk, the chunk's blend alpha cache inside one). */
    record BlendAlpha() implements Node {
        @Override public double min() { return 1.0; }
        @Override public double max() { return 1.0; }
    }

    /** {@code DensityFunctions.BlendOffset.INSTANCE} (0.0 outside a NoiseChunk, the chunk's blend offset cache inside one). */
    record BlendOffset() implements Node {
        @Override public double min() { return Double.NEGATIVE_INFINITY; }
        @Override public double max() { return Double.POSITIVE_INFINITY; }
    }

    /** {@code BeardifierMarker.INSTANCE} (always 0.0) or a chunk's {@code Beardifier} instance. */
    record Beardifier(DensityFunction vanilla, double min, double max) implements Node {}

    /** {@code DensityFunctions.EndIslandDensityFunction}. */
    record EndIslands(DensityFunction vanilla) implements Node {
        @Override public double min() { return -0.84375; }
        @Override public double max() { return 0.5625; }
    }

    /** A vanilla cache marker ({@code DensityFunctions.Marker}) or the {@code NoiseChunk} wrapper that replaced it. */
    record Marker(MarkerType type, Node child, DensityFunction vanilla, double min, double max) implements Node {}

    /** A density function class the compiler could not lower. Evaluated through its own {@code compute}. */
    record Unsupported(DensityFunction vanilla, String reason, double min, double max) implements Node {}

    /** Spline tree mirroring {@code CubicSpline}. Arrays are compared by identity, so splines are shared via the vanilla object memo. */
    sealed interface SplineSpec permits SplineConst, SplineMulti {}

    record SplineConst(float value) implements SplineSpec {}

    record SplineMulti(Node coordinate, float[] locations, List<SplineSpec> values, float[] derivatives) implements SplineSpec {}

    // ---- lifter nodes -----------------------------------------------------------------------

    /** JVM comparison kind feeding a conditional jump. */
    enum CmpKind { DCMPL, DCMPG, ICMP }

    /** JVM conditional-jump opcode semantics applied to the comparison result (or the int itself for ICMP against zero). */
    enum JumpOp { EQ, NE, LT, GE, GT, LE }

    /**
     * A conditional exactly as the bytecode expressed it: {@code a} and {@code b} are compared with
     * {@code kind} ({@code ICMP} compares two ints, otherwise two doubles), then the jump is taken when
     * {@code op} holds for the comparison result. {@code intA/intB} are used for ICMP, {@code a/b} otherwise.
     */
    record Cond(CmpKind kind, JumpOp op, Node a, Node b, INode intA, INode intB) {}

    /** {@code cond ? whenJump : whenFall}, lazily evaluating only the taken arm. */
    record Select(Cond cond, Node whenJump, Node whenFall) implements Node {
        @Override public double min() { return Math.min(whenJump.min(), whenFall.min()); }
        @Override public double max() { return Math.max(whenJump.max(), whenFall.max()); }
    }

    /** Whitelisted pure static method returning double ({@code java/lang/Math}, {@code net/minecraft/util/Mth}). Args are {@link Node} or {@link INode} per the descriptor. */
    record StaticCall(String owner, String name, String desc, List<Object> args) implements Node {
        @Override public double min() { return Double.NEGATIVE_INFINITY; }
        @Override public double max() { return Double.POSITIVE_INFINITY; }
    }

    /** {@code noise.getValue(x, y, z)} with arbitrary coordinate expressions. */
    record NoiseAt(int spec, Node x, Node y, Node z, double max) implements Node {
        @Override public double min() { return -max; }
    }

    /** JVM {@code i2d}. */
    record IntToDouble(INode value) implements Node {
        @Override public double min() { return Integer.MIN_VALUE; }
        @Override public double max() { return Integer.MAX_VALUE; }
    }

    /** {@code (double) (float) v}: a {@code d2f} immediately followed by {@code f2d}. */
    record RoundFloat(Node value) implements Node {
        @Override public double min() { return Double.NEGATIVE_INFINITY; }
        @Override public double max() { return Double.POSITIVE_INFINITY; }
    }
}
