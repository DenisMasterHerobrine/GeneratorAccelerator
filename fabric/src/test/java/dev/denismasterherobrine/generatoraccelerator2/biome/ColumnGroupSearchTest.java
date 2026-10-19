package dev.denismasterherobrine.generatoraccelerator2.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Test;

/**
 * The column searches ({@link FlatClimateTree#searchColumns}, {@link FlatClimateTree#searchFittestColumns}) give the plain
 * walks' answers ({@link FlatClimateTree#search}, {@link FlatClimateTree#searchFittest}) for every target and every last
 * answer. A column search answers from the column's nearest leaves per depth group when the answer is the only leaf at its
 * distance. Checked on vanilla's overworld tree with real climate columns, and on small random trees whose coarse values make
 * many exact ties.
 */
public class ColumnGroupSearchTest {
    @Test
    void overworldColumnsMatchTheWalk() {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        FlatClimateTree flat = flat(new Climate.ParameterList<>(entries));
        int[] keys = keys(flat, entries.size(), id -> ((Holder<?>) flat.leaf(id).value).unwrapKey().orElseThrow().location().hashCode());
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Climate.Sampler sampler = world.randomState.sampler();
        List<long[]> queries = new ArrayList<>();
        for (int c = 0; c < 24; c++) {
            int qx0 = (c % 6) * 4 * 53 - 300, qz0 = (c / 6) * 4 * 41 - 200;
            // the biome fill's order: sections bottom to top, per section x, then y, then z
            for (int s = world.heights.getMinSection(); s < world.heights.getMaxSection(); s++) {
                for (int j = 0; j < 4; j++) {
                    for (int k = 0; k < 4; k++) {
                        for (int l = 0; l < 4; l++) {
                            Climate.TargetPoint t = sampler.sample(qx0 + j, s * 4 + k, qz0 + l);
                            queries.add(new long[] { t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness() });
                        }
                    }
                }
            }
        }
        long before = FlatClimateTree.GROUP_ANSWERED.sum();
        compare(flat, keys, queries, new Random(7));
        FlatClimateTree.flushGroupCounts();
        long answered = FlatClimateTree.GROUP_ANSWERED.sum() - before;
        System.out.printf("ColumnGroupSearchTest overworld: %d queries, %d answered per column (both searches), %d walked, %d fills%n", queries.size(), answered,
            FlatClimateTree.GROUP_WALKED.sum(), FlatClimateTree.GROUP_FILLS.sum());
        org.junit.jupiter.api.Assertions.assertTrue(answered > queries.size(), "the column answers ran");
    }

    @Test
    void tiedRandomTreesMatchTheWalk() {
        Random r = new Random(11);
        for (int tree = 0; tree < 40; tree++) {
            int size = 2 + r.nextInt(tree < 20 ? 12 : 400);
            int biomes = 1 + r.nextInt(6);
            List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>();
            long[] depthLo = { 0, 10000, 2000, 11000, -5000 }, depthHi = { 0, 10000, 9000, 11000, 5000 };
            int groups = 1 + r.nextInt(depthLo.length);
            for (int i = 0; i < size; i++) {
                Climate.Parameter[] p = new Climate.Parameter[6];
                for (int d = 0; d < 6; d++) {
                    long a = (r.nextInt(9) - 4) * 2500L;
                    long b = a + r.nextInt(3) * 2500L;
                    p[d] = new Climate.Parameter(a, b);
                }
                int g = r.nextInt(groups);
                p[4] = new Climate.Parameter(depthLo[g], depthHi[g]);
                long offset = r.nextInt(4) == 0 ? (r.nextInt(3) + 1) * 2500L : 0L;
                entries.add(Pair.of(new Climate.ParameterPoint(p[0], p[1], p[2], p[3], p[4], p[5], offset), r.nextInt(biomes)));
            }
            Climate.ParameterList<Integer> list = new Climate.ParameterList<>(entries);
            FlatClimateTree flat = flat(list);
            if (!flat.hasSubtrees()) {
                continue;
            }
            int[] keys = keys(flat, size, id -> (Integer) flat.leaf(id).value + 1);
            List<long[]> queries = new ArrayList<>();
            for (int c = 0; c < 48; c++) {
                long[] column = new long[6];
                for (int d = 0; d < 6; d++) {
                    // mostly coarse values on the leaves' bounds (exact ties). Otherwise anywhere near them
                    column[d] = r.nextInt(3) > 0 ? (r.nextInt(11) - 5) * 1250L : (long) (r.nextGaussian() * 9000);
                }
                for (int y = 0; y < 96; y++) {
                    long depth = switch (r.nextInt(4)) {
                        case 0 -> (r.nextInt(13) - 1) * 1000L;
                        case 1 -> 5000L; // equidistant from depth 0 and 10000
                        default -> (long) (r.nextGaussian() * 6000 + 4000);
                    };
                    queries.add(new long[] { column[0], column[1], column[2], column[3], depth, column[5] });
                }
            }
            // interleave the columns as the biome fill does (16 at a time)
            List<long[]> filled = new ArrayList<>();
            for (int block = 0; block < 48; block += 16) {
                for (int y = 0; y < 96; y++) {
                    for (int c = block; c < block + 16; c++) {
                        filled.add(queries.get(c * 96 + y));
                    }
                }
            }
            compare(flat, keys, filled, new Random(tree));
        }
        FlatClimateTree.flushGroupCounts();
        System.out.printf("ColumnGroupSearchTest random trees: %d answered per column, %d walked, %d fills in all%n", FlatClimateTree.GROUP_ANSWERED.sum(),
            FlatClimateTree.GROUP_WALKED.sum(), FlatClimateTree.GROUP_FILLS.sum());
    }

    /**
     * Several trees asked in turn on one thread for each quart, as with a modpack's region trees, Biolith's and another
     * source's. Every tree keeps its own answers and last results. A column slot that held one tree's column and then another tree's
     * column is never read as the first tree's column again.
     */
    @Test
    void interleavedTreesMatchTheWalk() {
        Random r = new Random(23);
        List<FlatClimateTree> trees = new ArrayList<>();
        List<int[]> keys = new ArrayList<>();
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> overworld = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        FlatClimateTree vanilla = flat(new Climate.ParameterList<>(overworld));
        trees.add(vanilla);
        keys.add(keys(vanilla, 0, id -> ((Holder<?>) vanilla.leaf(id).value).unwrapKey().orElseThrow().location().hashCode()));
        while (trees.size() < 4) {
            List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>();
            int size = 50 + r.nextInt(2000);
            for (int i = 0; i < size; i++) {
                Climate.Parameter[] p = new Climate.Parameter[6];
                for (int d = 0; d < 6; d++) {
                    long a = (r.nextInt(17) - 8) * 1250L;
                    p[d] = new Climate.Parameter(a, a + r.nextInt(4) * 1250L);
                }
                long depth = new long[] { 0, 10000, 2000 }[r.nextInt(3)];
                p[4] = new Climate.Parameter(depth, depth == 2000 ? 9000 : depth);
                entries.add(Pair.of(new Climate.ParameterPoint(p[0], p[1], p[2], p[3], p[4], p[5], 0L), r.nextInt(30)));
            }
            FlatClimateTree t = flat(new Climate.ParameterList<>(entries));
            if (t.hasSubtrees()) {
                trees.add(t);
                keys.add(keys(t, 0, id -> (Integer) t.leaf(id).value + 1));
            }
        }
        FlatClimateTree.enterFill();
        int n = trees.size();
        int[] last = new int[n], u = new int[n], p = new int[n];
        java.util.Arrays.fill(last, -1);
        java.util.Arrays.fill(u, -1);
        java.util.Arrays.fill(p, -1);
        int[] ids = new int[2], ids2 = new int[2];
        long[] distance = new long[2], distance2 = new long[2];
        // many more columns than the thread's slots, every one asked by every tree: slots move between trees all the time
        for (int chunk = 0; chunk < 24; chunk++) {
            long[][] columns = new long[200][6];
            for (long[] c : columns) {
                for (int d = 0; d < 6; d++) {
                    c[d] = r.nextInt(3) == 0 ? (r.nextInt(17) - 8) * 1250L : (long) (r.nextGaussian() * 7000);
                }
            }
            // each tree asks a column several times in a row (so the column answers are used, not paused), then the next tree
            for (long[] c : columns) {
                for (int k = 0; k < n; k++) {
                    int t = (int) Math.floorMod(c[2] + k + chunk, (long) n);
                    for (int y = 0; y < 6; y++) {
                        long depth = r.nextInt(5) == 0 ? (r.nextInt(12) - 1) * 1000L : (long) (r.nextGaussian() * 6000 + 4000);
                        FlatClimateTree tree = trees.get(t);
                        int expected = tree.search(c[0], c[1], c[2], c[3], depth, c[5], last[t]);
                        int actual = tree.searchColumns(c[0], c[1], c[2], c[3], depth, c[5], last[t]);
                        assertEquals(expected, actual, "tree " + t + " nearest");
                        last[t] = expected;
                        tree.searchFittest(c[0], c[1], c[2], c[3], depth, c[5], keys.get(t), u[t], p[t], ids, distance);
                        tree.searchFittestColumns(c[0], c[1], c[2], c[3], depth, c[5], keys.get(t), u[t], p[t], ids2, distance2);
                        assertEquals(ids[0], ids2[0], "tree " + t + " Biolith's nearest");
                        assertEquals(ids[1], ids2[1], "tree " + t + " Biolith's other");
                        assertEquals(distance[0], distance2[0]);
                        assertEquals(distance[1], distance2[1]);
                        u[t] = ids[0];
                        p[t] = ids[1];
                    }
                }
            }
        }
        FlatClimateTree.exitFill();
    }

    private static <T> FlatClimateTree flat(Climate.ParameterList<T> list) {
        return ((Ga2ClimateTree) (Object) list.index).ga2$flat();
    }

    private static int[] keys(FlatClimateTree flat, int ignored, java.util.function.IntUnaryOperator key) {
        int[] keys = new int[flat.nodes() + 1];
        for (int id = 0; id < flat.nodes(); id++) {
            if (flat.leaf(id) != null) {
                keys[id] = key.applyAsInt(id);
            }
        }
        keys[flat.nodes()] = 0;
        return keys;
    }

    /** Every query through both searches from the same last answers: the walk's chain, restarted now and then from other leaves. */
    private static void compare(FlatClimateTree flat, int[] keys, List<long[]> queries, Random r) {
        FlatClimateTree.enterFill();
        try {
            compareInFill(flat, keys, queries, r);
        } finally {
            FlatClimateTree.exitFill();
        }
    }

    private static void compareInFill(FlatClimateTree flat, int[] keys, List<long[]> queries, Random r) {
        List<Integer> leaves = new ArrayList<>();
        for (int id = 0; id < flat.nodes(); id++) {
            if (flat.leaf(id) != null) {
                leaves.add(id);
            }
        }
        int last = -1, u = -1, p = -1;
        int[] ids = new int[2], ids2 = new int[2];
        long[] distance = new long[2], distance2 = new long[2];
        int n = 0;
        for (long[] q : queries) {
            if (r.nextInt(50) == 0) {
                last = r.nextInt(4) == 0 ? -1 : leaves.get(r.nextInt(leaves.size()));
            }
            int expected = flat.search(q[0], q[1], q[2], q[3], q[4], q[5], last);
            int actual = flat.searchColumns(q[0], q[1], q[2], q[3], q[4], q[5], last);
            assertEquals(expected, actual, "nearest leaf at query " + n + " " + java.util.Arrays.toString(q) + " from " + last);
            last = expected;
            flat.searchFittest(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids, distance);
            flat.searchFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, u, p, ids2, distance2);
            assertEquals(ids[0], ids2[0], "Biolith's nearest at query " + n);
            assertEquals(ids[1], ids2[1], "Biolith's other at query " + n + " " + java.util.Arrays.toString(q) + " from " + u + ", " + p);
            assertEquals(distance[0], distance2[0], "Biolith's nearest distance at query " + n);
            assertEquals(distance[1], distance2[1], "Biolith's other distance at query " + n);
            u = ids[0];
            p = ids[1];
            if (r.nextInt(50) == 0) {
                // Biolith's last answers as another chain left them: two leaves of different biomes, one, or none
                u = r.nextInt(5) == 0 ? -1 : leaves.get(r.nextInt(leaves.size()));
                p = -1;
                if (u >= 0 && r.nextBoolean()) {
                    int other = leaves.get(r.nextInt(leaves.size()));
                    p = keys[other] != keys[u] ? other : -1;
                }
            }
            n++;
        }
    }
}
