package dev.denismasterherobrine.generatoraccelerator2.parallellight;

import java.util.NoSuchElementException;

/**
 * A first-in first-out queue of longs that only grows. fastutil's {@code LongArrayFIFOQueue} shrinks its array whenever it falls to a
 * quarter full and grows it again on the next burst. A propagation worker fills and drains its queue once per batch, so that queue
 * reallocated the array over and over. Element order matches {@code LongArrayFIFOQueue}.
 */
final class LongRing {
    private long[] a = new long[1024];
    private int head, size;

    boolean isEmpty() {
        return size == 0;
    }

    int size() {
        return size;
    }

    void enqueue(long v) {
        if (size == a.length) {
            grow();
        }
        a[(head + size) & (a.length - 1)] = v;
        size++;
    }

    void clear() {
        head = 0;
        size = 0;
    }

    /** Resets an empty ring larger than {@code max} longs to its initial size, so the array of a huge batch is not kept. */
    void trim(int max) {
        if (size == 0 && a.length > max) {
            a = new long[1024];
            head = 0;
        }
    }

    long dequeueLong() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        long v = a[head];
        head = (head + 1) & (a.length - 1);
        size--;
        return v;
    }

    private void grow() {
        long[] b = new long[a.length * 2];
        int first = Math.min(size, a.length - head);
        System.arraycopy(a, head, b, 0, first);
        System.arraycopy(a, 0, b, first, size - first);
        a = b;
        head = 0;
    }
}
