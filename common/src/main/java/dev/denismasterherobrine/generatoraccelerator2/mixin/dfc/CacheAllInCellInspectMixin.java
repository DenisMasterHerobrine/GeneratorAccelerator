package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import org.spongepowered.asm.mixin.Mixin;

/** Adds nothing. Applied late so the mixin plugin can record other mods' changes to the cell cache read {@code FastFill} does in place. */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell", priority = 5000)
public abstract class CacheAllInCellInspectMixin {
}
