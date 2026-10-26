package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.PresentChunks;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A chunk asked off the server thread that already has the status is answered at once ({@link PresentChunks}). */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCachePresentMixin {
    @Shadow
    @Final
    Thread mainThread;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void ga2$presentOffThread(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() == this.mainThread) {
            return;
        }
        ChunkAccess chunk = PresentChunks.present((ServerChunkCache) (Object) this, x, z, status);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }
}
