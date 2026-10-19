package dev.denismasterherobrine.generatoraccelerator2.compat.winterly;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Winterly mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class WinterlyMixins {
    private WinterlyMixins() {}

    public static final String MOD_ID = "winterly";
    /**
     * Redirects {@code biome.shouldSnow(level, pos)} in {@code SnowAndFreezeFeature.place}. Where snow could fall on grass or a flower,
     * Winterly places its frozen grass or frozen flower instead, written through the level. Then vanilla's test runs.
     */
    public static final String FREEZE_TOP_LAYER = "neoforge.ru.pinkgoosik.winterly.mixin.common.FreezeTopLayerMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("snowAndFreeze"), FREEZE_TOP_LAYER, Set.of("canSetIce"),
            "Winterly's frozen grass and flowers still generate. GA2's snow and ice step places them."));
}
