package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player who enters a level is sent every ticking chunk in view at once ({@code updatePlayerStatus}). Their block data is
 * written ahead ({@link ChunkPackets}). Each chunk's send is timed.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapChunkSendMixin {
    @Shadow @Final ServerLevel level;
    @Shadow int viewDistance;

    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    /** The chunks vanilla's loop below sends: the ticking ones in view of the player's chunk. */
    @WrapMethod(method = "updatePlayerStatus")
    private void ga2$prepareJoin(ServerPlayer player, boolean added, Operation<Void> original) {
        if (!added || player.level() != this.level || !ChunkPackets.enabled()) {
            original.call(player, added);
            return;
        }
        int x = SectionPos.blockToSectionCoord(player.getBlockX());
        int z = SectionPos.blockToSectionCoord(player.getBlockZ());
        int distance = this.viewDistance;
        List<LevelChunk> chunks = new ArrayList<>();
        for (int cx = x - distance - 1; cx <= x + distance + 1; cx++) {
            for (int cz = z - distance - 1; cz <= z + distance + 1; cz++) {
                if (ChunkMap.isChunkInRange(cx, cz, x, z, distance)) {
                    ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(cx, cz));
                    LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
                }
            }
        }
        ChunkPackets.prepare(chunks);
        try {
            original.call(player, added);
        } finally {
            ChunkPackets.clear();
        }
    }

    @WrapOperation(method = "updateChunkTracking", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;playerLoadedChunk(Lnet/minecraft/server/level/ServerPlayer;Lorg/apache/commons/lang3/mutable/MutableObject;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void ga2$timeSend(ChunkMap map, ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk,
                              Operation<Void> original) {
        long start = System.nanoTime();
        original.call(map, player, packet, chunk);
        ChunkLoadStats.SEND.record(System.nanoTime() - start);
    }
}
