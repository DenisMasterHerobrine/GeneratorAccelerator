package dev.denismasterherobrine.generatoraccelerator2.api;

import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Lets a mod place one of its own feature classes through its own transcription when the level is
 * a generation region. The region exposes cached chunk access through
 * {@link dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor} and exact
 * block writes through {@link dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter}.
 * A fast path must reproduce {@code Feature.place(FeaturePlaceContext)} exactly: same random calls in
 * the same order, same reads, same writes and side effects. Return {@link #FALLBACK} to let vanilla
 * run that call. Registered for the exact class only. Subclasses are not affected.
 */
@FunctionalInterface
public interface FeatureFastPath {
    /** Returned to let the feature's own {@code place} run. */
    Object FALLBACK = new Object();

    /** @return {@code Boolean} (the placement result) or {@link #FALLBACK}. */
    Object place(FeaturePlaceContext<?> context, Ga2Region region);

    Map<Class<?>, FeatureFastPath> REGISTRY = new ConcurrentHashMap<>();

    static void register(Class<? extends Feature<?>> featureClass, FeatureFastPath path) {
        REGISTRY.put(featureClass, path);
    }

    static FeatureFastPath lookup(Class<?> featureClass) {
        return REGISTRY.isEmpty() ? null : REGISTRY.get(featureClass);
    }
}
