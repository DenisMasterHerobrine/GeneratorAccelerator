package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record whether any other mod changes {@code WorldGenRegion.getBlockState}
 * at all ({@code regionReadsUnchanged}). Only if none does, the predicate programs read the region directly, skipping the call.
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class WorldGenRegionReadsStrictInspectMixin {
}
