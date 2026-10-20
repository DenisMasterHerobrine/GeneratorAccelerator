package dev.denismasterherobrine.generatoraccelerator2.mixin.fluid;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.denismasterherobrine.generatoraccelerator2.fluid.StillWater;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Returns at the head for water that shows no face, before the water colour is blended ({@link StillWater}). Applied late so
 * the plugin sees other mods' changes to the renderer first (hook {@code fluids.hiddenWater}).
 */
@Mixin(value = LiquidBlockRenderer.class, priority = 5000)
public abstract class LiquidBlockRendererMixin {
    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void ga2$skipHidden(BlockAndTintGetter level, BlockPos pos, VertexConsumer buffer, BlockState state, FluidState fluid, CallbackInfo ci) {
        if (StillWater.hidden(level, pos, fluid)) {
            ci.cancel();
        }
    }
}
