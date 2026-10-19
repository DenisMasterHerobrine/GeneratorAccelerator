package dev.denismasterherobrine.generatoraccelerator2.compat.spawn;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithParameters;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * Spawn's restored biomes ({@code MultiNoiseBiomeSourceMixin}, a modifier of {@code parameters()}'s return value) applied to the built
 * list that GA2 returns at the head of the method ({@link BiolithParameters}). Spawn's merged handler is called with that list, as the
 * method's own return would call it ({@link BiolithParameters#finisher}).
 */
public final class SpawnBiomes {
    private SpawnBiomes() {}

    public static void register() {
        CompatAdapters.register(HookTargets.BIOME_PARAMETERS, SpawnMixins.PARAMETERS, SpawnBiomes::setUp);
    }

    private static boolean setUp() {
        Class<?>[] params = {Climate.ParameterList.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(MultiNoiseBiomeSource.class)) {
            if (m.getName().endsWith("$spawn$restoreDroppedBiomes") && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == Climate.ParameterList.class
                && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    BiolithParameters.finisher = (source, list) -> {
                        try {
                            return handler.invoke(source, (Climate.ParameterList<?>) list);
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
