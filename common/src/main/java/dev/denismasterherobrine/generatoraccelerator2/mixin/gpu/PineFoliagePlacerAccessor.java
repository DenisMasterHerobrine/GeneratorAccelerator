package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.foliageplacers.PineFoliagePlacer.class)
public interface PineFoliagePlacerAccessor {
    @Accessor("height")
    net.minecraft.util.valueproviders.IntProvider ga2$height();
}
