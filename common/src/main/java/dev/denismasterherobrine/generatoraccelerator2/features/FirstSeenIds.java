package dev.denismasterherobrine.generatoraccelerator2.features;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The distinct ids a bit storage hands out, in order of first appearance (ids under 64). One per nesting level of a thread
 * ({@link #take}, {@link #give}): a consumer that itself queries a container gets the next level's, so none is overwritten while
 * its ids are handed out.
 */
public final class FirstSeenIds implements IntConsumer {
    private static final ThreadLocal<Levels> LEVELS = ThreadLocal.withInitial(Levels::new);

    private long mask;
    public int count;
    public final int[] order = new int[64];

    private FirstSeenIds() {}

    private static final class Levels {
        FirstSeenIds[] items = new FirstSeenIds[2];
        int depth;
    }

    /** A cleared instance for this nesting level. Hand it back with {@link #give}. */
    public static FirstSeenIds take() {
        Levels l = LEVELS.get();
        if (l.depth == l.items.length) {
            l.items = Arrays.copyOf(l.items, l.depth * 2);
        }
        FirstSeenIds s = l.items[l.depth];
        if (s == null) {
            s = l.items[l.depth] = new FirstSeenIds();
        }
        l.depth++;
        s.mask = 0L;
        s.count = 0;
        return s;
    }

    /** Ends the innermost {@link #take}. */
    public static void give() {
        LEVELS.get().depth--;
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
