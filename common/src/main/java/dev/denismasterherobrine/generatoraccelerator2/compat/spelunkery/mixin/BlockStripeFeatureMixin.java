package dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryCells;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The two cellular noises of Spelunkery's stone stripes answered from one search per point ({@link SpelunkeryCells}). Applied only with
 * Spelunkery loaded and with the FastNoiseLite code that GA2's fused noise was written against.
 */
@Mixin(targets = "com.ordana.spelunkery.worldgen.features.BlockStripeFeature")
public abstract class BlockStripeFeatureMixin {
    // Spelunkery's override of Feature.place. In a Fabric game, the override has the method's intermediary name, because
    // Spelunkery is not on the build's class path.
    @WrapOperation(method = { "place", "method_13151" }, at = @At(value = "INVOKE",
        target = "Lcom/ordana/spelunkery/worldgen/feature_configs/util/FastNoiseLite;GetNoise(DDD)F"), require = 0)
    private float ga2$sharedCells(@Coerce Object noise, double x, double y, double z, Operation<Float> original) {
        return SpelunkeryCells.getNoise(noise, x, y, z, original);
    }
}
