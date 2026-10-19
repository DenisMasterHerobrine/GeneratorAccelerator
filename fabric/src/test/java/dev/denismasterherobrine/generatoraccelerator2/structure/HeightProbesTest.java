package dev.denismasterherobrine.generatoraccelerator2.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

/**
 * {@code getBaseHeight} with remembered answers ({@link HeightProbes}) against computing every probe: the same columns asked
 * twice, in another order, for the heightmaps structures ask, on the overworld, amplified and nether presets.
 */
public class HeightProbesTest {
    @Test
    void rememberedHeightsMatch() {
        Heightmap.Types[] types = { Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG };
        long remembered0 = HeightProbes.REMEMBERED.sum();
        int compared = 0;
        for (TerrainHarness.Preset preset : new TerrainHarness.Preset[] { TerrainHarness.Preset.OVERWORLD, TerrainHarness.Preset.AMPLIFIED, TerrainHarness.Preset.NETHER }) {
            TerrainHarness.World world = TerrainHarness.world(preset, 777L);
            Random r = new Random(preset.ordinal());
            List<int[]> asks = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                asks.add(new int[] { r.nextInt(4000) - 2000, r.nextInt(4000) - 2000, r.nextInt(types.length) });
            }
            int[] expected = new int[asks.size()];
            try {
                HeightProbes.ENABLED = false;
                for (int i = 0; i < asks.size(); i++) {
                    int[] a = asks.get(i);
                    expected[i] = world.generator.getBaseHeight(a[0], a[1], types[a[2]], world.heights, world.randomState);
                }
            } finally {
                HeightProbes.ENABLED = true;
            }
            for (int round = 0; round < 2; round++) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < asks.size(); i++) {
                    order.add(i);
                }
                Collections.shuffle(order, new Random(round * 31L + preset.ordinal()));
                for (int i : order) {
                    int[] a = asks.get(i);
                    assertEquals(expected[i], world.generator.getBaseHeight(a[0], a[1], types[a[2]], world.heights, world.randomState),
                        preset + " round " + round + " column " + a[0] + "," + a[1] + " " + types[a[2]]);
                    compared++;
                }
            }
        }
        assertTrue(HeightProbes.REMEMBERED.sum() > remembered0, "no probe was answered from an earlier one");
        System.out.println("HeightProbesTest: " + compared + " probes identical, " + (HeightProbes.REMEMBERED.sum() - remembered0) + " answered from earlier ones");
    }
}
