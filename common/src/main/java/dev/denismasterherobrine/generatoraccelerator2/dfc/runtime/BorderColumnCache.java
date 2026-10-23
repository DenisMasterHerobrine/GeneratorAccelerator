package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.memory.IdleTrims;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shares computed cell-corner columns between neighbouring chunks. Every chunk fills a
 * (cellCountXZ + 1)^2 grid of columns but only cellCountXZ^2 are unique to it, so up to a third
 * of corner work is a repeat of a neighbour's. Column values depend only on the world position
 * and the router, never on the chunk. This holds as long as no old-chunk blending is active and no
 * order-sensitive cache marker is in play. Callers check that before using the cache.
 *
 * <p>Entries are immutable snapshots in a direct-mapped table. A column replaces whatever shared its
 * slot. Generation proceeds in rings, so neighbours are filled close together in time, and a recent
 * column is what a neighbour asks for. A snapshot is one array: every root's corners, root after root,
 * then the snapshot's key. The key takes {@link #KEY} values, so a slot holds the array alone. Only
 * columns on a chunk border are shared ({@link #border}). No other chunk's columns meet the rest.
 * The table is sized from a byte budget once the first column shows how large a column is. Routers
 * with many interpolated roots, or tall worlds, get fewer slots. The table never holds more than that.
 */
public final class BorderColumnCache implements IdleTrims.Trimmable {
    /** Bytes of column values one cache may hold. */
    static final long BUDGET = 16L << 20;
    private static final int MIN_SLOTS = 256;
    /** Values at a snapshot's end that hold the key: the key's high int, then its low int. */
    public static final int KEY = 2;

    private final int maxSlots;
    private volatile AtomicReferenceArray<double[]> table;
    public final LongAdder hits = new LongAdder();
    public final LongAdder misses = new LongAdder();
    /** {@link System#nanoTime} of the last put. A cache that went without puts for {@link IdleTrims#IDLE_NANOS} drops its table. */
    private volatile long lastPut;

    /** @param maxSlots upper bound on the slots (rounded down to a power of two) */
    public BorderColumnCache(int maxSlots) {
        this.maxSlots = Integer.highestOneBit(Math.max(MIN_SLOTS, maxSlots));
        IdleTrims.register(this);
    }

    /** Whether the column at block {@code x, z} (a cell corner) lies on a chunk border, where neighbouring chunks share it. */
    public static boolean border(int x, int z) {
        return (x & 15) == 0 || (z & 15) == 0;
    }

    /** Cell coordinates are packed with the vertical layout so different height ranges never collide. */
    public static long key(int cellX, int cellZ, int cellNoiseMinY, int cellCountY) {
        long k = ((long) cellX & 0xFFFFFFL) | (((long) cellZ & 0xFFFFFFL) << 24);
        k ^= ((long) (cellNoiseMinY & 0xFF) << 48) ^ ((long) (cellCountY & 0xFF) << 56);
        return k;
    }

    public double[] get(long key) {
        AtomicReferenceArray<double[]> t = this.table;
        if (t != null) {
            double[] v = t.getAcquire(slot(key, t.length()));
            if (v != null && keyOf(v) == key) {
                this.hits.increment();
                return v;
            }
        }
        this.misses.increment();
        return null;
    }

    /** Publishes {@code snapshot} under {@code key}. It holds the columns' values, then {@link #KEY} values this writes. */
    public void put(long key, double[] snapshot) {
        if (MemoryPressure.high()) {
            return;
        }
        int n = snapshot.length;
        snapshot[n - 2] = (int) (key >>> 32);
        snapshot[n - 1] = (int) key;
        AtomicReferenceArray<double[]> t = this.table;
        if (t == null) {
            t = create(snapshot);
        }
        this.lastPut = System.nanoTime();
        // release: a reader that acquires the array sees every value written above
        t.setRelease(slot(key, t.length()), snapshot);
    }

    private static long keyOf(double[] v) {
        int n = v.length;
        return (long) (int) v[n - 2] << 32 | (int) v[n - 1] & 0xFFFFFFFFL;
    }

    private synchronized AtomicReferenceArray<double[]> create(double[] sample) {
        AtomicReferenceArray<double[]> t = this.table;
        if (t == null) {
            long bytes = 16L + 8L * sample.length;
            long slots = Math.max(MIN_SLOTS, Math.min(this.maxSlots, BUDGET / bytes));
            t = new AtomicReferenceArray<>(Integer.highestOneBit((int) slots));
            this.table = t;
        }
        return t;
    }

    /**
     * Drops the table of a dimension that stopped generating. A reader that still holds the old table reads it as before. The
     * next put makes a new table. Under memory pressure the table goes at once, and puts make none until the pressure is over.
     */
    @Override
    public void trimIdle(long nowNanos, boolean pressure) {
        if (this.table != null && (pressure || nowNanos - this.lastPut > IdleTrims.IDLE_NANOS)) {
            this.table = null;
        }
    }

    private static int slot(long key, int length) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & (length - 1);
    }

    public int entries() {
        AtomicReferenceArray<double[]> t = this.table;
        if (t == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.get(i) != null) {
                n++;
            }
        }
        return n;
    }
}
