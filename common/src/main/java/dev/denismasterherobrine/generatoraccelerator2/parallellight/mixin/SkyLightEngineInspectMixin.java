package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Its presence lets the mixin plugin inspect the propagation methods of the sky light engine once every
 * mod's mixins are applied. The parallel propagation runs vanilla's bodies of those methods itself. Another mod's change
 * to those methods therefore turns the parallel propagation off ({@code ParallelLight.setCompatGate}).
 */
@Mixin(SkyLightEngine.class)
public abstract class SkyLightEngineInspectMixin {
}
