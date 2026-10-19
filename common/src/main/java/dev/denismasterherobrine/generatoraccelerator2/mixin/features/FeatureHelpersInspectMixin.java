package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.Feature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the {@code Feature} helpers whose work
 * the fast paths replicate ({@code featureHelpers}).
 */
@Mixin(value = Feature.class, priority = 5000)
public abstract class FeatureHelpersInspectMixin {
}
