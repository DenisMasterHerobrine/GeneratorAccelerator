package dev.denismasterherobrine.generatoraccelerator2.biome;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
        Map<Object, Integer> numbers = new HashMap<>();
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

    /**
     * Searches at scattered points outside a biome fill, as structure checks and the kept biome answers ask them ({@code BiomeAnswers}),
     * against GA2's walks and vanilla's. Nov 11 2026: watched 4.6-4.9 us, unwatched 3.1, flat walk 2.5, with column parts 2.8, vanilla
     * 3.8 us per search. A column fill per watched search instead of walk and {@code alone} measured 4.8-9.8 us and was not kept.
     */
    @Test
    void scatteredWatched() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(entries);
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Climate.Sampler sampler = world.randomState.sampler();
        Random random = new Random(5);
        long[][] queries = new long[Integer.getInteger("ga2.bench.points", 20000)][];
        for (int i = 0; i < queries.length; i++) {
            Climate.TargetPoint t = sampler.sample(random.nextInt(100000) - 50000, random.nextInt(96) - 16, random.nextInt(100000) - 50000);
            queries[i] = new long[] { t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness() };
        }
        long watched = Long.MAX_VALUE;
        long unsure = 0;
        for (int round = 0; round < Integer.getInteger("ga2.bench.rounds", 9); round++) {
            unsure = 0;
            long t0 = System.nanoTime();
            for (long[] q : queries) {
                SearchTies.Watch watch = SearchTies.start();
                ClimateSearch.search(list.index, q[0], q[1], q[2], q[3], q[4], q[5]);
                if (!SearchTies.end(watch)) {
                    unsure++;
                }
            }
            watched = Math.min(watched, System.nanoTime() - t0);
        }
        System.out.printf("ColumnGroupSearchBench scattered, GA2 search, watched: %.0f ns per search, %d unsure%n", (double) watched / queries.length, unsure);
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
        Climate.TargetPoint[] targets = new Climate.TargetPoint[queries.length];
        for (int i = 0; i < queries.length; i++) {
            long[] q = queries[i];
            targets[i] = new Climate.TargetPoint(q[0], q[1], q[2], q[3], q[4], q[5]);
        }
        String[] names = { "GA2 search, unwatched", "GA2 flat walk", "GA2 walk with column parts", "vanilla walk" };
        for (int variant = 0; variant < names.length; variant++) {
            long best = Long.MAX_VALUE, checksum = 0;
            for (int round = 0; round < Integer.getInteger("ga2.bench.rounds", 9); round++) {
                int last = -1;
                checksum = 0;
                long t0 = System.nanoTime();
                for (int i = 0; i < queries.length; i++) {
                    long[] q = queries[i];
                    Object v = switch (variant) {
                        case 0 -> ClimateSearch.search(list.index, q[0], q[1], q[2], q[3], q[4], q[5]);
                        case 1 -> flat.leaf(last = flat.search(q[0], q[1], q[2], q[3], q[4], q[5], last)).value;
                        case 2 -> flat.leaf(last = flat.walkColumns(q[0], q[1], q[2], q[3], q[4], q[5], last)).value;
                        default -> {
                            Climate.TargetPoint t = targets[i];
                            yield ClimateSearch.vanilla(() -> list.findValue(t));
                        }
                    };
                    checksum += System.identityHashCode(v);
                }
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("ColumnGroupSearchBench scattered, %s: %.0f ns per search (checksum %d)%n", names[variant], (double) best / queries.length, checksum);
        }
    }

    /**
     * {@link #fillOrder} and scattered walks over several trees, as TerraBlender's regions ask them. Each tree is vanilla's with
     * its points moved a little. The chunks rotate through the trees, so a tree's nodes leave the cache between its chunks.
     */
    @Test
    void regions() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        int count = Integer.getInteger("ga2.bench.trees", 12);
        Random random = new Random(11);
        List<Climate.ParameterList<Holder<Biome>>> lists = new ArrayList<>();
        FlatClimateTree[] trees = new FlatClimateTree[count];
        for (int t = 0; t < count; t++) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> moved = new ArrayList<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> e : entries) {
                Climate.ParameterPoint p = e.getFirst();
                long s = t == 0 ? 0 : random.nextInt(1001) - 500;
                moved.add(Pair.of(new Climate.ParameterPoint(move(p.temperature(), s), move(p.humidity(), -s), move(p.continentalness(), s / 2),
                    move(p.erosion(), -s / 2), p.depth(), move(p.weirdness(), s), p.offset()), e.getSecond()));
            }
            Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(moved);
            lists.add(list);
            trees[t] = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
        }
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Climate.Sampler sampler = world.randomState.sampler();
        int chunks = Integer.getInteger("ga2.bench.chunks", 64);
        int minSection = world.heights.getMinSection(), maxSection = world.heights.getMaxSection();
        int perChunk = (maxSection - minSection) * 64;
        long[][] queries = new long[chunks * perChunk][];
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
        long[][] scattered = new long[Integer.getInteger("ga2.bench.points", 20000)][];
        for (int i = 0; i < scattered.length; i++) {
            Climate.TargetPoint t = sampler.sample(random.nextInt(100000) - 50000, random.nextInt(96) - 16, random.nextInt(100000) - 50000);
            scattered[i] = new long[] { t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness() };
        }
        String[] names = { "fill, column answers", "fill, walk (column parts)", "scattered, flat walk", "scattered, walk with column parts" };
        FlatClimateTree.enterFill();
        for (int variant = 0; variant < names.length; variant++) {
            if (variant == 2) {
                FlatClimateTree.exitFill();
            }
            long[][] qs = variant < 2 ? queries : scattered;
            long best = Long.MAX_VALUE, checksum = 0;
            for (int round = 0; round < Integer.getInteger("ga2.bench.rounds", 9); round++) {
                int[] last = new int[count];
                Arrays.fill(last, -1);
                checksum = 0;
                long t0 = System.nanoTime();
                for (int i = 0; i < qs.length; i++) {
                    long[] q = qs[i];
                    int t = variant < 2 ? (i / perChunk) % count : i % count;
                    FlatClimateTree flat = trees[t];
                    int id = switch (variant) {
                        case 0 -> flat.searchColumns(q[0], q[1], q[2], q[3], q[4], q[5], last[t]);
                        case 1, 3 -> flat.walkColumns(q[0], q[1], q[2], q[3], q[4], q[5], last[t]);
                        default -> flat.search(q[0], q[1], q[2], q[3], q[4], q[5], last[t]);
                    };
                    last[t] = id;
                    checksum += id;
                }
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf("ColumnGroupSearchBench regions (%d trees of %d nodes), %s: %.0f ns per search (checksum %d)%n", count, trees[0].nodes(), names[variant],
                (double) best / qs.length, checksum);
        }
        System.out.println("ColumnGroupSearchBench regions: " + lists.size() + " lists kept");
    }

    private static Climate.Parameter move(Climate.Parameter p, long s) {
        return new Climate.Parameter(p.min() + s, p.max() + s);
    }
}
