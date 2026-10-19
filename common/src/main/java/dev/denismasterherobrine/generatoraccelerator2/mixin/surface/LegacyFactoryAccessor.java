package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Seed of the legacy positional random factory, for allocation-free first draws. */
@Mixin(LegacyRandomSource.LegacyPositionalRandomFactory.class)
public interface LegacyFactoryAccessor {
    @Accessor("seed")
    long ga2$seed();
}
