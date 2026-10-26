package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A chunk's holder before the visible map is updated, for {@code compat.chunky.ChunkyHoldTickets}. */
@Mixin(ChunkMap.class)
public interface ChunkMapUpdatingInvoker {
    @Nullable
    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder ga2$updatingChunk(long pos);
}
