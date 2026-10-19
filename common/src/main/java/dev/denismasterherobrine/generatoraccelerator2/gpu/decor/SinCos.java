package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import java.util.stream.IntStream;

/**
 * {@code Math.sin} and {@code Math.cos} of an ore's angle as this JVM computes them, for the GPU. The angle is
 * {@code nextFloat() * (float) Math.PI}, so it has 2^24 values, one per {@code next(24)} draw k. HotSpot's {@code Math.sin} is an
 * intrinsic. It differs from {@code StrictMath.sin} (fdlibm) by one ulp for a few percent of the angles (measured: 3.3% for sin, 2.8%
 * for cos, never more than one ulp). The GPU runs fdlibm ({@link #SOURCE}). fdlibm uses only IEEE double operations, so the GPU's
 * result equals {@code StrictMath}'s exactly. The GPU then applies this JVM's difference from a table of 2 bits per k: 0 the same,
 * 1 one ulp up, 2 one ulp down, 3 anything else (the ore goes to the CPU).
 */
public final class SinCos {
    public static final int N = 1 << 24;
    /** 2 bits per k, 16 per int. */
    public final int[] sinFix = new int[N / 16], cosFix = new int[N / 16];
    public final int odd;

    private SinCos(int odd) {
        this.odd = odd;
    }

    /** The ore's angle for draw {@code k} ({@code OreFeature.place}). */
    public static float angle(int k) {
        return ((float) k * 5.9604645E-8F) * (float) Math.PI;
    }

    /**
     * The fancy trunk's branch angle for draw {@code k} ({@code FancyTrunkPlacer.placeTrunk}: {@code (double) (nextFloat() * 2.0F) * Math.PI}).
     */
    public static double fancyAngle(int k) {
        return (double) (((float) k * 5.9604645E-8F) * 2.0F) * Math.PI;
    }

    /** Builds both tables for the ore's angle (parallel, about a second of CPU in total). */
    public static SinCos build() {
        return build(k -> (double) angle(k));
    }

    /** Builds both tables for the fancy trunk's angle. */
    public static SinCos buildFancy() {
        return build(SinCos::fancyAngle);
    }

    private static SinCos build(java.util.function.IntToDoubleFunction angle) {
        int[] odd = new int[64];
        int[] s = new int[N / 16], c = new int[N / 16];
        IntStream.range(0, 64).parallel().forEach(block -> {
            int per = N / 64;
            for (int k = block * per, end = k + per; k < end; k++) {
                double x = angle.applyAsDouble(k);
                int cs = code(Math.sin(x), StrictMath.sin(x));
                int cc = code(Math.cos(x), StrictMath.cos(x));
                if (cs == 3 || cc == 3) {
                    odd[block]++;
                }
                s[k >> 4] |= cs << ((k & 15) * 2);
                c[k >> 4] |= cc << ((k & 15) * 2);
            }
        });
        int n = 0;
        for (int v : odd) {
            n += v;
        }
        SinCos t = new SinCos(n);
        System.arraycopy(s, 0, t.sinFix, 0, s.length);
        System.arraycopy(c, 0, t.cosFix, 0, c.length);
        return t;
    }

    private static int code(double jvm, double strict) {
        long d = Double.doubleToRawLongBits(jvm) - Double.doubleToRawLongBits(strict);
        return d == 0 ? 0 : d == 1 ? 1 : d == -1 ? 2 : 3;
    }

    /**
     * fdlibm's {@code sin}/{@code cos} ({@code java.lang.FdLibm}) for arguments up to 2^19 * pi / 2, and the corrected values
     * {@code os_sin(k, fix, &bad)} / {@code os_cos}. Needs the program's {@code cl_khr_fp64} and {@code FP_CONTRACT OFF}.
     */
    public static final String SOURCE = """
        // ---- Math.sin / Math.cos of the ore angle (gpu.decor.SinCos) ----
        __constant int FD_NPIO2_HW[32] = {
          0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C,
          0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C,
          0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB };
        int fd_hi(double x) { return (int) (as_long(x) >> 32); }
        double fd_hilo(int hi, int lo) { return as_double((long) (((ulong) (uint) hi << 32) | (ulong) (uint) lo)); }
        double fd_ksin(double x, double y, int iy) {
          int ix = fd_hi(x) & 0x7fffffff;
          if (ix < 0x3e400000) return x;
          double z = x * x, v = z * x;
          double r = 0x1.111111110f8a6p-7 + z * (-0x1.a01a019c161d5p-13 + z * (0x1.71de357b1fe7dp-19 + z * (-0x1.ae5e68a2b9cebp-26 + z * 0x1.5d93a5acfd57cp-33)));
          if (iy == 0) return x + v * (-0x1.5555555555549p-3 + z * r);
          return x - ((z * (0.5 * y - v * r) - y) - v * -0x1.5555555555549p-3);
        }
        double fd_kcos(double x, double y) {
          int ix = fd_hi(x) & 0x7fffffff;
          if (ix < 0x3e400000) return 1.0;
          double z = x * x;
          double r = z * (0x1.555555555554cp-5 + z * (-0x1.6c16c16c15177p-10 + z * (0x1.a01a019cb159p-16 + z * (-0x1.27e4f809c52adp-22
            + z * (0x1.1ee9ebdb4b1c4p-29 + z * -0x1.8fae9be8838d4p-37)))));
          if (ix < 0x3FD33333) return 1.0 - (0.5 * z - (z * r - x * y));
          double qx = ix > 0x3fe90000 ? 0.28125 : fd_hilo(ix - 0x00200000, 0);
          double hz = 0.5 * z - qx, a = 1.0 - qx;
          return a - (hz - (z * r - x * y));
        }
        /* __ieee754_rem_pio2 up to 2^19 * pi / 2; 99 beyond (not reached by an ore angle) */
        int fd_rem(double x, double* y0, double* y1) {
          int hx = fd_hi(x), ix = hx & 0x7fffffff;
          if (ix <= 0x3fe921fb) { *y0 = x; *y1 = 0.0; return 0; }
          if (ix < 0x4002d97c) {
            if (hx > 0) {
              double z = x - 0x1.921fb544p0;
              if (ix != 0x3ff921fb) { *y0 = z - 0x1.0b4611a626331p-34; *y1 = (z - *y0) - 0x1.0b4611a626331p-34; }
              else { z -= 0x1.0b4611a6p-34; *y0 = z - 0x1.3198a2e037073p-69; *y1 = (z - *y0) - 0x1.3198a2e037073p-69; }
              return 1;
            }
            double z = x + 0x1.921fb544p0;
            if (ix != 0x3ff921fb) { *y0 = z + 0x1.0b4611a626331p-34; *y1 = (z - *y0) + 0x1.0b4611a626331p-34; }
            else { z += 0x1.0b4611a6p-34; *y0 = z + 0x1.3198a2e037073p-69; *y1 = (z - *y0) + 0x1.3198a2e037073p-69; }
            return -1;
          }
          if (ix <= 0x413921fb) {
            double t = fabs(x);
            int n = (int) (t * 0x1.45f306dc9c883p-1 + 0.5);
            double fn = (double) n;
            double r = t - fn * 0x1.921fb544p0;
            double w = fn * 0x1.0b4611a626331p-34;
            if (n < 32 && ix != FD_NPIO2_HW[n - 1]) {
              *y0 = r - w;
            } else {
              int j = ix >> 20;
              *y0 = r - w;
              int i = j - ((fd_hi(*y0) >> 20) & 0x7ff);
              if (i > 16) {
                t = r; w = fn * 0x1.0b4611a6p-34; r = t - w;
                w = fn * 0x1.3198a2e037073p-69 - ((t - r) - w);
                *y0 = r - w;
                i = j - ((fd_hi(*y0) >> 20) & 0x7ff);
                if (i > 49) {
                  t = r; w = fn * 0x1.3198a2ep-69; r = t - w;
                  w = fn * 0x1.b839a252049c1p-104 - ((t - r) - w);
                  *y0 = r - w;
                }
              }
            }
            *y1 = (r - *y0) - w;
            if (hx < 0) { *y0 = -*y0; *y1 = -*y1; return -n; }
            return n;
          }
          return 99;
        }
        double fd_sin(double x, int* bad) {
          int ix = fd_hi(x) & 0x7fffffff;
          if (ix <= 0x3fe921fb) return fd_ksin(x, 0.0, 0);
          double y0, y1; int n = fd_rem(x, &y0, &y1);
          if (n == 99) { *bad = 1; return 0.0; }
          switch (n & 3) {
            case 0: return fd_ksin(y0, y1, 1);
            case 1: return fd_kcos(y0, y1);
            case 2: return -fd_ksin(y0, y1, 1);
            default: return -fd_kcos(y0, y1);
          }
        }
        double fd_cos(double x, int* bad) {
          int ix = fd_hi(x) & 0x7fffffff;
          if (ix <= 0x3fe921fb) return fd_kcos(x, 0.0);
          double y0, y1; int n = fd_rem(x, &y0, &y1);
          if (n == 99) { *bad = 1; return 0.0; }
          switch (n & 3) {
            case 0: return fd_kcos(y0, y1);
            case 1: return -fd_ksin(y0, y1, 1);
            case 2: return -fd_kcos(y0, y1);
            default: return fd_ksin(y0, y1, 1);
          }
        }
        double os_fix(double v, int k, __global const uint* fix, int* bad) {
          uint c = (fix[k >> 4] >> ((k & 15) * 2)) & 3u;
          if (c == 1u) return as_double(as_long(v) + 1L);
          if (c == 2u) return as_double(as_long(v) - 1L);
          if (c == 3u) *bad = 1;
          return v;
        }
        float os_angle(int k) { return ((float) k * as_float(0x33800000)) * as_float(0x40490fdb); }
        double os_sin(int k, __global const uint* fix, int* bad) { return os_fix(fd_sin((double) os_angle(k), bad), k, fix, bad); }
        double os_cos(int k, __global const uint* fix, int* bad) { return os_fix(fd_cos((double) os_angle(k), bad), k, fix, bad); }
        /* the fancy trunk's angle: (double) (nextFloat() * 2.0F) * Math.PI */
        double os_fangle(int k) { return (double) (((float) k * as_float(0x33800000)) * 2.0f) * as_double(0x400921fb54442d18L); }
        double os_fsin(int k, __global const uint* fix, int* bad) { return os_fix(fd_sin(os_fangle(k), bad), k, fix, bad); }
        double os_fcos(int k, __global const uint* fix, int* bad) { return os_fix(fd_cos(os_fangle(k), bad), k, fix, bad); }
        __kernel void k_sincos_test(__global const uint* sfix, __global const uint* cfix, __global const int* ks, int n, __global double* out) {
          int g = get_global_id(0); if (g >= n) return;
          int bad = 0; int k = ks[g];
          out[g * 2] = os_sin(k, sfix, &bad); out[g * 2 + 1] = os_cos(k, cfix, &bad);
          if (bad) { out[g * 2] = NAN; out[g * 2 + 1] = NAN; }
        }
        __kernel void k_fsincos_test(__global const uint* sfix, __global const uint* cfix, __global const int* ks, int n, __global double* out) {
          int g = get_global_id(0); if (g >= n) return;
          int bad = 0; int k = ks[g];
          out[g * 2] = os_fsin(k, sfix, &bad); out[g * 2 + 1] = os_fcos(k, cfix, &bad);
          if (bad) { out[g * 2] = NAN; out[g * 2 + 1] = NAN; }
        }
        """;
}
