package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.scheduling.RingSearches;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The level's ring position searches go to the worker pool a few at a time ({@link RingSearches}). */
@Mixin(value = ChunkGeneratorStructureState.class, priority = 5000)
public abstract class RingSearchesMixin {
    @Shadow @Final private Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringPositions;

    @ModifyArg(method = "generateRingPositions", require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        index = 1)
    private Executor ga2$fewAtATime(Executor executor) {
        return RingSearches.ENABLED ? RingSearches.executor() : executor;
    }

    /** A caller is about to wait for the positions. The remaining searches get the whole pool. */
    @Inject(method = "getRingPositionsFor", at = @At("HEAD"))
    private void ga2$hurry(ConcentricRingsStructurePlacement placement, CallbackInfoReturnable<List<ChunkPos>> cir) {
        CompletableFuture<List<ChunkPos>> positions = this.ringPositions.get(placement);
        if (positions != null && !positions.isDone()) {
            RingSearches.hurry();
        }
    }
}
