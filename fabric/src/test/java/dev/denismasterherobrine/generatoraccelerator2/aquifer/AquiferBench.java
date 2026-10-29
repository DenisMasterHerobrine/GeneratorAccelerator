package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.testutil.FillBench;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** {@code gradlew test --tests *AquiferBench -Dga2.bench=true} (add {@code -Dgeneratoraccelerator2.aquifer.stats=true} for path counts). */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class AquiferBench {
    @Test
    void noiseFill() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        for (var preset : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED)) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            int[][] chunks = FillBench.grid(200, 200, 8);
            Map<FillBench.Setup, Long> result = FillBench.run(settings, lookup.lookupOrThrow(Registries.NOISE), 1234567L, chunks,
                i -> DensityFunctions.BeardifierMarker.INSTANCE, FillBench.setups(), 2, 5);
            System.out.println("AquiferBench " + preset.identifier() + ", " + chunks.length + " chunks, no structures:\n" + FillBench.format(result));

            if (AquiferFastPath.STATS) {
                AquiferFastPath.resetStats();
                RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 1234567L);
                for (int[] c : chunks) {
                    NoiseChunk chunk = FillHarness.chunk(state, settings, c[0], c[1], DensityFunctions.BeardifierMarker.INSTANCE, FillHarness.vanillaPicker(settings));
                    FillHarness.fill(chunk, settings, c[0], c[1], false);
                }
                System.out.println("AquiferBench " + preset.identifier() + " aquifer paths: " + AquiferFastPath.statsLine());
            }
        }
    }
}
