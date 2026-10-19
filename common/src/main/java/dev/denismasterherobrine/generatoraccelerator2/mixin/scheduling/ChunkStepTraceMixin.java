package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds {@link StepTrace} ({@code -Dgeneratoraccelerator2.stepTrace=true}). Records each step's call and result per chunk. */
@Mixin(ChunkStep.class)
public abstract class ChunkStepTraceMixin {
    @Shadow
    @Final
    private ChunkStatus targetStatus;

    @Inject(method = "apply", at = @At("HEAD"))
    private void ga2$traceStart(WorldGenContext ctx, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir, @Share("ga2$start") LocalLongRef start) {
        if (StepTrace.ENABLED) {
            start.set(StepTrace.begin(this.targetStatus, chunk.getPos()));
        }
    }

    @ModifyReturnValue(method = "apply", at = @At("RETURN"))
    private CompletableFuture<ChunkAccess> ga2$traceEnd(CompletableFuture<ChunkAccess> result, WorldGenContext ctx, StaticCache2D<GenerationChunkHolder> cache,
                                                        ChunkAccess chunk, @Share("ga2$start") LocalLongRef start) {
        if (!StepTrace.ENABLED) {
            return result;
        }
        ChunkStatus status = this.targetStatus;
        long t0 = start.get();
        return result.whenComplete((c, e) -> StepTrace.end(status, chunk.getPos(), t0));
    }
}
