package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import dev.denismasterherobrine.generatoraccelerator2.mixin.BlendedNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.noise.Ga2ImprovedNoise;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import java.util.Arrays;

/** Flattened {@link BlendedNoise}; {@link #compute} mirrors the vanilla method operation by operation. */
public final class BlendedSpec {
    public final BlendedNoise source;
    // Octave data in getOctaveNoise(i) order (index 0 = highest frequency). Null entries mirror vanilla's null levels.
    public final int[][] mainPerm;
    public final double[] mainXo, mainYo, mainZo;
    public final int[][] minPerm;
    public final double[] minXo, minYo, minZo;
    public final int[][] maxPerm;
    public final double[] maxXo, maxYo, maxZo;
    public final double xzMultiplier, yMultiplier, xzFactor, yFactor, smearScaleMultiplier, maxValue;

    private BlendedSpec(BlendedNoise source) {
        BlendedNoiseAccessor b = (BlendedNoiseAccessor) source;
        this.source = source;
        this.mainPerm = new int[8][]; this.mainXo = new double[8]; this.mainYo = new double[8]; this.mainZo = new double[8];
        fill(b.ga2$mainNoise(), 8, mainPerm, mainXo, mainYo, mainZo);
        this.minPerm = new int[16][]; this.minXo = new double[16]; this.minYo = new double[16]; this.minZo = new double[16];
        fill(b.ga2$minLimitNoise(), 16, minPerm, minXo, minYo, minZo);
        this.maxPerm = new int[16][]; this.maxXo = new double[16]; this.maxYo = new double[16]; this.maxZo = new double[16];
        fill(b.ga2$maxLimitNoise(), 16, maxPerm, maxXo, maxYo, maxZo);
        this.xzMultiplier = b.ga2$xzMultiplier();
        this.yMultiplier = b.ga2$yMultiplier();
        this.xzFactor = b.ga2$xzFactor();
        this.yFactor = b.ga2$yFactor();
        this.smearScaleMultiplier = b.ga2$smearScaleMultiplier();
        this.maxValue = source.maxValue();
    }

    public static BlendedSpec of(BlendedNoise noise) {
        return new BlendedSpec(noise);
    }

    private static void fill(PerlinNoise noise, int count, int[][] perm, double[] xo, double[] yo, double[] zo) {
        for (int i = 0; i < count; i++) {
            ImprovedNoise level = noise.getOctaveNoise(i);
            if (level != null) {
                perm[i] = ((Ga2ImprovedNoise) (Object) level).ga2$perm();
                xo[i] = level.xo;
                yo[i] = level.yo;
                zo[i] = level.zo;
            }
        }
    }

    public Column newColumn() {
        return new Column(this);
    }

    /**
     * A bound of {@link #compute}'s magnitude, far below vanilla's {@code maxValue} (which bounds the sums before their scaling).
     * An octave of {@code ImprovedNoise.noise} is a convex combination of its corners' gradient terms, and a gradient's components
     * are -1, 0 or 1. Over the two corners of an axis the fade weights put at most 0.5 on the x and z distances, and at most 1 on
     * the y distance, which the "broken" y keeps in [0, 1). So an octave stays within 2. Each limit sum then stays within 2 times
     * its octaves' weights, and the result lies between the two sums, scaled down by 512 and 128. Widened for the roundings.
     */
    public double bound() {
        double min = 0.0;
        double max = 0.0;
        double weight = 1.0;
        for (int j = 0; j < 16; j++) {
            if (minPerm[j] != null) {
                min += 2.0 * weight;
            }
            if (maxPerm[j] != null) {
                max += 2.0 * weight;
            }
            weight *= 2.0;
        }
        return Math.max(min, max) / 512.0 / 128.0 * (1.0 + 1e-6);
    }

    /** Per-column state for {@link #compute}: x/z halves of all 40 octaves once per column, y half per corner. */
    public static final class Column {
        private final BlendedSpec s;
        private final int[] mA = new int[8], mB = new int[8], mZ = new int[8];
        private final double[] mfx = new double[8], mfxm = new double[8], mfz = new double[8], mfzm = new double[8], msx = new double[8], msz = new double[8];
        private final int[] nA = new int[16], nB = new int[16], nZ = new int[16];
        private final double[] nfx = new double[16], nfxm = new double[16], nfz = new double[16], nfzm = new double[16], nsx = new double[16], nsz = new double[16];
        private final int[] xA = new int[16], xB = new int[16], xZ = new int[16];
        private final double[] xfx = new double[16], xfxm = new double[16], xfz = new double[16], xfzm = new double[16], xsx = new double[16], xsz = new double[16];
        /** Per octave the lattice y of its gradient terms (MIN_VALUE: none since prepare) and the terms ({@link FusedNoise#terms}). */
        private final int[] mTy = new int[8], nTy = new int[16], xTy = new int[16];
        private final double[] mT = new double[8 * FusedNoise.TERMS], nT = new double[16 * FusedNoise.TERMS], xT = new double[16 * FusedNoise.TERMS];
        /**
         * Per octave: whether the octave's last corner also missed its stored cell. As in {@link NoiseSpec}'s branches, fine
         * octaves then skip the terms.
         */
        private final boolean[] mMiss = new boolean[8], nMiss = new boolean[16], xMiss = new boolean[16];

        private Column(BlendedSpec s) {
            this.s = s;
        }

        private static void prep(int[] perm, double xo, double zo, double wx, double wz, int i, int[] A, int[] B, int[] Z,
                                 double[] fx, double[] fxm, double[] fz, double[] fzm, double[] sx, double[] sz) {
            double dx = wx + xo;
            double dz = wz + zo;
            int ix = FusedNoise.floor(dx);
            int iz = FusedNoise.floor(dz);
            double ffx = dx - (double) ix;
            double ffz = dz - (double) iz;
            int X = ix & 255;
            A[i] = perm[X];
            B[i] = perm[X + 1];
            Z[i] = iz & 255;
            fx[i] = ffx;
            fxm[i] = ffx - 1.0;
            fz[i] = ffz;
            fzm[i] = ffz - 1.0;
            sx[i] = FusedNoise.smooth(ffx);
            sz[i] = FusedNoise.smooth(ffz);
        }

        public void prepare(int bx, int bz) {
            Arrays.fill(mTy, Integer.MIN_VALUE);
            Arrays.fill(nTy, Integer.MIN_VALUE);
            Arrays.fill(xTy, Integer.MIN_VALUE);
            Arrays.fill(mMiss, false);
            Arrays.fill(nMiss, false);
            Arrays.fill(xMiss, false);
            double d0 = (double) bx * s.xzMultiplier;
            double d2 = (double) bz * s.xzMultiplier;
            double d3 = d0 / s.xzFactor;
            double d5 = d2 / s.xzFactor;
            double d11 = 1.0;
            for (int i = 0; i < 8; i++) {
                if (s.mainPerm[i] != null) {
                    prep(s.mainPerm[i], s.mainXo[i], s.mainZo[i], FusedNoise.wrap(d3 * d11), FusedNoise.wrap(d5 * d11), i, mA, mB, mZ, mfx, mfxm, mfz, mfzm, msx, msz);
                }
                d11 /= 2.0;
            }
            d11 = 1.0;
            for (int j = 0; j < 16; j++) {
                double w0 = FusedNoise.wrap(d0 * d11);
                double w2 = FusedNoise.wrap(d2 * d11);
                if (s.minPerm[j] != null) {
                    prep(s.minPerm[j], s.minXo[j], s.minZo[j], w0, w2, j, nA, nB, nZ, nfx, nfxm, nfz, nfzm, nsx, nsz);
                }
                if (s.maxPerm[j] != null) {
                    prep(s.maxPerm[j], s.maxXo[j], s.maxZo[j], w0, w2, j, xA, xB, xZ, xfx, xfxm, xfz, xfzm, xsx, xsz);
                }
                d11 /= 2.0;
            }
        }

        /**
         * The y half of {@link FusedNoise#noiseY} for one octave, over the gradient terms of its lattice cell. The terms are
         * kept while the octave stays in that cell.
         */
        private static double octaveY(int[] perm, double yo, double wy, double yScale, double yMax, int i, int[] A, int[] B, int[] Z,
                                      double[] fx, double[] fxm, double[] fz, double[] fzm, double[] sx, double[] sz, int[] termY, double[] terms,
                                      boolean[] missed) {
            double dy = wy + yo;
            int iy = FusedNoise.floor(dy);
            double fy = dy - (double) iy;
            double d6;
            if (yScale != 0.0) {
                double d7 = (yMax >= 0.0 && yMax < fy) ? yMax : fy;
                d6 = (double) FusedNoise.floor(d7 / yScale + SHIFT_UP_EPSILON) * yScale;
            } else {
                d6 = 0.0;
            }
            int t = i * FusedNoise.TERMS;
            if (FusedNoise.TERM_CACHE != FusedNoise.TERMS_NEVER && termY[i] == iy) {
                missed[i] = false;
                return FusedNoise.cornersTerms(terms, t, sx[i], sz[i], fy - d6, fy);
            }
            if (FusedNoise.TERM_CACHE == FusedNoise.TERMS_NEVER || FusedNoise.TERM_CACHE == FusedNoise.TERMS_AUTO && missed[i]) {
                // a fine octave: the kernel straight from the hashes, the same operations as the terms' path
                return FusedNoise.cornersColumn(perm, A[i], B[i], Z[i], fx[i], fxm[i], fz[i], fzm[i], sx[i], sz[i], iy, fy - d6, fy);
            }
            missed[i] = true;
            FusedNoise.terms(perm, A[i], B[i], Z[i], iy, fx[i], fxm[i], fz[i], fzm[i], terms, t);
            termY[i] = iy;
            return FusedNoise.cornersTerms(terms, t, sx[i], sz[i], fy - d6, fy);
        }

        public double value(int by) {
            double d1 = (double) by * s.yMultiplier;
            double d4 = d1 / s.yFactor;
            double d6 = s.yMultiplier * s.smearScaleMultiplier;
            double d7 = d6 / s.yFactor;
            double d8 = 0.0;
            double d9 = 0.0;
            double d10 = 0.0;
            double d11 = 1.0;
            for (int i = 0; i < 8; i++) {
                if (s.mainPerm[i] != null) {
                    d10 += octaveY(s.mainPerm[i], s.mainYo[i], FusedNoise.wrap(d4 * d11), d7 * d11, d4 * d11, i, mA, mB, mZ, mfx, mfxm, mfz, mfzm, msx, msz, mTy, mT, mMiss) / d11;
                }
                d11 /= 2.0;
            }
            double d16 = (d10 / 10.0 + 1.0) / 2.0;
            boolean flag1 = d16 >= 1.0;
            boolean flag2 = d16 <= 0.0;
            d11 = 1.0;
            for (int j = 0; j < 16; j++) {
                double d13 = FusedNoise.wrap(d1 * d11);
                double d15 = d6 * d11;
                if (!flag1 && s.minPerm[j] != null) {
                    d8 += octaveY(s.minPerm[j], s.minYo[j], d13, d15, d1 * d11, j, nA, nB, nZ, nfx, nfxm, nfz, nfzm, nsx, nsz, nTy, nT, nMiss) / d11;
                }
                if (!flag2 && s.maxPerm[j] != null) {
                    d9 += octaveY(s.maxPerm[j], s.maxYo[j], d13, d15, d1 * d11, j, xA, xB, xZ, xfx, xfxm, xfz, xfzm, xsx, xsz, xTy, xT, xMiss) / d11;
                }
                d11 /= 2.0;
            }
            double lo = d8 / 512.0;
            double hi = d9 / 512.0;
            double r;
            if (d16 < 0.0) {
                r = lo;
            } else if (d16 > 1.0) {
                r = hi;
            } else {
                r = lo + d16 * (hi - lo);
            }
            return r / 128.0;
        }
    }

    private static final double SHIFT_UP_EPSILON = (double) 1.0E-7F;

    /** Bit-exact {@code BlendedNoise.compute}. */
    public double compute(int bx, int by, int bz) {
        double d0 = (double) bx * this.xzMultiplier;
        double d1 = (double) by * this.yMultiplier;
        double d2 = (double) bz * this.xzMultiplier;
        double d3 = d0 / this.xzFactor;
        double d4 = d1 / this.yFactor;
        double d5 = d2 / this.xzFactor;
        double d6 = this.yMultiplier * this.smearScaleMultiplier;
        double d7 = d6 / this.yFactor;
        double d8 = 0.0;
        double d9 = 0.0;
        double d10 = 0.0;
        double d11 = 1.0;
        for (int i = 0; i < 8; i++) {
            int[] perm = mainPerm[i];
            if (perm != null) {
                d10 += FusedNoise.noiseY(perm, mainXo[i], mainYo[i], mainZo[i],
                    FusedNoise.wrap(d3 * d11), FusedNoise.wrap(d4 * d11), FusedNoise.wrap(d5 * d11), d7 * d11, d4 * d11) / d11;
            }
            d11 /= 2.0;
        }
        double d16 = (d10 / 10.0 + 1.0) / 2.0;
        boolean flag1 = d16 >= 1.0;
        boolean flag2 = d16 <= 0.0;
        d11 = 1.0;
        for (int j = 0; j < 16; j++) {
            double d12 = FusedNoise.wrap(d0 * d11);
            double d13 = FusedNoise.wrap(d1 * d11);
            double d14 = FusedNoise.wrap(d2 * d11);
            double d15 = d6 * d11;
            if (!flag1) {
                int[] perm = minPerm[j];
                if (perm != null) {
                    d8 += FusedNoise.noiseY(perm, minXo[j], minYo[j], minZo[j], d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            if (!flag2) {
                int[] perm = maxPerm[j];
                if (perm != null) {
                    d9 += FusedNoise.noiseY(perm, maxXo[j], maxYo[j], maxZo[j], d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            d11 /= 2.0;
        }
        double lo = d8 / 512.0;
        double hi = d9 / 512.0;
        double r;
        if (d16 < 0.0) {
            r = lo;
        } else if (d16 > 1.0) {
            r = hi;
        } else {
            r = lo + d16 * (hi - lo);
        }
        return r / 128.0;
    }
}
