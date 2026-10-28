package dev.denismasterherobrine.generatoraccelerator2.compat.atmospheric;

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
 * Atmospheric's snowy barrel cacti ({@code SnowAndFreezeFeatureMixin}, a redirect of the snow test in {@code SnowAndFreezeFeature.place})
 * in GA2's snow and freeze. The fast path asks Atmospheric's merged redirect at that test ({@link FeatureWraps#claimSnow}).
 */
public final class AtmosphericFeatures {
    private AtmosphericFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("snowAndFreeze"), AtmosphericMixins.SNOW_AND_FREEZE, AtmosphericFeatures::setUp);
    }

    private static boolean setUp() {
        MethodHandle handler = Reflect.mergedMethod(SnowAndFreezeFeature.class, "$shouldSnow", false,
            boolean.class, Biome.class, LevelReader.class, BlockPos.class);
        if (handler == null) {
            return false;
        }
        return FeatureWraps.claimSnow((feature, biome, level, pos) -> {
            try {
                return (boolean) handler.invoke(feature, biome, level, pos);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
        });
    }
}
