package dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.SupplementariesMixins;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the barnacle block's placement test
 * ({@code isValidStateForPlacement}, {@code canBeAttachTo}). The barnacle search relies on that test writing nothing (hook
 * {@code supplementaries.barnacles}, {@code compat.supplementaries.Barnacles}).
 */
@Mixin(targets = SupplementariesMixins.BLOCK, priority = 5000)
public abstract class BarnaclesBlockInspectMixin {
}
