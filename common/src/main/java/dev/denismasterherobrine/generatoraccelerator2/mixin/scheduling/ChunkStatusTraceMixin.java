package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.mojang.datafixers.util.Either;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepTrace;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds {@link StepTrace} ({@code -Dgeneratoraccelerator2.stepTrace=true}). Records each step's call and result per chunk. */
@Mixin(ChunkStatus.class)
public abstract class ChunkStatusTraceMixin {
    @Inject(method = "generate", at = @At("HEAD"))
    private void ga2$traceStart(Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates, ThreadedLevelLightEngine lightEngine,
                                Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk, List<ChunkAccess> neighbors,
                                CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir, @Share("ga2$start") LocalLongRef start) {
        if (StepTrace.ENABLED) {
            start.set(StepTrace.begin((ChunkStatus) (Object) this, neighbors.get(neighbors.size() / 2).getPos()));
        }
    }

    @ModifyReturnValue(method = "generate", at = @At("RETURN"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> ga2$traceEnd(CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> result,
            Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates, ThreadedLevelLightEngine lightEngine,
            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk, List<ChunkAccess> neighbors,
            @Share("ga2$start") LocalLongRef start) {
        if (!StepTrace.ENABLED) {
            return result;
        }
        ChunkStatus status = (ChunkStatus) (Object) this;
        ChunkPos pos = neighbors.get(neighbors.size() / 2).getPos();
        long t0 = start.get();
        return result.whenComplete((c, e) -> StepTrace.end(status, pos, t0));
    }
}
