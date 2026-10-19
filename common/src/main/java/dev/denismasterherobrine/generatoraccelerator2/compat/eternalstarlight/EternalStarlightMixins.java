package dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Eternal Starlight mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin
 * reads it.
 */
public final class EternalStarlightMixins {
    private EternalStarlightMixins() {}

    public static final String MOD_ID = "eternal_starlight";
    /**
     * Wraps the first {@code setBlock} (the ice) in {@code SnowAndFreezeFeature.place}. In Eternal Starlight's dimension,
     * the wrapper places the mod's own ice.
     */
    public static final String SNOW_AND_FREEZE = "cn.leolezury.eternalstarlight.common.mixin.SnowAndFreezeFeatureMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("snowAndFreeze"), SNOW_AND_FREEZE, Set.of("place"),
            "Eternal Starlight's ice in its dimension still forms. GA2's snow and ice step places it."));
}
