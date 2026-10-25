package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the leaf rules and the shape update that the
 * tree transcription answers itself ({@code features.tree.LeafUpdates}, hook {@code treeLeaves}). After such a change every
 * state is asked.
 */
@Mixin(value = { LeavesBlock.class, BlockBehaviour.class, BlockBehaviour.BlockStateBase.class }, priority = 5000)
public abstract class TreeLeavesInspectMixin {
}
