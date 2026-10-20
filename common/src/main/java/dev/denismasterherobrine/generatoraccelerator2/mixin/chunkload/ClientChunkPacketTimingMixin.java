package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times how long the render thread applies a chunk packet. The network thread first hands the packet to the render thread.
 * That call is not counted.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientChunkPacketTimingMixin {
    @Unique
    private long ga2$start;

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void ga2$applyStart(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        this.ga2$start = Minecraft.getInstance().isSameThread() ? System.nanoTime() : 0;
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void ga2$applyEnd(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (this.ga2$start != 0 && Minecraft.getInstance().isSameThread()) {
            ChunkLoadStats.CLIENT_CHUNK.record(System.nanoTime() - this.ga2$start);
        }
        this.ga2$start = 0;
    }
}
