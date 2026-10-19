package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public interface CacheAllInCellAccessor {
    @Accessor("values") double[] ga2$values();
}
