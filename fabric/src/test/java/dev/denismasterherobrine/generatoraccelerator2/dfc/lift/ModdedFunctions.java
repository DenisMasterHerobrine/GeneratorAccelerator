package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Synthetic "modded" density functions covering the lifter's supported subset and its refusals. */
public final class ModdedFunctions {
    private ModdedFunctions() {}

    /** Shared no-op plumbing so the records only have to implement compute/minValue/maxValue. */
    public interface Simple extends DensityFunction.SimpleFunction {
        @Override
        default KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }

    public record ScaledSum(DensityFunction a, DensityFunction b, double scale) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            return (a.compute(ctx) + b.compute(ctx)) * scale;
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    public record Ternary(DensityFunction a, double threshold) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            double v = a.compute(ctx);
            return v > threshold ? v * 2.0 : Math.abs(v) - 1.0;
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    public record CoordMath(double factor) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            int x = ctx.blockX() >> 2;
            double d = (double) (x * 3 + ctx.blockZ() / 8) * 0.01;
            return Mth.clamp(d, -1.0, 1.0) + helper(ctx.blockY());
        }

        private double helper(int y) {
            return y < 0 ? -0.5 * factor : Mth.square(y * 0.001);
        }

        @Override public double minValue() { return -2.0; }
        @Override public double maxValue() { return 2.0; }
    }

    public record NoiseUser(DensityFunction.NoiseHolder noise, double xzScale) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            return noise.getValue(ctx.blockX() * xzScale, (double) ctx.blockY(), ctx.blockZ() * xzScale) * 2.0 + Math.sqrt(Math.abs((double) ctx.blockY()));
        }

        @Override public double minValue() { return -10.0; }
        @Override public double maxValue() { return 30.0; }
    }

    public record AndChain(DensityFunction a, DensityFunction b) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            double x = a.compute(ctx);
            double y = b.compute(ctx);
            if (x < 0.5 && y > 0.2 || x > 0.9) {
                return x;
            }
            return y;
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    public record FloatRound(DensityFunction a) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            return (double) (float) a.compute(ctx) + (float) ctx.blockY();
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    /** Not liftable: contains a loop. */
    public record Loopy(DensityFunction a) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            double s = 0.0;
            for (int i = 0; i < 3; i++) {
                s += a.compute(ctx) * i;
            }
            return s;
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    /** Not liftable: allocates. */
    public record Alloc(DensityFunction a) implements Simple {
        @Override
        public double compute(FunctionContext ctx) {
            double[] tmp = new double[] { a.compute(ctx) };
            return tmp[0];
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }

    /** Not liftable: mutable state. */
    public static final class Stateful implements Simple {
        private double last;
        private final DensityFunction a;

        public Stateful(DensityFunction a) {
            this.a = a;
        }

        @Override
        public double compute(FunctionContext ctx) {
            last = a.compute(ctx) + last * 0.5;
            return last;
        }

        @Override public double minValue() { return -1e6; }
        @Override public double maxValue() { return 1e6; }
    }
}
