package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.trunkplacers.BendingTrunkPlacer.class)
public interface BendingTrunkPlacerAccessor {
    @Accessor("minHeightForLeaves")
    int ga2$minLeaves();

    @Accessor("bendLength")
    net.minecraft.util.valueproviders.IntProvider ga2$bendLength();
}
