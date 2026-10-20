package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** A chunk packet takes its block data written ahead ({@link ChunkPackets}), when there is any, in place of writing it. */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketDataMixin {
    @Unique
    private byte[] ga2$prepared;

    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;calculateChunkSize(Lnet/minecraft/world/level/chunk/LevelChunk;)I"))
    private int ga2$preparedSize(LevelChunk chunk, Operation<Integer> original) {
        byte[] prepared = ChunkPackets.take(chunk);
        this.ga2$prepared = prepared;
        return prepared != null ? prepared.length : original.call(chunk);
    }

    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void ga2$preparedData(FriendlyByteBuf buf, LevelChunk chunk, Operation<Void> original) {
        byte[] prepared = this.ga2$prepared;
        this.ga2$prepared = null;
        if (prepared != null) {
            buf.writeBytes(prepared);
        } else {
            original.call(buf, chunk);
        }
    }
}
