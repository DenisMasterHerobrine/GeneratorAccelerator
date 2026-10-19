package dev.denismasterherobrine.generatoraccelerator2.compat.quark;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Quark mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class QuarkMixins {
    private QuarkMixins() {}

    public static final String MOD_ID = "quark";
    /**
     * Returns null instead of crashing where {@code WorldGenRegion.getChunk} is asked, without {@code require}, for a chunk
     * the region does not have. GA2's region chunk cache only answers lookups that the original method answered with a
     * chunk before. For every other lookup, the cache runs the original method, with Quark's change.
     */
    public static final String WORLD_GEN_REGION = "org.violetmoon.quark.mixin.mixins.WorldGenRegionMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey(FeatureTargets.REGION), WORLD_GEN_REGION, Set.of("quark$fixCreateParamaterBeingIgnored"),
            "Quark's crash guard for missing chunks still works. GA2's chunk lookup cache passes every unknown lookup through it."));
}
