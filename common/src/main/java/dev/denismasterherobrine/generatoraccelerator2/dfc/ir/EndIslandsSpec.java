package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.SimplexNoiseAccessor;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Exact port of {@code DensityFunctions.EndIslandDensityFunction}: the 2D simplex noise and the
 * 25x25 island height search, in the same float/double operations as vanilla. Implements
 * {@link DensityFunction} so generated code can call it like any extern without touching the
 * vanilla object (which is replaced by {@code RandomState} per seed).
 */
public final class EndIslandsSpec implements DensityFunction.SimpleFunction {
    private static final double SQRT_3 = Math.sqrt(3.0);
    private static final double F2 = 0.5 * (SQRT_3 - 1.0);
    private static final double G2 = (3.0 - SQRT_3) / 6.0;
    private static final int[][] GRADIENT = new int[][] {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private final int[] p;

    public EndIslandsSpec(DensityFunctions.EndIslandDensityFunction vanilla) {
        SimplexNoise noise = vanilla.islandNoise;
        this.p = ((SimplexNoiseAccessor) noise).ga2$p();
    }

    @Override
    public double compute(FunctionContext ctx) {
        return ((double) getHeightValue(ctx.blockX() / 8, ctx.blockZ() / 8) - 8.0) / 128.0;
    }

    /** {@code EndIslandDensityFunction.getHeightValue}. */
    private float getHeightValue(int x, int z) {
        int i = x / 2;
        int j = z / 2;
        int k = x % 2;
        int l = z % 2;
        float f = 100.0F - (float) Math.sqrt((double) (float) (x * x + z * z)) * 8.0F;
        f = clampF(f, -100.0F, 80.0F);
        for (int i1 = -12; i1 <= 12; i1++) {
            for (int j1 = -12; j1 <= 12; j1++) {
                long k1 = (long) (i + i1);
                long l1 = (long) (j + j1);
                if (k1 * k1 + l1 * l1 > 4096L && simplex2D((double) k1, (double) l1) < -0.9F) {
                    float f1 = (Math.abs((float) k1) * 3439.0F + Math.abs((float) l1) * 147.0F) % 13.0F + 9.0F;
                    float f2 = (float) (k - i1 * 2);
                    float f3 = (float) (l - j1 * 2);
                    float f4 = 100.0F - (float) Math.sqrt((double) (f2 * f2 + f3 * f3)) * f1;
                    f4 = clampF(f4, -100.0F, 80.0F);
                    f = Math.max(f, f4);
                }
            }
        }
        return f;
    }

    /** {@code Mth.clamp(float, float, float)}. */
    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private int p(int i) {
        return p[i & 0xFF];
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    /** {@code SimplexNoise.getValue(double, double)}. */
    private double simplex2D(double x, double y) {
        double d0 = (x + y) * F2;
        int i = floor(x + d0);
        int j = floor(y + d0);
        double d1 = (double) (i + j) * G2;
        double d2 = (double) i - d1;
        double d3 = (double) j - d1;
        double d4 = x - d2;
        double d5 = y - d3;
        int k;
        int l;
        if (d4 > d5) {
            k = 1;
            l = 0;
        } else {
            k = 0;
            l = 1;
        }
        double d6 = d4 - (double) k + G2;
        double d7 = d5 - (double) l + G2;
        double d8 = d4 - 1.0 + 2.0 * G2;
        double d9 = d5 - 1.0 + 2.0 * G2;
        int i1 = i & 0xFF;
        int j1 = j & 0xFF;
        int k1 = this.p(i1 + this.p(j1)) % 12;
        int l1 = this.p(i1 + k + this.p(j1 + l)) % 12;
        int i2 = this.p(i1 + 1 + this.p(j1 + 1)) % 12;
        double d10 = corner(k1, d4, d5, 0.0, 0.5);
        double d11 = corner(l1, d6, d7, 0.0, 0.5);
        double d12 = corner(i2, d8, d9, 0.0, 0.5);
        return 70.0 * (d10 + d11 + d12);
    }

    /** {@code SimplexNoise.getCornerNoise3D}. */
    private static double corner(int gradient, double x, double y, double z, double offset) {
        double d1 = offset - x * x - y * y - z * z;
        double d0;
        if (d1 < 0.0) {
            d0 = 0.0;
        } else {
            d1 *= d1;
            int[] g = GRADIENT[gradient];
            d0 = d1 * d1 * ((double) g[0] * x + (double) g[1] * y + (double) g[2] * z);
        }
        return d0;
    }

    @Override
    public double minValue() {
        return -0.84375;
    }

    @Override
    public double maxValue() {
        return 0.5625;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return DensityFunctions.EndIslandDensityFunction.CODEC;
    }
}
