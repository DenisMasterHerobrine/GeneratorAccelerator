package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** {@link AreaLocks}: no holder writes a chunk another holder reads, and everything else runs side by side. */
class AreaLocksTest {
    private static AreaLocks fresh() throws Exception {
        java.lang.reflect.Constructor<AreaLocks> c = AreaLocks.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    @Test
    void decorationsFourApartRunTogether() throws Exception {
        AreaLocks locks = fresh();
        AreaLocks.Request a = AreaLocks.features(new ChunkPos(0, 0), 2, 0);
        assertTrue(locks.acquireOrPark(a, () -> {}));
        // b writes x 3..5, a reads x -2..2. a writes x -1..1, b reads x 2..6
        assertTrue(locks.acquireOrPark(AreaLocks.features(new ChunkPos(4, 0), 2, 0), () -> {}), "4 apart: neither writes what the other reads");
        assertTrue(locks.acquireOrPark(AreaLocks.features(new ChunkPos(-4, -4), 2, 0), () -> {}), "diagonally 4 apart");
        boolean[] granted = new boolean[1];
        AreaLocks.Request c = AreaLocks.features(new ChunkPos(0, 3), 2, 0);
        assertFalse(locks.acquireOrPark(c, () -> granted[0] = true), "3 apart: c writes z 2..4, which a reads");
        locks.release(a);
        assertTrue(granted[0], "the release of the conflicting holder grants it");
    }

    @Test
    void guardRadiusOneKeepsOnlyTheWrites() throws Exception {
        AreaLocks locks = fresh();
        assertTrue(locks.acquireOrPark(AreaLocks.features(new ChunkPos(0, 0), 1, 0), () -> {}));
        assertFalse(locks.acquireOrPark(AreaLocks.features(new ChunkPos(2, 0), 1, 0), () -> {}), "their 3x3s share a column");
        assertTrue(locks.acquireOrPark(AreaLocks.features(new ChunkPos(0, 3), 1, 0), () -> {}), "3 apart with radius 1");
    }

    @Test
    void lightWaitsOnlyForWritersOfItsChunk() throws Exception {
        AreaLocks locks = fresh();
        AreaLocks.Request decoration = AreaLocks.features(new ChunkPos(0, 0), 2, 0);
        assertTrue(locks.acquireOrPark(decoration, () -> {}));
        assertTrue(locks.acquireOrPark(AreaLocks.single(new ChunkPos(2, 2), 0), () -> {}), "a chunk the decoration only reads");
        boolean[] granted = new boolean[1];
        assertFalse(locks.acquireOrPark(AreaLocks.single(new ChunkPos(1, 0), 0), () -> granted[0] = true), "a chunk the decoration writes");
        AreaLocks.Request lit = AreaLocks.single(new ChunkPos(5, 0), 0);
        assertTrue(locks.acquireOrPark(lit, () -> {}));
        assertFalse(locks.acquireOrPark(AreaLocks.features(new ChunkPos(4, 0), 2, 0), () -> {}), "a decoration that would write a chunk being scanned");
        locks.release(decoration);
        assertTrue(granted[0]);
    }

    private record Held(AreaLocks.Request request, int x, int z, int readRadius, int writeRadius) {}

    @Test
    void randomRequestsNeverReadWhatAnotherWrites() throws Exception {
        AreaLocks locks = fresh();
        Random random = new Random(42);
        List<Held> holding = new ArrayList<>();
        List<Held> granted = new ArrayList<>();
        int requests = 0;
        for (int round = 0; round < 20000; round++) {
            if (!holding.isEmpty() && random.nextInt(3) == 0) {
                Held h = holding.remove(random.nextInt(holding.size()));
                locks.release(h.request());
            } else {
                int x = random.nextInt(24);
                int z = random.nextInt(24);
                boolean light = random.nextInt(4) == 0;
                AreaLocks.Request r = light ? AreaLocks.single(new ChunkPos(x, z), 0) : AreaLocks.features(new ChunkPos(x, z), 2, random.nextInt(3));
                Held h = new Held(r, x, z, light ? 0 : 2, light ? -1 : 1);
                requests++;
                if (locks.acquireOrPark(r, () -> granted.add(h))) {
                    granted.add(h);
                }
            }
            holding.addAll(granted);
            granted.clear();
            // every holder pair: neither writes what the other reads
            for (int i = 0; i < holding.size(); i++) {
                for (int j = 0; j < holding.size(); j++) {
                    if (i != j) {
                        Held a = holding.get(i);
                        Held b = holding.get(j);
                        if (b.writeRadius() >= 0) {
                            int d = Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
                            assertTrue(d > a.readRadius() + b.writeRadius(), "holder at " + b.x() + "," + b.z() + " writes what " + a.x() + "," + a.z() + " reads");
                        }
                    }
                }
            }
        }
        while (!holding.isEmpty()) {
            Held h = holding.remove(0);
            locks.release(h.request());
            holding.addAll(granted);
            granted.clear();
        }
        assertTrue(requests > 1000);
        assertEquals(0, locks.counts()[1], "nothing left parked once every holder released");
        assertEquals(0, locks.counts()[0]);
    }
}
