package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature;
import net.minecraft.world.level.levelgen.feature.SeaPickleFeature;
import net.minecraft.world.level.levelgen.feature.VinesFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the block-placing features GPU decoration places
 * that have no CPU fast path of their own (hook {@code gpuDecorLeaves}). A changed class keeps the features that use it on the CPU.
 */
@Mixin(value = { SeaPickleFeature.class, BlockColumnFeature.class, VinesFeature.class, BlockBlobFeature.class }, priority = 5000)
public abstract class GpuDecorLeavesInspectMixin {
}
