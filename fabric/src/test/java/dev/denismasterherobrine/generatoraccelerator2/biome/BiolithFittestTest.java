package dev.denismasterherobrine.generatoraccelerator2.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Test;

/**
 * {@link FlatClimateTree#searchFittest} returns what Biolith's {@code MixinSearchTree.biolith$searchTreeGet} returns. The
 * Biolith method (3.0.14) is transcribed below from its bytecode. The results match for every target and every pair of last
 * answers: the same two leaves, the same distances and the same last answers afterwards. This includes exact distance ties,
 * leaves of the same biome next to each other, and overflowing inputs.
 */
public class BiolithFittestTest {
    @Test
    void overworldMatchesBiolith() {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        compare(new Climate.ParameterList<>(entries), h -> h.unwrapKey().orElseThrow().location(), 400_000);
    }

    @Test
    void netherMatchesBiolith() {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.NETHER,
            HarnessWorld.registries().biomes.asLookup()).parameters().values();
        compare(new Climate.ParameterList<>(entries), h -> h.unwrapKey().orElseThrow().location(), 100_000);
    }

    @Test
    void smallListsWithRepeatedBiomesMatchBiolith() {
        Random r = new Random(7);
        for (int size = 2; size <= 40; size += 3) {
            List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                // few distinct values: neighbouring leaves often share a biome
                entries.add(Pair.of(randomPoint(r), r.nextInt(Math.max(2, size / 4))));
            }
            compare(new Climate.ParameterList<>(entries), v -> v, 20_000);
        }
    }

    private static Climate.ParameterPoint randomPoint(Random r) {
        Climate.Parameter[] p = new Climate.Parameter[6];
        for (int d = 0; d < 6; d++) {
            float a = (r.nextInt(9) - 4) * 0.25f;
            float b = a + r.nextInt(3) * 0.25f;
            p[d] = Climate.Parameter.span(a, b);
        }
        return new Climate.ParameterPoint(p[0], p[1], p[2], p[3], p[4], p[5], r.nextInt(3) == 0 ? Climate.quantizeCoord(r.nextFloat() * 0.5f) : 0L);
    }

    /** Biolith's answer: the two leaves and their distances. */
    private record Fittest<T>(Climate.RTree.Leaf<T> ultimate, long ultimateDistance, Climate.RTree.Leaf<T> penultimate, long penultimateDistance) {}

    /** Vanilla's {@code Node.distance(values)} (Biolith's metric, {@code Node::distance}). */
    private static long distance(Climate.RTree.Node<?> node, long[] values) {
        long sum = 0L;
        for (int d = 0; d < 7; d++) {
            sum += net.minecraft.util.Mth.square(node.parameterSpace[d].distance(values[d]));
        }
        return sum;
    }

    /** The biome key Biolith compares, with its placeholder for no leaf. */
    private static <T> Object keyOf(Climate.RTree.Leaf<T> leaf, Function<T, Object> key) {
        return leaf == null ? "biolith:null" : key.apply(leaf.value);
    }

    /** {@code biolith$searchTreeGet(target, Node::distance)} with the thread's last answers in {@code last} (read and written). */
    @SuppressWarnings("unchecked")
    private static <T> Fittest<T> biolith(Climate.RTree<T> tree, Climate.TargetPoint target, Function<T, Object> key, Climate.RTree.Leaf<T>[] last) {
        long[] q = {target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness(), 0L};
        Climate.RTree.Node<T> node = tree.root;
        Iterator<Climate.RTree.Node<T>>[] stack = new Iterator[64];
        int depth = 0;
        Climate.RTree.Leaf<T> best = last[0];
        Climate.RTree.Leaf<T> second = last[1];
        long du = best != null ? distance(best, q) : Long.MAX_VALUE;
        long dp = second != null ? distance(second, q) : Long.MAX_VALUE;
        if (du > dp) {
            Climate.RTree.Leaf<T> t = best;
            best = second;
            second = t;
            long td = du;
            du = dp;
            dp = td;
        }
        if (node instanceof Climate.RTree.SubTree<T> sub) {
            stack[depth] = Arrays.asList(sub.children).iterator();
        } else if (node instanceof Climate.RTree.Leaf<T> leaf) {
            return new Fittest<>(leaf, distance(leaf, q), null, Long.MAX_VALUE);
        } else {
            return new Fittest<>(best, du, null, Long.MAX_VALUE);
        }
        while (stack[depth].hasNext()) {
            node = stack[depth].next();
            long d = distance(node, q);
            while (node instanceof Climate.RTree.SubTree<T> inner && dp > d) {
                stack[++depth] = Arrays.asList(inner.children).iterator();
                node = stack[depth].next();
                d = distance(node, q);
            }
            if (node instanceof Climate.RTree.Leaf<T> leaf && dp > d) {
                if (du > d) {
                    if (!Objects.equals(keyOf(leaf, key), keyOf(best, key))) {
                        dp = du;
                        second = best;
                    }
                    du = d;
                    best = leaf;
                } else if (!Objects.equals(keyOf(leaf, key), keyOf(best, key))) {
                    dp = d;
                    second = leaf;
                }
            }
            while (depth > 0 && !stack[depth].hasNext()) {
                depth--;
            }
        }
        last[0] = best;
        last[1] = second;
        return second == null ? new Fittest<>(best, du, null, Long.MAX_VALUE) : new Fittest<>(best, du, second, dp);
    }

    @SuppressWarnings("unchecked")
    private static <T> void compare(Climate.ParameterList<T> list, Function<T, Object> key, int perMode) {
        Climate.RTree<T> tree = list.index;
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) tree).ga2$flat();
        List<Climate.RTree.Leaf<T>> leaves = new ArrayList<>();
        collect(tree.root, leaves);
        // biome numbers as BiolithSearch makes them: equal keys get equal numbers. Slot [nodes] is the placeholder for no leaf
        int[] keys = new int[flat.nodes() + 1];
        Map<Object, Integer> numbers = new HashMap<>();
        numbers.put("biolith:null", 0);
        for (int id = 0; id < flat.nodes(); id++) {
            Climate.RTree.Leaf<?> leaf = flat.leaf(id);
            if (leaf != null) {
                keys[id] = numbers.computeIfAbsent(key.apply((T) leaf.value), k -> numbers.size());
            }
        }
        Random r = new Random(4321);
        long[] q = new long[6];
        Climate.RTree.Leaf<T>[] last = new Climate.RTree.Leaf[2];
        int[] flatLast = {-1, -1};
        int[] ids = new int[2];
        long[] distance = new long[2];
        int[] ids2 = new int[2];
        long[] distance2 = new long[2];
        int checked = 0;
        for (int mode = 0; mode < 4; mode++) {
            for (int it = 0; it < perMode; it++) {
                switch (mode) {
                    case 0 -> {
                        for (int d = 0; d < 6; d++) {
                            q[d] = Climate.quantizeCoord((float) (r.nextGaussian() * 0.6));
                        }
                    }
                    case 1 -> {
                        // a coarse grid lands on exact distance ties between leaves
                        for (int d = 0; d < 6; d++) {
                            q[d] = (r.nextInt(9) - 4) * 2500L;
                        }
                    }
                    case 2 -> {
                        // a column walk, like the biome fill of one chunk
                        if (it % 1536 == 0) {
                            for (int d = 0; d < 6; d++) {
                                q[d] = (long) (r.nextGaussian() * 6000);
                            }
                        }
                        for (int d = 0; d < 6; d++) {
                            q[d] += (long) (r.nextGaussian() * 150);
                        }
                        q[4] = (it % 96 < 48) ? 0 : (long) (r.nextFloat() * 12000);
                    }
                    default -> {
                        for (int d = 0; d < 6; d++) {
                            q[d] = r.nextInt(5) == 0
                                ? (r.nextBoolean() ? Long.MAX_VALUE - r.nextInt(3) : Long.MIN_VALUE + r.nextInt(3))
                                : (r.nextInt(3) == 0 ? (long) (r.nextGaussian() * 4e9) : r.nextInt(80001) - 40000);
                        }
                    }
                }
                Climate.TargetPoint target = new Climate.TargetPoint(q[0], q[1], q[2], q[3], q[4], q[5]);
                if (it % 7 == 0) {
                    // restart both chains from the same random last answers (or none)
                    last[0] = r.nextInt(5) == 0 ? null : leaves.get(r.nextInt(leaves.size()));
                    last[1] = r.nextInt(3) == 0 ? null : leaves.get(r.nextInt(leaves.size()));
                    flatLast[0] = last[0] == null ? -1 : ((Ga2ClimateLeaf) (Object) last[0]).ga2$index();
                    flatLast[1] = last[1] == null ? -1 : ((Ga2ClimateLeaf) (Object) last[1]).ga2$index();
                }
                Fittest<T> expected = biolith(tree, target, key, last);
                flat.searchFittest(q[0], q[1], q[2], q[3], q[4], q[5], keys, flatLast[0], flatLast[1], ids, distance);
                // the per-column variant, from the same last answers, must give the same answers
                flat.searchFittestColumns(q[0], q[1], q[2], q[3], q[4], q[5], keys, flatLast[0], flatLast[1], ids2, distance2);
                org.junit.jupiter.api.Assertions.assertArrayEquals(ids, ids2, "column parts ids, mode " + mode + " query " + it);
                org.junit.jupiter.api.Assertions.assertArrayEquals(distance, distance2, "column parts distances, mode " + mode + " query " + it);
                flatLast[0] = ids[0];
                flatLast[1] = ids[1];
                String where = "mode " + mode + " query " + it;
                assertSame(expected.ultimate(), ids[0] < 0 ? null : flat.leaf(ids[0]), "ultimate, " + where);
                assertEquals(expected.ultimateDistance(), distance[0], "ultimate distance, " + where);
                assertSame(expected.penultimate(), ids[1] < 0 ? null : flat.leaf(ids[1]), "penultimate, " + where);
                if (expected.penultimate() != null) {
                    assertEquals(expected.penultimateDistance(), distance[1], "penultimate distance, " + where);
                }
                assertSame(last[0], ids[0] < 0 ? null : flat.leaf(ids[0]), "last ultimate, " + where);
                assertSame(last[1], ids[1] < 0 ? null : flat.leaf(ids[1]), "last penultimate, " + where);
                checked++;
            }
        }
        assertEquals(4 * perMode, checked);
    }

    private static <T> void collect(Climate.RTree.Node<T> node, List<Climate.RTree.Leaf<T>> out) {
        if (node instanceof Climate.RTree.Leaf<T> leaf) {
            out.add(leaf);
        } else if (node instanceof Climate.RTree.SubTree<T> sub) {
            for (Climate.RTree.Node<T> child : sub.children) {
                collect(child, out);
            }
        }
    }
}
