package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The decoration random's draw count, which a GPU result advances by the draws it made. */
@Mixin(WorldgenRandom.class)
public interface WorldgenRandomAccessor {
    @Accessor("count")
    int ga2$count();

    @Accessor("count")
    void ga2$setCount(int v);
}
