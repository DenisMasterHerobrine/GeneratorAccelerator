package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.TickingHolders;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code tickChunks} finds the ticking chunks among the block ticking holders ({@link TickingHolders}) instead of among every
 * holder of the map. Its list is sized for those holders.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheTickingMixin {
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;getChunks()Ljava/lang/Iterable;"))
    private Iterable<ChunkHolder> ga2$tickingHolders(ChunkMap chunkMap, Operation<Iterable<ChunkHolder>> original) {
        return TickingHolders.enabled() ? ((TickingHolders.Owner) chunkMap).ga2$tickingHolders() : original.call(chunkMap);
    }

    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;size()I"))
    private int ga2$tickingCount(ChunkMap chunkMap, Operation<Integer> original) {
        return TickingHolders.enabled() ? ((TickingHolders.Owner) chunkMap).ga2$tickingHolders().size() : original.call(chunkMap);
    }
}
