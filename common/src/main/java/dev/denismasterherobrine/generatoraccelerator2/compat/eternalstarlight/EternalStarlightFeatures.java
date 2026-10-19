package dev.denismasterherobrine.generatoraccelerator2.compat.eternalstarlight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;

/**
 * Eternal Starlight's ice in GA2's snow and freeze fast path. Eternal Starlight's {@code SnowAndFreezeFeatureMixin} is a
 * {@code @WrapOperation} of the first {@code setBlock} in {@code SnowAndFreezeFeature.place}. The fast path calls Eternal
 * Starlight's own handler, with GA2's exact write as the wrapped call ({@link FeatureWraps#ice}).
 */
public final class EternalStarlightFeatures {
    private EternalStarlightFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("snowAndFreeze"), EternalStarlightMixins.SNOW_AND_FREEZE, EternalStarlightFeatures::setUp);
    }

    private static boolean setUp() {
        Class<?>[] params = {WorldGenLevel.class, BlockPos.class, BlockState.class, int.class, Operation.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(SnowAndFreezeFeature.class)) {
            if (m.getName().endsWith("$place") && m.getName().contains("eternal_starlight") && !Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == boolean.class && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    FeatureWraps.ice = (feature, level, pos, state, flags, write) -> {
                        try {
                            return (boolean) handler.invoke(feature, level, pos, state, flags, write);
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
