package dev.denismasterherobrine.generatoraccelerator2.mixin.fluid;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.fluid.StillWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves out the post-processing ticks of water that cannot flow ({@link StillWater}), and times the post-processing of each
 * chunk. Applied late so the plugin sees other mods' changes to the call first (hook {@code fluids.postProcess}).
 */
@Mixin(value = LevelChunk.class, priority = 5000)
public abstract class PostProcessFluidMixin {
    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void ga2$postProcessStart(CallbackInfo ci, @Share("ga2$start") LocalLongRef start) {
        start.set(System.nanoTime());
    }

    @Inject(method = "postProcessGeneration", at = @At("RETURN"))
    private void ga2$postProcessEnd(CallbackInfo ci, @Share("ga2$start") LocalLongRef start) {
        ChunkLoadStats.POST_PROCESS.record(System.nanoTime() - start.get());
    }

    @WrapOperation(method = "postProcessGeneration", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"),
        require = 0)
    private void ga2$skipStillWater(FluidState fluid, ServerLevel level, BlockPos pos, BlockState state, Operation<Void> original) {
        if (!StillWater.tickDoesNothing((LevelChunk) (Object) this, level, pos, fluid)) {
            original.call(fluid, level, pos, state);
        }
    }
}
