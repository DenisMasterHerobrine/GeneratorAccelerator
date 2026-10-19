package dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Repurposed Structures mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin
 * plugin reads it.
 */
public final class RepurposedStructuresMixins {
    private RepurposedStructuresMixins() {}

    public static final String MOD_ID = "repurposed_structures";
    /**
     * Wraps {@code SnowAndFreezeFeature.place}'s snow test. Inside the mod's tagged structures, no snow goes on a snow
     * layer thicker than one. The mod's other feature mixins cancel at the head of the method, before GA2's fast paths,
     * and need nothing.
     */
    public static final String SNOW = "com.telepathicgrunt.repurposedstructures.mixins.features.SmarterSnowPlacingInStructuresMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("snowAndFreeze"), SNOW, Set.of("repurposedstructures_smarterSnowPlacingInStructures"),
            "Repurposed Structures' snow rule in structures still applies. GA2's snow and ice step follows it."));
}
