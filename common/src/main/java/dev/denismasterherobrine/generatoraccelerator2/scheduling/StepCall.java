package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import com.mojang.datafixers.util.Either;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * One run of a chunk step: what {@code ChunkStatus.generate} hands the step's task, and vanilla's task itself.
 *
 * @param neighbors the chunks the step may see, a square around {@code chunk} as vanilla's {@code WorldGenRegion} takes them
 * @param vanilla   runs vanilla's task (and other mods' changes to it) with these arguments
 */
public record StepCall(ChunkStatus status, Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates,
                       ThreadedLevelLightEngine lightEngine, Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk,
                       List<ChunkAccess> neighbors, ChunkAccess chunk, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> vanilla) {
    /** The step running on this thread, while its task runs inline ({@link #runInline}). */
    private static final ThreadLocal<StepCall[]> CURRENT = ThreadLocal.withInitial(() -> new StepCall[1]);
    /** The future a hook inside the inline task moved the generator call to ({@link #async}). */
    private static final ThreadLocal<Object[]> ASYNC = ThreadLocal.withInitial(() -> new Object[1]);

    /** A region over the step's chunks, as vanilla's task builds it for a step that writes {@code writeRadius} chunks around its own. */
    public WorldGenRegion region(int writeRadius) {
        return new WorldGenRegion(this.level, this.neighbors, this.status, writeRadius);
    }

    /** Runs vanilla's task. A generation step's future always holds the chunk. */
    public CompletableFuture<ChunkAccess> runVanilla() {
        return this.vanilla.get().thenApply(StepCall::chunk);
    }

    /**
     * Runs vanilla's task here, with this step current for the hooks inside it. When one of them moved the generator call to the
     * worker pool, the step's future is that call's future instead of the task's.
     */
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runInline() {
        StepCall[] current = CURRENT.get();
        Object[] async = ASYNC.get();
        StepCall previousCall = current[0];
        Object previousAsync = async[0];
        current[0] = this;
        async[0] = null;
        try {
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> done = this.vanilla.get();
            @SuppressWarnings("unchecked")
            CompletableFuture<ChunkAccess> moved = (CompletableFuture<ChunkAccess>) async[0];
            return moved != null ? result(moved) : done;
        } finally {
            current[0] = previousCall;
            async[0] = previousAsync;
        }
    }

    /** A step future as {@code ChunkStatus.generate} returns it. */
    public static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> result(CompletableFuture<ChunkAccess> future) {
        return future.thenApply(Either::left);
    }

    /** The step whose task runs inline on this thread, or null. */
    @Nullable
    public static StepCall current() {
        return CURRENT.get()[0];
    }

    /** Makes {@code future} the current step's result ({@link #runInline}). */
    public static void async(CompletableFuture<ChunkAccess> future) {
        ASYNC.get()[0] = future;
    }

    private static ChunkAccess chunk(Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result) {
        return result.map(c -> c, failure -> {
            throw new IllegalStateException("chunk step failed: " + failure);
        });
    }
}
