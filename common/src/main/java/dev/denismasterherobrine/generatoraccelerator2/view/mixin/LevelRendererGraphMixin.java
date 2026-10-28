package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.view.RenderThreads;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Full rebuilds of the visibility graph run on their own thread ({@link RenderThreads#graph}).
 *
 * <p>A rebuild also reads the camera. Vanilla reads the camera's position when it schedules the rebuild. It reads the
 * position again and the camera's block, a mutable position, when the rebuild starts on the other thread. By then the
 * render thread may have moved the camera, or be writing its block. Here the rebuild starts from the position and block
 * of the moment it was scheduled. So one rebuild sees one camera.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererGraphMixin {
    @Unique
    private Vec3 ga2$position;
    @Unique
    private BlockPos ga2$block;

    @WrapOperation(method = "setupRender", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"))
    private ExecutorService ga2$graphExecutor(Operation<ExecutorService> original) {
        return RenderThreads.graph(original.call());
    }

    /** Records the camera of the moment the rebuild is scheduled, and times the rebuild. */
    @WrapOperation(method = "setupRender", require = 0, at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
    private Future<?> ga2$rebuild(ExecutorService executor, Runnable task, Operation<Future<?>> original, @Local(argsOnly = true) Camera camera) {
        this.ga2$position = camera.getPosition();
        this.ga2$block = camera.getBlockPosition().immutable();
        Runnable rebuild = () -> {
            long start = System.nanoTime();
            task.run();
            ChunkLoadStats.GRAPH_REBUILD.record(System.nanoTime() - start);
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
}
