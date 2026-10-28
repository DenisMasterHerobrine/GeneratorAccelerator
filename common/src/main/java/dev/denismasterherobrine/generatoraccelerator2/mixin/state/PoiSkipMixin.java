package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the POI bookkeeping of a generation region's block writes between two states without a POI type. The bookkeeping does
 * nothing in that case ({@link PoiStates}). Applied late so the plugin sees other mods' changes to the call first (hook
 * {@code state.poiSkip}).
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class PoiSkipMixin {
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;onBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"),
        require = 0)
    private void ga2$skipWithoutPoi(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState, Operation<Void> original) {
        if (!PoiStates.skip(level, oldState, newState)) {
            original.call(level, pos, oldState, newState);
        } else {
            PoiStates.skipped(level, pos, oldState, newState);
        }
    }
}
