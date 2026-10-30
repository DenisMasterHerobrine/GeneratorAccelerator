package dev.denismasterherobrine.generatoraccelerator2.compat.gtceu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ConcurrentVeinMap} answers as a {@code HashMap} with the same contents does, and a save iterating it while other threads add
 * veins sees no {@code ConcurrentModificationException} (GTCEu's bedrock fluid veins in TerraFirmaGreg).
 */
class ConcurrentVeinMapTest {
    @Test
    void answersAsAHashMap() {
        HashMap<Long, String> plain = new HashMap<>();
        plain.put(1L, "oil");
        HashMap<Long, String> veins = new ConcurrentVeinMap<>(plain);
        assertEquals("oil", veins.get(1L));
        assertTrue(veins.containsKey(1L));
        assertNull(veins.get(null), "a null key is absent, as in a HashMap without one");
        assertFalse(veins.containsKey(null));
        assertNull(veins.put(2L, "gas"));
        assertEquals(2, veins.size());
        assertEquals(Map.of(1L, "oil", 2L, "gas"), veins, "equals compares contents");
        assertEquals(Map.of(1L, "oil", 2L, "gas").hashCode(), veins.hashCode());
        assertEquals("brine", veins.computeIfAbsent(3L, k -> "brine"));
        assertEquals(3, veins.entrySet().size());
        @SuppressWarnings("unchecked")
        HashMap<Long, String> copy = (HashMap<Long, String>) veins.clone();
        veins.remove(1L);
        assertEquals(3, copy.size(), "the clone keeps its own contents");
        assertEquals(2, veins.size());
    }

    @Test
    void iterationWhileOthersAddVeins() throws InterruptedException {
        HashMap<Long, Integer> veins = new ConcurrentVeinMap<>(new HashMap<>());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(4);
        Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            long base = (long) t << 40;
            writers[t] = new Thread(() -> {
                started.countDown();
                // a fixed range of keys, added and taken out again: the map keeps changing without growing past it
                for (long i = 0; !stop.get(); i++) {
                    Long key = base + i % 20_000;
                    if (!veins.containsKey(key)) {
                        veins.put(key, (int) (i % 20_000));
                    } else if ((i & 1) == 0) {
                        veins.remove(key);
                    }
                    veins.get(key);
                }
            });
            writers[t].start();
        }
        started.await();
        try {
            for (int save = 0; save < 50; save++) {
                long sum = 0;
                for (Map.Entry<Long, Integer> e : veins.entrySet()) {
                    sum += e.getValue();
                }
                assertTrue(sum >= 0);
            }
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            stop.set(true);
            for (Thread w : writers) {
                w.join();
            }
        }
        assertNull(failure.get(), "the save's iteration failed");
        assertTrue(veins.size() <= 4 * 20_000);
    }
}
