package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;

/** Adds nothing. Lets the mixin plugin inspect the block light engine's propagation methods ({@link SkyLightEngineInspectMixin}). */
@Mixin(BlockLightEngine.class)
public abstract class BlockLightEngineInspectMixin {
}
