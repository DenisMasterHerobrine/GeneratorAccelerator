package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the direction lists that the multiface fast
 * path draws itself (hook {@code multifaceDirections}). After such a change the configuration is asked for them.
 */
@Mixin(value = MultifaceGrowthConfiguration.class, priority = 5000)
public abstract class MultifaceDirectionsInspectMixin {
}
