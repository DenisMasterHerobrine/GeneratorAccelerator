package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the NoiseChunk methods
 * that {@code FastFill} does in place: the constructor that makes the material rules, the aquifer rule,
 * {@code getInterpolatedState} and {@code updateForZ}.
 */
@Mixin(value = NoiseChunk.class, priority = 5000)
public abstract class NoiseChunkInspectMixin {
}
