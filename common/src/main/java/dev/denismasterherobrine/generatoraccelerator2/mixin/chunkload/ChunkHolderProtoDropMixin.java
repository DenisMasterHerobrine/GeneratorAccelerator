package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.memory.ProtoDrops;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A holder's side of letting idle chunks go ({@link ProtoDrops}): the mark on a level change, the last use, the reset. */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderProtoDropMixin implements ProtoDrops.Holder {
    @Shadow
    private CompletableFuture<?> saveSync;

    @Unique
    private volatile int ga2$lastUse;

    /** Server thread, as ModernFix's mark (it queued one task per call instead). */
    @Inject(method = "updateFutures", at = @At("TAIL"))
    private void ga2$markIdle(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        if (!ProtoDrops.active()) {
            return;
        }
        ChunkHolder self = (ChunkHolder) (Object) this;
        int level = self.getTicketLevel();
        if (level >= ChunkLevel.byStatus(FullChunkStatus.FULL) + 2 && ChunkLevel.isLoaded(level)) {
            ((ProtoDrops.Owner) chunkMap).ga2$markIdle(self.getPos().toLong());
        }
    }

    /** Work the save waits for: a neighbour's generation step, the light. Any thread. */
    @Inject(method = "addSaveDependency", at = @At("RETURN"))
    private void ga2$used(CompletableFuture<?> future, CallbackInfo ci) {
        this.ga2$lastUse = ProtoDrops.tick;
    }

    @Override
    public int ga2$lastUse() {
        return this.ga2$lastUse;
    }

    @Override
    public boolean ga2$busy() {
        return ((GenerationChunkHolderAccessor) this).ga2$task().get() != null;
    }

    /** ModernFix's reset: every status future, the save dependencies and the started work. */
    @Override
    public void ga2$resetFutures() {
        GenerationChunkHolderAccessor g = (GenerationChunkHolderAccessor) this;
        AtomicReferenceArray<?> futures = g.ga2$futures();
        for (int i = 0; i < futures.length(); i++) {
            futures.set(i, null);
        }
        this.saveSync = CompletableFuture.completedFuture(null);
        g.ga2$startedWork().set(null);
    }
}
