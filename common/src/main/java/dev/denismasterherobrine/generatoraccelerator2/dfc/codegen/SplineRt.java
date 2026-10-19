package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;

/**
 * Runtime form of a spline: the same float arithmetic as {@code CubicSpline.Multipoint.apply},
 * with each coordinate read from a scratch slot that the generated code fills beforehand.
 *
 * <p>In cell programs, each spline also keeps the last interval it found in a scratch slot of its own. There is one
 * scratch per chunk, so one per thread. Neighbouring blocks nearly always fall in the same interval. Checking the hint
 * takes two comparisons, where the binary search takes several unpredictable ones. The hint is only taken when it
 * equals the binary search's answer, so results do not change.
 */
public final class SplineRt {
    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/SplineRt";

    private final boolean constant;
    private final float value;
    private final int coordinateSlot;
    private final float[] locations;
    private final SplineRt[] values;
    private final float[] derivatives;
    /** Scratch slot holding the last interval as a double, or -1 to always search. */
    private final int hintSlot;

    private SplineRt(float value) {
        this.constant = true;
        this.value = value;
        this.coordinateSlot = -1;
        this.locations = null;
        this.values = null;
        this.derivatives = null;
        this.hintSlot = -1;
    }

    private SplineRt(int coordinateSlot, float[] locations, SplineRt[] values, float[] derivatives, int hintSlot) {
        this.constant = false;
        this.value = 0.0F;
        this.coordinateSlot = coordinateSlot;
        this.locations = locations;
        this.values = values;
        this.derivatives = derivatives;
        this.hintSlot = hintSlot;
    }

    public static SplineRt constant(float value) {
        return new SplineRt(value);
    }

    public static SplineRt multipoint(int coordinateSlot, float[] locations, SplineRt[] values, float[] derivatives) {
        return new SplineRt(coordinateSlot, locations, values, derivatives, -1);
    }

    /** @param hintSlot a scratch slot no one else writes, for the last interval ({@link #hintable} locations only), or -1 */
    public static SplineRt multipoint(int coordinateSlot, float[] locations, SplineRt[] values, float[] derivatives, int hintSlot) {
        if (hintSlot >= 0 && !hintable(locations)) {
            throw new IllegalArgumentException("interval hints need ascending locations");
        }
        return new SplineRt(coordinateSlot, locations, values, derivatives, hintSlot);
    }

    /**
     * True when the locations ascend (equal neighbours allowed, no NaN). Then the binary search answers the one {@code i}
     * with {@code (i == -1 || loc[i] <= f) && (i == n - 1 || f < loc[i + 1])} for any non-NaN {@code f}. The hint check
     * tests exactly this. Vanilla does not reject other orders. Splines with other orders keep the plain search.
     */
    public static boolean hintable(float[] locations) {
        if (locations.length == 0 || locations[0] != locations[0]) {
            return false;
        }
        for (int i = 0; i + 1 < locations.length; i++) {
            if (!(locations[i] <= locations[i + 1])) {
                return false;
            }
        }
        return true;
    }

    public float apply(double[] s) {
        if (constant) {
            return value;
        }
        float f = (float) s[coordinateSlot];
        int i = hintSlot >= 0 ? interval(s, f) : Interpreter.findIntervalStart(locations, f);
        int j = locations.length - 1;
        if (i < 0) {
            return Interpreter.linearExtend(f, locations, values[0].apply(s), derivatives, 0);
        } else if (i == j) {
            return Interpreter.linearExtend(f, locations, values[j].apply(s), derivatives, j);
        } else {
            float f1 = locations[i];
            float f2 = locations[i + 1];
            float f3 = (f - f1) / (f2 - f1);
            float f4 = derivatives[i];
            float f5 = derivatives[i + 1];
            float f6 = values[i].apply(s);
            float f7 = values[i + 1].apply(s);
            float f8 = f4 * (f2 - f1) - (f7 - f6);
            float f9 = -f5 * (f2 - f1) + (f7 - f6);
            return Interpreter.lerp(f3, f6, f7) + f3 * (1.0F - f3) * Interpreter.lerp(f3, f8, f9);
        }
    }

    /**
     * {@link Interpreter#findIntervalStart}, trying the last answer first. A stale or foreign hint only fails the check.
     * A fresh scratch holds 0.0, and NaN reads as 0. NaN coordinates fail every comparison and take the search.
     */
    private int interval(double[] s, float f) {
        float[] loc = locations;
        int last = loc.length - 1;
        int h = (int) s[hintSlot];
        if (h >= -1 && h <= last && (h < 0 ? f < loc[0] : loc[h] <= f) && (h == last || f < loc[h + 1])) {
            return h;
        }
        int i = Interpreter.findIntervalStart(loc, f);
        s[hintSlot] = i;
        return i;
    }
}
