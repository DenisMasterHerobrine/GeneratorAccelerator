package dev.denismasterherobrine.generatoraccelerator2.mixin.fluid;

import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the renderer and its face checks. The hidden
 * water skip leaves out the whole method ({@link LiquidRenderCallMixin}, hook {@code fluids.hiddenWater}).
 */
@Mixin(value = LiquidBlockRenderer.class, priority = 5000)
public abstract class LiquidBlockRendererMixin {
}
