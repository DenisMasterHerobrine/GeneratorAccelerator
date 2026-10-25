package dev.denismasterherobrine.generatoraccelerator2.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

/**
 * A light snapshot holds exactly what a clone of the working map holds, after every publish. Random batches replace, add
 * and remove sections across areas, columns and section heights. Negative coordinates and columns that grow up and down
 * are included. Each change is recorded as vanilla records it. Every snapshot is compared with a clone right after it is
 * built, and again after later batches, since a published snapshot must never change. Some snapshots are filled (a use
 * other than a lookup) before the next one is built from them.
 */
public class LightSnapshotTest {
    private static final int KEEP = 8;
    /** The light sections of an overworld. Sections outside them widen the areas. */
    private static final int MIN_Y = -5;
    private static final int HEIGHT = 26;

    @Test
    void matchesClones() {
        Random random = new Random(42);
        Long2ObjectOpenHashMap<DataLayer> working = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet changed = new LongOpenHashSet();
        LongOpenHashSet probes = new LongOpenHashSet();
        Deque<LightSnapshot> snapshots = new ArrayDeque<>();
        Deque<Long2ObjectOpenHashMap<DataLayer>> clones = new ArrayDeque<>();
        LightSnapshot published = null;
        long deltas = LightSnapshot.DELTAS.sum();
        long full = LightSnapshot.FULL.sum();
        for (int round = 0; round < 600; round++) {
            int ops = round % 50 == 0 ? 3000 : random.nextInt(80);
            for (int i = 0; i < ops; i++) {
                long key = key(random);
                probes.add(key);
                if (random.nextInt(4) == 0) {
                    working.remove(key);
                } else {
                    working.put(key, new DataLayer());
                }
                changed.add(key);
            }
            // recorded sections that did not change: one that still holds its layer, one that was never there
            if (!working.isEmpty() && random.nextBoolean()) {
                changed.add(working.keySet().iterator().nextLong());
            }
            long absent = key(random);
            if (!working.containsKey(absent)) {
                changed.add(absent);
                probes.add(absent);
            }
            if (changed.isEmpty()) {
                continue;
            }
            LightSnapshot next = LightSnapshot.next(round % 97 == 13 ? null : published, working, changed, MIN_Y, HEIGHT);
            changed.clear();
            Long2ObjectOpenHashMap<DataLayer> clone = working.clone();
            same(clone, next, probes, "round " + round);
            if (random.nextInt(10) == 0) {
                // a use other than a lookup fills the map. It must still hold the clone's sections.
                assertEquals(clone.size(), next.long2ObjectEntrySet().size(), "round " + round + ": filled entries");
                for (Long2ObjectMap.Entry<DataLayer> e : Long2ObjectMaps.fastIterable(next)) {
                    if (clone.get(e.getLongKey()) != e.getValue()) {
                        fail("round " + round + ": filled entry " + SectionPos.of(e.getLongKey()));
                    }
                }
                same(clone, next, probes, "round " + round + " filled");
            }
            snapshots.addLast(next);
            clones.addLast(clone);
            if (snapshots.size() > KEEP) {
                snapshots.removeFirst();
                clones.removeFirst();
            }
            if (round % 7 == 0) {
                var s = snapshots.iterator();
                var c = clones.iterator();
                int age = snapshots.size();
                while (s.hasNext()) {
                    same(c.next(), s.next(), probes, "round " + round + ", snapshot from " + --age + " publishes before");
                }
            }
            published = next;
        }
        assertTrue(LightSnapshot.DELTAS.sum() - deltas > 500, "snapshots built from the changed sections");
        assertTrue(LightSnapshot.FULL.sum() - full >= 6, "snapshots built from the whole map");

        // everything removed
        LongArrayList keys = new LongArrayList(working.keySet());
        for (int i = 0; i < keys.size(); i++) {
            working.remove(keys.getLong(i));
            changed.add(keys.getLong(i));
        }
        LightSnapshot empty = LightSnapshot.next(published, working, changed, MIN_Y, HEIGHT);
        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
        for (LongIterator it = probes.iterator(); it.hasNext(); ) {
            assertNull(empty.get(it.nextLong()));
        }
    }

    /** Sections near the origin, across areas, with heights mostly in an overworld's range and some far outside it. */
    private static long key(Random random) {
        int x = random.nextInt(29) - 14;
        int z = random.nextInt(29) - 14;
        int y = random.nextInt(8) == 0 ? random.nextInt(120) - 60 : random.nextInt(26) - 5;
        return SectionPos.asLong(x, y, z);
    }

    private static void same(Long2ObjectOpenHashMap<DataLayer> clone, LightSnapshot snapshot, LongOpenHashSet probes, String where) {
        assertEquals(clone.size(), snapshot.size(), where + ": sections");
        assertEquals(clone.isEmpty(), snapshot.isEmpty(), where + ": empty");
        for (LongIterator it = probes.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (clone.get(key) != snapshot.get(key) || clone.containsKey(key) != snapshot.containsKey(key)) {
                fail(where + ": section " + SectionPos.of(key) + " is " + snapshot.get(key) + ", the clone holds " + clone.get(key));
            }
        }
    }
}
