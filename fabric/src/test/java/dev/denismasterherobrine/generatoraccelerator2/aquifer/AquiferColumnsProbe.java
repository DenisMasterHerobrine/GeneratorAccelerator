package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in ({@code -Dga2.bench=true -Dgeneratoraccelerator2.aquifer.stats=true}): aquifer path counts of the real fill with a
 * search-mode aquifer, whole-cell air from known statuses off and on.
 */
public class AquiferColumnsProbe {
    @Test
    void searchModeAirBoxes() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        for (int round = 0; round < 4; round++) {
            boolean boxes = (round & 1) == 1;
            TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1000L + round / 2);
            AquiferFastPath.registerMode(world.randomState.aquiferRandom(), AquiferFastPath.SEARCH);
            AquiferFastPath.AIR_BOXES = boxes;
            AquiferFastPath.resetStats();
            long sky = FastFill.CELLS_SKY.sum();
            long t0 = System.nanoTime();
            for (int cx = 0; cx < 6; cx++) {
                for (int cz = 0; cz < 6; cz++) {
                    world.noise(200 + cx, -70 + cz);
                }
            }
            System.out.println("AquiferColumnsProbe air boxes " + (boxes ? "on" : "off") + ": " + AquiferFastPath.statsLine()
                + ", sky cells " + (FastFill.CELLS_SKY.sum() - sky) + String.format(" in %.0f ms", (System.nanoTime() - t0) / 1e6));
        }
        AquiferFastPath.AIR_BOXES = true;
    }
}
