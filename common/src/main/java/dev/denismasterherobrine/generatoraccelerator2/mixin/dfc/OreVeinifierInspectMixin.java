package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.levelgen.OreVeinifier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the vein rule. The whole-cell
 * short cut in {@code FastFill} assumes vanilla's rule. That short cut places no vein where the toggle stays below
 * the threshold or outside the y ranges of both vein types.
 */
@Mixin(value = OreVeinifier.class, priority = 5000)
public abstract class OreVeinifierInspectMixin {
}
