package dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import org.slf4j.Logger;

/**
 * Spelunkery's stone stripes ({@code BlockStripeFeature.place}) ask two FastNoiseLite cellular noises at the same domain-warped point for
 * every target block below the surface. One noise returns the nearest cell's value. The other returns the ratio of the two nearest
 * distances. Both noises have the same seed, frequency, coordinate transform, distance function and jitter. FastNoiseLite's cellular
 * search finds the nearest cell and both distances in the same pass, whatever the noise returns. So the first of the two calls at a point
 * runs that search once, as FastNoiseLite runs it, and keeps both answers. The search uses the same float and double operations in the
 * same order, over Spelunkery's own vector table. The second call at the same point with the same settings is answered from the kept
 * answers. Any other noise, or settings this class does not cover, runs FastNoiseLite's own code. Active only while the methods whose work
 * this class does are the code it was written against ({@link SpelunkeryMixins}).
 */
public final class SpelunkeryCells {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    /** The counts of threads that ended. */
    private static final java.util.concurrent.atomic.LongAdder RETIRED_SEARCHED = new java.util.concurrent.atomic.LongAdder(),
        RETIRED_SHARED = new java.util.concurrent.atomic.LongAdder();

    /** Calls answered by a search made here. Counted per thread, since a shared counter costs more than a call saves. */
    public static long searched() {
        long[] n = { RETIRED_SEARCHED.sum() };
        LAST.forEach(l -> n[0] += l.searched);
        return n[0];
    }

    /** Calls answered from the other call's search. */
    public static long shared() {
        long[] n = { RETIRED_SHARED.sum() };
        LAST.forEach(l -> n[0] += l.shared);
        return n[0];
    }
    /** {@code -Dgeneratoraccelerator2.verify.spelunkery=N}: every N-th answer is compared with FastNoiseLite's own. */
    private static final dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter VERIFY =
        new dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter("spelunkery");

    private static final VarHandle SEED, FREQUENCY, NOISE_TYPE, TRANSFORM, FRACTAL, DISTANCE, RETURN, JITTER;
    private static final float[] VECS;
    private static final Object CELLULAR, NO_FRACTAL, EUCLIDEAN_SQ, CELL_VALUE, DISTANCE2_DIV, XZ, XY, DEFAULT_OS2;
    private static final boolean READY;

    static {
        VarHandle seed = null, frequency = null, noiseType = null, transform = null, fractal = null, distance = null, ret = null, jitter = null;
        float[] vecs = null;
        Object cellular = null, noFractal = null, euclideanSq = null, cellValue = null, d2div = null, xz = null, xy = null, os2 = null;
        boolean ready = false;
        try {
            Class<?> c = Class.forName(SpelunkeryMixins.NOISE, false, SpelunkeryCells.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
            seed = lookup.findVarHandle(c, "mSeed", int.class);
            frequency = lookup.findVarHandle(c, "mFrequency", float.class);
            noiseType = handle(lookup, c, "mNoiseType");
            transform = handle(lookup, c, "mTransformType3D");
            fractal = handle(lookup, c, "mFractalType");
            distance = handle(lookup, c, "mCellularDistanceFunction");
            ret = handle(lookup, c, "mCellularReturnType");
            jitter = lookup.findVarHandle(c, "mCellularJitterModifier", float.class);
            Field f = c.getDeclaredField("RandVecs3D");
            f.setAccessible(true);
            vecs = (float[]) f.get(null);
            cellular = constant(c, "NoiseType", "Cellular");
            noFractal = constant(c, "FractalType", "None");
            euclideanSq = constant(c, "CellularDistanceFunction", "EuclideanSq");
            cellValue = constant(c, "CellularReturnType", "CellValue");
            d2div = constant(c, "CellularReturnType", "Distance2Div");
            xz = constant(c, "TransformType3D", "ImproveXZPlanes");
            xy = constant(c, "TransformType3D", "ImproveXYPlanes");
            os2 = constant(c, "TransformType3D", "DefaultOpenSimplex2");
            ready = vecs != null && vecs.length >= 1024;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Spelunkery FastNoiseLite unreadable -> stripes use own noise", e);
        }
        SEED = seed;
        FREQUENCY = frequency;
        NOISE_TYPE = noiseType;
        TRANSFORM = transform;
        FRACTAL = fractal;
        DISTANCE = distance;
        RETURN = ret;
        JITTER = jitter;
        VECS = vecs;
        CELLULAR = cellular;
        NO_FRACTAL = noFractal;
        EUCLIDEAN_SQ = euclideanSq;
        CELL_VALUE = cellValue;
        DISTANCE2_DIV = d2div;
        XZ = xz;
        XY = xy;
        DEFAULT_OS2 = os2;
        READY = ready;
    }

    private static VarHandle handle(MethodHandles.Lookup lookup, Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        return lookup.unreflectVarHandle(f);
    }

    private static Object constant(Class<?> owner, String enumName, String value) throws ClassNotFoundException {
        for (Class<?> inner : owner.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(enumName) && inner.isEnum()) {
                for (Object o : inner.getEnumConstants()) {
                    if (((Enum<?>) o).name().equals(value)) {
                        return o;
                    }
                }
            }
        }
        throw new ClassNotFoundException(owner.getName() + "." + enumName + "." + value);
    }

    /**
     * Per-thread search state. Holds the last point searched on this thread, with the settings used and both answers. Holds the
     * 27 cells around the last rounded point searched, with each cell's hash and jittered offset. These depend only on the cell,
     * the seed and the jitter. Also holds this thread's counts.
     */
    private static final class Last {
        long x = Long.MIN_VALUE, y, z;
        int seed;
        float frequency, jitter;
        Object transform;
        float cellValue, ratio;

        boolean cells;
        int cellX, cellY, cellZ, cellSeed, cellJitterBits;
        final int[] hash = new int[27];
        final float[] jx = new float[27], jy = new float[27], jz = new float[27];
        final float[] ax = new float[3], ay = new float[3], az = new float[3];

        long searched, shared;
    }

    private static final dev.denismasterherobrine.generatoraccelerator2.memory.ThreadEntries<Last> LAST =
        new dev.denismasterherobrine.generatoraccelerator2.memory.ThreadEntries<>(Last::new, l -> {
            RETIRED_SEARCHED.add(l.searched);
            RETIRED_SHARED.add(l.shared);
        });

    private SpelunkeryCells() {}

    /** {@code noise.GetNoise(x, y, z)} ({@code original} is FastNoiseLite's own call). */
    public static float getNoise(Object noise, double x, double y, double z, Operation<Float> original) {
        if (!READY || NOISE_TYPE.get(noise) != CELLULAR || FRACTAL.get(noise) != NO_FRACTAL || DISTANCE.get(noise) != EUCLIDEAN_SQ) {
            return original.call(noise, x, y, z);
        }
        Object ret = RETURN.get(noise);
        boolean wantsValue = ret == CELL_VALUE;
        if (!wantsValue && ret != DISTANCE2_DIV) {
            return original.call(noise, x, y, z);
        }
        int seed = (int) SEED.get(noise);
        float frequency = (float) FREQUENCY.get(noise);
        float jitter = (float) JITTER.get(noise);
        Object transform = TRANSFORM.get(noise);
        long bx = Double.doubleToRawLongBits(x);
        long by = Double.doubleToRawLongBits(y);
        long bz = Double.doubleToRawLongBits(z);
        Last last = LAST.get();
        float answer;
        if (last.x == bx && last.y == by && last.z == bz && last.seed == seed && Float.floatToRawIntBits(last.frequency) == Float.floatToRawIntBits(frequency)
            && Float.floatToRawIntBits(last.jitter) == Float.floatToRawIntBits(jitter) && last.transform == transform) {
            last.shared++;
            answer = wantsValue ? last.cellValue : last.ratio;
        } else {
            search(last, seed, frequency, jitter, transform, x, y, z);
            last.x = bx;
            last.y = by;
            last.z = bz;
            last.seed = seed;
            last.frequency = frequency;
            last.jitter = jitter;
            last.transform = transform;
            last.searched++;
            answer = wantsValue ? last.cellValue : last.ratio;
        }
        if (VERIFY.sample()) {
            float own = original.call(noise, x, y, z);
            VERIFY.result(Float.floatToRawIntBits(own) == Float.floatToRawIntBits(answer), "(" + x + ", " + y + ", " + z + ") " + ret, own, answer);
        }
        return answer;
    }

    /** {@code GetNoise}'s coordinate scaling and transform, then {@code SingleCellular}'s Euclidean-squared search, keeping both answers. */
    private static void search(Last out, int seed, float frequency, float jitterModifier, Object transform, double x, double y, double z) {
        x *= (double) frequency;
        y *= (double) frequency;
        z *= (double) frequency;
        if (transform == XY) {
            double xy = x + y;
            double s2 = xy * -0.211324865405187;
            z *= 0.577350269189626;
            x += s2 - z;
            y = y + s2 - z;
            z += xy * 0.577350269189626;
        } else if (transform == XZ) {
            double xz = x + z;
            double s2 = xz * -0.211324865405187;
            y *= 0.577350269189626;
            x += s2 - y;
            z += s2 - y;
            y += xz * 0.577350269189626;
        } else if (transform == DEFAULT_OS2) {
            double r = (x + y + z) * 0.6666666666666666;
            x = r - x;
            y = r - y;
            z = r - z;
        }
        int xr = x >= 0.0 ? (int) (x + 0.5) : (int) (x - 0.5);
        int yr = y >= 0.0 ? (int) (y + 0.5) : (int) (y - 0.5);
        int zr = z >= 0.0 ? (int) (z + 0.5) : (int) (z - 0.5);
        float distance0 = Float.MAX_VALUE;
        float distance1 = Float.MAX_VALUE;
        int closestHash = 0;
        float cellularJitter = 0.39614353F * jitterModifier;
        if (!out.cells || out.cellX != xr || out.cellY != yr || out.cellZ != zr || out.cellSeed != seed
            || out.cellJitterBits != Float.floatToRawIntBits(cellularJitter)) {
            cells(out, seed, cellularJitter, xr, yr, zr);
        }
        // (float) ((double) xi - x) for the three cells along each axis. A cell's vector is that value plus the cell's jittered offset.
        float[] ax = out.ax, ay = out.ay, az = out.az;
        for (int k = 0; k < 3; k++) {
            ax[k] = (float) ((double) (xr - 1 + k) - x);
            ay[k] = (float) ((double) (yr - 1 + k) - y);
            az[k] = (float) ((double) (zr - 1 + k) - z);
        }
        int[] hash = out.hash;
        float[] jx = out.jx, jy = out.jy, jz = out.jz;
        int c = 0;
        for (int a = 0; a < 3; a++) {
            float fx = ax[a];
            for (int b = 0; b < 3; b++) {
                float fy = ay[b];
                for (int d = 0; d < 3; d++, c++) {
                    float vecX = fx + jx[c];
                    float vecY = fy + jy[c];
                    float vecZ = az[d] + jz[c];
                    float newDistance = vecX * vecX + vecY * vecY + vecZ * vecZ;
                    float min = distance1 < newDistance ? distance1 : newDistance;
                    distance1 = min > distance0 ? min : distance0;
                    if (newDistance < distance0) {
                        distance0 = newDistance;
                        closestHash = hash[c];
                    }
                }
            }
        }
        out.cellValue = (float) closestHash * 4.656613E-10F;
        out.ratio = distance0 / distance1 - 1.0F;
    }

    /**
     * The 27 cells around {@code (xr, yr, zr)} in the search's order: x, then y, then z. Computes each cell's hash and its random
     * vector times the jitter, as {@code SingleCellular} computes them.
     */
    private static void cells(Last out, int seed, float cellularJitter, int xr, int yr, int zr) {
        float[] vecs = VECS;
        int xPrimed = (xr - 1) * 501125321;
        int yPrimedBase = (yr - 1) * 1136930381;
        int zPrimedBase = (zr - 1) * 1720413743;
        int c = 0;
        for (int xi = 0; xi < 3; xi++) {
            int yPrimed = yPrimedBase;
            for (int yi = 0; yi < 3; yi++) {
                int zPrimed = zPrimedBase;
                for (int zi = 0; zi < 3; zi++, c++) {
                    int hash = (seed ^ xPrimed ^ yPrimed ^ zPrimed) * 668265261;
                    int idx = hash & 1020;
                    out.hash[c] = hash;
                    out.jx[c] = vecs[idx] * cellularJitter;
                    out.jy[c] = vecs[idx | 1] * cellularJitter;
                    out.jz[c] = vecs[idx | 2] * cellularJitter;
                    zPrimed += 1720413743;
                }
                yPrimed += 1136930381;
            }
            xPrimed += 501125321;
        }
        out.cells = true;
        out.cellX = xr;
        out.cellY = yr;
        out.cellZ = zr;
        out.cellSeed = seed;
        out.cellJitterBits = Float.floatToRawIntBits(cellularJitter);
    }
}
