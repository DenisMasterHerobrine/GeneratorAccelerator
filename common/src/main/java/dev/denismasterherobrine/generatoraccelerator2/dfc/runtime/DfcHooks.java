package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.concurrent.atomic.AtomicInteger;

/** Global on/off switch for the NoiseChunk hooks, with a thread-local override used by tests and shadow verification. */
public final class DfcHooks {
    private DfcHooks() {}

    private static final ThreadLocal<Boolean> DISABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /**
     * Number of threads inside {@link #runVanilla}. While it is zero, no thread has the override set, so {@link #enabled}
     * skips the thread-local lookup. {@link #enabled} is called per climate sample and per cell. A thread that sets the
     * override increments this count first, so that thread always performs the lookup.
     */
    private static final AtomicInteger OVERRIDES = new AtomicInteger();

    /** {@code MaterialRuleList.calculate} walked by index (asked for every block of the noise fill). */

    public static boolean enabled() {
        return OVERRIDES.get() == 0 || !DISABLED.get();
    }

    /** Runs {@code r} with every compiled hook disabled on this thread (the vanilla path executes). */
    public static void runVanilla(Runnable r) {
        OVERRIDES.incrementAndGet();
        boolean prev = DISABLED.get();
        DISABLED.set(Boolean.TRUE);
        try {
            r.run();
        } finally {
            DISABLED.set(prev);
            OVERRIDES.decrementAndGet();
        }
    }
}
