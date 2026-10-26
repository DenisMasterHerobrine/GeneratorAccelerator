package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes vanilla's tree walk for a lookup that watches its searches ({@link SearchTies}). GA2 does not see whether the walk's
 * answer holds for any last result, so that lookup's answer is not kept. Applied late, so another mod's change to the walk is
 * merged before this injection.
 */
@Mixin(value = Climate.RTree.class, priority = 5000)
public abstract class ClimateRTreeWalkMixin {
    @Inject(method = "search", at = @At("HEAD"))
    private void ga2$walked(Climate.TargetPoint target, Climate.DistanceMetric<?> metric, CallbackInfoReturnable<Object> cir) {
        SearchTies.unsure(SearchTies.UNSEEN);
    }
}
