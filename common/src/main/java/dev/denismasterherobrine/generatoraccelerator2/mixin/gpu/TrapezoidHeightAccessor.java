package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TrapezoidHeight.class)
public interface TrapezoidHeightAccessor {
    @Accessor("minInclusive")
    VerticalAnchor ga2$min();

    @Accessor("maxInclusive")
    VerticalAnchor ga2$max();

    @Accessor("plateau")
    int ga2$plateau();
}
