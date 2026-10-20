package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes each chunk unload, so a read back soon after it is counted ({@link ChunkLoadStats#loadedFromDisk}). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelUnloadMixin {
    @Inject(method = "unload", at = @At("HEAD"))
    private void ga2$noteUnload(LevelChunk chunk, CallbackInfo ci) {
        ChunkLoadStats.unloaded(this, chunk.getPos());
    }
}
