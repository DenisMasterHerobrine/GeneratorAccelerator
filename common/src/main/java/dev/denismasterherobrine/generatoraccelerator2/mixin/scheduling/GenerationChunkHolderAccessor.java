package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The generation tasks that hold a chunk ({@code /ga2 steps}). */
@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor {
    @Accessor("generationRefCount")
    AtomicInteger ga2$generationRefCount();
}
