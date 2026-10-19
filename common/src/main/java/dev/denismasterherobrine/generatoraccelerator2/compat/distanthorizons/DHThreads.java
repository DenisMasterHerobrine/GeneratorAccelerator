package dev.denismasterherobrine.generatoraccelerator2.compat.distanthorizons;

import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;

/**
 * Distant Horizons' world generator threads. DH runs the generator's steps itself on these threads, outside the chunk map. DH
 * waits for each step's future on the same thread. DH makes {@code Util.backgroundExecutor()} run tasks on the calling thread.
 * DH logs a step future that is not done when the step returns as unsafe threading. GA2's work for such a step also runs on the
 * calling thread.
 */
public final class DHThreads {
    private DHThreads() {}

    public static final String MOD_ID = "distanthorizons";
    private static final boolean LOADED = Compat.isLoaded(MOD_ID);

    /** Same check as {@code DhApi.isDhThread()}. DH names its threads "DH-...". */
    public static boolean onDhThread() {
        return LOADED && Thread.currentThread().getName().startsWith("DH-");
    }
}
