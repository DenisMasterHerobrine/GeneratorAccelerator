package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** A latency (or duration) counter for {@code /ga2 gpu}: count, total and largest value, in nanoseconds. */
public final class Lat {
    private final LongAdder count = new LongAdder();
    private final LongAdder total = new LongAdder();
    private final AtomicLong max = new AtomicLong();

    public void add(long nanos) {
        count.increment();
        total.add(nanos);
        long m = max.get();
        while (nanos > m && !max.compareAndSet(m, nanos)) {
            m = max.get();
        }
    }

    /** Adds the time since {@code since} ({@code System.nanoTime()}). */
    public void since(long since) {
        add(System.nanoTime() - since);
    }

    public long count() {
        return count.sum();
    }

    public void reset() {
        count.reset();
        total.reset();
        max.set(0);
    }

    /** "avg A ms (max B)" with B in ms, or "-" when nothing was counted. */
    public String describe() {
        long n = count.sum();
        if (n == 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "avg %.2f ms (max %.0f)", total.sum() / 1e6 / n, max.get() / 1e6);
    }
}
