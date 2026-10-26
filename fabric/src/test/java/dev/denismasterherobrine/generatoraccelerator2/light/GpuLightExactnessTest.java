package dev.denismasterherobrine.generatoraccelerator2.light;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.light.GpuLight;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Light whose increases propagate on the GPU ({@code gpu.light.GpuLight}) equals vanilla's. The terrain and the sequence of engine
 * calls are {@link LightExactnessTest}'s: light sources per chunk in batches, then 1500 block changes, one section emptied and one
 * filled. Every batch with increases goes to the GPU. Every section's block and sky light, the section types, a brightness sample
 * and the sections notified of light changes must match vanilla's.
 */
@EnabledIfSystemProperty(named = "ga2.gpu", matches = "true")
public class GpuLightExactnessTest {
    private static GpuDevice device;

    private static synchronized GpuDevice device() throws Exception {
        if (device == null) {
            ClLoader.Loaded loaded = ClLoader.load(Path.of("build", "gpu-lib").toAbsolutePath());
            ClDeviceInfo info = loaded.api().devices(false).stream().filter(ClDeviceInfo::hasFp64).findFirst().orElseThrow();
            device = GpuRuntime.open(loaded, info);
        }
        return device;
    }

    @Test
    void overworld() throws Exception {
        compare("overworld 0,0", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
            LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0), true, 1234567L);
        compare("overworld -300,412", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
            LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, -300, 412), true, 99L);
        compare("amplified", TerrainHarness.world(TerrainHarness.Preset.AMPLIFIED, -42L),
            LightExactnessTest.terrain(TerrainHarness.Preset.AMPLIFIED, -42L, 40, -7), true, -42L);
    }

    @Test
    void nether() throws Exception {
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

    private static void compare(String where, TerrainHarness.World world, ProtoChunk[] base, boolean sky, long seed) throws Exception {
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
        GpuLight.testDevice = device();
        GpuLight.testAlways = true;
        ParallelLight.anyEngine = true;
        ParallelLight.helpersOverride = 0;
        ParallelLight.setIncreaseOffload(GpuLight.INSTANCE);
        try {
            // the program builds in the background at the first batch it is offered
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (!GpuLight.statusLines().get(0).startsWith("GPU light: on") && System.nanoTime() < deadline) {
                GpuLight.INSTANCE.wants(null, true, Integer.MAX_VALUE, 0);
                Thread.sleep(50);
            }
            assertTrue(GpuLight.statusLines().get(0).startsWith("GPU light: on"), "GPU light program: " + GpuLight.statusLines().get(0));
            long before = GpuLight.BATCHES.sum();
            long fallbacksBefore = GpuLight.FILL.sum() + GpuLight.RACE.sum() + GpuLight.OUTSIDE.sum() + GpuLight.UNSTORED.sum() + GpuLight.LARGE.sum();
            LightExactnessTest.Run run = LightExactnessTest.run(world, base, sky, seed, true, seed * 7 + 1, 3, LightExactnessTest::checkPublished);
            long batches = GpuLight.BATCHES.sum() - before;
            long fallbacks = GpuLight.FILL.sum() + GpuLight.RACE.sum() + GpuLight.OUTSIDE.sum() + GpuLight.UNSTORED.sum() + GpuLight.LARGE.sum() - fallbacksBefore;
            assertTrue(batches > 0, where + ": no batch propagated on the GPU (" + fallbacks + " handed back)");
            assertEquals(vanilla.layers.keySet(), run.layers.keySet(), where + ": sections with light data");
            for (Map.Entry<String, byte[]> e : vanilla.layers.entrySet()) {
                assertArrayEquals(e.getValue(), run.layers.get(e.getKey()), where + ": light of " + e.getKey());
            }
            assertEquals(vanilla.types, run.types, where + ": section types");
            assertArrayEquals(vanilla.brightness, run.brightness, where + ": brightness sample");
            assertEquals(vanillaUpdates, new TreeSet<>(run.updates), where + ": sections notified of light changes");
            System.out.println("GPU light exactness: " + where + " ok (" + vanilla.layers.size() + " sections, " + batches + " batches on the GPU, "
                + fallbacks + " handed back to the CPU)");
            for (String line : GpuLight.statusLines()) {
                System.out.println("  " + line);
            }
        } finally {
            GpuLight.testAlways = false;
            GpuLight.testDevice = null;
            ParallelLight.anyEngine = false;
            ParallelLight.helpersOverride = -1;
            ParallelLight.setIncreaseOffload(null);
        }
    }

    static {
        Gpu.class.getName();
    }
}
