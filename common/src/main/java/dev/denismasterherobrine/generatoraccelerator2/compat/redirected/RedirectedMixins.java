package dev.denismasterherobrine.generatoraccelerator2.compat.redirected;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Redirected mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. Each
 * mixin redirects an enum's {@code values()}, which returns a fresh copy per call, to one shared array with the same values in
 * the same order. The code around the call runs as vanilla's does. GA2's replacements of those methods do not copy the array, so
 * they need nothing.
 */
public final class RedirectedMixins {
    private RedirectedMixins() {}

    public static final String MOD_ID = "redirected";
    private static final String PREFIX = "toni.redirected.mixin.net.minecraft.world.level.";
    /** {@code Direction.values()} in {@code Feature.checkNeighbors}. */
    public static final String FEATURE = PREFIX + "levelgen.feature.FeatureMixin";
    /** {@code Direction.values()} in {@code TreeFeature.updateLeaves}. */
    public static final String TREE = PREFIX + "levelgen.feature.TreeFeatureMixin";
    /** {@code IOWorker.Priority.values()} in the IO worker's constructor. */
    public static final String IO_WORKER = PREFIX + "chunk.storage.IOWorkerMixin";

    private static final String WHAT = "Redirected's memory savings still work. They give exactly the same results, so GA2's speedups stay on.";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey(FeatureTargets.FEATURE_HELPERS), FEATURE, Set.of("redirectDirections"), WHAT),
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("trees"), TREE, Set.of("redirectDirections"), WHAT),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_OLD_CHUNK_SCAN, IO_WORKER, Set.of("redirectIOWorkerPriorities"), WHAT),
        new KnownMixins.Entry(MOD_ID, HookTargets.STORAGE_PRECOMPRESS, IO_WORKER, Set.of("redirectIOWorkerPriorities"), WHAT));

    /** Nothing to set up. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
