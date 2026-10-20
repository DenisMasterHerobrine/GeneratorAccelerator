package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkPackets;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes a batch's block data ahead once the batch is chosen ({@link ChunkPackets}) and times each chunk's send. */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @WrapOperation(method = "sendNextChunks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/PlayerChunkSender;collectChunksToSend(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;"))
    private List<LevelChunk> ga2$prepareBatch(PlayerChunkSender self, ChunkMap map, ChunkPos center, Operation<List<LevelChunk>> original) {
        List<LevelChunk> batch = original.call(self, map, center);
        ChunkPackets.prepare(batch);
        return batch;
    }

    @WrapOperation(method = "sendNextChunks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/PlayerChunkSender;sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void ga2$timeSend(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, Operation<Void> original) {
        long start = System.nanoTime();
        original.call(connection, level, chunk);
        ChunkLoadStats.SEND.record(System.nanoTime() - start);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void ga2$endBatch(ServerPlayer player, CallbackInfo ci) {
        ChunkPackets.clear();
    }
}
