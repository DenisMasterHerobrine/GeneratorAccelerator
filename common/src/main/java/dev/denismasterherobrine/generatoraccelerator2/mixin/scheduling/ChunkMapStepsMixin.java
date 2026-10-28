package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Either;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepThreads;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the thread running a step of vanilla's chunk map, see {@link StepThreads}. The step runs in the lambda of
 * {@code scheduleChunkGeneration} that sees the step's chunks, on the worldgen mailbox. Forge recompiles the class, which
 * renames and reorders that lambda. It is the only method of the class that calls {@code ChunkStatus.generate}.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapStepsMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkStatus;generate(Ljava/util/concurrent/Executor;Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;"
            + "Lnet/minecraft/server/level/ThreadedLevelLightEngine;Ljava/util/function/Function;Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> ga2$markMailbox(ChunkStatus status, Executor executor, ServerLevel level,
            ChunkGenerator generator, StructureTemplateManager templates, ThreadedLevelLightEngine lightEngine,
            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk, List<ChunkAccess> neighbors,
            Operation<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> original, @Local(argsOnly = true) ChunkHolder holder) {
        return StepThreads.runOnMailbox(holder, () -> original.call(status, executor, level, generator, templates, lightEngine, fullChunk, neighbors));
    }
}
