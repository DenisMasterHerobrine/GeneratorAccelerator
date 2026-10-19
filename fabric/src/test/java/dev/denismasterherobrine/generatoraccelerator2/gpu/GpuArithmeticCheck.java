package dev.denismasterherobrine.generatoraccelerator2.gpu;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.SplittableRandom;
import org.lwjgl.BufferUtils;

/**
 * Does the device compute Java's float and double expressions bit for bit? The patterns the generated code relies on: lerp
 * (a + t * (b - a), which a compiler may fuse into a multiply-add), the spline's final expression, the double-based float
 * division and float/double conversions. Run before every probe. Any difference names the pattern and the build options.
 */
final class GpuArithmeticCheck {
    private GpuArithmeticCheck() {}

    private static final String SRC = """
        #pragma OPENCL EXTENSION cl_khr_fp64 : enable
        #pragma OPENCL FP_CONTRACT OFF
        float lerpf(float t, float a, float b) { return a + t * (b - a); }
        double lerpd(double t, double a, double b) { return a + t * (b - a); }
        __kernel void k(__global const float* f, __global const double* d, __global float* of, __global double* od, int n) {
          int i = get_global_id(0); if (i >= n) return;
          float t = f[4 * i], a = f[4 * i + 1], b = f[4 * i + 2], e = f[4 * i + 3];
          of[4 * i] = lerpf(t, a, b);
          of[4 * i + 1] = lerpf(t, a, b) + t * (1.0f - t) * lerpf(t, e, b);
          of[4 * i + 2] = (float) ((double) a / (double) b);
          of[4 * i + 3] = (float) d[4 * i];
          double x = d[4 * i], y = d[4 * i + 1], z = d[4 * i + 2], w = d[4 * i + 3];
          od[4 * i] = lerpd(x, y, z);
          od[4 * i + 1] = x * y + z;
          od[4 * i + 2] = x * y * z * (x * (x * 6.0 - 15.0) + 10.0);
          od[4 * i + 3] = y / w;
        }
        """;

    /** @return a description of every mismatching pattern (empty when the device matches Java everywhere) */
    static String run(Cl cl, String options) {
        int n = 1 << 18;
        SplittableRandom rnd = new SplittableRandom(42);
        float[] f = new float[4 * n];
        double[] d = new double[4 * n];
        for (int i = 0; i < f.length; i++) {
            f[i] = (float) (rnd.nextDouble() * 2.0 - 0.5);
            d[i] = rnd.nextDouble() * 3.0 - 1.0;
        }
        ByteBuffer fb = BufferUtils.createByteBuffer(4 * f.length);
        fb.asFloatBuffer().put(f);
        ByteBuffer db = BufferUtils.createByteBuffer(8 * d.length);
        db.asDoubleBuffer().put(d);
        long program = cl.build(SRC, options, null);
        long k = cl.kernel(program, "k");
        long mf = cl.buffer(fb);
        long md = cl.buffer(db);
        long of = cl.buffer(4L * f.length);
        long od = cl.buffer(8L * d.length);
        Cl.arg(k, 0, mf);
        Cl.arg(k, 1, md);
        Cl.arg(k, 2, of);
        Cl.arg(k, 3, od);
        Cl.argInt(k, 4, n);
        Cl.nanos(cl.launch(k, n, 64));
        ByteBuffer rf = BufferUtils.createByteBuffer(4 * f.length);
        ByteBuffer rd = BufferUtils.createByteBuffer(8 * d.length);
        cl.read(of, rf);
        cl.read(od, rd);
        FloatBuffer gf = rf.asFloatBuffer();
        DoubleBuffer gd = rd.asDoubleBuffer();
        long[] bad = new long[8];
        for (int i = 0; i < n; i++) {
            float t = f[4 * i], a = f[4 * i + 1], b = f[4 * i + 2], e = f[4 * i + 3];
            float[] jf = { a + t * (b - a), (a + t * (b - a)) + t * (1.0F - t) * (e + t * (b - e)), (float) ((double) a / (double) b), (float) d[4 * i] };
            double x = d[4 * i], y = d[4 * i + 1], z = d[4 * i + 2], w = d[4 * i + 3];
            double[] jd = { y + x * (z - y), x * y + z, x * y * z * (x * (x * 6.0 - 15.0) + 10.0), y / w };
            for (int p = 0; p < 4; p++) {
                if (Float.floatToRawIntBits(jf[p]) != Float.floatToRawIntBits(gf.get(4 * i + p))) {
                    bad[p]++;
                }
                if (Double.doubleToRawLongBits(jd[p]) != Double.doubleToRawLongBits(gd.get(4 * i + p))) {
                    bad[4 + p]++;
                }
            }
        }
        cl.release(mf);
        cl.release(md);
        cl.release(of);
        cl.release(od);
        String[] names = { "float lerp", "float spline tail", "float divide via double", "double to float", "double lerp", "double x*y+z",
            "double smoothstep", "double divide" };
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < 8; p++) {
            if (bad[p] > 0) {
                sb.append(String.format(Locale.ROOT, "%s: %d of %d differ; ", names[p], bad[p], n));
            }
        }
        return sb.toString();
    }
}
