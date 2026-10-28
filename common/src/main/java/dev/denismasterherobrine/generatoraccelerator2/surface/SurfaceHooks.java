package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.StepVerify;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Switch for the surface hooks with a thread-local override for reference runs, plus counters for
 * {@code /ga2 surface stats}. Mirrors {@code features.FeatureHooks}.
 */
public final class SurfaceHooks {
    private SurfaceHooks() {}

    private static final ThreadLocal<Boolean> DISABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Threads inside {@link #runLoopRules}: GA2's builder off, the compiled rules in vanilla's loop on. */
    private static final ThreadLocal<Boolean> BUILDER_OFF = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Threads inside {@link #runVanilla} or {@link #runLoopRules}. While there are none, the overrides are not looked up (see {@code DfcHooks}). */
    private static final AtomicInteger OVERRIDES = new AtomicInteger();
    private static final HookTargets.Hook BUILD = HookTargets.hook(HookTargets.SURFACE_BUILD);
    private static final HookTargets.Hook BIOME_ZOOM = HookTargets.hook(HookTargets.SURFACE_BIOME_ZOOM);
    private static final HookTargets.Hook LOOP_RULES = HookTargets.hook(HookTargets.SURFACE_LOOP_RULES);

    public static final LongAdder CHUNKS_FAST = new LongAdder();
    public static final LongAdder CHUNKS_VANILLA = new LongAdder();
    public static final LongAdder CHUNKS_OPAQUE = new LongAdder();
    /** Chunks built with other mods' column code (their mixins on buildSurface, {@link ColumnHooks}). */
    public static final LongAdder CHUNKS_HOOKED = new LongAdder();
    public static final LongAdder TOP_MATERIAL_FAST = new LongAdder();
    public static final LongAdder SECTIONS_BULK = new LongAdder();
    public static final LongAdder COLUMNS_DEEP = new LongAdder();
    /** {@code -Dgeneratoraccelerator2.verify.surface=N}: every N-th chunk GA2 builds is checked against vanilla on a copy. */
    public static final StepVerify VERIFY = new StepVerify("surface");

    private static boolean overridden() {
        return OVERRIDES.get() != 0 && DISABLED.get();
    }

    public static boolean enabled() {
        return !overridden() && !(OVERRIDES.get() != 0 && BUILDER_OFF.get()) && SurfaceConfig.enabled() && BUILD.allowed();
    }

    /** Whether vanilla's loop applies the compiled rules ({@link LoopRules}) where it builds a surface. */
    public static boolean loopRulesEnabled() {
        return !overridden() && SurfaceConfig.enabled() && SurfaceConfig.loopRules() && LOOP_RULES.allowed();
    }

    public static boolean zoomAllowed() {
        return BIOME_ZOOM.unchanged();
    }

    /** Runs {@code r} with every surface hook disabled on this thread (the vanilla path executes). */
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

    /** Runs {@code r} with GA2's builder off on this thread: vanilla's loop builds surfaces with the compiled rules. */
    public static void runLoopRules(Runnable r) {
        OVERRIDES.incrementAndGet();
        boolean prev = BUILDER_OFF.get();
        BUILDER_OFF.set(Boolean.TRUE);
        try {
            r.run();
        } finally {
            BUILDER_OFF.set(prev);
            OVERRIDES.decrementAndGet();
        }
    }
}
