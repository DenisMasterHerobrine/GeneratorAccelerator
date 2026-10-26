package dev.denismasterherobrine.generatoraccelerator2.compat.winterly;

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
 * Winterly's frozen grass and flowers ({@code FreezeTopLayerMixin}, a redirect of the snow test in {@code SnowAndFreezeFeature.place}) in
 * GA2's snow and freeze. The fast path asks Winterly's merged redirect at that test ({@link FeatureWraps#claimSnow}).
 */
public final class WinterlyFeatures {
    private WinterlyFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("snowAndFreeze"), WinterlyMixins.FREEZE_TOP_LAYER, WinterlyFeatures::setUp);
    }

    private static boolean setUp() {
        MethodHandle handler = Reflect.mergedMethod(SnowAndFreezeFeature.class, "$winterly$canSetIce", false,
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
