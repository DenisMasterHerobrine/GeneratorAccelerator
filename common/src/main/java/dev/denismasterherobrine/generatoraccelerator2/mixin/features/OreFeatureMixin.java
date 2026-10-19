package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.OreFast;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes the vanilla ore feature through its transcription. Subclasses that change any of the ore feature's methods stay on their own code ({@code ModdedFeatures.fastPath}). */
@Mixin(value = OreFeature.class, priority = 5000)
public abstract class OreFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void ga2$place(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures.fastPath(this.getClass(), OreFeature.class) && ctx.level() instanceof Ga2Region region && region.ga2$plain() && FeatureHooks.enabled() && FeaturesCompat.ORE.allowed()
            && !dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps.oreVetoed(ctx.config())) {
            cir.setReturnValue(OreFast.place(ctx, region));
        }
    }
}
