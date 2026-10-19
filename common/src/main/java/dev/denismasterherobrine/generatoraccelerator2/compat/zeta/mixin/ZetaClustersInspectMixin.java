package dev.denismasterherobrine.generatoraccelerator2.compat.zeta.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaMixins;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the methods that the cluster walk replaces
 * ({@code forEachChunkBlock}) or relies on ({@code isInside}). Hook {@code zeta.clusters}.
 */
@Mixin(targets = { ZetaMixins.MULTI_CHUNK, ZetaMixins.SHAPE }, priority = 5000)
public abstract class ZetaClustersInspectMixin {
}
