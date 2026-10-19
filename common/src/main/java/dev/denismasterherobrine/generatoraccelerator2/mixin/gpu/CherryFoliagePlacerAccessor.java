package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.foliageplacers.CherryFoliagePlacer.class)
public interface CherryFoliagePlacerAccessor {
    @Accessor("height")
    net.minecraft.util.valueproviders.IntProvider ga2$height();

    @Accessor("wideBottomLayerHoleChance")
    float ga2$wideHole();

    @Accessor("cornerHoleChance")
    float ga2$cornerHole();

    @Accessor("hangingLeavesChance")
    float ga2$hanging();

    @Accessor("hangingLeavesExtensionChance")
    float ga2$hangingExtension();
}
