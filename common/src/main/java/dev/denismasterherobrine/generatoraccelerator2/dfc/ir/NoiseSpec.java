package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import dev.denismasterherobrine.generatoraccelerator2.mixin.NormalNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.PerlinNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.noise.Ga2ImprovedNoise;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Flattened, immutable description of one {@link NormalNoise} instance: permutation tables,
 * offsets and per-octave factors, laid out exactly as {@code PerlinNoiseMixin} compacts them so
 * {@link #getValue} reproduces {@code NormalNoise.getValue} bit for bit.
 */
public final class NoiseSpec {
    public static final double INPUT_FACTOR = 1.0181268882175227;

    /** One {@link PerlinNoise}: only the live octaves, in ascending index order. */
    public static final class Perlin {
        public final int count;
        public final int[][] perm;
        public final double[] xo;
        public final double[] yo;
        public final double[] zo;
        public final double[] amp;
        public final double[] inputFactor;
        public final double[] valueFactor;

        Perlin(int count, int[][] perm, double[] xo, double[] yo, double[] zo, double[] amp, double[] inputFactor, double[] valueFactor) {
            this.count = count;
            this.perm = perm;
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.amp = amp;
            this.inputFactor = inputFactor;
            this.valueFactor = valueFactor;
        }

        /** Bit-exact {@code PerlinNoise.getValue(x, y, z)}. */
        public double getValue(double x, double y, double z) {
            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                double f = inputFactor[i];
                double v = FusedNoise.noise(perm[i], xo[i], yo[i], zo[i], FusedNoise.wrap(x * f), FusedNoise.wrap(y * f), FusedNoise.wrap(z * f));
                sum += amp[i] * v * valueFactor[i];
            }
            return sum;
        }
    }

    public final NormalNoise source;
    public final Perlin first;
    public final Perlin second;
    public final double valueFactor;
    public final double maxValue;

    private NoiseSpec(NormalNoise source, Perlin first, Perlin second, double valueFactor, double maxValue) {
        this.source = source;
        this.first = first;
        this.second = second;
        this.valueFactor = valueFactor;
        this.maxValue = maxValue;
    }

    public static NoiseSpec of(NormalNoise noise) {
        NormalNoiseAccessor a = (NormalNoiseAccessor) noise;
        return new NoiseSpec(noise, perlin(a.ga2$first()), perlin(a.ga2$second()), a.ga2$valueFactor(), noise.maxValue());
    }

    public static Perlin perlin(PerlinNoise noise) {
        PerlinNoiseAccessor a = (PerlinNoiseAccessor) noise;
        ImprovedNoise[] levels = a.ga2$noiseLevels();
        DoubleList amplitudes = a.ga2$amplitudes();
        int count = 0;
        for (ImprovedNoise level : levels) {
            if (level != null) {
                count++;
            }
        }
        int[][] perm = new int[count][];
        double[] xo = new double[count];
        double[] yo = new double[count];
        double[] zo = new double[count];
        double[] amp = new double[count];
        double[] inputFactor = new double[count];
        double[] valueFactor = new double[count];
        // Same running products as vanilla's loop: d1 *= 2.0 and d2 /= 2.0 per index, null levels included.
        double in = a.ga2$lowestFreqInputFactor();
        double val = a.ga2$lowestFreqValueFactor();
        int k = 0;
        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise level = levels[i];
            if (level != null) {
                perm[k] = ((Ga2ImprovedNoise) (Object) level).ga2$perm();
                xo[k] = level.xo;
                yo[k] = level.yo;
                zo[k] = level.zo;
                amp[k] = amplitudes.getDouble(i);
                inputFactor[k] = in;
                valueFactor[k] = val;
                k++;
            }
            in *= 2.0;
            val /= 2.0;
        }
        return new Perlin(count, perm, xo, yo, zo, amp, inputFactor, valueFactor);
    }

    /** Bit-exact {@code NormalNoise.getValue}. */
    public double getValue(double x, double y, double z) {
        double d0 = x * INPUT_FACTOR;
        double d1 = y * INPUT_FACTOR;
        double d2 = z * INPUT_FACTOR;
        return (first.getValue(x, y, z) + second.getValue(d0, d1, d2)) * valueFactor;
    }

    public Column newColumn() {
        return new Column(this);
    }

    /**
     * Per-column evaluation state: {@link #prepare} does the x/z half of every octave once, then
     * {@link #value} does only the y half per corner. Bit-identical to {@link #getValue}.
     * One instance per generated program instance (i.e. per chunk), never shared across threads.
     */
    public static final class Column {
        private final NoiseSpec spec;
        private final Branch b1;
        private final Branch b2;

        /**
         * One octave set's column state, interleaved per octave, per program instance (a chunk's). {@code ints} holds A, B, Z
         * (3 per octave). {@code xz} holds the x/z fractions, their shifted forms and smoothsteps (fx, fxm, fz, fzm, sx, sz: 6 per
         * octave). An octave's state sits on one or two cache lines. The branch also keeps the gradient terms of the octave's
         * last lattice cell. The column's next corners reuse these terms while they stay in that cell.
         */
        private static final class Branch {
            final Perlin p;
            final int[] ints;
            final double[] xz;
            /** Per octave the lattice y its {@link #terms} are for ({@code Integer.MIN_VALUE}: none since {@link #prepare}). */
            final int[] termY;
            /** Per octave {@link FusedNoise#TERMS} gradient terms of the column's cell at {@link #termY}. */
            final double[] terms;
            /**
             * Per octave: whether the octave's last value was in another lattice cell than the one before. An octave whose corners
             * change cell at every step would refill its terms for every corner. This happens for a fine octave, where the step is a
             * lattice cell or more. Refilling costs more than the direct kernel. So after two such misses in a row, the octave
             * computes directly until a corner falls in its stored cell again.
             */
            final boolean[] missed;

            Branch(Perlin p) {
                this.p = p;
                int n = p.count;
                ints = new int[n * 3];
                xz = new double[n * 6];
                termY = new int[n];
                terms = new double[n * FusedNoise.TERMS];
                missed = new boolean[n];
            }

            void prepare(double x, double z) {
                int[] ints = this.ints;
                double[] xz = this.xz;
                java.util.Arrays.fill(termY, Integer.MIN_VALUE);
                java.util.Arrays.fill(missed, false);
                for (int i = 0; i < p.count; i++) {
                    double f = p.inputFactor[i];
                    double dx = FusedNoise.wrap(x * f) + p.xo[i];
                    double dz = FusedNoise.wrap(z * f) + p.zo[i];
                    int ix = FusedNoise.floor(dx);
                    int iz = FusedNoise.floor(dz);
                    double ffx = dx - (double) ix;
                    double ffz = dz - (double) iz;
                    int[] perm = p.perm[i];
                    int X = ix & 255;
                    int o = i * 3;
                    ints[o] = perm[X];
                    ints[o + 1] = perm[X + 1];
                    ints[o + 2] = iz & 255;
                    int d = i * 6;
                    xz[d] = ffx;
                    xz[d + 1] = ffx - 1.0;
                    xz[d + 2] = ffz;
                    xz[d + 3] = ffz - 1.0;
                    xz[d + 4] = FusedNoise.smooth(ffx);
                    xz[d + 5] = FusedNoise.smooth(ffz);
                }
            }

            double value(double y) {
                int[] ints = this.ints;
                double[] xz = this.xz;
                int[] termY = this.termY;
                double[] terms = this.terms;
                boolean[] missed = this.missed;
                double sum = 0.0;
                for (int i = 0; i < p.count; i++) {
                    double f = p.inputFactor[i];
                    double dy = FusedNoise.wrap(y * f) + p.yo[i];
                    int iy = FusedNoise.floor(dy);
                    double fy = dy - (double) iy;
                    int d = i * 6;
                    double v;
                    if (FusedNoise.TERM_CACHE != FusedNoise.TERMS_NEVER && termY[i] == iy) {
                        missed[i] = false;
                        v = FusedNoise.cornersTerms(terms, i * FusedNoise.TERMS, xz[d + 4], xz[d + 5], fy, fy);
                    } else if (FusedNoise.TERM_CACHE == FusedNoise.TERMS_NEVER || FusedNoise.TERM_CACHE == FusedNoise.TERMS_AUTO && missed[i]) {
                        // a fine octave: the kernel straight from the hashes, the same operations as the terms' path
                        int o = i * 3;
                        v = FusedNoise.cornersColumn(p.perm[i], ints[o], ints[o + 1], ints[o + 2], xz[d], xz[d + 1], xz[d + 2], xz[d + 3], xz[d + 4], xz[d + 5], iy, fy, fy);
                    } else {
                        missed[i] = true;
                        int o = i * 3;
                        int t = i * FusedNoise.TERMS;
                        FusedNoise.terms(p.perm[i], ints[o], ints[o + 1], ints[o + 2], iy, xz[d], xz[d + 1], xz[d + 2], xz[d + 3], terms, t);
                        termY[i] = iy;
                        v = FusedNoise.cornersTerms(terms, t, xz[d + 4], xz[d + 5], fy, fy);
                    }
                    sum += p.amp[i] * v * p.valueFactor[i];
                }
                return sum;
            }
        }

        private Column(NoiseSpec spec) {
            this.spec = spec;
            this.b1 = new Branch(spec.first);
            this.b2 = new Branch(spec.second);
        }

        public void prepare(double x, double z) {
            b1.prepare(x, z);
            b2.prepare(x * INPUT_FACTOR, z * INPUT_FACTOR);
        }

        public double value(double y) {
            return (b1.value(y) + b2.value(y * INPUT_FACTOR)) * spec.valueFactor;
        }
    }
}
