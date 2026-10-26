package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.tree.TreeFast;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes vanilla trees through the transcription. Placers and decorators, modded ones included, run unchanged inside it. */
@Mixin(value = TreeFeature.class, priority = 5000)
public abstract class TreeFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void ga2$place(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (ModdedFeatures.fastPath(this.getClass(), TreeFeature.class) && ctx.level() instanceof Ga2Region region && region.ga2$plain() && FeatureHooks.enabled() && FeaturesCompat.treesAllowed()) {
            cir.setReturnValue(TreeFast.place(ctx, region));
        }
    }
}
