package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureCensus;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.ParallelSteps;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepLane;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepThreads;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the STRUCTURE_STARTS, STRUCTURE_REFERENCES, SURFACE, CARVERS and FEATURES steps to {@link ParallelSteps}. Also hands over
 * INITIALIZE_LIGHT, which must not overlap a decoration writing into its chunk. When {@link ParallelSteps} declines a
 * step, vanilla runs it. SURFACE and CARVERS hand over only the generator call inside vanilla's step.
 */
@Mixin(value = ChunkStatusTasks.class, priority = 5000)
public abstract class ChunkStatusTasksMixin {
    /** The noise and biome steps queue at their chunk's priority ({@link StepLane}) when GA2 runs vanilla's scheduling. */
    @WrapMethod(method = "generateNoise")
    private static CompletableFuture<ChunkAccess> ga2$noiseLane(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                          Operation<CompletableFuture<ChunkAccess>> original) {
        if (!StepThreads.onVanillaMailbox()) {
            return original.call(ctx, step, cache, chunk);
        }
        return StepLane.withLevel(StepLane.level(cache, chunk), () -> original.call(ctx, step, cache, chunk));
    }

    @WrapMethod(method = "generateBiomes")
    private static CompletableFuture<ChunkAccess> ga2$biomeLane(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                          Operation<CompletableFuture<ChunkAccess>> original) {
        if (!StepThreads.onVanillaMailbox()) {
            return original.call(ctx, step, cache, chunk);
        }
        return StepLane.withLevel(StepLane.level(cache, chunk), () -> original.call(ctx, step, cache, chunk));
    }

    /**
     * SURFACE when other mods put code before the generator call and GA2 knows that code can run on a worker. OTBWG
     * builds its crag and basalt terrain there. GA2 runs the step itself. The region is built on the worldgen thread.
     * That code and the generator call run on the pool ({@link ParallelSteps#surfaceStep}). The mixin plugin records
     * other mods' changes to the method after this callback ({@code HookTargets.SURFACE_STEP_BODY}). If GA2 does not
     * handle one of those changes, vanilla's step runs.
     */
    @Inject(method = "generateSurface", at = @At("HEAD"), cancellable = true)
    private static void ga2$surfaceStep(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.surfaceStep(ctx, step, cache, chunk);
        if (future != null) {
            cir.setReturnValue(future);
        }
    }

    /**
     * SURFACE in all other cases. Vanilla's step runs as usual on the worldgen thread, including the region and whatever
     * other mods do before the generator call. Only the generator's {@code buildSurface} goes to the worker pool. The
     * step returns the future of that call ({@link #ga2$surfaceFuture}). The mixin plugin records other mods' changes
     * after this call ({@code HookTargets.SURFACE_STEP}). If there are any, the call runs inline.
     */
    @WrapOperation(method = "generateSurface", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
        require = 0)
    private static void ga2$parallelSurface(ChunkGenerator generator, WorldGenRegion region, StructureManager structures, RandomState randomState, ChunkAccess chunk,
                                            Operation<Void> original, @Local(argsOnly = true) ChunkStep step,
                                            @Local(argsOnly = true) StaticCache2D<GenerationChunkHolder> cache,
                                            @Share("ga2$async") LocalRef<CompletableFuture<ChunkAccess>> async) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.surface(step, cache, chunk, generator, region,
            () -> original.call(generator, region, structures, randomState, chunk));
        if (future == null) {
            original.call(generator, region, structures, randomState, chunk);
        } else {
            async.set(future);
        }
    }

    @ModifyReturnValue(method = "generateSurface", at = @At("RETURN"))
    private static CompletableFuture<ChunkAccess> ga2$surfaceFuture(CompletableFuture<ChunkAccess> done, @Share("ga2$async") LocalRef<CompletableFuture<ChunkAccess>> async) {
        CompletableFuture<ChunkAccess> future = async.get();
        return future != null ? future : done;
    }

    /** CARVERS, handled like SURFACE. The region and the carving mask filter around old chunks stay on the worldgen thread. */
    @WrapOperation(method = "generateCarvers", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;applyCarvers(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"),
        require = 0)
    private static void ga2$parallelCarvers(ChunkGenerator generator, WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                                            StructureManager structures, ChunkAccess chunk, Operation<Void> original,
                                            @Local(argsOnly = true) ChunkStep step, @Local(argsOnly = true) StaticCache2D<GenerationChunkHolder> cache,
                                            @Share("ga2$async") LocalRef<CompletableFuture<ChunkAccess>> async) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.carvers(step, cache, chunk, generator,
            () -> original.call(generator, region, seed, randomState, biomeManager, structures, chunk));
        if (future == null) {
            original.call(generator, region, seed, randomState, biomeManager, structures, chunk);
        } else {
            async.set(future);
        }
    }

    @ModifyReturnValue(method = "generateCarvers", at = @At("RETURN"))
    private static CompletableFuture<ChunkAccess> ga2$carversFuture(CompletableFuture<ChunkAccess> done, @Share("ga2$async") LocalRef<CompletableFuture<ChunkAccess>> async) {
        CompletableFuture<ChunkAccess> future = async.get();
        return future != null ? future : done;
    }

    @Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
    private static void ga2$parallelStructureStarts(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.structureStarts(ctx, step, cache, chunk);
        if (future != null) {
            cir.setReturnValue(future);
        }
    }

    @Inject(method = "generateStructureReferences", at = @At("HEAD"), cancellable = true)
    private static void ga2$parallelStructureReferences(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.structureReferences(ctx, step, cache, chunk);
        if (future != null) {
            cir.setReturnValue(future);
        }
    }

    /** Primes the four final heightmaps from the two worldgen heightmaps the chunk kept exact. See {@link HeightmapPriming}. */
    @WrapOperation(method = "generateFeatures", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"), require = 0)
    private static void ga2$primeFromWorldgenMaps(ChunkAccess chunk, Set<Heightmap.Types> types, Operation<Void> original) {
        HeightmapPriming.prime(chunk, types, (c, t) -> original.call(c, t));
    }

    /** While the decoration census runs ({@link FeatureCensus}), each decoration is timed. */
    @WrapOperation(method = "generateFeatures", at = @At(value = "INVOKE",
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

    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private static void ga2$parallelFeatures(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.features(ctx, step, cache, chunk);
        if (future != null) {
            cir.setReturnValue(future);
        }
    }

    @Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
    private static void ga2$initializeLight(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = ParallelSteps.initializeLight(ctx, step, cache, chunk);
        if (future != null) {
            cir.setReturnValue(future);
        }
    }
}
