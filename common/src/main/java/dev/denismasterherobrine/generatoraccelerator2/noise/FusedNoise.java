package dev.denismasterherobrine.generatoraccelerator2.noise;

/**
 * Fused, bit-exact re-implementation of the vanilla 1.20.1 Perlin kernel
 * ({@code ImprovedNoise.noise} + {@code sampleAndLerp}).
 *
 * Every floating point operation is performed in the same order and with the same
 * operands as vanilla, so results are identical to the last bit. The speed comes from:
 * a 512-entry int permutation table (no byte masking), a branch-free gradient dot,
 * a guarded identity fast path for {@code PerlinNoise.wrap}, and straight-line code
 * the JIT can inline as a unit.
 */
public final class FusedNoise {
    private FusedNoise() {}

    /** {@code (double) 1.0E-7F}, exactly the constant vanilla adds in the y-clamp path. */
    private static final double SHIFT_UP_EPSILON = (double) 1.0E-7F;
    private static final double ROUND_OFF = 3.3554432E7;
    /** Below this magnitude {@code wrap(v) == v} holds exactly (2^24 with margin for rounding at the edge). */
    private static final double WRAP_FAST_LIMIT = 1.6E7;

    /** Expands vanilla's {@code byte[256]} permutation into {@code int[512]} with {@code t[i] = p[i & 255] & 255}. */
    public static int[] expand(byte[] p) {
        int[] t = new int[512];
        for (int i = 0; i < 512; i++) {
            t[i] = p[i & 255] & 255;
        }
        return t;
    }

    public static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    private static long lfloor(double d) {
        long i = (long) d;
        return d < (double) i ? i - 1L : i;
    }

    /** Bit-exact {@code PerlinNoise.wrap} with an identity fast path. */
    public static double wrap(double v) {
        if (v > -WRAP_FAST_LIMIT && v < WRAP_FAST_LIMIT) {
            return v;
        }
        return v - (double) lfloor(v / ROUND_OFF + 0.5) * ROUND_OFF;
    }

    private static double smoothstep(double d) {
        return d * d * d * (d * (d * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    // SimplexNoise.GRADIENT split into three flat tables. Values are exactly representable, so
    // g * x is the same operation as vanilla's (double) int * x. Keep this branch-free: both a
    // switch and Perlin's bit-select form were measured to be far slower here because the hash
    // bits are random and every branch mispredicts (see docs/DFC.md).
    private static final double[] GX = { 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0 };
    private static final double[] GY = { 1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1 };
    private static final double[] GZ = { 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1 };

    private static double grad(int h, double x, double y, double z) {
        int g = h & 15;
        return GX[g] * x + GY[g] * y + GZ[g] * z;
    }

    /** Equivalent of {@code ImprovedNoise.noise(x, y, z)} (no y clamp). */
    public static double noise(int[] p, double xo, double yo, double zo, double x, double y, double z) {
        double dx = x + xo;
        double dy = y + yo;
        double dz = z + zo;
        int ix = floor(dx);
        int iy = floor(dy);
        int iz = floor(dz);
        double fx = dx - (double) ix;
        double fy = dy - (double) iy;
        double fz = dz - (double) iz;
        return corners(p, ix, iy, iz, fx, fy, fz, fy);
    }

    /** Equivalent of {@code ImprovedNoise.noise(x, y, z, yScale, yMax)}. */
    public static double noiseY(int[] p, double xo, double yo, double zo, double x, double y, double z, double yScale, double yMax) {
        double dx = x + xo;
        double dy = y + yo;
        double dz = z + zo;
        int ix = floor(dx);
        int iy = floor(dy);
        int iz = floor(dz);
        double fx = dx - (double) ix;
        double fy = dy - (double) iy;
        double fz = dz - (double) iz;
        double d6;
        if (yScale != 0.0) {
            double d7 = (yMax >= 0.0 && yMax < fy) ? yMax : fy;
            d6 = (double) floor(d7 / yScale + SHIFT_UP_EPSILON) * yScale;
        } else {
            d6 = 0.0;
        }
        return corners(p, ix, iy, iz, fx, fy - d6, fz, fy);
    }

    /**
     * Column form of the kernel. The caller precomputes everything that depends only on x and z
     * once per column: {@code A}, {@code B}, {@code Z}, the x/z fractions, their shifted variants and
     * smoothsteps. Only the y-dependent half of {@code sampleAndLerp} runs per corner.
     * Every operation is the same as in {@link #noise}/{@link #noiseY}, only reordered in time.
     */
    public static double cornersColumn(int[] p, int A, int B, int Z, double fx, double fxm, double fz, double fzm, double sx, double sz,
                                       int iy, double y, double ySmooth) {
        int Y = iy & 255;
        int AA = p[A + Y];
        int AB = p[A + Y + 1];
        int BA = p[B + Y];
        int BB = p[B + Y + 1];
        double y1 = y - 1.0;
        double d0 = grad(p[AA + Z], fx, y, fz);
        double d1 = grad(p[BA + Z], fxm, y, fz);
        double d2 = grad(p[AB + Z], fx, y1, fz);
        double d3 = grad(p[BB + Z], fxm, y1, fz);
        double d4 = grad(p[AA + Z + 1], fx, y, fzm);
        double d5 = grad(p[BA + Z + 1], fxm, y, fzm);
        double d6 = grad(p[AB + Z + 1], fx, y1, fzm);
        double d7 = grad(p[BB + Z + 1], fxm, y1, fzm);
        double sy = smoothstep(ySmooth);
        double l0 = lerp(sx, d0, d1);
        double l1 = lerp(sx, d2, d3);
        double l2 = lerp(sx, d4, d5);
        double l3 = lerp(sx, d6, d7);
        return lerp(sz, lerp(sy, l0, l1), lerp(sy, l2, l3));
    }

    /** Doubles per octave that {@link #terms} writes. */
    public static final int TERMS = 24;

    /**
     * How the column forms use {@link #terms} ({@code -Dgeneratoraccelerator2.termCache}, for A/B timing). {@link #TERMS_AUTO} keeps
     * an octave's terms while the octave's corners stay in one lattice cell. Once the octave changes cell at every corner, it
     * computes directly. {@link #TERMS_ALWAYS} refills the terms on every cell change. {@link #TERMS_NEVER} always computes
     * directly. All modes give the same bits.
     */
    public static final int TERM_CACHE = "always".equals(System.getProperty("generatoraccelerator2.termCache")) ? 1
        : "never".equals(System.getProperty("generatoraccelerator2.termCache")) ? 2 : 0;
    public static final int TERMS_AUTO = 0, TERMS_ALWAYS = 1, TERMS_NEVER = 2;

    /**
     * The y-independent parts of {@link #cornersColumn}'s eight gradients at lattice y {@code iy}, at {@code g[t..t + 24)}. Each
     * corner, in {@link #cornersColumn}'s order, gets the gradient's x term ({@code gx * } the corner's x fraction), its y factor
     * and its z term. A column's corners in one lattice cell share these values. Most corners of a low-frequency octave fall in
     * one cell. For them, the hash lookups and these products are done once.
     */
    public static void terms(int[] p, int A, int B, int Z, int iy, double fx, double fxm, double fz, double fzm, double[] g, int t) {
        int Y = iy & 255;
        int AA = p[A + Y];
        int AB = p[A + Y + 1];
        int BA = p[B + Y];
        int BB = p[B + Y + 1];
        term(g, t, p[AA + Z], fx, fz);
        term(g, t + 3, p[BA + Z], fxm, fz);
        term(g, t + 6, p[AB + Z], fx, fz);
        term(g, t + 9, p[BB + Z], fxm, fz);
        term(g, t + 12, p[AA + Z + 1], fx, fzm);
        term(g, t + 15, p[BA + Z + 1], fxm, fzm);
        term(g, t + 18, p[AB + Z + 1], fx, fzm);
        term(g, t + 21, p[BB + Z + 1], fxm, fzm);
    }

    private static void term(double[] g, int t, int h, double x, double z) {
        int k = h & 15;
        g[t] = GX[k] * x;
        g[t + 1] = GY[k];
        g[t + 2] = GZ[k] * z;
    }

    /**
     * {@link #cornersColumn} over {@link #terms}. Each gradient is {@code (gx * x + gy * y) + gz * z} with the stored products. The
     * operations and their order are the same as in {@link #grad}.
     */
    public static double cornersTerms(double[] g, int t, double sx, double sz, double y, double ySmooth) {
        double y1 = y - 1.0;
        double d0 = g[t] + g[t + 1] * y + g[t + 2];
        double d1 = g[t + 3] + g[t + 4] * y + g[t + 5];
        double d2 = g[t + 6] + g[t + 7] * y1 + g[t + 8];
        double d3 = g[t + 9] + g[t + 10] * y1 + g[t + 11];
        double d4 = g[t + 12] + g[t + 13] * y + g[t + 14];
        double d5 = g[t + 15] + g[t + 16] * y + g[t + 17];
        double d6 = g[t + 18] + g[t + 19] * y1 + g[t + 20];
        double d7 = g[t + 21] + g[t + 22] * y1 + g[t + 23];
        double sy = smoothstep(ySmooth);
        double l0 = lerp(sx, d0, d1);
        double l1 = lerp(sx, d2, d3);
        double l2 = lerp(sx, d4, d5);
        double l3 = lerp(sx, d6, d7);
        return lerp(sz, lerp(sy, l0, l1), lerp(sy, l2, l3));
    }

    /** {@code smoothstep}, exposed for column precomputation. */
    public static double smooth(double d) {
        return smoothstep(d);
    }

    /** Equivalent of {@code ImprovedNoise.sampleAndLerp}. */
    private static double corners(int[] p, int ix, int iy, int iz, double x, double y, double z, double ySmooth) {
        int X = ix & 255;
        int Y = iy & 255;
        int Z = iz & 255;
        int A = p[X];
        int B = p[X + 1];
        int AA = p[A + Y];
        int AB = p[A + Y + 1];
        int BA = p[B + Y];
        int BB = p[B + Y + 1];
        double x1 = x - 1.0;
        double y1 = y - 1.0;
        double z1 = z - 1.0;
        double d0 = grad(p[AA + Z], x, y, z);
        double d1 = grad(p[BA + Z], x1, y, z);
        double d2 = grad(p[AB + Z], x, y1, z);
        double d3 = grad(p[BB + Z], x1, y1, z);
        double d4 = grad(p[AA + Z + 1], x, y, z1);
        double d5 = grad(p[BA + Z + 1], x1, y, z1);
        double d6 = grad(p[AB + Z + 1], x, y1, z1);
        double d7 = grad(p[BB + Z + 1], x1, y1, z1);
        double sx = smoothstep(x);
        double sy = smoothstep(ySmooth);
        double sz = smoothstep(z);
        // Mth.lerp3(sx, sy, sz, d0..d7) = lerp(sz, lerp2(sx, sy, d0, d1, d2, d3), lerp2(sx, sy, d4, d5, d6, d7))
        double l0 = lerp(sx, d0, d1);
        double l1 = lerp(sx, d2, d3);
        double l2 = lerp(sx, d4, d5);
        double l3 = lerp(sx, d6, d7);
        return lerp(sz, lerp(sy, l0, l1), lerp(sy, l2, l3));
    }
}
