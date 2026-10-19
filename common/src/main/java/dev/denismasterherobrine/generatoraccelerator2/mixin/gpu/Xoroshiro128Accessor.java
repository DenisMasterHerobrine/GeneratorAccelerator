package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The generator's state. A GPU result checks the state it starts from and leaves the generator at the state it ends with. */
@Mixin(Xoroshiro128PlusPlus.class)
public interface Xoroshiro128Accessor {
    @Accessor("seedLo")
    long ga2$lo();

    @Accessor("seedHi")
    long ga2$hi();

    @Accessor("seedLo")
    void ga2$setLo(long v);

    @Accessor("seedHi")
    void ga2$setHi(long v);
}
