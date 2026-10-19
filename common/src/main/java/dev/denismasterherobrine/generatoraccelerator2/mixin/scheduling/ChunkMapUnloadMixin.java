package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ends vanilla's unload task for a chunk holder that is no longer waiting to unload.
 *
 * <p>An unload task re-queues itself on the main thread's unload queue until its holder is ready for saving. A holder
 * is taken back from the unload list when a ticket comes back ({@code updateChunkScheduling}). That holder keeps the
 * task. While the chunk is being generated again, the holder is not ready, so the task re-queues itself on every poll.
 * {@code processUnloads} then runs the task for the rest of every tick. With no time left, the main thread never polls
 * the chunk tasks that other threads wait for. A generation step that asks the server level for a chunk then never
 * finishes. Lithostitched's in-structure predicate does this from a feature's placement filter. The holder stays in
 * use and the loop goes on. Chunk generation stalls for minutes, and the worker pool adds a thread for every blocked
 * step.
 *
 * <p>Once the holder is ready, the task would do nothing, because {@code pendingUnloads.remove(pos, holder)} fails for
 * a holder that was taken back. A holder that goes back to the unload list gets a new task there. So the stale task is
 * dropped when it would re-queue itself.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUnloadMixin {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true)
    private void ga2$dropStaleUnload(long pos, ChunkHolder holder, CallbackInfo ci) {
        if (this.pendingUnloads.get(pos) != holder) {
            ci.cancel();
        }
    }
}
