package dev.denismasterherobrine.generatoraccelerator2.noise;

import dev.denismasterherobrine.generatoraccelerator2.mixin.BlendedNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.NormalNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.PerlinNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Line-by-line port of the vanilla 1.20.1 noise code, kept independent of the fused kernel.
 * The mixins replace the vanilla methods at runtime, so this is the only way to obtain the
 * original values for comparison in {@link SelfCheck}.
 */
public final class ReferenceNoise {
    private ReferenceNoise() {}

    private static final int[][] GRADIENT = new int[][] {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
        {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private static int p(byte[] p, int i) {
        return p[i & 0xFF] & 0xFF;
    }

    private static double gradDot(int hash, double x, double y, double z) {
        int[] g = GRADIENT[hash & 15];
        return (double) g[0] * x + (double) g[1] * y + (double) g[2] * z;
    }

    public static double improved(ImprovedNoise n, double x, double y, double z, double yScale, double yMax) {
        byte[] p = ((Ga2ImprovedNoise) (Object) n).ga2$p();
        double d0 = x + n.xo;
        double d1 = y + n.yo;
        double d2 = z + n.zo;
        int i = Mth.floor(d0);
        int j = Mth.floor(d1);
        int k = Mth.floor(d2);
        double d3 = d0 - (double) i;
        double d4 = d1 - (double) j;
        double d5 = d2 - (double) k;
        double d6;
        if (yScale != 0.0) {
            double d7;
            if (yMax >= 0.0 && yMax < d4) {
                d7 = yMax;
            } else {
                d7 = d4;
            }
            d6 = (double) Mth.floor(d7 / yScale + 1.0E-7F) * yScale;
        } else {
            d6 = 0.0;
        }
        return sampleAndLerp(p, i, j, k, d3, d4 - d6, d5, d4);
    }

    private static double sampleAndLerp(byte[] p, int X, int Y, int Z, double x, double y, double z, double ySmooth) {
        int i = p(p, X);
        int j = p(p, X + 1);
        int k = p(p, i + Y);
        int l = p(p, i + Y + 1);
        int i1 = p(p, j + Y);
        int j1 = p(p, j + Y + 1);
        double d0 = gradDot(p(p, k + Z), x, y, z);
        double d1 = gradDot(p(p, i1 + Z), x - 1.0, y, z);
        double d2 = gradDot(p(p, l + Z), x, y - 1.0, z);
        double d3 = gradDot(p(p, j1 + Z), x - 1.0, y - 1.0, z);
        double d4 = gradDot(p(p, k + Z + 1), x, y, z - 1.0);
        double d5 = gradDot(p(p, i1 + Z + 1), x - 1.0, y, z - 1.0);
        double d6 = gradDot(p(p, l + Z + 1), x, y - 1.0, z - 1.0);
        double d7 = gradDot(p(p, j1 + Z + 1), x - 1.0, y - 1.0, z - 1.0);
        double d8 = Mth.smoothstep(x);
        double d9 = Mth.smoothstep(ySmooth);
        double d10 = Mth.smoothstep(z);
        return Mth.lerp3(d8, d9, d10, d0, d1, d2, d3, d4, d5, d6, d7);
    }

    public static double wrap(double v) {
        return v - (double) Mth.lfloor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    public static double perlin(PerlinNoise noise, double x, double y, double z, double yScale, double yMax, boolean useOrigin) {
        PerlinNoiseAccessor a = (PerlinNoiseAccessor) noise;
        ImprovedNoise[] levels = a.ga2$noiseLevels();
        DoubleList amplitudes = a.ga2$amplitudes();
        double d0 = 0.0;
        double d1 = a.ga2$lowestFreqInputFactor();
        double d2 = a.ga2$lowestFreqValueFactor();
        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise level = levels[i];
            if (level != null) {
                double d3 = improved(level, wrap(x * d1), useOrigin ? -level.yo : wrap(y * d1), wrap(z * d1), yScale * d1, yMax * d1);
                d0 += amplitudes.getDouble(i) * d3 * d2;
            }
            d1 *= 2.0;
            d2 /= 2.0;
        }
        return d0;
    }

    public static double normal(NormalNoise noise, double x, double y, double z) {
        NormalNoiseAccessor a = (NormalNoiseAccessor) noise;
        double d0 = x * 1.0181268882175227;
        double d1 = y * 1.0181268882175227;
        double d2 = z * 1.0181268882175227;
        return (perlin(a.ga2$first(), x, y, z, 0.0, 0.0, false) + perlin(a.ga2$second(), d0, d1, d2, 0.0, 0.0, false)) * a.ga2$valueFactor();
    }

    public static double blended(BlendedNoise noise, int bx, int by, int bz) {
        BlendedNoiseAccessor b = (BlendedNoiseAccessor) noise;
        double d0 = (double) bx * b.ga2$xzMultiplier();
        double d1 = (double) by * b.ga2$yMultiplier();
        double d2 = (double) bz * b.ga2$xzMultiplier();
        double d3 = d0 / b.ga2$xzFactor();
        double d4 = d1 / b.ga2$yFactor();
        double d5 = d2 / b.ga2$xzFactor();
        double d6 = b.ga2$yMultiplier() * b.ga2$smearScaleMultiplier();
        double d7 = d6 / b.ga2$yFactor();
        double d8 = 0.0;
        double d9 = 0.0;
        double d10 = 0.0;
        double d11 = 1.0;
        for (int i = 0; i < 8; i++) {
            ImprovedNoise level = b.ga2$mainNoise().getOctaveNoise(i);
            if (level != null) {
                d10 += improved(level, wrap(d3 * d11), wrap(d4 * d11), wrap(d5 * d11), d7 * d11, d4 * d11) / d11;
            }
            d11 /= 2.0;
        }
        double d16 = (d10 / 10.0 + 1.0) / 2.0;
        boolean flag1 = d16 >= 1.0;
        boolean flag2 = d16 <= 0.0;
        d11 = 1.0;
        for (int j = 0; j < 16; j++) {
            double d12 = wrap(d0 * d11);
            double d13 = wrap(d1 * d11);
            double d14 = wrap(d2 * d11);
            double d15 = d6 * d11;
            if (!flag1) {
                ImprovedNoise level = b.ga2$minLimitNoise().getOctaveNoise(j);
                if (level != null) {
                    d8 += improved(level, d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            if (!flag2) {
                ImprovedNoise level = b.ga2$maxLimitNoise().getOctaveNoise(j);
                if (level != null) {
                    d9 += improved(level, d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            d11 /= 2.0;
        }
        return Mth.clampedLerp(d8 / 512.0, d9 / 512.0, d16) / 128.0;
    }
}
