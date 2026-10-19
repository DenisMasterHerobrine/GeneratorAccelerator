package dev.denismasterherobrine.generatoraccelerator2.features.region;

/** Bumped whenever tags are (re)bound, so per-block-state caches derived from tags rebuild lazily. */
public final class TagEpoch {
    private TagEpoch() {}

    private static volatile int epoch = 1;

    public static int current() {
        return epoch;
    }

    public static void bump() {
        epoch++;
    }
}
