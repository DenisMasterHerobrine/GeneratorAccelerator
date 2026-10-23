package dev.denismasterherobrine.generatoraccelerator2.memory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Caches that let go of their memory once nothing used them for a while. A dimension a player left keeps its caches until the
 * server stops. In a pack with many dimensions that adds up. Every {@link #PERIOD_TICKS} server ticks each registered cache
 * is asked to drop what it holds if it is idle. A cache fills again on its next use.
 */
public final class IdleTrims {
    private IdleTrims() {}

    /**
     * A cache that can drop its contents. Called on the server thread, or on a collector's notification thread when the heap
     * fills ({@link MemoryPressure}). Other threads may still be using the cache.
     */
    public interface Trimmable {
        /** @param pressure the heap is nearly full: drop the contents even if the cache is in use */
        void trimIdle(long nowNanos, boolean pressure);
    }

    static final int PERIOD_TICKS = 200;
    /** Caches unused this long drop their contents. */
    public static final long IDLE_NANOS = 30_000_000_000L;
    /** Weakly held. A cache that is gone needs no trim. */
    private static final Set<Trimmable> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static int ticks;

    public static void register(Trimmable cache) {
        ALL.add(cache);
    }

    /** At the end of every server tick. */
    public static void tick() {
        if (++ticks < PERIOD_TICKS) {
            return;
        }
        ticks = 0;
        trim(MemoryPressure.high());
    }

    /** Every cache drops its contents (the heap filled). */
    static void trimAll() {
        trim(true);
    }

    private static void trim(boolean pressure) {
        Trimmable[] caches;
        synchronized (ALL) {
            caches = ALL.toArray(new Trimmable[0]);
        }
        long now = System.nanoTime();
        for (Trimmable cache : caches) {
            cache.trimIdle(now, pressure);
        }
    }
}
