package dev.denismasterherobrine.generatoraccelerator2.compat.quark.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the placement test of Quark's big stone
 * clusters ({@code canPlaceBlock}, and the generator context's {@code canPlaceAt}). Zeta's cluster walk leaves that test out
 * where the cluster cannot reach (hook {@code zeta.clusters}, {@code compat.zeta.ZetaClusters}).
 */
@Mixin(targets = { "org.violetmoon.quark.content.world.gen.BigStoneClusterGenerator", "org.violetmoon.quark.content.world.gen.BigStoneClusterGenerator$1" },
    priority = 5000)
public abstract class StoneClustersInspectMixin {
}
