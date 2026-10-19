package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes placement through the explicit pipeline. Applied late so the plugin can see other mods' changes to the method first. */
@Mixin(value = PlacedFeature.class, priority = 5000)
public abstract class PlacedFeatureMixin {
    @Inject(method = "placeWithContext", at = @At("HEAD"), cancellable = true)
    private void ga2$place(PlacementContext ctx, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FeatureHooks.enabled() && FeaturesCompat.placementPipelineAllowed()) {
            cir.setReturnValue(PlacementPipeline.place((PlacedFeature) (Object) this, ctx, random, pos));
        }
    }
}
