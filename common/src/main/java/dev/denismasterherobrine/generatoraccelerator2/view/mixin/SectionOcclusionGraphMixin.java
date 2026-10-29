package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.view.RenderThreads;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full rebuilds of the section occlusion graph run on their own thread ({@link RenderThreads#graph}). Vanilla's rebuild reads
 * the live camera's block position on the worker. Here it reads the position the rebuild was scheduled with, as it already does
 * for the camera's exact position.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
    @Shadow @Final private AtomicReference<SectionOcclusionGraph.GraphState> currentGraph;

    @Unique
    private BlockPos ga2$block;
    /** The graph shown last. Render thread only. */
    @Unique
    private SectionOcclusionGraph.GraphState ga2$shown;
    /** The last rebuilt graph and the camera position it started from. Written by the graph thread, position first. */
    @Unique
    private volatile Vec3 ga2$landedAt;
    @Unique
    private volatile SectionOcclusionGraph.GraphState ga2$landed;
    @Unique
    private Vec3 ga2$position;

    @Inject(method = "scheduleFullUpdate", require = 0, at = @At("HEAD"))
    private void ga2$snapshotCamera(boolean smartCull, Camera camera, Vec3 cameraPos, LongOpenHashSet loadedEmptySections, CallbackInfo ci) {
        this.ga2$position = cameraPos;
        this.ga2$block = camera.blockPosition().immutable();
    }

    @WrapOperation(method = "scheduleFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/Util;backgroundExecutor()Lnet/minecraft/TracingExecutor;"))
    private TracingExecutor ga2$graphExecutor(Operation<TracingExecutor> original) {
        return RenderThreads.graph(original.call());
    }

    /** Times the rebuild and records the graph it made with its camera. */
    @WrapOperation(method = "scheduleFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> ga2$rebuild(Runnable task, Executor executor, Operation<CompletableFuture<Void>> original) {
        Vec3 position = this.ga2$position;
        Runnable rebuild = () -> {
            long start = System.nanoTime();
            task.run();
            ChunkLoadStats.GRAPH_REBUILD.record(System.nanoTime() - start);
            this.ga2$landedAt = position;
            this.ga2$landed = this.currentGraph.get();
        };
        return original.call(rebuild, executor);
    }

    @WrapOperation(method = "initializeQueueForFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;blockPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos ga2$scheduledBlock(Camera camera, Operation<BlockPos> original) {
        BlockPos block = this.ga2$block;
        return block != null ? block : original.call(camera);
    }

    /** Counts how far the camera moved while a rebuilt graph was made. */
    @Inject(method = "update", require = 0, at = @At("HEAD"))
    private void ga2$beforeUpdate(boolean smartCull, Camera camera, Frustum frustum, List<SectionRenderDispatcher.RenderSection> visibleSections,
                                  LongOpenHashSet loadedEmptySections, CallbackInfo ci) {
        SectionOcclusionGraph.GraphState now = this.currentGraph.get();
        if (now == this.ga2$shown || now != this.ga2$landed) {
            // the same graph, or the graph thread is still recording the rebuild's camera
            return;
        }
        this.ga2$shown = now;
        Vec3 builtAt = this.ga2$landedAt;
        if (builtAt != null) {
            Vec3 at = camera.position();
            ChunkLoadStats.graphShown(Math.round(Math.sqrt(Mth.square(at.x - builtAt.x) + Mth.square(at.z - builtAt.z))));
        }
    }
}
