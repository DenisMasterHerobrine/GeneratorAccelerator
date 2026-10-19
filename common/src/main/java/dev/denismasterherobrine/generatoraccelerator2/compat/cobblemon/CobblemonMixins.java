package dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Cobblemon mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class CobblemonMixins {
    private CobblemonMixins() {}

    public static final String MOD_ID = "cobblemon";
    /**
     * A cancellable callback right after {@code state.canSurvive(level, pos)} in {@code SimpleBlockFeature.place}. Grown hearty
     * grains are placed as a double plant, or not at all, regardless of what {@code canSurvive} returned. Every other block
     * continues as in vanilla.
     */
    public static final String SIMPLE_BLOCK = "com.cobblemon.mod.common.mixin.SimpleBlockFeatureMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("simpleBlock"), SIMPLE_BLOCK, Set.of("place"),
            "Cobblemon's hearty grains still generate. GA2's block placement checks for them at the same point vanilla does."));
}
