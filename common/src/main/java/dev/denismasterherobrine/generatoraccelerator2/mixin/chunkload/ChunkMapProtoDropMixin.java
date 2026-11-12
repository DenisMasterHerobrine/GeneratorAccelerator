package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds;
import dev.denismasterherobrine.generatoraccelerator2.memory.ProtoDrops;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Idle chunks at levels 35 to 44 saved and let go ({@link ProtoDrops}). The holders come in order of the tick they were marked, so
 * a tick stops at the first one not due yet.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapProtoDropMixin implements ProtoDrops.Owner {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    protected abstract void scheduleUnload(long pos, ChunkHolder holder);

    /** Position to the tick it was marked, oldest first. Made on the first mark. Server thread only. */
    @Unique
    private Long2IntLinkedOpenHashMap ga2$idle;

    @Override
    public void ga2$markIdle(long pos) {
        Long2IntLinkedOpenHashMap idle = this.ga2$idle;
        if (idle == null) {
            idle = new Long2IntLinkedOpenHashMap();
            this.ga2$idle = idle;
        }
        idle.putAndMoveToLast(pos, ProtoDrops.tick);
    }

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void ga2$readTick(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        ProtoDrops.tick = this.level.getServer().getTickCount();
    }

    @Inject(method = "processUnloads", at = @At("TAIL"))
    private void ga2$dropIdle(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        Long2IntLinkedOpenHashMap idle = this.ga2$idle;
        if (idle == null || idle.isEmpty()) {
            return;
        }
        if (!ProtoDrops.active()) {
            idle.clear();
            return;
        }
        int now = ProtoDrops.tick;
        if (now - idle.get(idle.firstLongKey()) < ProtoDrops.IDLE_TICKS
            || !hasMoreTime.getAsBoolean() && idle.size() <= ProtoDrops.FORCE_SIZE) {
            return;
        }
        ChunkyHolds holds = ((ChunkyHolds.Owner) this.level.getChunkSource()).ga2$chunkyHolds();
        int lowest = ChunkLevel.byStatus(FullChunkStatus.FULL) + 2;
        int drops = 0;
        for (int visits = 0; visits < ProtoDrops.MAX_VISITS && drops < ProtoDrops.MAX_DROPS && !idle.isEmpty(); visits++) {
            long pos = idle.firstLongKey();
            if (now - idle.get(pos) < ProtoDrops.IDLE_TICKS) {
                // the rest were marked later
                return;
            }
            idle.removeFirstInt();
            ChunkHolder holder = this.updatingChunkMap.get(pos);
            if (holder == null || holder.getTicketLevel() < lowest || !ChunkLevel.isLoaded(holder.getTicketLevel())) {
                continue;
            }
            if (holds != null && holds.isKept(pos)) {
                ProtoDrops.KEPT_FOR_CHUNKY.increment();
                continue;
            }
            ProtoDrops.Holder h = (ProtoDrops.Holder) holder;
            if (!holder.isReadyForSaving() || h.ga2$busy() || now - h.ga2$lastUse() < ProtoDrops.IDLE_TICKS) {
                // in use since its mark: it waits again, as ModernFix's entry did
                idle.putAndMoveToLast(pos, now);
                continue;
            }
            if (holder.getLatestChunk() == null) {
                continue;
            }
            if (drops > 0 && !hasMoreTime.getAsBoolean() && idle.size() <= ProtoDrops.FORCE_SIZE) {
                idle.putAndMoveToFirst(pos, now - ProtoDrops.IDLE_TICKS);
                return;
            }
            // vanilla's unload task, run at once: it takes the holder out of pendingUnloads and saves the chunk
            this.pendingUnloads.put(pos, holder);
            ProtoDrops.runNow = true;
            try {
                this.scheduleUnload(pos, holder);
            } finally {
                ProtoDrops.runNow = false;
            }
            if (this.pendingUnloads.get(pos) == holder) {
                // the task did not run here. Once it runs, it finds the holder gone and does nothing
                this.pendingUnloads.remove(pos);
                ProtoDrops.fail();
                return;
            }
            h.ga2$resetFutures();
            ProtoDrops.DROPPED.increment();
            drops++;
        }
    }
}
