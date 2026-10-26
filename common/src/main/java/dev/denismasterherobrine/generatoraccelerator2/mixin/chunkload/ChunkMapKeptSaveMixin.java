package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves the chunks {@link ChunkyHolds} keeps out of the early saves in {@code processUnloads}. They are saved when they are let
 * go, with their sections encoded ahead. Only this per-tick loop skips them. Autosaves, {@code /save-all} and shutdown go through
 * {@code saveAllChunks} and still save them.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapKeptSaveMixin {
    @Shadow @Final ServerLevel level;

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;)Z"))
    private boolean ga2$notWhileKept(ChunkMap self, ChunkHolder holder, Operation<Boolean> original) {
        ChunkyHolds holds = ((ChunkyHolds.Owner) this.level.getChunkSource()).ga2$chunkyHolds();
        if (holds != null && holds.isKept(holder.getPos().toLong())) {
            return false;
        }
        return original.call(self, holder);
    }
}
