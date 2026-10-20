package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.view.RenderThreads;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full rebuilds of the section occlusion graph run on their own thread ({@link RenderThreads#graph}).
 *
 * <p>A rebuild also reads the camera. Vanilla reads the camera's position when it schedules the rebuild. It reads the
 * position again and the camera's block, a mutable position, when the rebuild starts on the other thread. By then the
 * render thread may have moved the camera, or be writing its block. Here the rebuild starts from the position and block
 * of the moment it was scheduled. So one rebuild sees one camera.
 *
 * <p>The sections at the view's edge are kept when a rebuilt graph is shown. A rebuild only reaches the sections within the
 * view distance of the camera it started from. Meanwhile the graph shown grows as sections are meshed and chunks arrive,
 * and it measures the view distance from the current camera. So when the camera moved during the rebuild, the graph shown
 * holds a strip of new sections ahead that the rebuild could not reach. Vanilla drops that strip with the old graph. The
 * strip then stays hidden until a later rebuild reaches it, and new chunks blink at the edge while the camera moves. Here
 * the rebuilt graph takes over the old graph's sections in that strip, and the graph grows on from them as vanilla grows
 * it. Inside the rebuild's own view distance the rebuild decides alone, as in vanilla. A section kept that the camera
 * cannot see is drawn behind the terrain in front of it, so the image stays the same.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
    @Shadow @Final private AtomicReference<SectionOcclusionGraph.GraphState> currentGraph;
    @Shadow @Final private AtomicBoolean needsFrustumUpdate;
    @Shadow @Nullable private ViewArea viewArea;

    @Unique
    private Vec3 ga2$position;
    @Unique
    private BlockPos ga2$block;
    /** The graph shown and grown by the render thread. Render thread only. */
    @Unique
    private SectionOcclusionGraph.GraphState ga2$shown;
    /** The last rebuilt graph and the camera position it started from. Written by the graph thread, position first. */
    @Unique
    private volatile Vec3 ga2$landedAt;
    @Unique
    private volatile SectionOcclusionGraph.GraphState ga2$landed;

    @Inject(method = "scheduleFullUpdate", require = 0, at = @At("HEAD"))
    private void ga2$snapshotCamera(boolean smartCull, Camera camera, Vec3 position, CallbackInfo ci) {
        this.ga2$position = position;
        this.ga2$block = camera.getBlockPosition().immutable();
    }

    @WrapOperation(method = "scheduleFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"))
    private ExecutorService ga2$graphExecutor(Operation<ExecutorService> original) {
        return RenderThreads.graph(original.call());
    }

    /** Times the rebuild and records the graph it made with its camera. */
    @WrapOperation(method = "scheduleFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
    private Future<?> ga2$rebuild(ExecutorService executor, Runnable task, Operation<Future<?>> original) {
        Vec3 position = this.ga2$position;
        Runnable rebuild = () -> {
            long start = System.nanoTime();
            task.run();
            ChunkLoadStats.GRAPH_REBUILD.record(System.nanoTime() - start);
            this.ga2$landedAt = position;
            this.ga2$landed = this.currentGraph.get();
        };
        return original.call(executor, rebuild);
    }

    @WrapOperation(method = "initializeQueueForFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 ga2$scheduledPosition(Camera camera, Operation<Vec3> original) {
        Vec3 position = this.ga2$position;
        return position != null ? position : original.call(camera);
    }

    @WrapOperation(method = "initializeQueueForFullUpdate", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;getBlockPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos ga2$scheduledBlock(Camera camera, Operation<BlockPos> original) {
        BlockPos block = this.ga2$block;
        return block != null ? block : original.call(camera);
    }

    /** A new view area starts from an empty graph. Nothing is kept from the old area. */
    @Inject(method = "waitAndReset", require = 0, at = @At("RETURN"))
    private void ga2$reset(@Nullable ViewArea area, CallbackInfo ci) {
        this.ga2$landed = null;
        this.ga2$shown = this.currentGraph.get();
    }

    @Inject(method = "update", require = 0, at = @At("HEAD"))
    private void ga2$beforeUpdate(boolean smartCull, Camera camera, Frustum frustum, List<SectionRenderDispatcher.RenderSection> sections,
                                  CallbackInfo ci) {
        this.ga2$keepEdge(camera.getPosition());
    }

    @Inject(method = "addSectionsInFrustum", require = 0, at = @At("HEAD"))
    private void ga2$beforeFrustum(Frustum frustum, List<SectionRenderDispatcher.RenderSection> sections, CallbackInfo ci) {
        this.ga2$keepEdge(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }

    /**
     * On the render thread, before the graph is grown or read. Once a rebuilt graph replaced the one shown, it takes over the
     * old graph's sections in chunks beyond the rebuild's view distance and within the current camera's.
     */
    @Unique
    private void ga2$keepEdge(Vec3 camera) {
        SectionOcclusionGraph.GraphState now = this.currentGraph.get();
        SectionOcclusionGraph.GraphState old = this.ga2$shown;
        if (now == old || now != this.ga2$landed) {
            // the same graph, or the graph thread is still recording the rebuild's camera
            return;
        }
        this.ga2$shown = now;
        Vec3 builtAt = this.ga2$landedAt;
        ViewArea area = this.viewArea;
        if (old == null || now == null || builtAt == null || area == null) {
            return;
        }
        int bx = SectionPos.blockToSectionCoord(Mth.floor(builtAt.x));
        int bz = SectionPos.blockToSectionCoord(Mth.floor(builtAt.z));
        int cx = SectionPos.blockToSectionCoord(Mth.floor(camera.x));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(camera.z));
        long travel = Math.round(Math.sqrt(Mth.square(camera.x - builtAt.x) + Mth.square(camera.z - builtAt.z)));
        if (!RenderThreads.KEEP_EDGE || (bx == cx && bz == cz)) {
            ChunkLoadStats.graphShown(travel, 0);
            return;
        }
        int distance = area.getViewDistance();
        SectionOcclusionGraph.GraphStorage from = old.storage();
        SectionOcclusionGraph.GraphStorage to = now.storage();
        int kept = 0;
        for (SectionOcclusionGraph.Node node : from.renderSections) {
            SectionRenderDispatcher.RenderSection section = node.section;
            BlockPos origin = section.getOrigin();
            if (ga2$edge(bx, bz, cx, cz, distance, SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ()))
                && to.sectionToNodeMap.get(section) == null) {
                to.sectionToNodeMap.put(section, node);
                to.renderSections.add(node);
                // grown on from the current camera, as for a section just meshed
                now.events().sectionsToPropagateFrom().add(section);
                kept++;
            }
        }
        for (Long2ObjectMap.Entry<List<SectionRenderDispatcher.RenderSection>> entry : from.chunksWaitingForNeighbors.long2ObjectEntrySet()) {
            long chunk = entry.getLongKey();
            if (!ga2$edge(bx, bz, cx, cz, distance, ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
                continue;
            }
            for (SectionRenderDispatcher.RenderSection section : entry.getValue()) {
                SectionOcclusionGraph.Node node = from.sectionToNodeMap.get(section);
                if (node != null && node.section == section && to.sectionToNodeMap.get(section) == null) {
                    to.sectionToNodeMap.put(section, node);
                    to.chunksWaitingForNeighbors.computeIfAbsent(chunk, c -> new ArrayList<>()).add(section);
                    kept++;
                }
            }
            // neighbors that arrived before the rebuilt graph was shown are checked again
            now.events().chunksWhichReceivedNeighbors().add(chunk);
        }
        ChunkLoadStats.graphShown(travel, kept);
        if (kept > 0) {
            this.needsFrustumUpdate.set(true);
        }
    }

    /** Whether the chunk is beyond the view distance of the rebuild's camera and within the current camera's. */
    @Unique
    private static boolean ga2$edge(int bx, int bz, int cx, int cz, int distance, int x, int z) {
        return !ChunkTrackingView.isInViewDistance(bx, bz, distance, x, z) && ChunkTrackingView.isInViewDistance(cx, cz, distance, x, z);
    }
}
