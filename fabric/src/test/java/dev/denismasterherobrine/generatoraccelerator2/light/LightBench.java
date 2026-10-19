package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Single-thread cost of lighting chunks the way the server does for new chunks (section status, light sources,
 * propagation in batches) with the fast engine off and on: 144 full chunks of a dev-run world when one is around
 * ({@link RegionChunks}), else the exactness test's terrain. {@code gradlew test --tests "*LightBench*" -Dga2.bench=true}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class LightBench {
    @Test
    void light() throws IOException {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Path region = RegionChunks.defaultRegion();
        ProtoChunk[] base = region != null ? RegionChunks.load(region, 10, 10, 12, world) : null;
        if (base == null) {
            base = LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0);
        }
        System.out.println("light bench: " + base.length + " chunks from " + (region != null ? region : "test terrain"));
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        for (int round = 0; round < rounds; round++) {
            String only = System.getProperty("ga2.bench.only", "");
            for (boolean fast : only.isEmpty() ? new boolean[] { false, true } : new boolean[] { Boolean.parseBoolean(only) }) {
                FastLight.ENABLED = fast;
                LightExactnessTest.Run run = LightExactnessTest.run(world, base, true, 1234567L, !Boolean.getBoolean("ga2.bench.lightOnly"));
                if (round >= rounds / 2) {
                    System.out.println(String.format(Locale.ROOT, "light bench: fast=%-5s (on %s) initial light %.3f ms/chunk, 1500 block changes %.1f ms", fast,
                        run.fastWasOn, run.lightNanos / 1e6 / base.length, run.updateNanos / 1e6));
                }
            }
        }
        FastLight.ENABLED = true;
    }
}
