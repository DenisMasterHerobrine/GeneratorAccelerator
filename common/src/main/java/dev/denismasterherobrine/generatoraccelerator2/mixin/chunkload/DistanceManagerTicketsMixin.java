package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadConfig;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The player ticket throttler's window ({@link ChunkLoadConfig#playerTicketWindow}). The throttler hands out player tickets
 * nearest first. It holds a slot for each ticket until the ticket's chunk is entity ticking. Vanilla gives it 4 slots.
 * The order of the tickets stays the same. Also times each ticket from its slot to entity ticking.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerTicketsMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ThrottlingChunkTaskDispatcher;<init>(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;I)V"), index = 2)
    private int ga2$ticketWindow(int maxTasks) {
        return ChunkLoadConfig.playerTicketWindow();
    }

    @WrapOperation(method = "runAllUpdates", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder;getEntityTickingChunkFuture()Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<LevelChunk>> ga2$timeTicket(ChunkHolder holder, Operation<CompletableFuture<ChunkResult<LevelChunk>>> original) {
        CompletableFuture<ChunkResult<LevelChunk>> ready = original.call(holder);
        long start = System.nanoTime();
        ready.thenRun(() -> ChunkLoadStats.TICKET_READY.record(System.nanoTime() - start));
        return ready;
    }
}
