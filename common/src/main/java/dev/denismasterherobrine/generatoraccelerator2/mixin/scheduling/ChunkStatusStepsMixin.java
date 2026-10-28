package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Either;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureCensus;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.ParallelSteps;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepCall;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepLane;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepThreads;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands the STRUCTURE_STARTS, STRUCTURE_REFERENCES, SURFACE, CARVERS and FEATURES steps to {@link ParallelSteps}. Also hands over
 * INITIALIZE_LIGHT, which must not overlap a decoration writing into its chunk. When {@link ParallelSteps} declines a
 * step, vanilla runs it. SURFACE and CARVERS hand over only the generator call inside vanilla's task.
 *
 * <p>The tasks are lambdas in {@code ChunkStatus}, named here by their intermediary names: {@code method_16569} is SURFACE's,
 * {@code method_38282} CARVERS' and {@code method_51375} FEATURES'.
 */
@Mixin(value = ChunkStatus.class, priority = 5000)
public abstract class ChunkStatusStepsMixin {
    /** Each step's task, as {@code generate} runs it. */
    @WrapOperation(method = "generate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkStatus$GenerationTask;doWork(Lnet/minecraft/world/level/chunk/ChunkStatus;Ljava/util/concurrent/Executor;"
            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/server/level/ThreadedLevelLightEngine;"
            + "Ljava/util/function/Function;Ljava/util/List;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> ga2$steps(ChunkStatus.GenerationTask task, ChunkStatus status, Executor executor,
            ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates, ThreadedLevelLightEngine lightEngine,
            Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk, List<ChunkAccess> neighbors, ChunkAccess chunk,
            Operation<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> original) {
        StepCall call = new StepCall(status, executor, level, generator, templates, lightEngine, fullChunk, neighbors, chunk,
            () -> original.call(task, status, executor, level, generator, templates, lightEngine, fullChunk, neighbors, chunk));
        CompletableFuture<ChunkAccess> future = null;
        if (status == ChunkStatus.NOISE || status == ChunkStatus.BIOMES) {
            // the noise and biome steps queue at their chunk's priority (StepLane) when GA2 runs vanilla's scheduling
            if (StepThreads.onVanillaMailbox()) {
                return StepLane.withLevel(StepLane.level(call), call::runInline);
            }
        } else if (status == ChunkStatus.STRUCTURE_STARTS) {
            future = ParallelSteps.structureStarts(call);
        } else if (status == ChunkStatus.STRUCTURE_REFERENCES) {
            future = ParallelSteps.structureReferences(call);
        } else if (status == ChunkStatus.SURFACE) {
            // when other mods put code before the generator call and GA2 knows that code can run on a worker, GA2 runs the
            // whole step itself (OTBWG builds its crag and basalt terrain there). Otherwise only the call moves (ga2$parallelSurface)
            future = ParallelSteps.surfaceStep(call);
        } else if (status == ChunkStatus.FEATURES) {
            future = ParallelSteps.features(call);
        } else if (status == ChunkStatus.INITIALIZE_LIGHT) {
            future = ParallelSteps.initializeLight(call);
        }
        return future != null ? StepCall.result(future) : call.runInline();
    }

    /**
     * SURFACE in all other cases. Vanilla's task runs as usual on the worldgen thread, including the region and whatever
     * other mods do before the generator call. Only the generator's {@code buildSurface} goes to the worker pool, and the
     * step's future becomes that call's ({@link StepCall#async}). The mixin plugin records other mods' changes after this
     * call ({@code HookTargets.SURFACE_STEP}). If there are any, the call runs inline.
     */
    @WrapOperation(method = "method_16569", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
        require = 0)
    private static void ga2$parallelSurface(ChunkGenerator generator, WorldGenRegion region, StructureManager structures, RandomState randomState, ChunkAccess chunk,
                                            Operation<Void> original) {
        StepCall call = StepCall.current();
        CompletableFuture<ChunkAccess> future = call == null || call.chunk() != chunk ? null
            : ParallelSteps.surface(call, generator, region, () -> original.call(generator, region, structures, randomState, chunk));
        if (future == null) {
            original.call(generator, region, structures, randomState, chunk);
        } else {
            StepCall.async(future);
        }
    }

    /** CARVERS, handled like SURFACE. The region and the carving mask filter around old chunks stay on the worldgen thread. */
    @WrapOperation(method = "method_38282", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;applyCarvers(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V"),
        require = 0)
    private static void ga2$parallelCarvers(ChunkGenerator generator, WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                                            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving carving, Operation<Void> original) {
        StepCall call = StepCall.current();
        CompletableFuture<ChunkAccess> future = call == null || call.chunk() != chunk ? null
            : ParallelSteps.carvers(call, generator, () -> original.call(generator, region, seed, randomState, biomeManager, structures, chunk, carving));
        if (future == null) {
            original.call(generator, region, seed, randomState, biomeManager, structures, chunk, carving);
        } else {
            StepCall.async(future);
        }
    }

    /** Primes the four final heightmaps from the two worldgen heightmaps the chunk kept exact. See {@link HeightmapPriming}. */
    @WrapOperation(method = "method_51375", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"), require = 0)
    private static void ga2$primeFromWorldgenMaps(ChunkAccess chunk, Set<Heightmap.Types> types, Operation<Void> original) {
        HeightmapPriming.prime(chunk, types, (c, t) -> original.call(c, t));
    }

    /** While the decoration census runs ({@link FeatureCensus}), each decoration is timed. */
    @WrapOperation(method = "method_51375", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"),
        require = 0)
    private static void ga2$census(ChunkGenerator generator, WorldGenLevel region, ChunkAccess chunk, StructureManager structures,
                                   Operation<Void> original) {
        if (!FeatureCensus.on) {
            original.call(generator, region, chunk, structures);
            return;
        }
        FeatureCensus.chunkStart(region);
        try {
            original.call(generator, region, chunk, structures);
        } finally {
            FeatureCensus.chunkEnd();
        }
    }
}
