package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.foliageplacers.RandomSpreadFoliagePlacer.class)
public interface RandomSpreadFoliagePlacerAccessor {
    @Accessor("foliageHeight")
    net.minecraft.util.valueproviders.IntProvider ga2$height();

    @Accessor("leafPlacementAttempts")
    int ga2$attempts();
}
