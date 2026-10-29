package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/**
 * A RandomState's own climate sampler (the one the stronghold ring search, structure biome checks and the spawn search
 * use) must give the same target points through the compiled program as through vanilla's unwrapped density functions,
 * for every preset, also while many threads sample it at once.
 */
public class GlobalSamplerTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES,
        NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.CAVES, NoiseGeneratorSettings.FLOATING_ISLANDS);
    private static final int POINTS = 4000;
    private static final int THREADS = 8;

    @Test
    void globalSamplerMatchesVanilla() throws Exception {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long compared = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 20261005L);
            Climate.Sampler sampler = state.sampler();
            BoundSampler bound = BoundSampler.lookup(sampler);
            assertNotNull(bound, "global sampler not bound on " + preset.identifier());
            assertNotNull(bound.sharedSamples, "global sampler bound as a chunk sampler on " + preset.identifier());
            long before = bound.sharedSamples.sum();
            compared += compare(sampler, preset.identifier().toString(), new Random(preset.identifier().hashCode()), POINTS);

            // the stronghold ring search samples from up to a hundred pool tasks at once
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<Long>> futures = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    long seed = preset.identifier().hashCode() * 31L + t;
                    futures.add(pool.submit(() -> compare(sampler, preset.identifier() + " (threads)", new Random(seed), POINTS)));
                }
                for (Future<Long> f : futures) {
                    compared += f.get();
                }
            } finally {
                pool.shutdown();
            }
            assertTrue(bound.sharedSamples.sum() - before >= (long) POINTS * (THREADS + 1), "compiled global sampler was not used on " + preset.identifier());
        }
        System.out.println("GlobalSamplerTest: compared " + compared + " target points");
    }

    private static long compare(Climate.Sampler sampler, String where, Random rng, int points) {
        for (int i = 0; i < points; i++) {
            int qx, qz;
            switch (rng.nextInt(4)) {
                case 0 -> { qx = rng.nextInt(15_000_000) - 7_500_000; qz = rng.nextInt(15_000_000) - 7_500_000; }
                // findBiomeHorizontal around a stronghold ring position, and the spawn search's rings
                case 1 -> { qx = rng.nextInt(2000) - 1000 + (rng.nextInt(57) - 28); qz = rng.nextInt(2000) - 1000 + (rng.nextInt(57) - 28); }
                default -> { qx = rng.nextInt(1024) - 512; qz = rng.nextInt(1024) - 512; }
            }
            int qy = rng.nextInt(3) == 0 ? 0 : rng.nextInt(100) - 18;
            final int fx = qx, fy = qy, fz = qz;
            Climate.TargetPoint[] vanilla = new Climate.TargetPoint[1];
            DfcHooks.runVanilla(() -> vanilla[0] = sampler.sample(fx, fy, fz));
            Climate.TargetPoint compiled = sampler.sample(fx, fy, fz);
            String at = where + " quart " + qx + "," + qy + "," + qz;
            assertEquals(vanilla[0].temperature(), compiled.temperature(), at + " temperature");
            assertEquals(vanilla[0].humidity(), compiled.humidity(), at + " humidity");
            assertEquals(vanilla[0].continentalness(), compiled.continentalness(), at + " continentalness");
            assertEquals(vanilla[0].erosion(), compiled.erosion(), at + " erosion");
            assertEquals(vanilla[0].depth(), compiled.depth(), at + " depth");
            assertEquals(vanilla[0].weirdness(), compiled.weirdness(), at + " weirdness");
        }
        return points;
    }
}
