package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes the vanilla GeodeFeature through its transcription. Subclasses stay on their own code. */
@Mixin(value = GeodeFeature.class, priority = 5000)
public abstract class GeodeFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void ga2$place(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures.fastPath(this.getClass(), GeodeFeature.class) && ctx.level() instanceof Ga2Region region && region.ga2$plain() && FeatureHooks.enabled() && FeaturesCompat.GEODE.allowed()) {
            cir.setReturnValue(dev.denismasterherobrine.generatoraccelerator2.features.fast.GeodeFast.place((GeodeFeature) (Object) this, ctx, region));
        }
    }
}
