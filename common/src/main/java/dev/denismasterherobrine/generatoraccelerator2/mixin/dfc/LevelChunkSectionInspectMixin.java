package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to
 * {@code LevelChunkSection.setBlockState}, which the noise fill's section writes ({@code SectionWriter}) do the work of.
 */
@Mixin(value = LevelChunkSection.class, priority = 5000)
public abstract class LevelChunkSectionInspectMixin {
}
