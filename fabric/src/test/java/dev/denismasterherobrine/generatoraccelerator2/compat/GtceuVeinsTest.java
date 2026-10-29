package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gregtechceu.gtceu.config.ConfigHolder;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuVeins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.junit.jupiter.api.Test;

/** GregTech's {@code removeVanillaLargeOreVeins}, read from a stand-in of its config class. */
public class GtceuVeinsTest {
    @Test
    void veinRuleLeftOutOnlyWhileGregTechRemovesVeins() {
        GtceuVeins.register();
        try {
            assertFalse(GtceuVeins.removesVeins(), "no config loaded: GregTech's handler does nothing");
            ConfigHolder.INSTANCE = new ConfigHolder();
            assertFalse(GtceuVeins.removesVeins(), "the setting is off by default");
            TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
            NoiseChunk kept = FillHarness.chunk(w.randomState, w.settings, 3, -7, DensityFunctions.BeardifierMarker.INSTANCE, w.fluidPicker);
            FastFill.Rules keptRules = (FastFill.Rules) (Object) kept;
            assertNotNull(keptRules.ga2$materialRules()[1], "the overworld has ore veins");
            assertFalse(keptRules.ga2$placesNoVein(keptRules.ga2$materialRules()[1]), "vanilla's veins stay while the setting is off");

            ConfigHolder.INSTANCE.worldgen.oreVeins.removeVanillaLargeOreVeins = true;
            assertTrue(GtceuVeins.removesVeins());
            NoiseChunk removed = FillHarness.chunk(w.randomState, w.settings, 3, -7, DensityFunctions.BeardifierMarker.INSTANCE, w.fluidPicker);
            FastFill.Rules removedRules = (FastFill.Rules) (Object) removed;
            assertTrue(removedRules.ga2$placesNoVein(removedRules.ga2$materialRules()[1]), "the rule made while the setting is on places no vein");
            assertFalse(keptRules.ga2$placesNoVein(keptRules.ga2$materialRules()[1]), "a chunk keeps the answer of the moment its rule was made");
        } finally {
            ConfigHolder.INSTANCE = null;
        }
    }
}
