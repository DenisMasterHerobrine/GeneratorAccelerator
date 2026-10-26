package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.SmallFeatures;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes the vanilla KelpFeature through its transcription. Subclasses stay on their own code. */
@Mixin(value = KelpFeature.class, priority = 5000)
public abstract class KelpFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void ga2$place(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (ModdedFeatures.fastPath(this.getClass(), KelpFeature.class) && ctx.level() instanceof Ga2Region region && region.ga2$plain() && FeatureHooks.enabled() && FeaturesCompat.KELP.allowed()) {
            cir.setReturnValue(SmallFeatures.kelp(ctx, region));
        }
    }
}
