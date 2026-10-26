package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.Climate;

/** The exact flat search behind {@code Climate.ParameterList.findValueIndex}. */
public final class ClimateSearch {
    private ClimateSearch() {}

    private static final ThreadLocal<boolean[]> DISABLED = ThreadLocal.withInitial(() -> new boolean[1]);
    /**
     * Number of threads inside {@link #vanilla}. While it is zero, no thread has the override set. {@link #enabled} runs for
     * every biome lookup and then skips the thread-local read. A thread that sets the override counts itself first, so that
     * thread always reads it.
     */
    private static final AtomicInteger OVERRIDES = new AtomicInteger();
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.BIOME_CLIMATE_SEARCH);

    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.biomes", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    /** Searches also walked and compared ({@code verify.biomes}), and the searches that differed. */
    public static final AtomicLong VERIFIED = new AtomicLong(), DIFFERING = new AtomicLong();

    public static boolean enabled() {
        return (OVERRIDES.get() == 0 || !DISABLED.get()[0]) && BiomeConfig.flatClimateSearch() && HOOK.allowed();
    }

    /** Runs {@code body} with vanilla's tree walk on this thread (tests). */
    public static <T> T vanilla(Supplier<T> body) {
        OVERRIDES.incrementAndGet();
        boolean[] d = DISABLED.get();
        boolean previous = d[0];
        d[0] = true;
        try {
            return body.get();
        } finally {
            d[0] = previous;
            OVERRIDES.decrementAndGet();
        }
    }

    /** Vanilla {@code RTree.search(target, Node::distance)}: the nearest leaf's value. Updates the thread's last result. */
    public static <T> T search(Climate.RTree<T> tree, Climate.TargetPoint target) {
        return search(tree, target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness());
    }

    /** {@link #search(Climate.RTree, Climate.TargetPoint)} for the target point of these six quantized coordinates. */
    @SuppressWarnings("unchecked")
    public static <T> T search(Climate.RTree<T> tree, long q0, long q1, long q2, long q3, long q4, long q5) {
        Climate.RTree.Leaf<T> last = tree.lastResult.get();
        // the GPU's nearest leaf, in a chunk whose biome step it handed it to. It is the only leaf at its distance, so the walk
        // ends on it whatever the last result
        Climate.RTree.Leaf<T> handed = BiomeHand.nearest(tree, q0, q1, q2, q3, q4, q5);
        if (handed != null) {
            if (handed != last) {
                tree.lastResult.set(handed);
            }
            return handed.value;
        }
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) tree).ga2$flat();
        int lastId = last == null ? -1 : ((Ga2ClimateLeaf) (Object) last).ga2$index();
        int id = FlatClimateTree.COLUMNS ? flat.searchColumns(q0, q1, q2, q3, q4, q5, lastId) : flat.search(q0, q1, q2, q3, q4, q5, lastId);
        if (VERIFY_EVERY > 0 && SEEN.incrementAndGet() % VERIFY_EVERY == 0) {
            // -Dgeneratoraccelerator2.verify.biomes=N also walks every N-th search, from the same last result.
            int walked = flat.search(q0, q1, q2, q3, q4, q5, lastId);
            VERIFIED.incrementAndGet();
            if (walked != id && DIFFERING.incrementAndGet() <= 10) {
                Ga2Log.get("Verify").warn("climate search mismatch at [{}, {}, {}, {}, {}, {}] (leaf {}): column {}, walk {}",
                    q0, q1, q2, q3, q4, q5, lastId, id, walked);
            }
        }
        // the search returns the leaf's value. A tie with a leaf of the same value gives the same answer
        if (SearchTies.watching() && !flat.alone(q0, q1, q2, q3, q4, q5, id, true)) {
            SearchTies.unsure(SearchTies.TIE);
        }
        Climate.RTree.Leaf<T> leaf = (Climate.RTree.Leaf<T>) flat.leaf(id);
        if (leaf != last) {
            // The same leaf as before is common (the quarts of one column). A thread-local write costs more than the read above.
            tree.lastResult.set(leaf);
        }
        return leaf.value;
    }
}
