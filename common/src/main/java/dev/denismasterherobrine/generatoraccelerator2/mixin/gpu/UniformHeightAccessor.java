package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(UniformHeight.class)
public interface UniformHeightAccessor {
    @Accessor("minInclusive")
    VerticalAnchor ga2$min();

    @Accessor("maxInclusive")
    VerticalAnchor ga2$max();
}
