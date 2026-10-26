package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.SculkFast;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SculkPatchFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes the vanilla SculkPatchFeature through its transcription ({@link SculkFast}). Subclasses stay on their own code. */
@Mixin(value = SculkPatchFeature.class, priority = 5000)
public abstract class SculkPatchFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void ga2$place(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (ModdedFeatures.fastPath(this.getClass(), SculkPatchFeature.class) && ctx.level() instanceof Ga2Region region
            && region.ga2$plain() && FeatureHooks.enabled() && FeaturesCompat.SCULK.allowed() && SculkFast.applies()
            && SculkFast.othersOutOfReach(ctx, region)) {
            cir.setReturnValue(SculkFast.place(ctx, region));
        }
    }
}
