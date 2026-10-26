package dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.blueprint.BlueprintClimate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SamplerWrappers;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves Blueprint's {@code ScopedDensityFunctionContext.getTargetPoint} from a binding. That method evaluates the six
 * functions of the context's per-point sampler at the context's block position, which is the quart point's corner. Each
 * value is rounded to float. The result equals {@code Climate.Sampler.sample} at that quart point. GA2 serves it with the
 * binding of the sampler that Blueprint's caches wrap ({@code compat.blueprint.BlueprintClimate}).
 */
@Mixin(targets = "com.teamabnormals.blueprint.core.util.BiomeUtil$ScopedDensityFunctionContext", remap = false)
public abstract class ScopedContextMixin {
    @Shadow private Climate.Sampler optimizedSampler;
    @Shadow private Climate.TargetPoint targetPoint;
    @Shadow private int blockX;
    @Shadow private int blockY;
    @Shadow private int blockZ;

    @Inject(method = "getTargetPoint", at = @At("HEAD"), cancellable = true)
    private void ga2$compiledTargetPoint(CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (this.targetPoint != null || this.optimizedSampler == null || !DfcHooks.enabled()) {
            return;
        }
        BoundSampler bound = SamplerWrappers.resolve(this.optimizedSampler);
        if (bound != null) {
            // blockX = QuartPos.toBlock(quartX), so the quart point is blockX >> 2
            Climate.TargetPoint point = bound.sample(this.blockX >> 2, this.blockY >> 2, this.blockZ >> 2);
            if (BlueprintClimate.verifyNow()) {
                // Blueprint's own computation, through Blueprint's caches with this context
                DensityFunction.FunctionContext ctx = (DensityFunction.FunctionContext) (Object) this;
                Climate.TargetPoint expected = Climate.target((float) this.optimizedSampler.temperature().compute(ctx), (float) this.optimizedSampler.humidity().compute(ctx),
                    (float) this.optimizedSampler.continentalness().compute(ctx), (float) this.optimizedSampler.erosion().compute(ctx),
                    (float) this.optimizedSampler.depth().compute(ctx), (float) this.optimizedSampler.weirdness().compute(ctx));
                BlueprintClimate.verified(expected.equals(point));
            }
            this.targetPoint = point;
            cir.setReturnValue(point);
        }
    }
}
