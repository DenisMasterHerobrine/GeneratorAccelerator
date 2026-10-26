package dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures;

import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;

/**
 * Variants & Ventures' spawner mobs ({@code DungeonFeatureMixin}, a modifier of {@code randomEntityId(random)}'s value in
 * {@code MonsterRoomFeature.place}) in GA2's monster room. GA2's transcription hands the mob it drew to Variants & Ventures' merged
 * handler. It also passes the three locals the handler reads: the origin, the random and the level. They are passed as references that
 * the handler only reads ({@link FeatureWraps#spawnerMob}).
 */
public final class VariantsAndVenturesFeatures {
    private VariantsAndVenturesFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("monsterRoom"), VariantsAndVenturesMixins.DUNGEON, VariantsAndVenturesFeatures::setUp);
    }

    private static boolean setUp() {
        MethodHandle handler = Reflect.mergedMethod(MonsterRoomFeature.class, "$variantsandventures$getMobSpawnerEntity", false,
            EntityType.class, EntityType.class, LocalRef.class, LocalRef.class, LocalRef.class);
        if (handler == null) {
            return false;
        }
        FeatureWraps.spawnerMob = (feature, original, origin, random, level) -> {
            try {
                return (EntityType<?>) handler.invoke(feature, original, new FeatureWraps.Ref<>(origin), new FeatureWraps.Ref<>(random),
                    new FeatureWraps.Ref<>(level));
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
        };
        return true;
    }
}
