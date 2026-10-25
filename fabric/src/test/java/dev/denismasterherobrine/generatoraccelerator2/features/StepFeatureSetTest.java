package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link StepFeatureSet} answers {@code applyBiomeDecoration}'s calls as vanilla's {@code IntArraySet}: the same size and, sorted
 * as vanilla sorts it, the same array, over random add sequences of the sizes large packs reach. And the decoration step makes it.
 */
public class StepFeatureSetTest {
    @Test
    void sameAsArraySet() {
        Random random = new Random(3);
        for (int run = 0; run < 2000; run++) {
            int distinct = 1 + random.nextInt(run % 10 == 0 ? 600 : 60);
            int adds = random.nextInt(distinct * 9 + 1);
            IntSet vanilla = new IntArraySet();
            IntSet fast = new StepFeatureSet();
            for (int i = 0; i < adds; i++) {
                int k = random.nextInt(distinct) - (random.nextInt(50) == 0 ? distinct : 0);
                assertEquals(vanilla.add(k), fast.add(k), "add " + k + ", run " + run);
            }
            assertEquals(vanilla.size(), fast.size(), "size, run " + run);
            int[] a = vanilla.toIntArray();
            int[] b = fast.toIntArray();
            Arrays.sort(a);
            Arrays.sort(b);
            assertArrayEquals(a, b, "sorted array, run " + run);
        }
    }

    @Test
    void decorationStepMakesIt() {
        assertTrue(Arrays.stream(ChunkGenerator.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("ga2$stepSet")),
            "the step set mixin applied to applyBiomeDecoration");
    }

    /** Informational: a step of a large pack (nine biomes of 220 features, 400 distinct) both ways. */
    @Test
    void costOfALargeStep() {
        Random random = new Random(5);
        int[] adds = new int[9 * 220];
        for (int i = 0; i < adds.length; i++) {
            adds[i] = random.nextInt(400);
        }
        long[] best = { Long.MAX_VALUE, Long.MAX_VALUE };
        int sink = 0;
        for (int round = 0; round < 200; round++) {
            for (int way = 0; way < 2; way++) {
                long t0 = System.nanoTime();
                IntSet set = way == 0 ? new IntArraySet() : new StepFeatureSet();
                for (int k : adds) {
                    set.add(k);
                }
                int[] out = set.toIntArray();
                Arrays.sort(out);
                sink += out.length;
                best[way] = Math.min(best[way], System.nanoTime() - t0);
            }
        }
        System.out.println("StepFeatureSetTest: a step of 1,980 adds over 400 features, best of 200: array set " + best[0] / 1000 + " us, hash set "
            + best[1] / 1000 + " us (" + sink + ")");
    }
}
