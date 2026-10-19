package dev.denismasterherobrine.generatoraccelerator2.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Test;

/**
 * The flat climate search returns vanilla's leaf for every target and every last result, including exact
 * distance ties (where vanilla's answer depends on the last result) and overflowing inputs.
 */
public class ClimateSearchTest {
    @Test
    void overworldMatchesVanilla() {
        compare(new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, HarnessWorld.registries().biomes.asLookup()).parameters(), 600_000);
    }

    @Test
    void netherMatchesVanilla() {
        compare(new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.NETHER, HarnessWorld.registries().biomes.asLookup()).parameters(), 200_000);
    }

    @Test
    void tinyListsMatchVanilla() {
        Random r = new Random(5);
        for (int size = 1; size <= 9; size++) {
            List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                entries.add(Pair.of(randomPoint(r), i));
            }
            compare(new Climate.ParameterList<>(entries), 20_000);
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

    private static <T> void compare(Climate.ParameterList<T> list, int perMode) {
        Climate.RTree<T> tree = list.index;
        List<Climate.RTree.Leaf<T>> leaves = new ArrayList<>();
        collect(tree.root, leaves);
        Random r = new Random(1234);
        long[] q = new long[6];
        Climate.RTree.Leaf<T> vanillaLast = null;
        Climate.RTree.Leaf<T> flatLast = null;
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
                    // restart both chains from the same random last result (or none)
                    Climate.RTree.Leaf<T> start = r.nextInt(5) == 0 ? null : leaves.get(r.nextInt(leaves.size()));
                    vanillaLast = start;
                    flatLast = start;
                }
                tree.lastResult.set(vanillaLast);
                T expected = ClimateSearch.vanilla(() -> list.findValue(target));
                vanillaLast = tree.lastResult.get();
                tree.lastResult.set(flatLast);
                T actual = list.findValue(target);
                flatLast = tree.lastResult.get();
                assertSame(vanillaLast, flatLast, "leaf for mode " + mode + " query " + it);
                assertSame(expected, actual, "value for mode " + mode + " query " + it);
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
