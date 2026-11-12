package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.TickingHolders;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the chunk map's set of block ticking holders in step with each holder's level ({@link TickingHolders}). */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderTickingMixin {
    @Inject(method = "updateFutures", at = @At("TAIL"))
    private void ga2$trackTicking(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ChunkHolder self = (ChunkHolder) (Object) this;
        TickingHolders.update((TickingHolders.Owner) chunkMap, self,
            ChunkLevel.fullStatus(self.getTicketLevel()).isOrAfter(FullChunkStatus.BLOCK_TICKING));
    }
}
