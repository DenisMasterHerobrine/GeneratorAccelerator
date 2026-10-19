package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GPU decoration's trees ({@code gpu.decor.TreeSpec}) read this vanilla object's configuration. */
@Mixin(net.minecraft.world.level.levelgen.feature.trunkplacers.CherryTrunkPlacer.class)
public interface CherryTrunkPlacerAccessor {
    @Accessor("branchCount")
    net.minecraft.util.valueproviders.IntProvider ga2$branchCount();

    @Accessor("branchHorizontalLength")
    net.minecraft.util.valueproviders.IntProvider ga2$branchLength();

    @Accessor("branchStartOffsetFromTop")
    net.minecraft.util.valueproviders.UniformInt ga2$branchStart();

    @Accessor("secondBranchStartOffsetFromTop")
    net.minecraft.util.valueproviders.UniformInt ga2$secondBranchStart();

    @Accessor("branchEndOffsetFromTop")
    net.minecraft.util.valueproviders.IntProvider ga2$branchEnd();
}
