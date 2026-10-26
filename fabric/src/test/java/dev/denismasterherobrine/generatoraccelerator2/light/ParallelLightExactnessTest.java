package dev.denismasterherobrine.generatoraccelerator2.light;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Light from the parallel propagation equals vanilla's. The terrain and sequence of engine calls are the same as in
 * {@link LightExactnessTest}: light sources per chunk in batches, then 1500 block changes, one section emptied and one
 * filled. Every batch is split between the calling thread and 1, 2, 3 or 7 helpers, several times each. Every section's
 * block and sky light, the section types and a brightness sample must match vanilla's. The "light changed" notifications
 * must name the same sections. Their order follows the threads.
 */
public class ParallelLightExactnessTest {
    private static final int[] HELPERS = { 1, 2, 3, 7 };
    private static final int REPEATS = 3;

    @Test
    void overworld() {
        compare("overworld 0,0", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
            LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0), true, 1234567L);
        compare("overworld -300,412", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
            LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, -300, 412), true, 99L);
        compare("amplified", TerrainHarness.world(TerrainHarness.Preset.AMPLIFIED, -42L),
            LightExactnessTest.terrain(TerrainHarness.Preset.AMPLIFIED, -42L, 40, -7), true, -42L);
    }

    @Test
    void nether() {
        compare("nether", TerrainHarness.world(TerrainHarness.Preset.NETHER, 1234567L),
            LightExactnessTest.terrain(TerrainHarness.Preset.NETHER, 1234567L, 3, 9), false, 5L);
    }

    /** Full chunks of a dev-run world, when one is around. */
    @Test
    void realChunks() throws Exception {
        Path region = RegionChunks.defaultRegion();
        Assumptions.assumeTrue(region != null, "no dev-run region file");
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        ProtoChunk[] chunks = RegionChunks.load(region, 4, 6, 8, world);
        Assumptions.assumeTrue(chunks != null, "region square not fully generated");
        compare("real chunks", world, chunks, true, 77L);
    }

    private static void compare(String where, TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed) {
        LightExactnessTest.Run vanilla;
        boolean fast = FastLight.ENABLED;
        boolean snapshots = LightSnapshot.ENABLED;
        try {
            FastLight.ENABLED = false;
            LightSnapshot.ENABLED = false;
            ParallelLight.ENABLED = false;
            vanilla = LightExactnessTest.run(world, base, sky, seed);
        } finally {
            FastLight.ENABLED = fast;
            LightSnapshot.ENABLED = snapshots;
            ParallelLight.ENABLED = true;
        }
        TreeSet<String> vanillaUpdates = new TreeSet<>(vanilla.updates);
        ParallelLight.anyEngine = true;
        ParallelLight.minEntriesOverride = 0;
        try {
            for (int helpers : HELPERS) {
                ParallelLight.helpersOverride = helpers;
                for (int repeat = 0; repeat < REPEATS; repeat++) {
                    long before = parallelBatches();
                    // the helpers write the working map directly. The published map must still match it after every batch.
                    LightExactnessTest.Run run = LightExactnessTest.run(world, base, sky, seed, true, seed * 7 + 1, 3, LightExactnessTest::checkPublished);
                    String label = where + " with " + helpers + " helpers, run " + repeat;
                    assertTrue(parallelBatches() > before, label + ": batches were split");
                    assertEquals(vanilla.layers.keySet(), run.layers.keySet(), label + ": sections with light data");
                    for (Map.Entry<String, byte[]> e : vanilla.layers.entrySet()) {
                        assertArrayEquals(e.getValue(), run.layers.get(e.getKey()), label + ": light of " + e.getKey());
                    }
                    assertEquals(vanilla.types, run.types, label + ": section types");
                    assertArrayEquals(vanilla.brightness, run.brightness, label + ": brightness sample");
                    assertEquals(vanillaUpdates, new TreeSet<>(run.updates), label + ": sections notified of light changes");
                }
            }
        } finally {
            ParallelLight.anyEngine = false;
            ParallelLight.minEntriesOverride = -1;
            ParallelLight.helpersOverride = -1;
        }
        System.out.println("parallel light exactness: " + where + " ok (" + vanilla.layers.size() + " sections, " + HELPERS.length * REPEATS + " runs)");
    }

    private static long parallelBatches() {
        for (String line : ParallelLight.statusLines()) {
            if (line.startsWith("parallel light: ") && line.contains(" batches split")) {
                return Long.parseLong(line.substring("parallel light: ".length(), line.indexOf(' ', "parallel light: ".length())));
            }
        }
        throw new IllegalStateException("no stats line");
    }

    static {
        // reference the stats class so a rename shows up here
        ParallelLightStats.class.getName();
    }
}
