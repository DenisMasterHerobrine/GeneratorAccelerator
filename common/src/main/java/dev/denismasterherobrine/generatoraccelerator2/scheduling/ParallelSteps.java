package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Runs the SURFACE and CARVERS chunk steps on the worker pool, the way vanilla already runs NOISE.
 *
 * <p>Vanilla runs both inside {@code ChunkGenerationTask.runUntilWait} on the single worldgen
 * mailbox, so only one chunk's surface or carvers run at a time. Each step only writes its own chunk.
 * It only reads data that is final once the step may start: the chunk's own noise, neighbour biomes,
 * structure starts. The dependency system orders steps by future completion rather than by thread. A
 * chunk's next step, and every neighbour step that reads or writes the chunk, waits for the returned
 * future. Returning a future completed on a worker therefore generates the same chunks.
 *
 * <p>Everything that touches other chunks' mutable state or blocks on IO stays on the mailbox
 * thread, as for NOISE: the generation region, the structure manager view, {@code Blender.of} and the
 * carving mask filter around old chunks. {@code Blender.of} stays there because old world blending
 * data is computed lazily and unsynchronized. Vanilla's own step body does all of that, and only its
 * generator call is handed over. So other mods' code in the step before that call keeps running where
 * and when vanilla runs it. The exception is code that GA2 knows can run on a worker
 * ({@link StepHandlers}). GA2 then runs the SURFACE step itself and moves that code to the worker with
 * the call. A chunk without a NoiseChunk (reloaded from disk between steps) would build one, including
 * a Blender, inside the step, so that case runs inline.
 */
public final class ParallelSteps {
    private ParallelSteps() {}

    public static final LongAdder SURFACE_ASYNC = new LongAdder();
    public static final LongAdder SURFACE_INLINE = new LongAdder();
    public static final LongAdder CARVERS_ASYNC = new LongAdder();
    public static final LongAdder CARVERS_INLINE = new LongAdder();
    public static final LongAdder FEATURES_ASYNC = new LongAdder();
    public static final LongAdder FEATURES_INLINE = new LongAdder();
    public static final LongAdder STARTS_ASYNC = new LongAdder();
    public static final LongAdder STARTS_INLINE = new LongAdder();
    public static final LongAdder REFERENCES_ASYNC = new LongAdder();
    public static final LongAdder REFERENCES_INLINE = new LongAdder();

    /** SURFACE steps GA2 ran itself, with other mods' code before the generator call moved to the worker ({@link #surfaceStep}). */
    public static final LongAdder SURFACE_TAKEN = new LongAdder();

    /**
     * The whole SURFACE step, when other mods add code before its generator call that GA2 knows can run on a worker
     * ({@link StepHandlers}). The region, the structure manager view and the blender are made here, on the worldgen thread,
     * as vanilla's step makes them. That code and the generator call then run on the worker pool, in vanilla's order.
     * Without such code the step stays vanilla's, with only the generator call on the pool ({@link #surface}).
     *
     * @return the step's future, or {@code null} to run vanilla's step
     */
    public static CompletableFuture<ChunkAccess> surfaceStep(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (!SchedulingConfig.parallelSurface() || !StepThreads.onVanillaMailbox() || !HookTargets.allowed(HookTargets.SURFACE_STEP_BODY)
            || !HookTargets.unchanged(HookTargets.SURFACE_ENTRY)) {
            return null;
        }
        StepHandlers.Handler[] handlers = StepHandlers.surface();
        if (handlers == null) {
            return null;
        }
        ChunkGenerator generator = ctx.generator();
        if (generator.getClass() == NoiseBasedChunkGenerator.class && ((ChunkAccessAccessor) chunk).ga2$noiseChunk() == null
            && dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.of(chunk) == null) {
            // a NoiseChunk would be built inside the step, blender included. Run vanilla's step, all inline
            return null;
        }
        ServerLevel level = ctx.level();
        WorldGenRegion region = new WorldGenRegion(level, cache, step, chunk);
        net.minecraft.world.level.StructureManager structures = level.structureManager().forWorldGenRegion(region);
        net.minecraft.world.level.levelgen.RandomState randomState = level.getChunkSource().randomState();
        Blender blender = Blender.of(region);
        StepHandlers.Frame frame = new StepHandlers.Frame(ctx, step, cache, chunk, level, region);
        SURFACE_ASYNC.increment();
        SURFACE_TAKEN.increment();
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_surface", () -> {
            run(() -> {
                StepHandlers.run(handlers, frame, false);
                StepThreads.withBlender(blender, () -> generator.buildSurface(region, structures, randomState, chunk));
                StepHandlers.run(handlers, frame, true);
            }, step, chunk, generator);
            return chunk;
        }), StepLane.executor(StepLane.level(cache, chunk)));
    }

    /**
     * The generator call of the SURFACE step on the worker pool. {@code call} runs it with vanilla's arguments.
     * Vanilla's step has built the region and done everything other mods add before the call on the worldgen thread.
     *
     * @return the step's future, or {@code null} to make the call inline
     */
    public static CompletableFuture<ChunkAccess> surface(ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, ChunkGenerator generator,
                                                         WorldGenRegion region, Runnable call) {
        if (!SchedulingConfig.parallelSurface() || !StepThreads.onVanillaMailbox() || !HookTargets.unchanged(HookTargets.SURFACE_STEP)) {
            // off, another chunk system's thread, or another mod's code after the call in the step
            SURFACE_INLINE.increment();
            return null;
        }
        boolean noiseBased = generator.getClass() == NoiseBasedChunkGenerator.class;
        if (noiseBased && ((ChunkAccessAccessor) chunk).ga2$noiseChunk() == null
            && dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.of(chunk) == null) {
            SURFACE_INLINE.increment();
            return null;
        }
        if (!HookTargets.unchanged(HookTargets.SURFACE_ENTRY)) {
            // another mod wraps the blender or the inner call: keep the step where vanilla runs it
            SURFACE_INLINE.increment();
            return null;
        }
        // the generator's own entry point, so other mods' changes to it keep working. The blender is computed here, on the
        // worldgen thread, like vanilla's step computes it. SurfaceBlenderMixin hands it in
        Blender blender = Blender.of(region);
        Runnable work = () -> StepThreads.withBlender(blender, call);
        SURFACE_ASYNC.increment();
        java.util.function.Supplier<ChunkAccess> body = Util.wrapThreadWithTaskName("wgen_surface", () -> {
            run(work, step, chunk, generator);
            return chunk;
        });
        java.util.concurrent.Executor executor = StepLane.executor(StepLane.level(cache, chunk));
        // the GPU's surface may still be on its way. The step then runs once the surface is in (or the wait for it is over),
        // instead of a worker waiting for it
        CompletableFuture<Void> gpu = dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob.whenReady(chunk);
        if (gpu != null) {
            SURFACE_DEFERRED.increment();
            return gpu.thenApplyAsync(v -> body.get(), executor);
        }
        return CompletableFuture.supplyAsync(body, executor);
    }

    /** SURFACE steps run once the GPU's surface was in, rather than waiting for it on a worker. */
    public static final java.util.concurrent.atomic.LongAdder SURFACE_DEFERRED = new java.util.concurrent.atomic.LongAdder();

    /**
     * The generator call of the CARVERS step on the worker pool. Vanilla's step has built the region and applied the
     * carving mask filter around old chunks on the worldgen thread.
     *
     * @return the step's future, or {@code null} to make the call inline
     */
    public static CompletableFuture<ChunkAccess> carvers(ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, ChunkGenerator generator,
                                                         Runnable call) {
        if (!SchedulingConfig.parallelCarvers() || !StepThreads.onVanillaMailbox() || !HookTargets.unchanged(HookTargets.CARVER_STEP)) {
            CARVERS_INLINE.increment();
            return null;
        }
        if (generator.getClass() == NoiseBasedChunkGenerator.class && ((ChunkAccessAccessor) chunk).ga2$noiseChunk() == null
            && dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.of(chunk) == null) {
            CARVERS_INLINE.increment();
            return null;
        }
        CARVERS_ASYNC.increment();
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_carvers", () -> {
            run(call, step, chunk, generator);
            return chunk;
        }), StepLane.executor(StepLane.level(cache, chunk)));
    }

    public static final LongAdder FEATURES_WAITED = new LongAdder();
    /** From a FEATURES step scheduled to its locks granted. */
    public static final dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat FEATURES_LOCK_WAIT = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat();
    public static final LongAdder LIGHT_WAITED = new LongAdder();
    public static final LongAdder LIGHT_INIT_ASYNC = new LongAdder();

    public static final LongAdder STARTS_AFTER_RINGS = new LongAdder();

    /**
     * STRUCTURE_STARTS on the worker pool. The step writes only its own chunk's structure starts. No other
     * step reads them before this one completes, since every reader needs STRUCTURE_STARTS within radius 8.
     * The structure mixins serialize the structures that lay themselves out through global state.
     *
     * <p>A ring placement (strongholds) asks for its ring positions and waits for them. The level computes them on
     * the worker pool from its construction on. While they are being computed, the step is queued behind them instead.
     * Every step waiting inside the pool would make the pool start another thread, because the pool keeps its
     * parallelism while a task blocks. A few dozen steps at the start of a world left the pool at a hundred threads. A
     * chunk that no ring position can be ({@code RingBounds}, near the origin) is answered without them and does not wait.
     *
     * @return the step's future, or {@code null} to let vanilla run the step inline
     */
    public static CompletableFuture<ChunkAccess> structureStarts(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (StepThreads.runningVanilla()) {
            return null;
        }
        if (!SchedulingConfig.parallelStructureStarts() || !StepThreads.onVanillaMailbox()) {
            STARTS_INLINE.increment();
            return null;
        }
        STARTS_ASYNC.increment();
        MinecraftServer server = ctx.level().getServer();
        java.util.function.Supplier<ChunkAccess> body = Util.wrapThreadWithTaskName("wgen_structure_starts", () -> StepThreads.guarded(server, () -> {
            run(() -> StepThreads.vanilla(() -> step.task().doWork(ctx, step, cache, chunk)).join(), step, chunk, ctx.generator());
            return chunk;
        }));
        java.util.concurrent.Executor executor = StepLane.executor(StepLane.level(cache, chunk));
        CompletableFuture<?> rings = pendingRings(ctx.level(), chunk.getPos());
        if (rings == null) {
            return CompletableFuture.supplyAsync(body, executor);
        }
        STARTS_AFTER_RINGS.increment();
        RingSearches.hurry();
        // a failed ring computation fails the step as it would in vanilla (the step's own wait rethrows it)
        return rings.handle((done, failure) -> null).thenApplyAsync(ignored -> body.get(), executor);
    }

    /**
     * The ring positions still being computed that the structure start of {@code pos} can ask for, or null when there are
     * none. There are none when all are done, or when no ring position can be the chunk. Ring positions never change once
     * the level exists.
     */
    @javax.annotation.Nullable
    private static CompletableFuture<?> pendingRings(ServerLevel level, ChunkPos pos) {
        net.minecraft.world.level.chunk.ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        java.util.Map<net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement, ? extends CompletableFuture<?>> rings =
            ((dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkGeneratorStructureStateAccessor) state).ga2$ringPositions();
        java.util.List<CompletableFuture<?>> pending = null;
        for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement, ? extends CompletableFuture<?>> e : rings.entrySet()) {
            CompletableFuture<?> f = e.getValue();
            if (!f.isDone() && !dev.denismasterherobrine.generatoraccelerator2.structure.RingBounds.startNeverWaits(state, e.getKey(), pos.x, pos.z)) {
                if (pending == null) {
                    pending = new java.util.ArrayList<>();
                }
                pending.add(f);
            }
        }
        return pending == null ? null : CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    /**
     * STRUCTURE_REFERENCES on the worker pool. The step reads the structure starts within 8 chunks, which are complete
     * before this step can run. It writes only this chunk's references.
     *
     * @return the step's future, or {@code null} to let vanilla run the step inline
     */
    public static CompletableFuture<ChunkAccess> structureReferences(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (StepThreads.runningVanilla()) {
            return null;
        }
        if (!SchedulingConfig.parallelStructureReferences() || !StepThreads.onVanillaMailbox()) {
            REFERENCES_INLINE.increment();
            return null;
        }
        REFERENCES_ASYNC.increment();
        MinecraftServer server = ctx.level().getServer();
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_structure_references", () -> StepThreads.guarded(server, () -> {
            run(() -> StepThreads.vanilla(() -> step.task().doWork(ctx, step, cache, chunk)).join(), step, chunk, ctx.generator());
            return chunk;
        })), StepLane.executor(StepLane.level(cache, chunk)));
    }

    /**
     * FEATURES on the worker pool, under the area locks of {@link AreaLocks}. The decoration writes its 3x3
     * chunks and reads farther. So no other decoration within the guard radius runs at the same time, and
     * neither does a light initialization of a chunk the decoration writes. The whole original step
     * (heightmap priming included) runs after the grant. The locks are released when the step finishes.
     *
     * @return the step's future, or {@code null} to let vanilla run the step inline
     */
    public static CompletableFuture<ChunkAccess> features(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (StepThreads.runningVanilla()) {
            return null;
        }
        if (!StepThreads.onVanillaMailbox()) {
            FEATURES_INLINE.increment();
            return null;
        }
        AreaLocks locks = AreaLocks.of(ctx.level());
        if (!SchedulingConfig.parallelFeatures() && locks.idle()) {
            FEATURES_INLINE.increment();
            return null;
        }
        FEATURES_ASYNC.increment();
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        AreaLocks.Request request = AreaLocks.features(chunk.getPos(), SchedulingConfig.featuresGuardRadius(), StepLane.level(cache, chunk));
        MinecraftServer server = ctx.level().getServer();
        Runnable granted = dispatcher("wgen_features", server, locks, request, ctx, step, cache, chunk, result);
        long scheduled = System.nanoTime();
        Runnable dispatch = () -> {
            FEATURES_LOCK_WAIT.since(scheduled);
            granted.run();
        };
        if (locks.acquireOrPark(request, dispatch)) {
            dispatch.run();
        } else {
            FEATURES_WAITED.increment();
        }
        return result;
    }

    /**
     * INITIALIZE_LIGHT, kept from running while a parallel decoration writes into the chunk. The step's sky
     * light source scan would see half a feature. The step holds a read key on its chunk, which keeps out every
     * decoration that writes there. With {@code parallelLightInit} and {@code parallelFeatures} on, a granted step
     * runs on the worker pool. With either off, the step runs inline as vanilla does: directly when no lock is held
     * or parked, otherwise holding the key, or later on the pool once the decorations around it finish. The key
     * covers the synchronous part, as the mailbox thread does in vanilla. The light engine finishes the step on its
     * own thread.
     *
     * @return the step's future, or {@code null} to let vanilla run the step inline
     */
    public static CompletableFuture<ChunkAccess> initializeLight(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (StepThreads.runningVanilla() || !StepThreads.onVanillaMailbox()) {
            return null;
        }
        if (chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) {
            // loaded from disk: every neighbour that could decorate into it is past FEATURES, so nothing writes into it any
            // more. Vanilla's inline step costs less than a trip through the pool
            return null;
        }
        AreaLocks locks = AreaLocks.of(ctx.level());
        // On the pool while its read key keeps out every decoration that writes the chunk. Decorations take area locks only when parallel.
        boolean async = SchedulingConfig.parallelLightInit() && SchedulingConfig.parallelFeatures();
        if (!async && locks.idle()) {
            // every request is registered by this thread, so nothing can start while this step runs
            return null;
        }
        AreaLocks.Request request = AreaLocks.single(chunk.getPos(), StepLane.level(cache, chunk));
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        MinecraftServer server = ctx.level().getServer();
        Runnable dispatch = dispatcher("wgen_light_init", server, locks, request, ctx, step, cache, chunk, result);
        if (locks.acquireOrPark(request, dispatch)) {
            if (async) {
                LIGHT_INIT_ASYNC.increment();
                dispatch.run();
                return result;
            }
            CompletableFuture<ChunkAccess> inner;
            try {
                inner = StepThreads.vanilla(() -> step.task().doWork(ctx, step, cache, chunk));
            } finally {
                locks.release(request);
            }
            return inner;
        }
        LIGHT_WAITED.increment();
        return result;
    }

    /** Hands a granted request's step to the worker pool. If the pool refuses (shutting down), the locks are released and the step fails. */
    private static Runnable dispatcher(String taskName, MinecraftServer server, AreaLocks locks, AreaLocks.Request request, WorldGenContext ctx, ChunkStep step,
                                       StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, CompletableFuture<ChunkAccess> result) {
        return () -> {
            try {
                StepLane.execute(StepLane.level(cache, chunk), Util.wrapThreadWithTaskName(taskName, () -> {
                    StepThreads.guarded(server, () -> runLocked(locks, request, ctx, step, cache, chunk, result));
                }));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                locks.release(request);
                result.completeExceptionally(e);
            }
        };
    }

    /** Runs the original step while {@code request}'s locks are held, releases them, then completes {@code result}. */
    @SuppressWarnings("unchecked")
    private static Void runLocked(AreaLocks locks, AreaLocks.Request request, WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
                                  ChunkAccess chunk, CompletableFuture<ChunkAccess> result) {
        CompletableFuture<ChunkAccess> inner;
        try {
            CompletableFuture<ChunkAccess>[] holder = new CompletableFuture[1];
            run(() -> holder[0] = StepThreads.vanilla(() -> step.task().doWork(ctx, step, cache, chunk)), step, chunk, ctx.generator());
            inner = holder[0];
        } catch (Throwable t) {
            locks.release(request);
            result.completeExceptionally(t);
            return null;
        }
        locks.release(request);
        inner.whenComplete((done, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                result.complete(done);
            }
        });
        return null;
    }

    /** Step bodies running on this thread (one inside another only if a body runs another step itself). */
    private static final ThreadLocal<int[]> RUNNING = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Runs a step body. A failure carries the same crash report vanilla builds in {@code ChunkMap.applyStep}. When the thread's
     * outermost body ends, the thread's reused fast-path objects let go of the region
     * ({@link dev.denismasterherobrine.generatoraccelerator2.features.region.RegionRefs}).
     */
    private static void run(Runnable work, ChunkStep step, ChunkAccess chunk, ChunkGenerator generator) {
        int[] running = RUNNING.get();
        running[0]++;
        try {
            work.run();
        } catch (ReportedException e) {
            throw e;
        } catch (Exception exception) {
            ChunkPos pos = chunk.getPos();
            CrashReport report = CrashReport.forThrowable(exception, "Exception generating new chunk");
            CrashReportCategory category = report.addCategory("Chunk to be generated");
            category.setDetail("Status being generated", () -> step.targetStatus().getName());
            category.setDetail("Location", String.format(Locale.ROOT, "%d,%d", pos.x, pos.z));
            category.setDetail("Position hash", ChunkPos.asLong(pos.x, pos.z));
            category.setDetail("Generator", generator);
            category.setDetail("Thread", Thread.currentThread().getName());
            throw new ReportedException(report);
        } finally {
            if (--running[0] == 0) {
                dev.denismasterherobrine.generatoraccelerator2.features.region.RegionRefs.release();
            }
        }
    }
}
