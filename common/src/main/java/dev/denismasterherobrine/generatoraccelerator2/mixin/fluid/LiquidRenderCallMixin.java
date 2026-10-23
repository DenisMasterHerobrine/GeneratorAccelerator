package dev.denismasterherobrine.generatoraccelerator2.mixin.fluid;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.denismasterherobrine.generatoraccelerator2.fluid.StillWater;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves out the renderer's call for water that shows no face, before the water colour is blended ({@link StillWater}). The
 * condition is checked at the call, so no callback object is made for each fluid block. Applied late so the plugin sees other
 * mods' changes to the call first (hook {@code fluids.hiddenWater}).
 */
@Mixin(value = BlockRenderDispatcher.class, priority = 5000)
public abstract class LiquidRenderCallMixin {
    @WrapWithCondition(method = "renderLiquid", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/LiquidBlockRenderer;tesselate(Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/material/FluidState;)V"))
    private boolean ga2$drawShownWater(LiquidBlockRenderer renderer, BlockAndTintGetter level, BlockPos pos, VertexConsumer buffer, BlockState state,
                                       FluidState fluid) {
        return !StillWater.hidden(level, pos, fluid);
    }
}
