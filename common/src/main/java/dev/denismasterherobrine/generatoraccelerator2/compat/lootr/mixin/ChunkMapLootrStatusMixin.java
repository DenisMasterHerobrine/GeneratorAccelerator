package dev.denismasterherobrine.generatoraccelerator2.compat.lootr.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrEntries;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows a chunk's Lootr entries once the chunk is FULL again and hides them when it drops below ({@link LootrEntries}). The call
 * for FULL comes after the chunk's full future completes.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLootrStatusMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "onFullChunkStatusChange", at = @At("HEAD"))
    private void ga2$lootrStatus(ChunkPos pos, FullChunkStatus status, CallbackInfo ci) {
        LootrEntries.ENTRIES.statusChanged(this.level.dimension(), pos.toLong(), status.isOrAfter(FullChunkStatus.FULL));
    }
}
