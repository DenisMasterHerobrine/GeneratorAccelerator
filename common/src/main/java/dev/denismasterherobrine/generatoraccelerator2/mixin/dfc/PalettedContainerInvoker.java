package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Empty palette data of a given size for the noise fill's section writes ({@code SectionWriter}), without a resize copy. */
@Mixin(PalettedContainer.class)
public interface PalettedContainerInvoker {
    @SuppressWarnings("rawtypes")
    @Invoker("createOrReuseData")
    PalettedContainer.Data ga2$createOrReuseData(@Nullable PalettedContainer.Data data, int bits);
}
