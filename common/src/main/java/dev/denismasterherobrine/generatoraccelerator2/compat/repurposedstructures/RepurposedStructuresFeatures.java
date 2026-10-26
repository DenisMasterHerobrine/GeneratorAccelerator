package dev.denismasterherobrine.generatoraccelerator2.compat.repurposedstructures;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;

/**
 * Repurposed Structures' snow rule ({@code SmarterSnowPlacingInStructuresMixin}, a wrapper of the snow test in
 * {@code SnowAndFreezeFeature.place}) in GA2's snow and freeze fast path. The fast path asks Repurposed Structures' own
 * wrapper, with vanilla's {@code Biome.shouldSnow} as the wrapped call ({@link FeatureWraps}).
 */
public final class RepurposedStructuresFeatures {
    private RepurposedStructuresFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("snowAndFreeze"), RepurposedStructuresMixins.SNOW, RepurposedStructuresFeatures::setUp);
    }

    private static boolean setUp() {
        MethodHandle handler = Reflect.mergedMethod(SnowAndFreezeFeature.class, "$repurposedstructures_smarterSnowPlacingInStructures", false,
            boolean.class, Biome.class, LevelReader.class, BlockPos.class, Operation.class);
        if (handler == null) {
            return false;
        }
        Operation<Boolean> original = args -> ((Biome) args[0]).shouldSnow((LevelReader) args[1], (BlockPos) args[2]);
        return FeatureWraps.claimSnow((feature, biome, level, pos) -> {
            try {
                return (boolean) handler.invoke(feature, biome, level, pos, original);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
        });
    }
}
