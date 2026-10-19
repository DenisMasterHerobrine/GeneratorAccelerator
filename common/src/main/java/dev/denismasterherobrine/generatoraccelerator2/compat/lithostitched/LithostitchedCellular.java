package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.CoordSubstitution;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Lithostitched's {@code cellular} density function, compiled as a call into the mod's own object ({@link ExternSamplers}). The
 * function walks a 3x3 (or 3x3x3) grid of cells with Java's legacy random and returns a distance or the nearest cell's value. That
 * walk has no IR form, so the CPU calls the object. The GPU runs a transcription ({@link #CL}). The transcription is checked
 * against the live object on a spread of points before the GPU gets it. Lithostitched reads the world seed from a static field,
 * which it sets before the worlds are created.
 *
 * <p>An optional condition evaluates another function at the winning cell's position. Inside a NoiseChunk that function's cache
 * markers are the chunk's wrappers. The object's own function sees no wrappers. The two agree when every marker computes its
 * function at a foreign position ({@link CoordSubstitution} rewrites such a function). A condition with any other marker keeps the
 * whole function as its own code. With a condition, only the CPU computes it.
 */
final class LithostitchedCellular {
    private LithostitchedCellular() {}

    static final String CELLULAR = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction";
    static final String CONDITION = CELLULAR + "$CellCondition";
    static final String GRID = CELLULAR + "$GridDimensions";
    private static final String HOOKS = "dev.worldgen.lithostitched.impl.LithostitchedInternalHooks";
    /** Return types as {@code ReturnType} orders them. */
    private static final List<String> RETURNS = List.of("CELL_VALUE", "DISTANCE_1", "DISTANCE_2", "DISTANCE_2_ADD", "DISTANCE_2_SUB", "DISTANCE_2_MUL",
        "DISTANCE_2_DIV");
    private static final float[] NO_TABLE = new float[0];
    private static volatile boolean refusalLogged;

    static Node lower(DensityFunction fn, DensityFunctionLowering.Context ctx) {
        Object[] c = Reflect.components(fn, CELLULAR, "condition", "gridDimensions", "jitter", "returnType", "salt");
        if (c == null || !(c[0] instanceof Optional<?> condition) || !(c[2] instanceof Float jitter) || !(c[3] instanceof Enum<?> returnType)
            || !(c[4] instanceof Integer salt)) {
            return null;
        }
        Object[] grid = Reflect.components(c[1], GRID, "horizontal", "vertical");
        int ret = RETURNS.indexOf(returnType.name());
        if (grid == null || !(grid[0] instanceof Integer h) || !(grid[1] instanceof Integer v) || h <= 0 || v < 0 || ret < 0) {
            return null;
        }
        if (condition.isPresent()) {
            Object[] cc = Reflect.components(condition.get(), CONDITION, "function", "range", "fallbackValue");
            if (cc == null || !(cc[0] instanceof DensityFunction function)) {
                return null;
            }
            try {
                // only checks that NoiseChunk's wrappers in the function would compute as the function itself does
                CoordSubstitution.at(ctx.lower(function), new INode.IntCoord(0), new INode.IntCoord(1), new INode.IntCoord(2), ctx);
            } catch (CoordSubstitution.Declined e) {
                ctx.decline("the condition at a cell's position: " + e.getMessage());
                return null;
            }
        }
        ExternSamplers.Sampler sampler = (x, y, z) -> fn.compute(new DensityFunction.SinglePointContext((int) x, (int) y, (int) z));
        int index = ExternSamplers.register(fn, sampler);
        ExternSamplers.registerGpu(index, condition.isPresent() ? null : describe(fn, sampler, h, v, jitter, ret, salt));
        Node x = ctx.intern(new Node.IntToDouble(new INode.IntCoord(0)));
        // a 2D grid never reads y
        Node y = v == 0 ? ctx.intern(new Node.Const(0.0)) : ctx.intern(new Node.IntToDouble(new INode.IntCoord(1)));
        Node z = ctx.intern(new Node.IntToDouble(new INode.IntCoord(2)));
        Node call = ctx.intern(new Node.StaticCall(ExternSamplers.OWNER, ExternSamplers.NAME, ExternSamplers.DESC, List.of(new INode.IntConst(index), x, y, z)));
        double min = fn.minValue(), max = fn.maxValue();
        return ctx.intern(new Node.MulOrAdd(true, call, 1.0, min, max));
    }

    /** The GPU's form, checked against the object, or null. The world seed is read now. Every world compiles its own routers. */
    private static ExternSamplers.GpuForm describe(DensityFunction fn, ExternSamplers.Sampler sampler, int h, int v, float jitter, int ret, int salt) {
        long seed;
        try {
            seed = Reflect.type(HOOKS).getField("WORLD_SEED").getLong(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return refuse("the world seed (" + e + ")");
        }
        // CellularDensityFunction: nextIntBetweenInclusive(0, (int) ((float) size * jitter)), in float as the mod computes it
        int[] p = { h, v, (int) ((float) h * jitter), (int) ((float) v * jitter), ret, salt, (int) seed, (int) (seed >>> 32) };
        java.util.Random r = new java.util.Random(0x63656C6CL);
        for (int i = 0; i < 1024; i++) {
            int span = i < 512 ? 4096 : 30_000_000;
            int x = r.nextInt(2 * span) - span, y = r.nextInt(1024) - 512, z = r.nextInt(2 * span) - span;
            double want = sampler.sample(x, y, z);
            double got = sample(p, x, y, z);
            if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got)) {
                return refuse("the transcription differs at " + x + ", " + y + ", " + z + " (" + want + " vs " + got + ")");
            }
        }
        return new ExternSamplers.GpuForm(ExternSamplers.GpuForm.LITHO_CELLULAR, p, new float[0], NO_TABLE);
    }

    private static ExternSamplers.GpuForm refuse(String why) {
        if (!refusalLogged) {
            refusalLogged = true;
            Ga2Log.get("Compat").info("Lithostitched cellular: {} -> CPU", why);
        }
        return null;
    }

    private static final long MULTIPLIER = 25214903917L, MASK = 281474976710655L;

    /**
     * {@code CellularDensityFunction.sample2D} and {@code sample3D} without a condition, on {@code WorldgenRandom}'s legacy random.
     * {@link #CL} is the same code in OpenCL.
     */
    static double sample(int[] p, int bx, int by, int bz) {
        int h = p[0], v = p[1], jh = p[2], jv = p[3], ret = p[4], salt = p[5];
        long seed = (p[6] & 0xFFFFFFFFL) | ((long) p[7] << 32);
        boolean flat = v == 0;
        int gx = Math.floorDiv(bx, h), gz = Math.floorDiv(bz, h), gy = flat ? 0 : Math.floorDiv(by, v);
        float d1 = Float.MAX_VALUE, d2 = Float.MAX_VALUE, value = Float.MAX_VALUE;
        long[] st = new long[1];
        for (int xo = -1; xo <= 1; xo++) {
            for (int yo = flat ? 0 : -1; yo <= (flat ? 0 : 1); yo++) {
                for (int zo = -1; zo <= 1; zo++) {
                    long s = flat ? (long) (gx + xo) * 341873128712L + (long) (gz + zo) * 132897987541L + seed + (long) salt
                        : (long) (gx + xo) * 341873128712L + (long) (gy + yo) * 297845856431L + (long) (gz + zo) * 132897987541L + seed + (long) salt;
                    st[0] = (s ^ MULTIPLIER) & MASK;
                    int tx = (gx + xo) * h + nextInt(st, jh + 1);
                    int ty = flat ? 0 : (gy + yo) * v + nextInt(st, jv + 1);
                    int tz = (gz + zo) * h + nextInt(st, jh + 1);
                    float cell = (float) next(st, 24) * 5.9604645E-8F;
                    double dx = tx - bx, dz = tz - bz;
                    float distance;
                    if (flat) {
                        distance = (float) Math.sqrt(dx * dx + dz * dz);
                    } else {
                        double dy = ty - by;
                        distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    }
                    if (distance < d2) {
                        if (distance < d1) {
                            d2 = d1;
                            d1 = distance;
                            value = cell;
                        } else {
                            d2 = distance;
                        }
                    }
                }
            }
        }
        return switch (ret) {
            case 0 -> (double) value;
            case 1 -> (double) d1;
            case 2 -> (double) d2;
            case 3 -> (double) (d2 + d1) * 0.5;
            case 4 -> (double) (d2 - d1);
            case 5 -> (double) (d2 * d1) * 0.5;
            default -> (double) (d1 / d2);
        };
    }

    private static int next(long[] st, int bits) {
        st[0] = st[0] * MULTIPLIER + 11L & MASK;
        return (int) (st[0] >> 48 - bits);
    }

    /** {@code BitRandomSource.nextInt(bound)}. */
    private static int nextInt(long[] st, int bound) {
        if ((bound & bound - 1) == 0) {
            return (int) ((long) bound * (long) next(st, 31) >> 31);
        }
        int j, k;
        do {
            j = next(st, 31);
            k = j % bound;
        } while (j - k + (bound - 1) < 0);
        return k;
    }

    /**
     * {@link #sample} in OpenCL. Java's int and long arithmetic wraps, so it is done in unsigned types here, where wrapping is
     * defined. A float quotient is the double quotient rounded once, which is the correctly rounded float quotient.
     */
    static final String CL = """
        ulong lc_next(ulong* st, int bits) { *st = (*st * 25214903917UL + 11UL) & 281474976710655UL; return *st >> (48 - bits); }
        int lc_next_int(ulong* st, int bound) {
          if ((bound & (bound - 1)) == 0) return (int) (((long) bound * (long) (int) lc_next(st, 31)) >> 31);
          int j, k;
          do { j = (int) lc_next(st, 31); k = j % bound; } while ((int) ((uint) j - (uint) k + (uint) (bound - 1)) < 0);
          return k;
        }
        int lc_floor_div(int a, int b) { int q = a / b; return (q * b != a && (a < 0) != (b < 0)) ? q - 1 : q; }
        double litho_cellular(__global const int* p, __global const float* fp, __global const float* rv, double x, double y, double z) {
          int bx = (int) x, by = (int) y, bz = (int) z;
          int h = p[0], v = p[1], jh = p[2], jv = p[3], ret = p[4], salt = p[5];
          ulong seed = (ulong) (uint) p[6] | ((ulong) (uint) p[7] << 32);
          int flat = v == 0;
          int gx = lc_floor_div(bx, h), gz = lc_floor_div(bz, h), gy = flat ? 0 : lc_floor_div(by, v);
          float d1 = FLT_MAX, d2 = FLT_MAX, value = FLT_MAX;
          for (int xo = -1; xo <= 1; xo++) {
            for (int yo = flat ? 0 : -1; yo <= (flat ? 0 : 1); yo++) {
              for (int zo = -1; zo <= 1; zo++) {
                ulong s = (ulong) (long) (gx + xo) * 341873128712UL + (ulong) (long) (gz + zo) * 132897987541UL + seed + (ulong) (long) salt;
                if (!flat) s += (ulong) (long) (gy + yo) * 297845856431UL;
                ulong st = (s ^ 25214903917UL) & 281474976710655UL;
                int tx = (int) ((uint) (gx + xo) * (uint) h + (uint) lc_next_int(&st, jh + 1));
                int ty = flat ? 0 : (int) ((uint) (gy + yo) * (uint) v + (uint) lc_next_int(&st, jv + 1));
                int tz = (int) ((uint) (gz + zo) * (uint) h + (uint) lc_next_int(&st, jh + 1));
                float cell = (float) (int) lc_next(&st, 24) * 5.9604645E-8f;
                double dx = (double) (int) ((uint) tx - (uint) bx), dz = (double) (int) ((uint) tz - (uint) bz);
                float distance;
                if (flat) {
                  distance = (float) sqrt(dx * dx + dz * dz);
                } else {
                  double dy = (double) (int) ((uint) ty - (uint) by);
                  distance = (float) sqrt(dx * dx + dy * dy + dz * dz);
                }
                if (distance < d2) {
                  if (distance < d1) { d2 = d1; d1 = distance; value = cell; } else d2 = distance;
                }
              }
            }
          }
          switch (ret) {
            case 0: return (double) value;
            case 1: return (double) d1;
            case 2: return (double) d2;
            case 3: return (double) (d2 + d1) * 0.5;
            case 4: return (double) (d2 - d1);
            case 5: return (double) (d2 * d1) * 0.5;
            default: return (double) (float) ((double) d1 / (double) d2);
          }
        }
        /* the setup's check: litho_cellular at given points */
        __kernel void k_litho_cellular_test(__global const int* oi, __global const float* of, int io, int fo, int to, __global const double* xyz, int n,
                                            __global double* out) {
          int g = get_global_id(0);
          if (g >= n) return;
          out[g] = litho_cellular(oi + io, of + fo, of + to, xyz[g * 3], xyz[g * 3 + 1], xyz[g * 3 + 2]);
        }
        """;
}
