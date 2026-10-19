package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.VineBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the multiface block, the multiface default
 * spreader and glow lichen. GPU decoration transcribes these (hook {@code gpuMultiface}). A change keeps glow lichen on the CPU.
 */
@Mixin(value = { MultifaceBlock.class, MultifaceSpreader.class, MultifaceSpreader.DefaultSpreaderConfig.class, GlowLichenBlock.class, VineBlock.class }, priority = 5000)
public abstract class GpuMultifaceInspectMixin {
}
