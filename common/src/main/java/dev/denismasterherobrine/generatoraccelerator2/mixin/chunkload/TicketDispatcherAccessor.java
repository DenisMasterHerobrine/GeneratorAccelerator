package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThrottlingChunkTaskDispatcher.class)
public interface TicketDispatcherAccessor {
    @Accessor("chunkPositionsInExecution")
    LongSet ga2$inExecution();

    @Accessor("maxChunksInExecution")
    int ga2$maxChunksInExecution();
}
