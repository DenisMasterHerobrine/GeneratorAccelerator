package dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.Barnacles;
import dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.SupplementariesMixins;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Searches each direction of a barnacle growth once ({@link Barnacles}). Applied late, because the plugin first inspects other mods'
 * changes to the search (hook {@code supplementaries.barnacles}). Applied only with the code the search was written against.
 */
@Mixin(targets = SupplementariesMixins.FEATURE, priority = 5000)
public abstract class BarnaclesFeatureMixin {
    // Supplementaries' override of Feature.place. In a Fabric game, the override has the method's intermediary name, because
    // Supplementaries is not on the build's class path.
    @Inject(method = { "place", "method_13151" }, at = @At("HEAD"), cancellable = true, require = 0)
    private void ga2$searchOnce(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        Boolean placed = Barnacles.place(this, ctx);
        if (placed != null) {
            cir.setReturnValue(placed);
        }
    }
}
