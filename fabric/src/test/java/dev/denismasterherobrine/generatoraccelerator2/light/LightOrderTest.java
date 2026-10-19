package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * Does vanilla's light depend on the order chunks are lit in? Lights the same chunks with vanilla's engine in several
 * orders and batch sizes and counts the light values that differ from the first order. Diagnostic: prints, asserts
 * nothing.
 */
public class LightOrderTest {
    private static final long[][] ORDERS = { { 1, 3 }, { 2, 1 }, { 3, 25 }, { 4, 2 }, { 5, 7 }, { 6, 1 }, { 7, 4 }, { 8, 25 } };

    @Test
    void orders() throws Exception {
        FastLight.ENABLED = false;
        try {
            report("overworld 0,0", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
                LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0), true);
            report("overworld -300,412", TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L),
                LightExactnessTest.terrain(TerrainHarness.Preset.OVERWORLD, 1234567L, -300, 412), true);
            report("amplified", TerrainHarness.world(TerrainHarness.Preset.AMPLIFIED, -42L),
                LightExactnessTest.terrain(TerrainHarness.Preset.AMPLIFIED, -42L, 40, -7), true);
            report("nether", TerrainHarness.world(TerrainHarness.Preset.NETHER, 1234567L),
                LightExactnessTest.terrain(TerrainHarness.Preset.NETHER, 1234567L, 3, 9), false);
            Path region = RegionChunks.defaultRegion();
            if (region != null) {
                TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
                ProtoChunk[] chunks = RegionChunks.load(region, 4, 6, 8, world);
                if (chunks != null) {
                    report("real chunks", world, chunks, true);
                }
            }
        } finally {
            FastLight.ENABLED = true;
        }
    }

    private static void report(String where, TerrainHarness.World world, ProtoChunk[] base, boolean sky) {
        for (boolean changes : new boolean[] { false, true }) {
            LightExactnessTest.Run first = null;
            StringBuilder sb = new StringBuilder(where).append(changes ? " (with changes)" : " (initial light)").append(':');
            for (long[] o : ORDERS) {
                LightExactnessTest.Run run = LightExactnessTest.run(world, base, sky, 1234567L, changes, o[0], (int) o[1]);
                if (first == null) {
                    first = run;
                    continue;
                }
                int sections = 0;
                long nibbles = 0;
                for (Map.Entry<String, byte[]> e : first.layers.entrySet()) {
                    byte[] a = e.getValue();
                    byte[] b = run.layers.get(e.getKey());
                    if (a == null || b == null) {
                        if (a != b) {
                            sections++;
                        }
                        continue;
                    }
                    if (!Arrays.equals(a, b)) {
                        sections++;
                        for (int i = 0; i < a.length; i++) {
                            if ((a[i] & 15) != (b[i] & 15)) nibbles++;
                            if ((a[i] >> 4 & 15) != (b[i] >> 4 & 15)) nibbles++;
                        }
                    }
                }
                boolean typesSame = first.types.equals(run.types);
                boolean brightSame = Arrays.equals(first.brightness, run.brightness);
                sb.append(String.format(" [order %d batch %d: %d sections, %d values differ%s%s]", o[0], o[1], sections, nibbles,
                    typesSame ? "" : ", types differ", brightSame ? "" : ", brightness differs"));
            }
            System.out.println(sb);
        }
    }
}
