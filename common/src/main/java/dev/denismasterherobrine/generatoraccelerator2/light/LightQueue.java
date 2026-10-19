package dev.denismasterherobrine.generatoraccelerator2.light;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.NoSuchElementException;

/**
 * The light engine's increase and decrease queues ({@code LightEngine}'s two {@code LongArrayFIFOQueue}s) without fastutil's shrinking.
 * The fastutil queue halves its array whenever it falls to a quarter full. A light batch grew the array from a few entries to tens of
 * thousands and shrank it back down while draining. Every step copied the array, on the light thread, for every batch. This queue keeps
 * its array while it drains, up to {@link #KEEP} longs. A larger array goes back to the initial size once the queue is empty. The
 * entries and their order are the same. Only the timing of array reallocation differs.
 */
public final class LightQueue extends LongArrayFIFOQueue {
    /** Longs an emptied queue keeps (two per entry), i.e. 2 MB. */
    static final int KEEP = 1 << 18;

    @Override
    public long dequeueLong() {
        if (this.start == this.end) {
            throw new NoSuchElementException();
        }
        long t = this.array[this.start];
        if (++this.start == this.length) {
            this.start = 0;
        }
        if (this.start == this.end && this.length > KEEP) {
            this.array = new long[INITIAL_CAPACITY];
            this.length = INITIAL_CAPACITY;
            this.start = this.end = 0;
        }
        return t;
    }
}
