package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A holder's status futures, started work and own generation task, for letting an idle chunk go ({@code memory.ProtoDrops}). */
@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor {
    @Accessor("futures")
    AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> ga2$futures();

    @Accessor("startedWork")
    AtomicReference<ChunkStatus> ga2$startedWork();

    @Accessor("task")
    AtomicReference<ChunkGenerationTask> ga2$task();
}
