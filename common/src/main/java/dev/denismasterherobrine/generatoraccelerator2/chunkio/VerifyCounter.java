package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * An in-run check of one fast path against vanilla's answer. {@code -Dgeneratoraccelerator2.verify.<name>=N} samples every
 * N-th case. The first differences are logged with the expected value. A running count is logged at the first check and
 * then at every power of ten.
 */
public final class VerifyCounter {
    private static final Logger LOGGER = Ga2Log.get("Verify");

    private final String name;
    private final int every;
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();
    private final AtomicLong differing = new AtomicLong();

    public VerifyCounter(String name) {
        this.name = name;
        this.every = Integer.getInteger("generatoraccelerator2.verify." + name, 0);
    }

    public boolean enabled() {
        return every > 0;
    }

    /** True for the cases to check. */
    public boolean sample() {
        return every > 0 && seen.incrementAndGet() % every == 0;
    }

    public void result(boolean same, Object where, Object expected, Object got) {
        long c = checked.incrementAndGet();
        if (!same && differing.incrementAndGet() <= 10) {
            LOGGER.warn("{} mismatch at {}: expected {}, got {}", name, where, expected, got);
        }
        long p = 1;
        while (p < c) {
            p *= 10;
        }
        if (p == c) {
            LOGGER.info("{}: {} checked, {} mismatched", name, c, differing.get());
        }
    }
}
