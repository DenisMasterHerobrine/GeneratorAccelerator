package dev.denismasterherobrine.generatoraccelerator2.compat.owo;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** owo-lib mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class OwoMixins {
    private OwoMixins() {}

    public static final String MOD_ID = "owo";
    /**
     * {@code OreFeature.doPlace}. owo's handlers remember the placed ores whose block another mod registered with owo
     * ({@code Maldenhagen}). Those blocks need the level's own write. At the end, the handlers set them again through the level
     * ({@code malding}, {@code coping}). For an ore whose targets name none of those blocks, both handlers do nothing.
     */
    public static final String COPENHAGEN = "io.wispforest.owo.mixin.Copenhagen";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("ore"), COPENHAGEN, Set.of("malding", "coping"),
            "Ores placed into special owo blocks still work. Those ores use vanilla's placement. All other ores stay fast."));
}
