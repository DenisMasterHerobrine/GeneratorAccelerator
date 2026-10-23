package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.NoiseBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Random;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;

/**
 * The bounds of a noise over a cell's inputs ({@link NoiseBounds}) hold for every block of the cell, as the kernel computes it,
 * for random noises (octaves, amplitudes, some negative or zero), random cells far from and near the origin, every scale a
 * datapack uses, and shifted inputs anywhere in the shift's interval. They are also tighter than the noise's own maximum for
 * most cells, which is what lets a cell go whole.
 */
public class NoiseBoundsTest {
    private static final double[] SCALES = { 0.0, 0.015625, 0.1, 0.25, 0.5, 1.0, 2.0, 4.0, -0.5 };

    @Test
    void boundsHoldOnRandomCells() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1L); // boots the game (registries, mixins)
        Random r = new Random(20261023L);
        long points = 0;
        int boxes = 0;
        int tighter = 0;
        int farOut = 0;
        for (int n = 0; n < 60; n++) {
            int first = -8 + r.nextInt(8);
            int count = 1 + r.nextInt(6);
            double[] amplitudes = new double[count];
            for (int i = 0; i < count; i++) {
                amplitudes[i] = r.nextInt(5) == 0 ? 0.0 : (r.nextInt(8) == 0 ? -1.0 : 1.0) * (0.25 + r.nextDouble() * 1.75);
            }
            NormalNoise noise = NormalNoise.create(new XoroshiroRandomSource(r.nextLong()), first, amplitudes);
            NoiseSpec spec = NoiseSpec.of(noise);
            double max = Math.abs(spec.maxValue);
            NoiseBounds bounds = new NoiseBounds();
            for (int b = 0; b < 40; b++) {
                int x0 = (r.nextInt(4) == 0 ? r.nextInt(64) - 32 : r.nextInt(1 << 21) - (1 << 20)) & ~3;
                int z0 = (r.nextInt(4) == 0 ? r.nextInt(64) - 32 : r.nextInt(1 << 21) - (1 << 20)) & ~3;
                int y0 = -64 + 8 * r.nextInt(48);
                int w = r.nextInt(3) == 0 ? 8 : 4;
                int h = r.nextInt(3) == 0 ? 4 : 8;
                int x1 = x0 + w - 1, y1 = y0 + h - 1, z1 = z0 + w - 1;
                double xs = SCALES[r.nextInt(SCALES.length)];
                double ys = SCALES[r.nextInt(SCALES.length)];
                // a shift interval on each axis, as a shifted noise's shift nodes are bounded, or none
                double[] sx = shift(r), sy = shift(r), sz = shift(r);
                double xl = Math.min((double) x0 * xs, (double) x1 * xs) + sx[0];
                double xh = Math.max((double) x0 * xs, (double) x1 * xs) + sx[1];
                double yl = Math.min((double) y0 * ys, (double) y1 * ys) + sy[0];
                double yh = Math.max((double) y0 * ys, (double) y1 * ys) + sy[1];
                double zl = Math.min((double) z0 * xs, (double) z1 * xs) + sz[0];
                double zh = Math.max((double) z0 * xs, (double) z1 * xs) + sz[1];
                if (!bounds.normal(spec, xl, xh, yl, yh, zl, zh)) {
                    // past the wrap limit on a fine octave (far lands): nothing is bounded there, by design
                    farOut++;
                    continue;
                }
                double lo = bounds.lo;
                double hi = bounds.hi;
                String where = "noise " + n + " box " + b + " at " + x0 + "," + y0 + "," + z0 + " scales " + xs + "/" + ys;
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int k = 0; k < 3; k++) {
                                double shx = at(sx, k, r), shy = at(sy, k, r), shz = at(sz, k, r);
                                double v = spec.getValue((double) x * xs + shx, (double) y * ys + shy, (double) z * xs + shz);
                                assertTrue(v >= lo && v <= hi, where + ": value " + v + " outside [" + lo + ", " + hi + "] at " + x + "," + y + "," + z);
                                points++;
                            }
                        }
                    }
                }
                boxes++;
                if (hi - lo < max) {
                    tighter++;
                }
            }
        }
        assertTrue(boxes > farOut * 4, "cells bounded: " + boxes + ", past the wrap limit: " + farOut);
        assertTrue(tighter * 2 > boxes, "bounds tighter than half the noise's own range on " + tighter + " of " + boxes + " cells");
        System.out.println("NoiseBoundsTest: " + points + " values inside their cell's bounds; " + tighter + " of " + boxes + " cells bounded tighter than the noise's range, "
            + farOut + " cells past the wrap limit");
    }

    /** A shift interval: none, a point, or a range up to a shift noise's reach. */
    private static double[] shift(Random r) {
        switch (r.nextInt(3)) {
            case 0 -> {
                return new double[] { 0.0, 0.0 };
            }
            case 1 -> {
                double s = (r.nextDouble() - 0.5) * 17.6;
                return new double[] { s, s };
            }
            default -> {
                double a = (r.nextDouble() - 0.5) * 17.6;
                double b = a + r.nextDouble() * (r.nextInt(2) == 0 ? 0.3 : 8.0);
                return new double[] { a, b };
            }
        }
    }

    /** The interval's ends, then points inside it. */
    private static double at(double[] s, int k, Random r) {
        return k == 0 ? s[0] : k == 1 ? s[1] : s[0] + r.nextDouble() * (s[1] - s[0]);
    }
}
