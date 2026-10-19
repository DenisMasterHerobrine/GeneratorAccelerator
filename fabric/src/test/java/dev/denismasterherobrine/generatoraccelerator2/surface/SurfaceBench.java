package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@code gradlew test --tests *SurfaceBench -Dga2.bench=true}: median time per chunk of the surface
 * step body on real terrain, vanilla vs GA2 (interpreted / compiled, deep bands on / off). Each round
 * builds every site's surface on fresh copies. Copies are made outside the timed region.
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class SurfaceBench {
    private enum Setup { VANILLA, GA2_INTERPRETED_NO_DEEP, GA2_INTERPRETED, GA2 }

    @Test
    void surface() {
        for (TerrainHarness.Preset preset : List.of(TerrainHarness.Preset.OVERWORLD, TerrainHarness.Preset.NETHER)) {
            List<TerrainHarness.Site> sites = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                sites.add(TerrainHarness.site(preset, 1234567L, (i % 6) * 7 - 20, (i / 6) * 9 - 15));
            }
            long[][] samples = new long[Setup.values().length][];
            int rounds = Integer.getInteger("ga2.bench.rounds", 7);
            for (Setup s : Setup.values()) {
                samples[s.ordinal()] = new long[rounds];
            }
            String only = System.getProperty("ga2.bench.setups");
            List<Setup> setups = only == null ? List.of(Setup.values())
                : Arrays.stream(only.split(",")).map(String::trim).map(Setup::valueOf).toList();
            for (int round = 0; round < rounds + 2; round++) {
                for (Setup setup : setups) {
                    List<ProtoChunk> copies = new ArrayList<>();
                    for (TerrainHarness.Site site : sites) {
                        copies.add(site.center());
                    }
                    SurfaceConfig.compileOverride = setup == Setup.GA2;
                    SurfaceConfig.deepBandsOverride = setup != Setup.GA2_INTERPRETED_NO_DEEP;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < sites.size(); i++) {
                        TerrainHarness.Site site = sites.get(i);
                        ProtoChunk chunk = copies.get(i);
                        if (setup == Setup.VANILLA) {
                            SurfaceHooks.runVanilla(() -> TerrainHarness.vanillaSurface(site, chunk));
                        } else {
                            TerrainHarness.vanillaSurface(site, chunk);
                        }
                    }
                    long dt = (System.nanoTime() - t0) / sites.size();
                    if (round >= 2) {
                        samples[setup.ordinal()][round - 2] = dt;
                    }
                }
            }
            SurfaceConfig.compileOverride = null;
            SurfaceConfig.deepBandsOverride = null;
            StringBuilder sb = new StringBuilder("SurfaceBench " + preset + " (" + sites.size() + " chunks, median of " + rounds + "):");
            long vanilla = setups.contains(Setup.VANILLA) ? median(samples[Setup.VANILLA.ordinal()]) : 1;
            for (Setup s : setups) {
                long m = median(samples[s.ordinal()]);
                sb.append(String.format("%n  %-26s %9.1f us/chunk  %5.2fx", s, m / 1000.0, (double) vanilla / m));
            }
            System.out.println(sb);
        }
    }

    private static long median(long[] v) {
        long[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
