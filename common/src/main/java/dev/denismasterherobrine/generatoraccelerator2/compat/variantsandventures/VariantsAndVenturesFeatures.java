package dev.denismasterherobrine.generatoraccelerator2.compat.variantsandventures;

import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
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
        Class<?>[] params = {EntityType.class, LocalRef.class, LocalRef.class, LocalRef.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(MonsterRoomFeature.class)) {
            if (m.getName().endsWith("$variantsandventures$getMobSpawnerEntity") && !Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == EntityType.class && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    FeatureWraps.spawnerMob = (feature, original, origin, random, level) -> {
                        try {
                            return (EntityType<?>) handler.invoke(feature, original, new FeatureWraps.Ref<>(origin), new FeatureWraps.Ref<>(random),
                                new FeatureWraps.Ref<>(level));
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IllegalStateException(t);
                        }
                    };
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }
}
