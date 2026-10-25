package dev.denismasterherobrine.generatoraccelerator2.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Time to publish one light layer at the end of a batch: vanilla's clone of the whole working map against a snapshot built
 * from the changed sections ({@link LightSnapshot}). The working map holds 20 sections per chunk column over a square of
 * loaded chunks. A batch lights a few new chunks next to each other, as a pregeneration's light batches do: their sections
 * and a few sections of each neighbour change. Also times a lookup in each published map.
 * {@code gradlew :fabric:test --tests "*LightSnapshotBench*" -Dga2.bench=snapshot}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "snapshot")
public class LightSnapshotBench {
    private static final int SECTIONS_PER_COLUMN = 20;
    private static final int ROUNDS = 400;

    @Test
    void publish() {
        for (int side : new int[] { 36, 61, 87 }) {
            for (int chunksPerBatch : new int[] { 2, 6 }) {
                bench(side, chunksPerBatch);
            }
        }
    }

    private static void bench(int side, int chunksPerBatch) {
        Long2ObjectOpenHashMap<DataLayer> working = new Long2ObjectOpenHashMap<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                for (int y = -5; y < SECTIONS_PER_COLUMN - 5; y++) {
                    working.put(SectionPos.asLong(x, y, z), new DataLayer());
                }
            }
        }
        Random random = new Random(side * 31L + chunksPerBatch);
        LongOpenHashSet changed = new LongOpenHashSet();
        LightSnapshot published = LightSnapshot.next(null, working, changed, -5, SECTIONS_PER_COLUMN);
        long[] clone = new long[ROUNDS];
        long[] snapshot = new long[ROUNDS];
        long sink = 0;
        for (int round = 0; round < ROUNDS; round++) {
            changed.clear();
            int cx = random.nextInt(side - chunksPerBatch - 2) + 1;
            int cz = random.nextInt(side - 2) + 1;
            for (int i = 0; i < chunksPerBatch; i++) {
                for (int y = -5; y < SECTIONS_PER_COLUMN - 5; y++) {
                    change(working, changed, SectionPos.asLong(cx + i, y, cz));
                }
                // light reaching into the neighbours' upper sections
                for (int y = 4; y < 8; y++) {
                    change(working, changed, SectionPos.asLong(cx + i, y, cz - 1));
                    change(working, changed, SectionPos.asLong(cx + i, y, cz + 1));
                }
            }
            long t0 = System.nanoTime();
            Long2ObjectOpenHashMap<DataLayer> copy = working.clone();
            long t1 = System.nanoTime();
            LightSnapshot next = LightSnapshot.next(published, working, changed, -5, SECTIONS_PER_COLUMN);
            long t2 = System.nanoTime();
            clone[round] = t1 - t0;
            snapshot[round] = t2 - t1;
            sink += copy.size() + next.size();
            published = next;
        }
        int columns = side * side;
        long[] walk = new long[2_000_000];
        long[] scattered = new long[2_000_000];
        long[] keys = working.keySet().toLongArray();
        for (int i = 0; i < walk.length; i++) {
            int column = i / SECTIONS_PER_COLUMN % columns;
            walk[i] = SectionPos.asLong(column % side, i % SECTIONS_PER_COLUMN - 5, column / side);
            scattered[i] = keys[random.nextInt(keys.length)];
        }
        double[] walked = lookups(working, published, walk);
        double[] spread = lookups(working, published, scattered);
        Arrays.sort(clone);
        Arrays.sort(snapshot);
        System.out.println(String.format(Locale.ROOT,
            "light snapshot bench: %,7d sections, %d chunks per batch (%,d changed): clone %6.1f us, snapshot %5.1f us (medians);"
                + " lookup ns, plain map / snapshot: column by column %.1f / %.1f, scattered %.1f / %.1f",
            working.size(), chunksPerBatch, changed.size(), clone[ROUNDS / 2] / 1e3, snapshot[ROUNDS / 2] / 1e3, walked[0], walked[1], spread[0], spread[1]));
        if (sink == 42) {
            System.out.println();
        }
    }

    private static void change(Long2ObjectOpenHashMap<DataLayer> working, LongOpenHashSet changed, long key) {
        working.put(key, new DataLayer());
        changed.add(key);
    }

    /** Nanoseconds per lookup of {@code keys} in a plain map and in the snapshot, the best of five passes. */
    private static double[] lookups(Long2ObjectOpenHashMap<DataLayer> working, LightSnapshot snapshot, long[] keys) {
        long best0 = Long.MAX_VALUE;
        long best1 = Long.MAX_VALUE;
        long sink = 0;
        for (int pass = 0; pass < 5; pass++) {
            long t0 = System.nanoTime();
            for (long key : keys) {
                sink += working.get(key) != null ? 1 : 0;
            }
            long t1 = System.nanoTime();
            for (long key : keys) {
                sink += snapshot.get(key) != null ? 1 : 0;
            }
            long t2 = System.nanoTime();
            best0 = Math.min(best0, t1 - t0);
            best1 = Math.min(best1, t2 - t1);
        }
        if (sink == 42) {
            System.out.println();
        }
        return new double[] { (double) best0 / keys.length, (double) best1 / keys.length };
    }
}
