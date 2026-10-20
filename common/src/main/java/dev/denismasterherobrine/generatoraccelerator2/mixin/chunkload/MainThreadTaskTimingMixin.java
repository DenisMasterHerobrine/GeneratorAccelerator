package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the chunk tasks the server thread runs: loads, promotions to full chunks, entity ticking. A task that waits for
 * another chunk runs other tasks while it waits. Those count as part of the outer task.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class MainThreadTaskTimingMixin {
    @Unique
    private int ga2$depth;
    @Unique
    private long ga2$start;

    @Inject(method = "doRunTask", at = @At("HEAD"))
    private void ga2$taskStart(Runnable task, CallbackInfo ci) {
        if (this.ga2$depth++ == 0) {
            this.ga2$start = System.nanoTime();
        }
    }

    @Inject(method = "doRunTask", at = @At("RETURN"))
    private void ga2$taskEnd(Runnable task, CallbackInfo ci) {
        if (--this.ga2$depth == 0) {
            ChunkLoadStats.MAIN_TASKS.record(System.nanoTime() - this.ga2$start);
        }
    }
}
