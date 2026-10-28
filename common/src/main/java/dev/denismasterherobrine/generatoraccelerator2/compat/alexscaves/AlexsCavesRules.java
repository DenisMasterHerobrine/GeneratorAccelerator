package dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceExtern;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.objectweb.asm.tree.ClassNode;

/**
 * Alex's Caves' surface condition {@code SimplexConditionSource(noiseMin, noiseMax, noiseScale, yScale, offsetType)} (2.0.2), which the
 * Magnetic Caves and Forlorn Hollows rules test. It samples the mod's seedless simplex noise at the block, shifted by the offset
 * type, and tests {@code noiseMin < n <= noiseMax}. Compiled as an extern condition that calls the mod's own noise
 * ({@code ACMath.sampleNoise3D}), instead of running as an opaque rule object. An opaque rule anywhere in the overworld's rules made
 * every chunk build its surface through a shadow vanilla context, without GA2's locked sections. The condition's code is checked by
 * its fingerprint ({@link CodeShape}). On another version it stays opaque.
 *
 * <p>On the GPU's surface the condition runs as OpenCL ({@link Simplex#gpu}). The mod's simplex noise ({@code ACSimplexNoise},
 * Gustavson's simplex in doubles over a fixed permutation) is transcribed operation for operation and reads the mod's own tables.
 * This holds only for the code it was transcribed from, checked by fingerprint.
 */
public final class AlexsCavesRules {
    private AlexsCavesRules() {}

    static final String SIMPLEX = "com.github.alexmodguy.alexscaves.server.level.surface.ACSurfaceRuleConditionRegistry$SimplexConditionSource";
    private static final String CONDITION = SIMPLEX + "$1NoiseCondition";
    private static final String MATH = "com.github.alexmodguy.alexscaves.server.misc.ACMath";
    /** The condition's {@code test} as 2.0.2 has it. */
    private static final String TEST_FINGERPRINT = "c7a2e09cc51c4185";
    private static final String SIMPLEX_NOISE = "com.github.alexmodguy.alexscaves.server.misc.ACSimplexNoise";
    /** {@code ACMath.sampleNoise3D(IIIF)F} and {@code ACSimplexNoise}'s {@code noise(DDD)D}, {@code fastfloor} and 3D {@code dot} as 2.0.2 has them. */
    private static final String SAMPLE_FINGERPRINT = "fcd5655df6c17f75";
    private static final String NOISE_FINGERPRINT = "adb97e79e1e77e93";
    private static final String FLOOR_FINGERPRINT = "ac0ceadae6917770";
    private static final String DOT_FINGERPRINT = "cf35ea531f3fb57b";

    private static volatile Boolean known;

    public static void register() {
        SurfaceExtern.register((SurfaceExtern.CondLowering) AlexsCavesRules::lower);
    }

    @Nullable
    static Ir.Cond lower(SurfaceRules.ConditionSource source, SurfaceExtern.Context ctx) {
        if (!source.getClass().getName().equals(SIMPLEX) || !codeKnown() || Noise.SAMPLE == null) {
            return null;
        }
        Object[] c = Reflect.components(source, SIMPLEX, "noiseMin", "noiseMax", "noiseScale", "yScale", "offsetType");
        if (c == null || !(c[0] instanceof Float min) || !(c[1] instanceof Float max) || !(c[2] instanceof Float scale) || !(c[3] instanceof Float yScale)
            || !(c[4] instanceof Integer offset)) {
            return null;
        }
        return ctx.extern(new Simplex(min, max, scale, yScale, offset));
    }

    private static boolean codeKnown() {
        Boolean k = known;
        if (k == null) {
            String test = Names.runtimeMethod("net/minecraft/world/level/levelgen/SurfaceRules$Condition", "test", "()Z");
            String got = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(CONDITION), test, "()Z"));
            k = got.equals(TEST_FINGERPRINT);
            if (!k) {
                Ga2Log.get("Surface").info("Alex's Caves simplex condition: unknown code ({}) -> runs as a rule object", got);
            }
            known = k;
        }
        return k;
    }

    /**
     * The noise's tables for the GPU, or null when its code is not the one transcribed: {@code perm} (512 entries), {@code permMod12}
     * (512), then the 12 gradients' x, y and z. Read once.
     */
    @Nullable
    private static int[] gpuTables() {
        int[] t = GpuTables.TABLES;
        return t.length == 0 ? null : t;
    }

    private static final class GpuTables {
        static final int[] TABLES = read();

        private static int[] read() {
            String grad = "L" + SIMPLEX_NOISE.replace('.', '/') + "$Grad;";
            ClassNode noise = CodeShape.classNode(SIMPLEX_NOISE);
            String[] got = {
                CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(MATH), "sampleNoise3D", "(IIIF)F")),
                CodeShape.fingerprint(CodeShape.method(noise, "noise", "(DDD)D")),
                CodeShape.fingerprint(CodeShape.method(noise, "fastfloor", "(D)I")),
                CodeShape.fingerprint(CodeShape.method(noise, "dot", "(" + grad + "DDD)D")) };
            if (!got[0].equals(SAMPLE_FINGERPRINT) || !got[1].equals(NOISE_FINGERPRINT) || !got[2].equals(FLOOR_FINGERPRINT) || !got[3].equals(DOT_FINGERPRINT)) {
                Ga2Log.get("Surface").info("Alex's Caves simplex noise: unknown code ({}) -> its condition keeps the surface on the CPU", String.join(", ", got));
                return new int[0];
            }
            try {
                Class<?> c = Reflect.type(SIMPLEX_NOISE);
                short[] perm = (short[]) staticField(c, "perm");
                short[] permMod12 = (short[]) staticField(c, "permMod12");
                Object[] grads = (Object[]) staticField(c, "grad3");
                if (perm.length != 512 || permMod12.length != 512 || grads.length != 12) {
                    return new int[0];
                }
                int[] t = new int[1024 + 36];
                for (int i = 0; i < 512; i++) {
                    // the indices the noise forms from these stay within the tables
                    if (perm[i] < 0 || perm[i] > 255 || permMod12[i] < 0 || permMod12[i] > 11) {
                        return new int[0];
                    }
                    t[i] = perm[i];
                    t[512 + i] = permMod12[i];
                }
                String[] axes = { "x", "y", "z" };
                for (int g = 0; g < 12; g++) {
                    for (int a = 0; a < 3; a++) {
                        Field f = grads[g].getClass().getDeclaredField(axes[a]);
                        f.trySetAccessible();
                        double v = f.getDouble(grads[g]);
                        if (v != -1.0 && v != 0.0 && v != 1.0) {
                            return new int[0];
                        }
                        t[1024 + g * 3 + a] = (int) v;
                    }
                }
                return t;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Ga2Log.get("Surface").info("Alex's Caves simplex noise: tables unreadable ({}) -> its condition keeps the surface on the CPU", e.toString());
                return new int[0];
            }
        }

        private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
            Field f = c.getDeclaredField(name);
            f.trySetAccessible();
            return f.get(null);
        }
    }

    /**
     * {@code ACSimplexNoise.noise(x, y, z)} and the condition's test in OpenCL, over the tables at {@code t} ({@link #gpuTables}). Float
     * steps stay float: the program turns off contraction and divides floats correctly rounded.
     */
    private static final String GPU_NOISE = """
        double ac_corner(__global const int* t, int gi, double x, double y, double z) {
          double d = 0.6 - x * x - y * y - z * z;
          if (d < 0.0) return 0.0;
          d *= d;
          __global const int* g = t + 1024 + gi * 3;
          return d * d * ((double) g[0] * x + (double) g[1] * y + (double) g[2] * z);
        }
        double ac_simplex(__global const int* t, double xin, double yin, double zin) {
          double s = (xin + yin + zin) * AC_F3;
          int i = jd2i_floor(xin + s), j = jd2i_floor(yin + s), k = jd2i_floor(zin + s);
          double tt = (double) sf_iadd(sf_iadd(i, j), k) * AC_G3;
          double x0 = xin - ((double) i - tt), y0 = yin - ((double) j - tt), z0 = zin - ((double) k - tt);
          int i1, j1, k1, i2, j2, k2;
          if (x0 >= y0) {
            if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
            else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
          } else {
            if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
            else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
            else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
          }
          double x1 = x0 - (double) i1 + AC_G3, y1 = y0 - (double) j1 + AC_G3, z1 = z0 - (double) k1 + AC_G3;
          double x2 = x0 - (double) i2 + AC_F3, y2 = y0 - (double) j2 + AC_F3, z2 = z0 - (double) k2 + AC_F3;
          double x3 = x0 - 1.0 + 0.5, y3 = y0 - 1.0 + 0.5, z3 = z0 - 1.0 + 0.5;
          int ii = i & 255, jj = j & 255, kk = k & 255;
          __global const int* p = t;
          __global const int* m = t + 512;
          int g0 = m[ii + p[jj + p[kk]]];
          int g1 = m[ii + i1 + p[jj + j1 + p[kk + k1]]];
          int g2 = m[ii + i2 + p[jj + j2 + p[kk + k2]]];
          int g3 = m[ii + 1 + p[jj + 1 + p[kk + 1]]];
          double n0 = ac_corner(t, g0, x0, y0, z0), n1 = ac_corner(t, g1, x1, y1, z1);
          double n2 = ac_corner(t, g2, x2, y2, z2), n3 = ac_corner(t, g3, x3, y3, z3);
          return 32.0 * (n0 + n1 + n2 + n3);
        }
        int ac_simplex_in(__global const int* t, int bx, int by, int bz, int off, float ys, float sc, float mn, float mx) {
          int x = bx + off * 1000;
          int y = jd2i((double) ((float) by * ys + (float) (off * 2000)));
          int z = bz - off * 3000;
          double n = (double) (float) ac_simplex(t, (double) (((float) x + sc) / sc), (double) (((float) y + sc) / sc), (double) (((float) z + sc) / sc));
          return n > (double) mn && n <= (double) mx;
        }
        """.replace("AC_F3", "(" + Double.toHexString(1.0 / 3.0) + ")").replace("AC_G3", "(" + Double.toHexString(1.0 / 6.0) + ")");

    /** The mod's noise, opened once. */
    private static final class Noise {
        @Nullable
        static final MethodHandle SAMPLE = open();

        @Nullable
        private static MethodHandle open() {
            Class<?> math = Reflect.type(MATH);
            if (math == null) {
                return null;
            }
            try {
                return MethodHandles.publicLookup().findStatic(math, "sampleNoise3D", MethodType.methodType(float.class, int.class, int.class, int.class, float.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    /** The condition's {@code test}, on the block GA2's evaluation is at. */
    private record Simplex(float min, float max, float scale, float yScale, int offset) implements SurfaceExtern.Cond, SurfaceExtern.Gpu {
        @Override
        public boolean test(SurfaceEval s) {
            int x = s.blockX + this.offset * 1000;
            int y = (int) ((float) s.blockY * this.yScale + (float) (this.offset * 2000));
            int z = s.blockZ - this.offset * 3000;
            double n;
            try {
                n = (double) (float) Noise.SAMPLE.invokeExact(x, y, z, this.scale);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
            return n > (double) this.min && n <= (double) this.max;
        }

        @Override
        public Ir.Scope scope() {
            // without a y scale, the noise's y is the offset's alone
            return this.yScale == 0.0F ? Ir.Scope.COLUMN : Ir.Scope.BLOCK;
        }

        @Override
        public boolean positional() {
            return true;
        }

        @Override
        public String gpu(SurfaceExtern.GpuSetup setup) {
            int[] tables = gpuTables();
            if (tables == null) {
                return null;
            }
            setup.helper("ac_simplex", GPU_NOISE);
            return "ac_simplex_in(s->sp + SF_EXT(" + setup.table(tables) + "), s->x, s->y, s->z, " + offset + ", " + f(yScale) + ", " + f(scale) + ", "
                + f(min) + ", " + f(max) + ")";
        }

        /** An OpenCL float literal of exactly {@code v}. */
        private static String f(float v) {
            return "(" + Float.toHexString(v) + "f)";
        }
    }
}
