package dev.denismasterherobrine.generatoraccelerator2.biome;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * Whether a biome lookup's climate searches gave answers that hold for any last result ({@link BiomeAnswers}). Vanilla's tree
 * search starts from the thread's last answer and keeps it when another leaf is exactly as near. The search's answer then
 * depends on what the thread asked before. When a single leaf is nearest, it is the answer whatever the last result
 * ({@code FlatClimateTree}). While a thread watches, GA2's searches check that ({@code FlatClimateTree.alone}). A search GA2 does
 * not see (vanilla's walk, Biolith's own) makes the lookup unsure too.
 */
public final class SearchTies {
    private SearchTies() {}

    /** Threads watching. While zero, searches skip the check. */
    private static final AtomicInteger WATCHING = new AtomicInteger();

    /** One thread's watch. It lives in the thread's climate cache, which a search reads anyway ({@code FlatClimateTree.thread}). */
    public static final class Watch {
        boolean on;
        boolean unsure;
    }

    /** Starts this thread's watch, or returns null when it already watches (an inner lookup then counts for the outer one). */
    @Nullable
    public static Watch start() {
        Watch w = FlatClimateTree.watch();
        if (w.on) {
            return null;
        }
        w.on = true;
        w.unsure = false;
        WATCHING.incrementAndGet();
        return w;
    }

    /** Ends the watch {@link #start} returned. True when every search in it held for any last result. */
    public static boolean end(Watch w) {
        w.on = false;
        WATCHING.decrementAndGet();
        return !w.unsure;
    }

    /** True when this thread watches. */
    public static boolean watching() {
        return WATCHING.get() != 0 && FlatClimateTree.watch().on;
    }

    /** {@link #watching} with this thread's watch at hand. */
    static boolean watching(Watch w) {
        return WATCHING.get() != 0 && w.on;
    }

    /** Why a lookup was unsure: a tie in GA2's search, in Biolith's first or second answer, a search GA2 does not see. */
    public static final int TIE = 0, BIOLITH_FIRST = 1, BIOLITH_SECOND = 2, UNSEEN = 3;
    /** Per reason, the searches that made a watching lookup unsure. */
    public static final LongAdder[] REASONS = {
        new LongAdder(), new LongAdder(), new LongAdder(),
        new LongAdder() };

    /** A search whose answer may depend on the last result, or one GA2 does not see. */
    public static void unsure(int reason) {
        if (WATCHING.get() != 0) {
            unsure(FlatClimateTree.watch(), reason);
        }
    }

    /** {@link #unsure} with this thread's watch at hand. */
    static void unsure(Watch w, int reason) {
        if (w.on) {
            w.unsure = true;
            REASONS[reason].increment();
        }
    }
}
