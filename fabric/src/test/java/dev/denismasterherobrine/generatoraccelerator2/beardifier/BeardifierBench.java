package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import dev.denismasterherobrine.generatoraccelerator2.testutil.FillBench;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** {@code gradlew test --tests *BeardifierBench -Dga2.bench=true}: every chunk carries a village-sized set of pieces and junctions. */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class BeardifierBench {
    @Test
    void noiseFillWithStructures() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        int[][] chunks = FillBench.grid(200, 200, 6);
        Map<FillBench.Setup, Long> result = FillBench.run(settings, lookup.lookupOrThrow(Registries.NOISE), 1234567L, chunks,
            i -> FillHarness.village(chunks[i][0], chunks[i][1], 70, i), FillBench.setups(), 2, 5);
        System.out.println("BeardifierBench overworld, " + chunks.length + " chunks, village pieces in every chunk:\n" + FillBench.format(result));
    }
}
