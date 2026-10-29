package dev.denismasterherobrine.generatoraccelerator2.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

/**
 * The kept answers ({@code BiomeAnswers}) against the lookup they keep: the same biome for every point, kept points not looked
 * up again, points that share a slot told apart, and points outside the packed range always looked up.
 */
public class BiomeAnswersTest {
    @Test
    void sameAnswersAsTheLookup() {
        List<Holder.Reference<Biome>> biomes = HarnessWorld.registries().biomes.listElements().toList();
        assertTrue(biomes.size() > 10, "too few biomes: " + biomes.size());
        AtomicLong looked = new AtomicLong();
        BiomeAnswers.Lookup truth = (x, y, z) -> {
            looked.incrementAndGet();
            long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
            return biomes.get((int) Math.floorMod(h, (long) biomes.size()));
        };
        BiomeAnswers answers = new BiomeAnswers(truth);
        Random r = new Random(11);
        int[][] points = new int[20_000][];
        for (int i = 0; i < points.length; i++) {
            points[i] = new int[] { r.nextInt(4000) - 2000, r.nextInt(96) - 16, r.nextInt(4000) - 2000 };
        }
        // the far corners of the packed range and just past it
        int far = 1 << 23;
        int[][] edges = { { far - 1, 0, -far }, { -far, 79, far - 1 }, { far, 0, 0 }, { 0, 0, -far - 1 }, { 5, Short.MAX_VALUE + 1, 5 } };
        for (int round = 0; round < 2; round++) {
            for (int[] p : points) {
                assertSame(truth.at(p[0], p[1], p[2]), answers.get(p[0], p[1], p[2]), "quart " + p[0] + " " + p[1] + " " + p[2]);
            }
            for (int[] p : edges) {
                assertSame(truth.at(p[0], p[1], p[2]), answers.get(p[0], p[1], p[2]), "quart " + p[0] + " " + p[1] + " " + p[2]);
            }
        }
        // asking a few points again and again looks each up once
        for (int i = 0; i < 20; i++) {
            answers.get(points[i][0], points[i][1], points[i][2]);
        }
        looked.set(0);
        for (int i = 0; i < 1000; i++) {
            int[] p = points[i % 20];
            answers.get(p[0], p[1], p[2]);
        }
        assertEquals(0, looked.get(), "kept points were looked up again");
    }

    /** A lookup whose climate search had a tie is not kept: the next ask looks it up again. */
    @Test
    void tiedAnswersAreNotKept() {
        List<Holder.Reference<Biome>> biomes = HarnessWorld.registries().biomes.listElements().toList();
        AtomicLong looked = new AtomicLong();
        BiomeAnswers answers = new BiomeAnswers((x, y, z) -> {
            looked.incrementAndGet();
            if (y == 79) {
                SearchTies.unsure(SearchTies.TIE);
            }
            return biomes.get(0);
        });
        answers.get(3, 79, 4);
        answers.get(3, 79, 4);
        assertEquals(2, looked.get(), "a tied answer was kept");
        answers.get(3, 10, 4);
        answers.get(3, 10, 4);
        assertEquals(3, looked.get(), "an answer without a tie was not kept");
        assertTrue(!SearchTies.watching(), "the watch was left on");
    }
}
