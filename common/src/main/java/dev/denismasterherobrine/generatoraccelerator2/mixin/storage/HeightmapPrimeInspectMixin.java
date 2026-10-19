package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code Heightmap.primeHeightmaps}.
 * {@code read} skips that call for a loaded chunk when there is nothing to prime ({@code storage.primeHeightmaps}).
 */
@Mixin(value = Heightmap.class, priority = 5000)
public abstract class HeightmapPrimeInspectMixin {
}
