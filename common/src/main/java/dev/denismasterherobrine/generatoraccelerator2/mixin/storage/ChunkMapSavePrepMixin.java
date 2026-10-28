package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unloading chunks have their sections encoded on the worker pool before the server thread saves them ({@link SavePrep}).
 *
 * <p>{@code scheduleUnload} queues the unload task on the main-thread unload queue once the chunk to save is ready. This
 * mixin starts the encoding at that moment and queues a wrapped task. While the encoding runs, the wrapped task waits for
 * the next tick. It waits at most {@link SavePrep#MAX_DEFERRALS} times, and only inside the chunk map's tick. The saves of
 * {@code saveAllChunks}, at shutdown and on {@code /save-all flush}, never wait. Waiting tasks go back on the unload queue
 * at the start of the next {@code processUnloads}, including the one at shutdown. The save either finds the prepared tags
 * or encodes them itself. Either way it saves the same chunk with the same tags. Only the tick the save runs in can change.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSavePrepMixin implements SavePrep.Owner {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private Queue<Runnable> unloadQueue;

    /** Unload tasks waiting for their sections (main thread). */
    @Unique private final ArrayList<Runnable> ga2$waiting = new ArrayList<>();
    @Unique private boolean ga2$ticking;

    /** Unload tasks may wait only inside the chunk map's tick. The flag is cleared however the tick ends. */
    @WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;)V")
    private void ga2$tick(BooleanSupplier hasMoreTime, Operation<Void> original) {
        this.ga2$ticking = true;
        try {
            original.call(hasMoreTime);
        } finally {
            this.ga2$ticking = false;
        }
    }

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void ga2$requeueWaiting(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        if (!this.ga2$waiting.isEmpty()) {
            this.unloadQueue.addAll(this.ga2$waiting);
            this.ga2$waiting.clear();
        }
    }

    @WrapOperation(method = "scheduleUnload", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> ga2$encodeAhead(CompletableFuture<ChunkAccess> saveSync, Consumer<? super ChunkAccess> task, Executor executor,
                                                    Operation<CompletableFuture<Void>> original,
                                                    @Local(argsOnly = true) ChunkHolder holder) {
        if (!SavePrep.enabled()) {
            return original.call(saveSync, task, executor);
        }
        ServerLevel level = this.level;
        // Runs on the thread that completes the work on the chunk, as vanilla's queueing does.
        Executor queue = r -> {
            CompletableFuture<Void> job = SavePrep.start(level, holder);
            executor.execute(job == null ? r : new SavePrep.Deferred(r, job, this, holder));
        };
        return original.call(saveSync, task, queue);
    }

    @Override
    public boolean ga2$mayDefer() {
        return this.ga2$ticking;
    }

    @Override
    public void ga2$defer(Runnable task) {
        this.ga2$waiting.add(task);
    }
}
