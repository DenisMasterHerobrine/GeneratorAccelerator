package dev.denismasterherobrine.generatoraccelerator2.compat.winterly;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
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
        Class<?>[] params = {Biome.class, LevelReader.class, BlockPos.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(SnowAndFreezeFeature.class)) {
            if (m.getName().endsWith("$winterly$canSetIce") && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class
                && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    return FeatureWraps.claimSnow((feature, biome, level, pos) -> {
                        try {
                            return (boolean) handler.invoke(feature, biome, level, pos);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IllegalStateException(t);
                        }
                    });
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }
}
