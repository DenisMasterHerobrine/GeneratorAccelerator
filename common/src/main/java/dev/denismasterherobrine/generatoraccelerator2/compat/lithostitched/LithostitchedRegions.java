package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import java.lang.ref.WeakReference;

/**
 * Lithostitched's biome injector regions. For every noise biome whose biome has regions, the dimension's region function
 * is computed at the quart's block position ({@code RegionManager$BiomeRegions.getRegionValue}). The overworld's region
 * function is {@code 0.5 * (1 + fast_noise(cellular, y_scale 0))} with two-dimensional shifts. It does not read y, yet a
 * chunk's biome fill asked it at all 96 heights of each of the chunk's 16 quart columns. When a function provably does
 * not read y, its value is remembered per thread for the last 16 columns. Every other height of a column takes the value
 * from there.
 *
 * <p>A provably y-free function is built only from constants, arithmetic on such functions, markers, registry holders,
 * noises with {@code y_scale} 0, shift noises, splines over such functions, and Lithostitched's {@code fast_noise} with
 * {@code y_scale} 0 and a constant y shift other than {@code -0.0}. Outside a NoiseChunk, markers compute their argument.
 * A noise with {@code y_scale} 0 gets the y coordinate as {@code ±0.0}. Every Perlin octave adds its y offset, a
 * non-negative number, before anything else, so both zeros give the same bits. GA2's kernel does the same. For such a
 * {@code fast_noise}, the y input is the same number for every height. Anything else is asked every time.
 */
public final class LithostitchedRegions {
    private LithostitchedRegions() {}

    static final String FAST_NOISE = LithostitchedDensity.FAST_NOISE;
    private static final long NEGATIVE_ZERO = Double.doubleToRawLongBits(-0.0);

    public static final LongAdder COMPUTED = new LongAdder();
    public static final LongAdder REMEMBERED = new LongAdder();

    /** {@code -Dgeneratoraccelerator2.lithostitched.regionMemo=false} asks the function every time. */
    private static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lithostitched.regionMemo"));

    private static final Map<DensityFunction, Boolean> COLUMN_ONLY = new ConcurrentHashMap<>();

    /** Called when a server stops. Its region functions are dropped as keys, which releases its noises and registries too. */
    public static void forgetWorlds() {
        COLUMN_ONLY.clear();
    }

    /**
     * The last 16 quart columns of one function, per thread. The function is held weakly. It leads to its world's registries, and a
     * worker thread outlives the world.
     */
    private static final class Memo {
        WeakReference<DensityFunction> function = new WeakReference<>(null);
        final int[] x = new int[16];
        final int[] z = new int[16];
        final double[] value = new double[16];
        final boolean[] set = new boolean[16];
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    /** {@code function.compute(context)} for the region lookup. */
    public static double value(DensityFunction function, DensityFunction.FunctionContext context, Operation<Double> original) {
        if (!ENABLED || !columnOnly(function)) {
            return original.call(function, context);
        }
        Memo memo = MEMO.get();
        int x = context.blockX();
        int z = context.blockZ();
        int slot = ((x >> 2) & 3) << 2 | ((z >> 2) & 3);
        if (memo.function.get() != function) {
            memo.function = new WeakReference<>(function);
            Arrays.fill(memo.set, false);
        } else if (memo.set[slot] && memo.x[slot] == x && memo.z[slot] == z) {
            REMEMBERED.increment();
            return memo.value[slot];
        }
        double v = original.call(function, context);
        COMPUTED.increment();
        memo.x[slot] = x;
        memo.z[slot] = z;
        memo.value[slot] = v;
        memo.set[slot] = true;
        return v;
    }

    /** True when {@code function} reads only x and z (see the class comment). */
    public static boolean columnOnly(DensityFunction function) {
        Boolean known = COLUMN_ONLY.get(function);
        if (known == null) {
            known = yFree(function, 0);
            COLUMN_ONLY.put(function, known);
        }
        return known;
    }

    private static boolean yFree(DensityFunction f, int depth) {
        if (depth > 256) {
            return false;
        }
        int d = depth + 1;
        return switch (f) {
            case DensityFunctions.Constant c -> true;
            case DensityFunctions.Ap2 a -> yFree(a.argument1(), d) && yFree(a.argument2(), d);
            case DensityFunctions.MulOrAdd m -> yFree(m.input(), d);
            case DensityFunctions.Mapped m -> yFree(m.input(), d);
            case DensityFunctions.Clamp c -> yFree(c.input(), d);
            case DensityFunctions.RangeChoice r -> yFree(r.input(), d) && yFree(r.whenInRange(), d) && yFree(r.whenOutOfRange(), d);
            case DensityFunctions.Marker m -> yFree(m.wrapped(), d);
            case DensityFunctions.HolderHolder h -> h.function().isBound() && yFree(h.function().value(), d);
            case DensityFunctions.Noise n -> n.yScale() == 0.0;
            case DensityFunctions.ShiftA s -> true;
            case DensityFunctions.ShiftB s -> true;
            case DensityFunctions.ShiftedNoise s -> s.yScale() == 0.0 && yFree(s.shiftX(), d) && yFree(s.shiftY(), d) && yFree(s.shiftZ(), d);
            case DensityFunctions.Spline s -> splineYFree(s.spline(), d);
            default -> fastNoiseYFree(f, d);
        };
    }

    private static boolean splineYFree(CubicSpline<?, ?> spline, int depth) {
        if (depth > 256) {
            return false;
        }
        if (spline instanceof CubicSpline.Constant<?, ?>) {
            return true;
        }
        if (!(spline instanceof CubicSpline.Multipoint<?, ?> m) || !(m.coordinate() instanceof DensityFunctions.Spline.Coordinate coordinate)) {
            return false;
        }
        Holder<DensityFunction> function = coordinate.function();
        if (!function.isBound() || !yFree(function.value(), depth + 1)) {
            return false;
        }
        for (CubicSpline<?, ?> value : m.values()) {
            if (!splineYFree(value, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /** Lithostitched's {@code fast_noise}. Its y input {@code blockY * yScale + shiftY} is the same for every height here. */
    private static boolean fastNoiseYFree(DensityFunction f, int depth) {
        Object[] c = Reflect.components(f, FAST_NOISE, "config", "xzScale", "yScale", "shiftX", "shiftY", "shiftZ");
        if (c == null || !(c[2] instanceof Double yScale) || yScale != 0.0 || !(c[3] instanceof DensityFunction shiftX)
            || !(c[4] instanceof DensityFunctions.Constant shiftY) || !(c[5] instanceof DensityFunction shiftZ)) {
            return false;
        }
        return Double.doubleToRawLongBits(shiftY.value()) != NEGATIVE_ZERO && yFree(shiftX, depth) && yFree(shiftZ, depth);
    }
}
