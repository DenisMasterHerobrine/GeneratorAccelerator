package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.function.IntConsumer;

/** The distinct ids a bit storage hands out, in order of first appearance (ids under 64). One per thread, cleared by {@link #get}. */
public final class FirstSeenIds implements IntConsumer {
    private static final ThreadLocal<FirstSeenIds> LOCAL = ThreadLocal.withInitial(FirstSeenIds::new);

    private long mask;
    public int count;
    public final int[] order = new int[64];

    private FirstSeenIds() {}

    public static FirstSeenIds get() {
        FirstSeenIds s = LOCAL.get();
        s.mask = 0L;
        s.count = 0;
        return s;
    }

    @Override
    public void accept(int id) {
        long bit = 1L << id;
        if ((mask & bit) == 0L) {
            mask |= bit;
            order[count++] = id;
        }
    }
}
