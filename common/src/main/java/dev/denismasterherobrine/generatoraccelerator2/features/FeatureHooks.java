package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global on/off switch for every feature-stage hook, with a thread-local override used by the tests
 * (reference runs) and by diagnostics. Mirrors {@code dfc.runtime.DfcHooks}.
 */
public final class FeatureHooks {
    private FeatureHooks() {}

    private static final ThreadLocal<Boolean> DISABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Threads inside {@link #runVanilla}. While there are none, the override is not looked up (see {@code DfcHooks}). */
    private static final AtomicInteger OVERRIDES = new AtomicInteger();

    public static boolean enabled() {
        return (OVERRIDES.get() == 0 || !DISABLED.get()) && FeaturesConfig.enabled();
    }

    /** Runs {@code r} with every feature hook disabled on this thread (the vanilla path executes). */
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
