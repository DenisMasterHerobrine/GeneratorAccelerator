package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SamplerWrappers;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes biome sampling of chunk samplers through the compiled six-root program. */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin {
    @Inject(method = "sample", at = @At("HEAD"), cancellable = true)
    private void ga2$sample(int quartX, int quartY, int quartZ, CallbackInfoReturnable<Climate.TargetPoint> cir) {
        // the GPU's point, at a quart of a chunk whose biome step it handed its points to
        Climate.TargetPoint handed = BiomeHand.point((Climate.Sampler) (Object) this, quartX, quartY, quartZ);
        if (handed != null) {
            cir.setReturnValue(handed);
            return;
        }
        if (!DfcHooks.enabled()) {
            return;
        }
        BoundSampler bound = SamplerWrappers.resolve((Climate.Sampler) (Object) this);
        if (bound != null) {
            cir.setReturnValue(bound.sample(quartX, quartY, quartZ));
        }
    }
}
