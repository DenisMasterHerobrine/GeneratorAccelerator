package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces {@code runLightUpdates} of a server light engine. It runs vanilla's node checks, then the decrease and increase
 * propagation on several threads when the batch is large enough ({@link ParallelLight}), then vanilla's end of the batch.
 * It also times every batch, split or not. The light thread's time in propagation can then be compared with the parallel
 * propagation off.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineParallelMixin {
    @Unique private long ga2pl$started;

    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void ga2pl$runLightUpdates(CallbackInfoReturnable<Integer> cir) {
        long t0 = System.nanoTime();
        int processed = ParallelLight.runLightUpdates((LightEngine<?, ?>) (Object) this);
        if (processed >= 0) {
            ParallelLightStats.batch(System.nanoTime() - t0);
            cir.setReturnValue(processed);
        } else {
            this.ga2pl$started = t0;
        }
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void ga2pl$timeVanilla(CallbackInfoReturnable<Integer> cir) {
        if (this.ga2pl$started != 0) {
            ParallelLightStats.batch(System.nanoTime() - this.ga2pl$started);
            this.ga2pl$started = 0;
        }
    }
}
