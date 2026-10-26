package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.testutil.FillBench;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import java.util.Arrays;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Times the aquifer's block-by-block search mode on vanilla terrain. Packs whose fluid functions depend on evaluation
 * order run this mode, e.g. with Tectonic. Run with {@code gradlew test --tests *AquiferSearchBench -Dga2.bench=true}.
 * Each round fills a grid of chunks with a fresh RandomState registered as not classify-safe. Rounds alternate with
 * classify mode for reference.
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class AquiferSearchBench {
    @Test
    void searchMode() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var noises = lookup.lookupOrThrow(Registries.NOISE);
        int[][] chunks = FillBench.grid(300, -200, 6);
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        long[] search = new long[rounds];
        long[] classify = new long[rounds];
        for (int r = -3; r < rounds; r++) {
            for (int mode = 0; mode < 2; mode++) {
                RandomState state = RandomState.create(settings, noises, 1234567L + r);
                if (mode == 0) {
                    AquiferFastPath.registerRandomState(state.aquiferRandom(), false);
                }
                long t0 = System.nanoTime();
                for (int[] c : chunks) {
                    NoiseChunk chunk = FillHarness.chunk(state, settings, c[0], c[1], DensityFunctions.BeardifierMarker.INSTANCE, FillHarness.vanillaPicker(settings));
                    FillHarness.fill(chunk, settings, c[0], c[1], false);
                }
                long ns = (System.nanoTime() - t0) / chunks.length;
                if (r >= 0) {
                    (mode == 0 ? search : classify)[r] = ns;
                }
            }
        }
        Arrays.sort(search);
        Arrays.sort(classify);
        System.out.println("AquiferSearchBench overworld, " + chunks.length + " chunks: search mode median " + search[rounds / 2] / 1000 + " us/chunk, classify mode "
            + classify[rounds / 2] / 1000 + " us/chunk");
    }
}
