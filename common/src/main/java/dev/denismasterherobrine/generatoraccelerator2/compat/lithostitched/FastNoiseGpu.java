package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import java.lang.reflect.Field;
import java.util.Random;

/**
 * GPU computation of Lithostitched's FastNoiseLite noises ({@code FNL}, FastNoiseLite by Jordan Peck, MIT). The settings are read
 * off the live noise object. Lithostitched binds the object's seed per world before routers compile. This transcription of the 3D
 * path is then checked against the live object on a spread of points. Only a noise that matches on every point gets a GPU form.
 * Any other noise stays a call into Lithostitched's own code, and the dimension's noise stays on the CPU.
 *
 * <p>Covered: cellular noise (every distance function and return type, any jitter), no fractal, every 3D transform. Lithostitched's
 * configs use doubles for coordinates ({@code FNLfloat = double}) and floats inside.
 */
final class FastNoiseGpu {
    private FastNoiseGpu() {}

    static final String FNL = "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FNL";
    private static final int PRIME_X = 501125321, PRIME_Y = 1136930381, PRIME_Z = 1720413743;
    /** Transforms as {@code FNL.TransformType3D} orders them. */
    static final int T_NONE = 0, T_XY = 1, T_XZ = 2, T_OS2 = 3;
    static final int D_EUCLIDEAN = 0, D_EUCLIDEAN_SQ = 1, D_MANHATTAN = 2, D_HYBRID = 3;
    static final int R_CELL_VALUE = 0, R_DISTANCE = 1, R_DISTANCE2 = 2, R_ADD = 3, R_SUB = 4, R_MUL = 5, R_DIV = 6;

    /** The GPU form of a {@code FastNoiseConfig}. Null when the GPU does not compute it exactly. The reason is then logged once. */
    static ExternSamplers.GpuForm describe(Object config, ExternSamplers.Sampler sampler) {
        try {
            Field f = field(config.getClass(), "fnl");
            Object fnl = f.get(config);
            Class<?> c = fnl.getClass();
            if (!c.getName().equals(FNL)) {
                return null;
            }
            String noise = name(c, fnl, "mNoiseType"), fractal = name(c, fnl, "mFractalType");
            if (!noise.equals("Cellular") || !fractal.equals("None")) {
                return refuse(config, "noise " + noise + ", fractal " + fractal);
            }
            int transform = switch (name(c, fnl, "mTransformType3D")) {
                case "None" -> T_NONE;
                case "ImproveXYPlanes" -> T_XY;
                case "ImproveXZPlanes" -> T_XZ;
                case "DefaultOpenSimplex2" -> T_OS2;
                default -> -1;
            };
            int dist = switch (name(c, fnl, "mCellularDistanceFunction")) {
                case "Euclidean" -> D_EUCLIDEAN;
                case "EuclideanSq" -> D_EUCLIDEAN_SQ;
                case "Manhattan" -> D_MANHATTAN;
                case "Hybrid" -> D_HYBRID;
                default -> -1;
            };
            int ret = switch (name(c, fnl, "mCellularReturnType")) {
                case "CellValue" -> R_CELL_VALUE;
                case "Distance" -> R_DISTANCE;
                case "Distance2" -> R_DISTANCE2;
                case "Distance2Add" -> R_ADD;
                case "Distance2Sub" -> R_SUB;
                case "Distance2Mul" -> R_MUL;
                case "Distance2Div" -> R_DIV;
                default -> -1;
            };
            if (transform < 0 || dist < 0 || ret < 0) {
                return refuse(config, "settings");
            }
            int seed = field(c, "mSeed").getInt(fnl);
            float freq = field(c, "mFrequency").getFloat(fnl), jitter = field(c, "mCellularJitterModifier").getFloat(fnl);
            float[] table = (float[]) field(c, "RandVecs3D").get(null);
            if (table == null || table.length != 1024) {
                return refuse(config, "its random vectors");
            }
            int[] ints = { seed, transform, dist, ret };
            float[] floats = { freq, jitter };
            // Checked against the live object on a spread of points: block-scale coordinates, fractions, far out, negative.
            Random r = new Random(0x6E6F697365L);
            for (int i = 0; i < 1024; i++) {
                double span = i < 512 ? 4096.0 : 3.0e7;
                double x = (r.nextDouble() * 2.0 - 1.0) * span, y = (r.nextDouble() * 2.0 - 1.0) * 512.0, z = (r.nextDouble() * 2.0 - 1.0) * span;
                if ((i & 3) == 0) {
                    x = Math.floor(x);
                    y = Math.floor(y);
                    z = Math.floor(z);
                }
                float want = (float) sampler.sample(x, y, z);
                float got = cellular(ints, floats, table, x, y, z);
                if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)) {
                    return refuse(config, "the transcription differs at " + x + ", " + y + ", " + z + " (" + want + " vs " + got + ")");
                }
            }
            return new ExternSamplers.GpuForm(ExternSamplers.GpuForm.FNL_CELLULAR, ints, floats, table);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return refuse(config, e.toString());
        }
    }

    private static ExternSamplers.GpuForm refuse(Object config, String why) {
        Ga2Log.get("Compat").info("Lithostitched fast noise {} -> CPU on GPU path: {}", config, why);
        return null;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // the superclass may declare it
            } catch (LinkageError e) {
                throw new NoSuchFieldException(name + " (" + k.getName() + " names a class this side does not have)");
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String name(Class<?> c, Object o, String field) throws ReflectiveOperationException {
        Object v = field(c, field).get(o);
        return v instanceof Enum<?> e ? e.name() : String.valueOf(v);
    }

    private static float max(float a, float b) {
        return a > b ? a : b;
    }

    private static float min(float a, float b) {
        return a < b ? a : b;
    }

    private static float abs(float f) {
        return f < 0 ? -f : f;
    }

    private static int round(double f) {
        return f >= 0.0 ? (int) (f + 0.5) : (int) (f - 0.5);
    }

    /** {@code FNL.GetNoise(x, y, z)} for a cellular noise without fractal ({@code SingleCellular} after the frequency and transform). */
    static float cellular(int[] p, float[] fp, float[] rv, double x, double y, double z) {
        int seed = p[0], transform = p[1], dist = p[2], ret = p[3];
        x *= fp[0];
        y *= fp[0];
        z *= fp[0];
        if (transform == T_XY) {
            double xy = x + y;
            double s2 = xy * -0.211324865405187;
            z *= 0.577350269189626;
            x += s2 - z;
            y = y + s2 - z;
            z += xy * 0.577350269189626;
        } else if (transform == T_XZ) {
            double xz = x + z;
            double s2 = xz * -0.211324865405187;
            y *= 0.577350269189626;
            x += s2 - y;
            z += s2 - y;
            y += xz * 0.577350269189626;
        } else if (transform == T_OS2) {
            double r3 = 2.0 / 3.0;
            double r = (x + y + z) * r3;
            x = r - x;
            y = r - y;
            z = r - z;
        }
        int xr = round(x), yr = round(y), zr = round(z);
        float distance0 = Float.MAX_VALUE, distance1 = Float.MAX_VALUE;
        int closestHash = 0;
        float jitter = 0.39614353f * fp[1];
        int xPrimed = (xr - 1) * PRIME_X;
        int yPrimedBase = (yr - 1) * PRIME_Y;
        int zPrimedBase = (zr - 1) * PRIME_Z;
        for (int xi = xr - 1; xi <= xr + 1; xi++) {
            int yPrimed = yPrimedBase;
            for (int yi = yr - 1; yi <= yr + 1; yi++) {
                int zPrimed = zPrimedBase;
                for (int zi = zr - 1; zi <= zr + 1; zi++) {
                    int hash = (seed ^ xPrimed ^ yPrimed ^ zPrimed) * 668265261;
                    int idx = hash & (255 << 2);
                    float vx = (float) (xi - x) + rv[idx] * jitter;
                    float vy = (float) (yi - y) + rv[idx | 1] * jitter;
                    float vz = (float) (zi - z) + rv[idx | 2] * jitter;
                    float d = switch (dist) {
                        case D_MANHATTAN -> abs(vx) + abs(vy) + abs(vz);
                        case D_HYBRID -> (abs(vx) + abs(vy) + abs(vz)) + (vx * vx + vy * vy + vz * vz);
                        default -> vx * vx + vy * vy + vz * vz;
                    };
                    distance1 = max(min(distance1, d), distance0);
                    if (d < distance0) {
                        distance0 = d;
                        closestHash = hash;
                    }
                    zPrimed += PRIME_Z;
                }
                yPrimed += PRIME_Y;
            }
            xPrimed += PRIME_X;
        }
        if (dist == D_EUCLIDEAN && ret != R_CELL_VALUE) {
            distance0 = (float) Math.sqrt(distance0);
            if (ret != R_DISTANCE) {
                distance1 = (float) Math.sqrt(distance1);
            }
        }
        return switch (ret) {
            case R_CELL_VALUE -> closestHash * (1 / 2147483648.0f);
            case R_DISTANCE -> distance0 - 1;
            case R_DISTANCE2 -> distance1 - 1;
            case R_ADD -> (distance1 + distance0) * 0.5f - 1;
            case R_SUB -> distance1 - distance0 - 1;
            case R_MUL -> distance1 * distance0 * 0.5f - 1;
            default -> distance0 / distance1 - 1;
        };
    }

    /** The OpenCL transcription of {@link #cellular}. It goes into the GPU program's prelude, with fp64 and no contraction. */
    static final String CL = """
        /* FastAbs: keeps -0.0 as Java's does */
        float fnl_abs(float f) { return f < 0.0f ? -f : f; }
        float fnl_cellular(__global const int* p, __global const float* fp, __global const float* rv, double x, double y, double z) {
          int seed = p[0], transform = p[1], dist = p[2], ret = p[3];
          x *= (double) fp[0]; y *= (double) fp[0]; z *= (double) fp[0];
          if (transform == 1) {
            double xy = x + y, s2 = xy * -0.211324865405187;
            z *= 0.577350269189626; x += s2 - z; y = y + s2 - z; z += xy * 0.577350269189626;
          } else if (transform == 2) {
            double xz = x + z, s2 = xz * -0.211324865405187;
            y *= 0.577350269189626; x += s2 - y; z += s2 - y; y += xz * 0.577350269189626;
          } else if (transform == 3) {
            double r = (x + y + z) * (2.0 / 3.0);
            x = r - x; y = r - y; z = r - z;
          }
          int xr = x >= 0.0 ? (int) (x + 0.5) : (int) (x - 0.5);
          int yr = y >= 0.0 ? (int) (y + 0.5) : (int) (y - 0.5);
          int zr = z >= 0.0 ? (int) (z + 0.5) : (int) (z - 0.5);
          float d0 = FLT_MAX, d1 = FLT_MAX;
          int closest = 0;
          float jitter = 0.39614353f * fp[1];
          uint xp = (uint) (xr - 1) * 501125321u, ypb = (uint) (yr - 1) * 1136930381u, zpb = (uint) (zr - 1) * 1720413743u;
          for (int xi = xr - 1; xi <= xr + 1; xi++) {
            uint yp = ypb;
            for (int yi = yr - 1; yi <= yr + 1; yi++) {
              uint zp = zpb;
              for (int zi = zr - 1; zi <= zr + 1; zi++) {
                int hash = (int) (((uint) seed ^ xp ^ yp ^ zp) * 668265261u);
                int idx = hash & (255 << 2);
                float vx = (float) ((double) xi - x) + rv[idx] * jitter;
                float vy = (float) ((double) yi - y) + rv[idx | 1] * jitter;
                float vz = (float) ((double) zi - z) + rv[idx | 2] * jitter;
                float d = dist == 2 ? fnl_abs(vx) + fnl_abs(vy) + fnl_abs(vz)
                  : dist == 3 ? (fnl_abs(vx) + fnl_abs(vy) + fnl_abs(vz)) + (vx * vx + vy * vy + vz * vz)
                  : vx * vx + vy * vy + vz * vz;
                float m = d1 < d ? d1 : d;
                d1 = m > d0 ? m : d0;
                if (d < d0) { d0 = d; closest = hash; }
                zp += 1720413743u;
              }
              yp += 1136930381u;
            }
            xp += 501125321u;
          }
          if (dist == 0 && ret != 0) {
            d0 = (float) sqrt((double) d0);
            if (ret != 1) d1 = (float) sqrt((double) d1);
          }
          switch (ret) {
            case 0: return (float) closest * 0x1p-31f;
            case 1: return d0 - 1.0f;
            case 2: return d1 - 1.0f;
            case 3: return (d1 + d0) * 0.5f - 1.0f;
            case 4: return d1 - d0 - 1.0f;
            case 5: return d1 * d0 * 0.5f - 1.0f;
            default: return d0 / d1 - 1.0f;
          }
        }
        /* the setup's check: fnl_cellular at given points */
        __kernel void k_fnl_cellular_test(__global const int* oi, __global const float* of, int io, int fo, int to, __global const double* xyz, int n,
                                          __global float* out) {
          int g = get_global_id(0);
          if (g >= n) return;
          out[g] = fnl_cellular(oi + io, of + fo, of + to, xyz[g * 3], xyz[g * 3 + 1], xyz[g * 3 + 2]);
        }
        """;
}
