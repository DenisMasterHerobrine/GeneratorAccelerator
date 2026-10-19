package dev.denismasterherobrine.generatoraccelerator2.noise;

import java.util.Random;
import java.util.stream.IntStream;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.slf4j.Logger;

/**
 * Compares the mixin-replaced noise classes against {@link ReferenceNoise} bit for bit.
 * Enabled with {@code -Dgeneratoraccelerator2.selfcheck=true}. Logs a summary and any mismatch.
 */
public final class SelfCheck {
    private SelfCheck() {}

    private static long comparisons;
    private static long mismatches;

    public static boolean run(Logger log) {
        comparisons = 0;
        mismatches = 0;
        long t0 = System.nanoTime();
        Random rng = new Random(0x5EEDL);
        for (int round = 0; round < 8; round++) {
            long seed = rng.nextLong();
            XoroshiroRandomSource source = new XoroshiroRandomSource(seed);
            PositionalRandomFactory factory = source.forkPositional();

            // Raw ImprovedNoise, both entry points, nearby and far coordinates.
            ImprovedNoise improved = new ImprovedNoise(factory.fromHashOf("ga2:improved" + round));
            for (int i = 0; i < 20000; i++) {
                double x = coord(rng), y = coord(rng), z = coord(rng);
                compare(log, "ImprovedNoise.noise", improved.noise(x, y, z), ReferenceNoise.improved(improved, x, y, z, 0.0, 0.0), x, y, z);
                double yScale = rng.nextInt(4) == 0 ? 0.0 : rng.nextDouble() * 50.0;
                double yMax = rng.nextDouble() * 4.0 - 1.0;
                compare(log, "ImprovedNoise.noise(yScale)", improved.noise(x, y, z, yScale, yMax), ReferenceNoise.improved(improved, x, y, z, yScale, yMax), x, y, z);
            }

            // NormalNoise with random octave layouts including zero amplitudes.
            int octaves = 1 + rng.nextInt(9);
            double[] amplitudes = new double[octaves];
            for (int i = 0; i < octaves; i++) amplitudes[i] = rng.nextInt(5) == 0 ? 0.0 : 0.5 + rng.nextInt(4) * 0.5;
            amplitudes[rng.nextInt(octaves)] = 1.0;
            int firstOctave = -12 + rng.nextInt(12);
            NormalNoise normal = NormalNoise.create(factory.fromHashOf("ga2:normal" + round), firstOctave, amplitudes);
            for (int i = 0; i < 20000; i++) {
                double x = coord(rng), y = coord(rng), z = coord(rng);
                compare(log, "NormalNoise.getValue", normal.getValue(x, y, z), ReferenceNoise.normal(normal, x, y, z), x, y, z);
            }

            // Legacy PerlinNoise with the 6-argument getValue, both origin modes.
            PerlinNoise legacy = PerlinNoise.createLegacyForBlendedNoise(new XoroshiroRandomSource(seed ^ 0x9E3779B97F4A7C15L), IntStream.rangeClosed(-7, 0));
            for (int i = 0; i < 10000; i++) {
                double x = coord(rng), y = coord(rng), z = coord(rng);
                double yScale = rng.nextDouble() * 20.0, yMax = rng.nextDouble() * 400.0 - 100.0;
                boolean origin = rng.nextBoolean();
                compare(log, "PerlinNoise.getValue(6)", legacy.getValue(x, y, z, yScale, yMax, origin), ReferenceNoise.perlin(legacy, x, y, z, yScale, yMax, origin), x, y, z);
                compare(log, "PerlinNoise.getValue(3)", legacy.getValue(x, y, z), ReferenceNoise.perlin(legacy, x, y, z, 0.0, 0.0, false), x, y, z);
            }

            // BlendedNoise with the overworld parameters at cell-corner positions, near and far.
            BlendedNoise blended = new BlendedNoise(new XoroshiroRandomSource(seed + 7), 0.25, 0.125, 80.0, 160.0, 8.0);
            for (int i = 0; i < 20000; i++) {
                int bx, bz;
                if (rng.nextInt(4) == 0) {
                    bx = rng.nextInt(60_000_000) - 30_000_000;
                    bz = rng.nextInt(60_000_000) - 30_000_000;
                } else {
                    bx = (rng.nextInt(20000) - 10000) * 4;
                    bz = (rng.nextInt(20000) - 10000) * 4;
                }
                int by = -64 + rng.nextInt(49) * 8;
                compare(log, "BlendedNoise.compute", blended.compute(new DensityFunction.SinglePointContext(bx, by, bz)), ReferenceNoise.blended(blended, bx, by, bz), bx, by, bz);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (mismatches == 0) {
            log.info("passed: {} comparisons bit-identical to vanilla ({} ms)", comparisons, ms);
        } else {
            log.error("FAILED: {}/{} comparisons differ from vanilla", mismatches, comparisons);
        }
        return mismatches == 0;
    }

    private static double coord(Random rng) {
        switch (rng.nextInt(6)) {
            case 0: return rng.nextDouble() * 6.0E7 - 3.0E7;                 // beyond the wrap fast path
            case 1: return (double) (rng.nextInt(2000) - 1000);               // exact integers (zero fractions)
            default: return (rng.nextDouble() * 2.0 - 1.0) * 200000.0;        // typical scaled block coordinates
        }
    }

    private static void compare(Logger log, String what, double actual, double expected, double x, double y, double z) {
        comparisons++;
        if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
            mismatches++;
            if (mismatches <= 10) {
                log.error("mismatch in {} at ({}, {}, {}): fused={} vanilla={}", what, x, y, z, actual, expected);
            }
        }
    }
}
