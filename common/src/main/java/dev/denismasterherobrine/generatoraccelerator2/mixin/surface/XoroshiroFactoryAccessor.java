package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Seeds of the Xoroshiro positional random factory, for allocation-free first draws. */
@Mixin(XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class)
public interface XoroshiroFactoryAccessor {
    @Accessor("seedLo")
    long ga2$seedLo();

    @Accessor("seedHi")
    long ga2$seedHi();
}
