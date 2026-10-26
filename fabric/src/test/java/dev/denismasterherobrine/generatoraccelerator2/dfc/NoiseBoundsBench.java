package dev.denismasterherobrine.generatoraccelerator2.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.NoiseBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuDatapack;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The noise step of fresh chunks on one thread with the second whole-cell pass (noise leaves bounded over the cell) off and on,
 * alternating, over a datapack's overworld when {@code -Dga2.gpu.datapack=<folder holding data/>} names one (Terralith's data
 * folder, extracted from its jar) and over the vanilla overworld otherwise. Prints the wall time per chunk and the cells
 * handled whole. {@code gradlew :fabric:test --tests "*NoiseBoundsBench*" -Dga2.bench=true [-Dga2.gpu.datapack=...]}.
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class NoiseBoundsBench {
    @Test
    void secondPassOffAndOn() throws Exception {
        String dir = System.getProperty("ga2.gpu.datapack");
        boolean synthetic = Boolean.getBoolean("ga2.bench.synthetic");
        TerrainHarness.World world;
        if (synthetic) {
            // the overworld with three per-block noise terms, as NoiseCellBoundsTest adds them
            HarnessWorld.Registries2 registries = HarnessWorld.registries();
            var lookup = registries.lookup;
            var overworld = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            var noises = lookup.lookupOrThrow(Registries.NOISE);
            var dim = lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
            var biomeSource = MultiNoiseBiomeSource.createFromPreset(Holder.direct(
                new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, registries.biomes.asLookup())));
            world = new TerrainHarness.World(registries, Holder.direct(NoiseCellBoundsTest.withPerBlockNoise(overworld, noises)), biomeSource, noises, dim, 1234567L);
        } else {
            world = dir == null || dir.isBlank() ? TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L) : GpuDatapack.world(Path.of(dir), 1234567L);
        }
        int rounds = Integer.getInteger("ga2.bench.rounds", 6);
        int chunks = Integer.getInteger("ga2.bench.chunks", 48);
        // warm-up: the JIT and the programs
        for (int i = 0; i < 24; i++) {
            world.noise(5000 + i, 5000);
        }
        double[][] time = new double[2][rounds];
        long[][] whole = new long[2][rounds];
        long[][] won = new long[2][rounds];
        for (int r = 0; r < rounds; r++) {
            for (int pass = 0; pass < 2; pass++) {
                boolean tight = pass == 1;
                CellBounds.TIGHT = tight;
                long wholeBefore = FastFill.CELLS_SKY.sum() + FastFill.CELLS_SOLID.sum() + FastFill.CELLS_SOLID_VEINS.sum();
                long wonBefore = CellBounds.TIGHT_WON.sum();
                // a fresh area per round and pass, so the border column cache never serves one pass from the other
                int baseX = 1000 * (r * 2 + pass + 1);
                long start = System.nanoTime();
                for (int i = 0; i < chunks; i++) {
                    world.noise(baseX + (i % 8), 7000 + (i / 8) * 3);
                }
                time[pass][r] = (System.nanoTime() - start) / 1e6 / chunks;
                whole[pass][r] = (FastFill.CELLS_SKY.sum() + FastFill.CELLS_SOLID.sum() + FastFill.CELLS_SOLID_VEINS.sum() - wholeBefore) / chunks;
                won[pass][r] = (CellBounds.TIGHT_WON.sum() - wonBefore) / chunks;
            }
        }
        CellBounds.TIGHT = true;
        for (int pass = 0; pass < 2; pass++) {
            double[] t = time[pass].clone();
            Arrays.sort(t);
            long[] w = whole[pass].clone();
            Arrays.sort(w);
            System.out.printf(Locale.ROOT, "[NoiseBoundsBench] second pass %-3s biomes+noise %7.2f ms/chunk (median of %d rounds of %d chunks; %s), cells whole %d per chunk, decided by the second pass %d%n",
                pass == 1 ? "on" : "off", t[rounds / 2], rounds, chunks, Arrays.toString(time[pass]), w[rounds / 2], Arrays.stream(won[pass]).sorted().toArray()[rounds / 2]);
        }
        System.out.println("[NoiseBoundsBench] " + (synthetic ? "overworld with per-block noise terms" : dir == null ? "vanilla overworld" : "datapack " + dir) + "; second pass tried " + CellBounds.TIGHT_TRIED.sum()
            + " cells, decided " + CellBounds.TIGHT_WON.sum() + ", paused " + CellBounds.TIGHT_PAUSED.sum() + "; octaves bounded " + NoiseBounds.OCTAVES.sum()
            + ", too fine " + NoiseBounds.OCTAVES_WIDE.sum());
    }
}
