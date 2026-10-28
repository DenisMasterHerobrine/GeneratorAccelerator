package dev.denismasterherobrine.generatoraccelerator2.testutil;

/**
 * How much of their sweep the fuzz and matrix tests run. The default run checks a smaller sample on one terrain seed, so the
 * suite stays short. {@code -Dga2.fuzz=full} runs the whole sweep (before a release, or after a change to a fast path it covers).
 */
public final class Fuzz {
    private Fuzz() {}

    /** True with {@code -Dga2.fuzz=full}. */
    public static boolean full() {
        return "full".equals(System.getProperty("ga2.fuzz"));
    }

    /** {@code full} in a full run, else {@code quick}. */
    public static int size(int full, int quick) {
        return full() ? full : quick;
    }

    /** All the terrain seeds in a full run, else the first. */
    public static long[] seeds(long... all) {
        return full() ? all : new long[] { all[0] };
    }
}
