package dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Variants & Ventures mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class VariantsAndVenturesMixins {
    private VariantsAndVenturesMixins() {}

    public static final String MOD_ID = "variantsandventures";
    /**
     * Modifies the value of {@code randomEntityId(random)} in {@code MonsterRoomFeature.place}. Puts the mod's own mobs or vanilla's
     * variants in place of a zombie or skeleton spawner. The choice depends on the biome at the room's origin and on the feature's
     * random, with draws after vanilla's.
     */
    public static final String DUNGEON = "com.faboslav.variantsandventures.common.mixin.DungeonFeatureMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, FeatureTargets.compatKey("monsterRoom"), DUNGEON, Set.of("variantsandventures$getMobSpawnerEntity"),
            "Variants & Ventures' biome-based spawner mobs still work. GA2's dungeons ask for them at the same point vanilla does."));
}
