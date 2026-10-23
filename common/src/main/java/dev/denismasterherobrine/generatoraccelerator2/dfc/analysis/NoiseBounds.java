package dev.denismasterherobrine.generatoraccelerator2.dfc.analysis;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sound bounds of a {@link NoiseSpec} (vanilla's {@code NormalNoise}) over a box of inputs, for the whole-cell classification
 * ({@link CellBounds}).
 *
 * <p>A noise leaf evaluated per block was bounded by the noise's own maximum, which is about twice what the noise ever reaches
 * and far more than it moves over one 4x8x4 cell. Terrain whose per-block tree samples a noise (Terralith's caves, most
 * datapack terrain) therefore never bounded a cell away from zero, and only the sky went whole. Here each octave is bounded
 * over the cell: the cell's inputs fall into one or two lattice cells per axis, and inside a lattice cell Perlin noise is a
 * polynomial, {@code lerp(sz, lerp(sy, lerp(sx, d0, d1), lerp(sx, d2, d3)), lerp(sy, lerp(sx, d4, d5), lerp(sx, d6, d7)))} with
 * each corner's {@code d} linear in the fractions. The fractions and their smoothsteps are intervals, each {@code d} is an
 * interval, and {@code lerp(t, a, b) = a + t * (b - a)} is multilinear in {@code (t, a, b)}, so its range over a box lies at
 * the box's corners. An octave whose inputs span more than two lattice cells on an axis keeps the octave's own bound, 2.
 *
 * <p>Soundness in floating point. Every input the kernel computes for a block of the cell, {@code wrap(x * f) + xo}, is
 * monotone in the block coordinate (each operation rounds monotonically and {@code wrap} is the identity below
 * {@link #LIMIT}), so the values computed at the cell's two ends bound the values of every block between them, and so do
 * their lattice cells. The fraction {@code dx - floor(dx)} is exact. The kernel's arithmetic and the bounds' are both within
 * a few ulps of the real polynomial, whose range the corner evaluation gives, so each octave's interval is widened by
 * {@link #OCTAVE_EPS} (values are at most 2, so that is about 2^16 ulps) and the sum by {@link #REL} of its magnitude.
 * {@code dfc/NoiseBoundsTest} checks the bounds against the kernel on random boxes, scales and shifts.
 *
 * <p>One instance per {@link CellBounds} instance (one thread at a time). Results come back in {@link #lo} and {@link #hi}.
 */
public final class NoiseBounds {
    /** Inputs below this magnitude pass {@code wrap} unchanged ({@code FusedNoise.WRAP_FAST_LIMIT}, with a margin). */
    private static final double LIMIT = 1.5E7;
    /** Widening of an octave's value bounds, for the roundings of the kernel and of these bounds. */
    private static final double OCTAVE_EPS = 0x1.0p-36;
    private static final double REL = 0x1.0p-40;
    private static final double ABS = 0x1.0p-50;

    // SimplexNoise.GRADIENT, as FusedNoise splits it
    private static final double[] GX = { 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0 };
    private static final double[] GY = { 1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1 };
    private static final double[] GZ = { 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1 };

    /** Octaves bounded over their lattice cells, and octaves that spanned too many cells and kept their own bound. */
    public static final LongAdder OCTAVES = new LongAdder();
    public static final LongAdder OCTAVES_WIDE = new LongAdder();

    /** The bounds of the last successful {@link #normal}. */
    public double lo;
    public double hi;
    /** The last sub-result. */
    private double tlo;
    private double thi;

    /**
     * Bounds of {@code spec.getValue(x, y, z)} over {@code x} in {@code [xlo, xhi]}, {@code y} in {@code [ylo, yhi]} and
     * {@code z} in {@code [zlo, zhi]} (the inputs as the noise is given them). False when an input is past the wrap limit, in
     * which case nothing is bounded.
     */
    public boolean normal(NoiseSpec spec, double xlo, double xhi, double ylo, double yhi, double zlo, double zhi) {
        if (!perlin(spec.first, xlo, xhi, ylo, yhi, zlo, zhi)) {
            return false;
        }
        double a = tlo;
        double b = thi;
        double f = NoiseSpec.INPUT_FACTOR;
        if (!perlin(spec.second, xlo * f, xhi * f, ylo * f, yhi * f, zlo * f, zhi * f)) {
            return false;
        }
        a += tlo;
        b += thi;
        // (first + second) * valueFactor
        double vf = spec.valueFactor;
        double p = a * vf;
        double q = b * vf;
        double l = Math.min(p, q);
        double h = Math.max(p, q);
        double w = REL * (Math.abs(l) + Math.abs(h)) + ABS;
        lo = l - w;
        hi = h + w;
        return true;
    }

    /** {@code PerlinNoise.getValue} over the box, into {@link #tlo}/{@link #thi}. */
    private boolean perlin(NoiseSpec.Perlin p, double xlo, double xhi, double ylo, double yhi, double zlo, double zhi) {
        double lo = 0.0;
        double hi = 0.0;
        for (int i = 0; i < p.count; i++) {
            double f = p.inputFactor[i];
            double ax = Math.min(xlo * f, xhi * f);
            double bx = Math.max(xlo * f, xhi * f);
            double ay = Math.min(ylo * f, yhi * f);
            double by = Math.max(ylo * f, yhi * f);
            double az = Math.min(zlo * f, zhi * f);
            double bz = Math.max(zlo * f, zhi * f);
            if (!(ax > -LIMIT && bx < LIMIT && ay > -LIMIT && by < LIMIT && az > -LIMIT && bz < LIMIT)) {
                return false;
            }
            // wrap is the identity here. The sum with the octave's offset is monotone. Two ulps cover a caller's rounded inputs.
            double dxlo = Math.nextDown(Math.nextDown(ax + p.xo[i]));
            double dxhi = Math.nextUp(Math.nextUp(bx + p.xo[i]));
            double dylo = Math.nextDown(Math.nextDown(ay + p.yo[i]));
            double dyhi = Math.nextUp(Math.nextUp(by + p.yo[i]));
            double dzlo = Math.nextDown(Math.nextDown(az + p.zo[i]));
            double dzhi = Math.nextUp(Math.nextUp(bz + p.zo[i]));
            int ix0 = FusedNoise.floor(dxlo);
            int ix1 = FusedNoise.floor(dxhi);
            int iy0 = FusedNoise.floor(dylo);
            int iy1 = FusedNoise.floor(dyhi);
            int iz0 = FusedNoise.floor(dzlo);
            int iz1 = FusedNoise.floor(dzhi);
            double olo;
            double ohi;
            if (ix1 - ix0 > 1 || iy1 - iy0 > 1 || iz1 - iz0 > 1) {
                // a fine octave: every gradient term is within 2, and the lerps are convex
                olo = -2.0;
                ohi = 2.0;
                OCTAVES_WIDE.increment();
            } else {
                olo = Double.POSITIVE_INFINITY;
                ohi = Double.NEGATIVE_INFINITY;
                int[] perm = p.perm[i];
                for (int ix = ix0; ix <= ix1; ix++) {
                    double fx0 = ix == ix0 ? Math.max(0.0, dxlo - ix) : 0.0;
                    double fx1 = ix == ix1 ? Math.min(1.0, dxhi - ix) : 1.0;
                    for (int iy = iy0; iy <= iy1; iy++) {
                        double fy0 = iy == iy0 ? Math.max(0.0, dylo - iy) : 0.0;
                        double fy1 = iy == iy1 ? Math.min(1.0, dyhi - iy) : 1.0;
                        for (int iz = iz0; iz <= iz1; iz++) {
                            double fz0 = iz == iz0 ? Math.max(0.0, dzlo - iz) : 0.0;
                            double fz1 = iz == iz1 ? Math.min(1.0, dzhi - iz) : 1.0;
                            cell(perm, ix, iy, iz, fx0, fx1, fy0, fy1, fz0, fz1);
                            olo = Math.min(olo, tlo);
                            ohi = Math.max(ohi, thi);
                        }
                    }
                }
                olo -= OCTAVE_EPS;
                ohi += OCTAVE_EPS;
            }
            OCTAVES.increment();
            // the kernel's amp * v * valueFactor, with either factor's sign
            double amp = p.amp[i];
            double vf = p.valueFactor[i];
            double s1 = amp * olo;
            double s2 = amp * ohi;
            double slo = Math.min(s1, s2);
            double shi = Math.max(s1, s2);
            double t1 = slo * vf;
            double t2 = shi * vf;
            lo += Math.min(t1, t2);
            hi += Math.max(t1, t2);
        }
        tlo = lo;
        thi = hi;
        return true;
    }

    /** {@code ImprovedNoise.sampleAndLerp} over the fraction box of one lattice cell, into {@link #tlo}/{@link #thi}. */
    private void cell(int[] p, int ix, int iy, int iz, double fx0, double fx1, double fy0, double fy1, double fz0, double fz1) {
        int X = ix & 255;
        int Y = iy & 255;
        int Z = iz & 255;
        int A = p[X];
        int B = p[X + 1];
        int AA = p[A + Y];
        int AB = p[A + Y + 1];
        int BA = p[B + Y];
        int BB = p[B + Y + 1];
        double gx0 = fx0 - 1.0, gx1 = fx1 - 1.0;
        double gy0 = fy0 - 1.0, gy1 = fy1 - 1.0;
        double gz0 = fz0 - 1.0, gz1 = fz1 - 1.0;
        dot(p[AA + Z], fx0, fx1, fy0, fy1, fz0, fz1);
        double d0l = tlo, d0h = thi;
        dot(p[BA + Z], gx0, gx1, fy0, fy1, fz0, fz1);
        double d1l = tlo, d1h = thi;
        dot(p[AB + Z], fx0, fx1, gy0, gy1, fz0, fz1);
        double d2l = tlo, d2h = thi;
        dot(p[BB + Z], gx0, gx1, gy0, gy1, fz0, fz1);
        double d3l = tlo, d3h = thi;
        dot(p[AA + Z + 1], fx0, fx1, fy0, fy1, gz0, gz1);
        double d4l = tlo, d4h = thi;
        dot(p[BA + Z + 1], gx0, gx1, fy0, fy1, gz0, gz1);
        double d5l = tlo, d5h = thi;
        dot(p[AB + Z + 1], fx0, fx1, gy0, gy1, gz0, gz1);
        double d6l = tlo, d6h = thi;
        dot(p[BB + Z + 1], gx0, gx1, gy0, gy1, gz0, gz1);
        double d7l = tlo, d7h = thi;
        // smoothstep is monotone on [0, 1]
        double sx0 = FusedNoise.smooth(fx0), sx1 = FusedNoise.smooth(fx1);
        double sy0 = FusedNoise.smooth(fy0), sy1 = FusedNoise.smooth(fy1);
        double sz0 = FusedNoise.smooth(fz0), sz1 = FusedNoise.smooth(fz1);
        lerp(sx0, sx1, d0l, d0h, d1l, d1h);
        double l0l = tlo, l0h = thi;
        lerp(sx0, sx1, d2l, d2h, d3l, d3h);
        double l1l = tlo, l1h = thi;
        lerp(sx0, sx1, d4l, d4h, d5l, d5h);
        double l2l = tlo, l2h = thi;
        lerp(sx0, sx1, d6l, d6h, d7l, d7h);
        double l3l = tlo, l3h = thi;
        lerp(sy0, sy1, l0l, l0h, l1l, l1h);
        double m0l = tlo, m0h = thi;
        lerp(sy0, sy1, l2l, l2h, l3l, l3h);
        double m1l = tlo, m1h = thi;
        lerp(sz0, sz1, m0l, m0h, m1l, m1h);
    }

    /** The gradient dot product over the offset box, into {@link #tlo}/{@link #thi}. The components are -1, 0 or 1, so each product is exact. */
    private void dot(int h, double x0, double x1, double y0, double y1, double z0, double z1) {
        int g = h & 15;
        double gx = GX[g];
        double gy = GY[g];
        double gz = GZ[g];
        double lo = 0.0;
        double hi = 0.0;
        if (gx >= 0.0) {
            lo += gx * x0;
            hi += gx * x1;
        } else {
            lo += gx * x1;
            hi += gx * x0;
        }
        if (gy >= 0.0) {
            lo += gy * y0;
            hi += gy * y1;
        } else {
            lo += gy * y1;
            hi += gy * y0;
        }
        if (gz >= 0.0) {
            lo += gz * z0;
            hi += gz * z1;
        } else {
            lo += gz * z1;
            hi += gz * z0;
        }
        tlo = lo;
        thi = hi;
    }

    /** {@code a + t * (b - a)} over the box, into {@link #tlo}/{@link #thi}: multilinear, so its range is at the box's corners. */
    private void lerp(double t0, double t1, double a0, double a1, double b0, double b1) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            double t = (c & 1) == 0 ? t0 : t1;
            double a = (c & 2) == 0 ? a0 : a1;
            double b = (c & 4) == 0 ? b0 : b1;
            double v = a + t * (b - a);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        tlo = lo;
        thi = hi;
    }
}
