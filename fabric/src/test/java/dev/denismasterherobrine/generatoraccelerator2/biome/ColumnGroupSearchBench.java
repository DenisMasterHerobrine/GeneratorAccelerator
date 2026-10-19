package dev.denismasterherobrine.generatoraccelerator2.biome;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in ({@code -Dga2.bench=true}): times the column searches per search, with and without the column answers. Uses
 * vanilla's overworld tree and real climate samples in the biome fill's order. Searches are chained through the last
 * answers as the game keeps them.
 */
public class ColumnGroupSearchBench {
    @Test
    void fillOrder() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(entries);
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
        int[] keys = new int[flat.nodes() + 1];
        java.util.Map<Object, Integer> numbers = new java.util.HashMap<>();
        numbers.put("none", 0);
        for (int id = 0; id < flat.nodes(); id++) {
            if (flat.leaf(id) != null) {
                keys[id] = numbers.computeIfAbsent(((Holder<?>) flat.leaf(id).value).unwrapKey().orElseThrow(), k -> numbers.size());
            }
        }
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Climate.Sampler sampler = world.randomState.sampler();
        int chunks = Integer.getInteger("ga2.bench.chunks", 64);
        int minSection = world.heights.getMinSection(), maxSection = world.heights.getMaxSection();
        long[][] queries = new long[chunks * (maxSection - minSection) * 64][];
        int n = 0;
        for (int c = 0; c < chunks; c++) {
            int qx0 = (c % 8) * 4 * 37, qz0 = (c / 8) * 4 * 29;
            for (int s = minSection; s < maxSection; s++) {
                for (int j = 0; j < 4; j++) {
                    for (int k = 0; k < 4; k++) {
                        for (int l = 0; l < 4; l++) {
                            Climate.TargetPoint t = sampler.sample(qx0 + j, s * 4 + k, qz0 + l);
                            queries[n++] = new long[] { t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness() };
                        }
                    }
                }
            }
        }
        int[] ids = new int[2];
        long[] distance = new long[2];
        String[] names = { "nearest, walk (column parts)", "nearest, column answers", "Biolith's pair, walk (column parts)", "Biolith's pair, column answers" };
        FlatClimateTree.enterFill();
        for (int variant = 0; variant < 4; variant++) {
            long best = Long.MAX_VALUE, checksum = 0;
            for (int round = 0; round < Integer.getInteger("ga2.bench.rounds", 9); round++) {
                int last = -1, u = -1, p = -1;
                checksum = 0;
                long t0 = System.nanoTime();
                for (long[] q : queries) {
                    switch (variant) {
                        case 0 -> last = flat.walkColumns(q[0], q[1], q[2], q[3], q[4], q[5], last);
                        case 1 -> last = flat.searchColumns(q[0], q[1], q[2], q[3], q[4], q[5], last);
                        case 2 -> {
                            flat.walkFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
                            u = ids[0];
                            p = ids[1];
                        }
                        default -> {
                            flat.searchFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
                            u = ids[0];
                            p = ids[1];
                        }
                    }
                    checksum += variant < 2 ? last : u * 31L + p;
                }
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("ColumnGroupSearchBench %s: %d queries, best round %.2f ms, %.0f ns per search (checksum %d)%n", names[variant], queries.length,
                best / 1e6, (double) best / queries.length, checksum);
        }
    }
}
