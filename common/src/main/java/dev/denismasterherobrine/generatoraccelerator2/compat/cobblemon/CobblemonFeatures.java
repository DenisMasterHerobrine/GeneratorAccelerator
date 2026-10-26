package dev.denismasterherobrine.generatoraccelerator2.compat.cobblemon;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cobblemon's hearty grains in GA2's simple block feature. Cobblemon's {@code SimpleBlockFeatureMixin} adds a cancellable
 * callback right after the survival test of {@code SimpleBlockFeature.place}. GA2's transcription calls Cobblemon's merged
 * handler at that point with the locals the handler takes: the state, the position and the level. When the handler cancels,
 * the transcription returns the handler's answer ({@link FeatureWraps#simpleBlock}).
 */
public final class CobblemonFeatures {
    private CobblemonFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("simpleBlock"), CobblemonMixins.SIMPLE_BLOCK, CobblemonFeatures::setUp);
    }

    private static boolean setUp() {
        MethodHandle handler = Reflect.mergedMethod(SimpleBlockFeature.class, "$cobblemon$place", false, void.class,
            FeaturePlaceContext.class, CallbackInfoReturnable.class, BlockState.class, BlockPos.class, WorldGenLevel.class);
        if (handler == null) {
            return false;
        }
        FeatureWraps.simpleBlock = (feature, ctx, state, pos, level) -> {
            CallbackInfoReturnable<Boolean> cir = new CallbackInfoReturnable<>("place", true);
            try {
                handler.invoke(feature, ctx, cir, state, pos, level);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
            return cir.isCancelled() ? cir.getReturnValue() : null;
        };
        return true;
    }
}
