package dev.denismasterherobrine.generatoraccelerator2.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Cost of one sample through a random state's own climate sampler, compiled and vanilla, on one thread and on eight
 * (the stronghold ring searches sample a 57x57 quart square around each ring position). Opt-in: {@code -Dga2.bench=true}.
 */
public class GlobalSamplerBench {
    private static final int SIDE = 57;

    @Test
    void globalSamplerCost() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"), "benchmark, run with -Dga2.bench=true");
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 1234567L);
        Climate.Sampler sampler = state.sampler();
        for (int round = 0; round < 5; round++) {
            long compiled = time(sampler, 1, round, false);
            long vanilla = time(sampler, 1, round, true);
            long compiled8 = time(sampler, 8, round, false);
            long vanilla8 = time(sampler, 8, round, true);
            System.out.printf("round %d: one thread %.0f ns/sample compiled, %.0f vanilla; eight threads %.0f / %.0f ns/sample (wall per sample)%n",
                round, compiled / (double) (SIDE * SIDE * 16), vanilla / (double) (SIDE * SIDE * 16),
                compiled8 / (double) (SIDE * SIDE * 16 * 8), vanilla8 / (double) (SIDE * SIDE * 16 * 8));
        }
    }

    /** Nanoseconds for {@code threads} threads to each take 16 ring-search squares of samples. */
    private static long time(Climate.Sampler sampler, int threads, int round, boolean vanilla) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Double>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int ox = (t * 1000 + round * 10_000) >> 2;
                futures.add(pool.submit(() -> {
                    double[] sink = new double[1];
                    Runnable body = () -> {
                        for (int square = 0; square < 16; square++) {
                            int cx = ox + square * 200;
                            for (int dx = 0; dx < SIDE; dx++) {
                                for (int dz = 0; dz < SIDE; dz++) {
                                    sink[0] += sampler.sample(cx + dx, 0, dz - 28).continentalness();
                                }
                            }
                        }
                    };
                    if (vanilla) {
                        DfcHooks.runVanilla(body);
                    } else {
                        body.run();
                    }
                    return sink[0];
                }));
            }
            for (Future<Double> f : futures) {
                f.get();
            }
            return System.nanoTime() - t0;
        } finally {
            pool.shutdown();
        }
    }
}
