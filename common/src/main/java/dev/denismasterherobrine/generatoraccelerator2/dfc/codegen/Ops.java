package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.Arrays;

/**
 * Tiny static helpers the generated code calls. Each helper is the exact vanilla operation sequence
 * (shared with {@link Interpreter}). HotSpot inlines them, so they cost nothing over inline bytecode.
 * They keep the emitter small and obviously correct.
 */
public final class Ops {
    private Ops() {}

    public static final String INTERNAL = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/Ops";

    public static void reset(double[] s, int count) {
        Arrays.fill(s, 0, count, Double.NaN);
    }

    public static void resetRange(double[] s, int from, int to) {
        Arrays.fill(s, from, to, Double.NaN);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static double clampedMap(double v, double a, double b, double from, double to) {
        return Interpreter.clampedMap(v, a, b, from, to);
    }

    public static double abs(double v) {
        return Math.abs(v);
    }

    public static double square(double v) {
        return v * v;
    }

    public static double cube(double v) {
        return v * v * v;
    }

    public static double halfNegative(double v) {
        return v > 0.0 ? v : v * 0.5;
    }

    public static double quarterNegative(double v) {
        return v > 0.0 ? v : v * 0.25;
    }

    public static double invert(double v) {
        return 1.0 / v;
    }

    public static double squeeze(double v) {
        double d0 = clamp(v, -1.0, 1.0);
        return d0 / 2.0 - d0 * d0 * d0 / 24.0;
    }

    /** {@code Mth.lerp3} with vanilla's argument order (x, y, z, v000, v100, v010, v110, v001, v101, v011, v111). */
    public static double lerp3(double x, double y, double z, double v000, double v100, double v010, double v110,
                               double v001, double v101, double v011, double v111) {
        return lerp(z, lerp2(x, y, v000, v100, v010, v110), lerp2(x, y, v001, v101, v011, v111));
    }

    /**
     * {@link #lerp3} of corners {@code c..c+7}. The two z-independent halves (the two {@link #lerp2} calls) are kept
     * for the current cell row in {@code row[slot]} and {@code row[slot + 1]}. NaN means not yet computed in this row.
     * A cell row shares (x, y) across its z positions. The operations match {@code lerp3}, so the double is the same.
     */
    public static double cellLerp(double[] row, int slot, double[] corners, int c, double x, double y, double z) {
        double a = row[slot];
        double b;
        if (a != a) {
            a = lerp2(x, y, corners[c], corners[c + 1], corners[c + 2], corners[c + 3]);
            b = lerp2(x, y, corners[c + 4], corners[c + 5], corners[c + 6], corners[c + 7]);
            row[slot] = a;
            row[slot + 1] = b;
        } else {
            b = row[slot + 1];
        }
        return lerp(z, a, b);
    }

    public static double lerp2(double x, double y, double a, double b, double c, double d) {
        return lerp(y, lerp(x, a, b), lerp(x, c, d));
    }

    public static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public static double rarity3D(double v) {
        return Interpreter.rarity3D(v);
    }

    public static double rarity2D(double v) {
        return Interpreter.rarity2D(v);
    }
}
