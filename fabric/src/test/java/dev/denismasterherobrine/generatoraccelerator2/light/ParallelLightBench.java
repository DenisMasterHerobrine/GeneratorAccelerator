package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Wall time of lighting new chunks (section status, light sources, propagation in batches of {@code batch} chunks) on the
 * light thread alone (fast engine) and with the propagation split over 1-7 helpers. 144 full chunks of a dev-run world
 * when one is around. {@code gradlew test --tests "*ParallelLightBench*" -Dga2.bench=true}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class ParallelLightBench {
    @Test
    void light() throws Exception {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Path region = RegionChunks.defaultRegion();
        ProtoChunk[] base = region != null ? RegionChunks.load(region, 10, 10, 12, world) : null;
        if (base == null) {
            base = LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0);
        }
        System.out.println("parallel light bench: " + base.length + " chunks from " + (region != null ? region : "test terrain"));
        int[] helperCounts = System.getProperty("ga2.bench.helpers") != null
            ? java.util.Arrays.stream(System.getProperty("ga2.bench.helpers").split(",")).mapToInt(Integer::parseInt).toArray() : new int[] { 0, 1, 2, 3, 4, 7 };
        int[] batches = System.getProperty("ga2.bench.batch") != null
            ? java.util.Arrays.stream(System.getProperty("ga2.bench.batch").split(",")).mapToInt(Integer::parseInt).toArray() : new int[] { 3, 12 };
        int rounds = Integer.getInteger("ga2.bench.rounds", 10);
        if (Boolean.getBoolean("ga2.bench.track")) {
            dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats.TRACK = line -> System.out.println("track " + line);
        }
        ParallelLight.anyEngine = true;
        try {
            for (int batch : batches) {
                double[] best = new double[helperCounts.length];
                double[][] parts = new double[helperCounts.length][3];
                java.util.Arrays.fill(best, Double.MAX_VALUE);
                java.util.List<String>[] stats = new java.util.List[helperCounts.length];
                for (int round = 0; round < rounds; round++) {
                    for (int i = 0; i < helperCounts.length; i++) {
                        int helpers = helperCounts[i];
                        dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats.reset();
                        ParallelLight.ENABLED = helpers > 0;
                        ParallelLight.helpersOverride = helpers;
                        ParallelLight.minEntriesOverride = Integer.getInteger("ga2.bench.minEntries", 2048);
                        LightExactnessTest.Run run = LightExactnessTest.run(world, base, true, 1234567L, false, 11L, batch);
                        if (round >= 2 && run.lightNanos / 1e6 / base.length < best[i]) {
                            best[i] = run.lightNanos / 1e6 / base.length;
                            parts[i][0] = run.initNanos / 1e6 / base.length;
                            parts[i][1] = run.sourcesNanos / 1e6 / base.length;
                            parts[i][2] = run.updatesNanos / 1e6 / base.length;
                            stats[i] = ParallelLight.statusLines();
                        }
                    }
                }
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "parallel light bench: batches of %d chunks, best ms/chunk:", batch));
                for (int i = 0; i < helperCounts.length; i++) {
                    sb.append(String.format(Locale.ROOT, "%n    %s %.3f (init %.3f, sources %.3f, updates %.3f)", helperCounts[i] == 0 ? "light thread alone" : helperCounts[i] + " helpers",
                        best[i], parts[i][0], parts[i][1], parts[i][2]));
                    if (stats[i] != null) {
                        for (String line : stats[i].subList(1, stats[i].size())) {
                            sb.append("%n        ".formatted()).append(line.trim());
                        }
                    }
                }
                System.out.println(sb);
            }
        } finally {
            ParallelLight.ENABLED = true;
            ParallelLight.anyEngine = false;
            ParallelLight.helpersOverride = -1;
            ParallelLight.minEntriesOverride = -1;
        }
        for (String line : ParallelLight.statusLines()) {
            System.out.println(line);
        }
    }
}
