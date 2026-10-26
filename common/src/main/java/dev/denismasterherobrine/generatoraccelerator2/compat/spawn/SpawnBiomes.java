package dev.denismasterherobrine.generatoraccelerator2.compat.spawn;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithParameters;
import java.lang.invoke.MethodHandle;
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
        MethodHandle handler = Reflect.mergedMethod(MultiNoiseBiomeSource.class, "$spawn$restoreDroppedBiomes", false,
            Climate.ParameterList.class, Climate.ParameterList.class);
        if (handler == null) {
            return false;
        }
        BiolithParameters.finisher = (source, list) -> {
            try {
                return handler.invoke(source, (Climate.ParameterList<?>) list);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
        };
        return true;
    }
}
