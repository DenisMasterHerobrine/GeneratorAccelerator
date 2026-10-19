package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the layer search that the placement
 * pipeline transcribes ({@code placementLayers}).
 */
@SuppressWarnings("deprecation")
@Mixin(value = CountOnEveryLayerPlacement.class, priority = 5000)
public abstract class CountOnEveryLayerInspectMixin {
}
