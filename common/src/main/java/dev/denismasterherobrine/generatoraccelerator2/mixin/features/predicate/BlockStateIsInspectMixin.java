package dev.denismasterherobrine.generatoraccelerator2.mixin.features.predicate;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code BlockStateBase.is(HolderSet)} and
 * {@code HolderSet.Direct.contains}, which the predicate programs answer by block identity for fixed block lists.
 */
@Mixin(targets = { "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", "net.minecraft.core.HolderSet$Direct" }, priority = 5000)
public abstract class BlockStateIsInspectMixin {
}
