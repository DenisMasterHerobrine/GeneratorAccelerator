package dev.denismasterherobrine.generatoraccelerator2.biome;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in ({@code -Dga2.bench=true}): Biolith's search ({@link FlatClimateTree#searchFittest}) over real overworld climate
 * samples in the biome fill's order. That order is sections bottom to top, then x, y and z quarts within each section.
 * Searches are chained through the thread's last answers as Biolith keeps them.
 */
public class BiolithSearchBench {
    @Test
    void fillOrder() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes).parameters().values();
        Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(entries);
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
        int[] keys = new int[flat.nodes() + 1];
        Map<Object, Integer> numbers = new HashMap<>();
        numbers.put("none", 0);
        for (int id = 0; id < flat.nodes(); id++) {
            Climate.RTree.Leaf<?> leaf = flat.leaf(id);
            if (leaf != null) {
                keys[id] = numbers.computeIfAbsent(((Holder<?>) leaf.value).unwrapKey().orElseThrow(), k -> numbers.size());
            }
        }
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Climate.Sampler sampler = world.randomState.sampler();
        int chunks = Integer.getInteger("ga2.bench.chunks", 48);
        int minSection = world.heights.getMinSectionY();
        int maxSection = world.heights.getMaxSectionY() + 1;
        long[][] queries = new long[chunks * (maxSection - minSection) * 64][];
        int n = 0;
        for (int c = 0; c < chunks; c++) {
            int qx0 = (c % 8) * 4 * 37;
            int qz0 = (c / 8) * 4 * 29;
            for (int s = minSection; s < maxSection; s++) {
                for (int j = 0; j < 4; j++) {
                    for (int k = 0; k < 4; k++) {
                        for (int l = 0; l < 4; l++) {
                            Climate.TargetPoint t = sampler.sample(qx0 + j, s * 4 + k, qz0 + l);
                            queries[n++] = new long[] {t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness()};
                        }
                    }
                }
            }
        }
        int[] ids = new int[2];
        long[] distance = new long[2];
        int[] ids2 = new int[2];
        long[] distance2 = new long[2];
        // both walks on the same chains: every answer must agree
        int u = -1;
        int p = -1;
        for (long[] q : queries) {
            flat.searchFittest(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
            flat.searchFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids2, distance2);
            Assertions.assertArrayEquals(ids, ids2);
            Assertions.assertArrayEquals(distance, distance2);
            u = ids[0];
            p = ids[1];
        }
        for (int variant = 0; variant < 2; variant++) {
            long best = Long.MAX_VALUE;
            long checksum = 0;
            for (int round = 0; round < Integer.getInteger("ga2.bench.rounds", 9); round++) {
                u = -1;
                p = -1;
                long t0 = System.nanoTime();
                for (long[] q : queries) {
                    if (variant == 0) {
                        flat.searchFittest(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
                    } else {
                        flat.searchFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
                    }
                    u = ids[0];
                    p = ids[1];
                    checksum += u * 31L + p;
                }
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("BiolithSearchBench %s: %d queries, best round %.1f ms, %.0f ns per search (checksum %d)%n", variant == 0 ? "walk" : "column parts",
                queries.length, best / 1e6, (double) best / queries.length, checksum);
        }
    }
}
