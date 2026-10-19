package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SlicePool;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A new interpolator reuses slices that finished fills gave back to {@link SlicePool}, when the pool has slices of its shape. */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorPoolMixin {
    @Inject(method = "allocateSlice", at = @At("HEAD"), cancellable = true)
    private void ga2$pooledSlice(int cellCountY, int cellCountXZ, CallbackInfoReturnable<double[][]> cir) {
        double[][] slice = SlicePool.take(cellCountXZ + 1, cellCountY + 1);
        if (slice != null) {
            cir.setReturnValue(slice);
        }
    }
}
