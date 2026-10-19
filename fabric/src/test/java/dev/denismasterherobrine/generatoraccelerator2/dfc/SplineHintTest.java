package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.SplineRt;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A spline with an interval hint gives the same float as one without. Checked for coordinates that walk slowly (the
 * hint mostly hits), jump (the hint misses), sit on the locations, repeat locations, or are NaN, infinite or a signed
 * zero. Also checked with a scratch whose hint slot holds anything.
 */
public class SplineHintTest {
    private static final int COORD = 0;
    private static final int NESTED_COORD = 1;
    private static final int HINT = 2;
    private static final int NESTED_HINTS = 3;

    @Test
    void hintedSplinesMatchTheSearch() {
        Random random = new Random(20261003L);
        long compared = 0;
        for (int shape = 0; shape < 300; shape++) {
            float[] locations = locations(random, 1 + random.nextInt(8));
            int nestedPoints = 1 + random.nextInt(5);
            float[] nestedLocations = locations(random, nestedPoints);
            SplineRt[] plainValues = new SplineRt[locations.length];
            SplineRt[] hintedValues = new SplineRt[locations.length];
            for (int i = 0; i < locations.length; i++) {
                if (random.nextInt(3) == 0) {
                    SplineRt[] leaves = constants(random, nestedPoints);
                    float[] derivatives = derivatives(random, nestedPoints);
                    plainValues[i] = SplineRt.multipoint(NESTED_COORD, nestedLocations, leaves, derivatives);
                    hintedValues[i] = SplineRt.multipoint(NESTED_COORD, nestedLocations, leaves, derivatives, NESTED_HINTS + i);
                } else {
                    plainValues[i] = hintedValues[i] = SplineRt.constant(random.nextFloat() * 4 - 2);
                }
            }
            float[] derivatives = derivatives(random, locations.length);
            SplineRt plain = SplineRt.multipoint(COORD, locations, plainValues, derivatives);
            SplineRt hinted = SplineRt.multipoint(COORD, locations, hintedValues, derivatives, HINT);
            double[] scratch = new double[NESTED_HINTS + locations.length];
            // whatever the hint slots hold at first
            for (int s = HINT; s < scratch.length; s++) {
                scratch[s] = switch (random.nextInt(4)) {
                    case 0 -> 0.0;
                    case 1 -> Double.NaN;
                    case 2 -> random.nextInt(20) - 10;
                    default -> random.nextDouble() * 1e9;
                };
            }
            double c = random.nextGaussian() * 2;
            double n = random.nextGaussian();
            for (int step = 0; step < 400; step++) {
                switch (random.nextInt(12)) {
                    case 0 -> c = random.nextGaussian() * 3;                                      // jump
                    case 1 -> c = locations[random.nextInt(locations.length)];                     // exactly on a location
                    case 2 -> c = new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0 }[random.nextInt(5)];
                    default -> c = (Double.isFinite(c) ? c : 0.0) + random.nextGaussian() * 0.05;  // slow walk
                }
                n = random.nextInt(10) == 0 ? nestedLocations[random.nextInt(nestedPoints)] : n + random.nextGaussian() * 0.05;
                scratch[COORD] = c;
                scratch[NESTED_COORD] = n;
                float expected = plain.apply(scratch);
                float actual = hinted.apply(scratch);
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
                    "shape " + shape + " step " + step + " coordinate " + c + " nested " + n + " locations " + Arrays.toString(locations));
                compared++;
            }
        }
        System.out.println("[SplineHintTest] " + compared + " hinted evaluations identical to the binary search");
    }

    @Test
    void onlyAscendingLocationsTakeHints() {
        assertTrue(SplineRt.hintable(new float[] { -1, 0, 0, 2 }));
        assertTrue(SplineRt.hintable(new float[] { 3 }));
        assertFalse(SplineRt.hintable(new float[] {}));
        assertFalse(SplineRt.hintable(new float[] { 0, 2, 1 }));
        assertFalse(SplineRt.hintable(new float[] { 0, Float.NaN, 1 }));
        assertFalse(SplineRt.hintable(new float[] { Float.NaN }));
    }

    /** Ascending, with repeats now and then (Tectonic's splines repeat locations). */
    private static float[] locations(Random random, int n) {
        float[] l = new float[n];
        float v = (float) (random.nextGaussian() * 2);
        for (int i = 0; i < n; i++) {
            l[i] = v;
            if (random.nextInt(4) != 0) {
                v += 0.01F + random.nextFloat();
            }
        }
        return l;
    }

    private static float[] derivatives(Random random, int n) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            d[i] = random.nextInt(3) == 0 ? 0.0F : random.nextFloat() * 2 - 1;
        }
        return d;
    }

    private static SplineRt[] constants(Random random, int n) {
        SplineRt[] v = new SplineRt[n];
        for (int i = 0; i < n; i++) {
            v[i] = SplineRt.constant(random.nextFloat() * 2 - 1);
        }
        return v;
    }
}
