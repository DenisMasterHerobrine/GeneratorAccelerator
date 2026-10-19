package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code findValueIndex(target)} through the exact flat search. Uses vanilla's metric, {@code Node::distance}. */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateParameterListMixin<T> {
    @Shadow @Final private Climate.RTree<T> index;

    @Inject(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void ga2$flatSearch(Climate.TargetPoint target, CallbackInfoReturnable<T> cir) {
        // Another mod may build the tree lazily inside the two-argument search, such as ModernFix's deferred index
        if (this.index != null && ClimateSearch.enabled()) {
            cir.setReturnValue(ClimateSearch.search(this.index, target));
        }
    }
}
