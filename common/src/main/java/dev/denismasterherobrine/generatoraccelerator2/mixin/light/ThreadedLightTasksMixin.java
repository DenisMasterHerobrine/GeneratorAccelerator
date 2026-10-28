package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightTasks;
import dev.denismasterherobrine.generatoraccelerator2.light.LightTasks;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Light tasks go straight to the light thread instead of through the chunk priority sorter ({@link LightTasks}). Each engine
 * decides once, when it is made. {@code addTask} puts the task into a queue and asks for an update. An update takes up to
 * vanilla's 1000 tasks from the queue into vanilla's task list and runs vanilla's {@code runUpdate}. When it ends with tasks still
 * queued, the next update is asked for right away. {@code tryScheduleUpdate}, which the server thread calls, asks the same way.
 * The engine methods that queue tasks record which method a task came from, for the counts.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLightTasksMixin implements Ga2LightTasks {
    @SuppressWarnings("rawtypes")
    @Shadow @Final private ObjectList lightTasks;
    @Shadow @Final private ProcessorMailbox<Runnable> taskMailbox;
    @Shadow @Final private AtomicBoolean scheduled;

    @Unique private final ConcurrentLinkedQueue<Pair<Object, Runnable>> ga2$queue = new ConcurrentLinkedQueue<>();
    @Unique private final boolean ga2$direct = LightTasks.on();

    @Inject(method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
        at = @At("HEAD"), cancellable = true)
    private void ga2$queue(int x, int z, IntSupplier level, @Coerce Object type, Runnable task, CallbackInfo ci) {
        if (this.ga2$direct) {
            this.ga2$queue.add(LightTasks.queued(type, task));
            this.ga2$schedule();
            ci.cancel();
        }
    }

    @Inject(method = "tryScheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void ga2$tryScheduleUpdate(CallbackInfo ci) {
        if (this.ga2$direct) {
            if (this.ga2$hasTasks() || ((LevelLightEngine) (Object) this).hasLightWork()) {
                this.ga2$schedule();
            }
            ci.cancel();
        }
    }

    /** Asks the light thread for an update, unless one is asked for or running. */
    @Unique
    private void ga2$schedule() {
        if (this.scheduled.compareAndSet(false, true)) {
            this.taskMailbox.tell(this::ga2$update);
        }
    }

    /** One update on the light thread. A task queued while it ran asks for the next one, here or in {@link #ga2$schedule}. */
    @Unique
    @SuppressWarnings("unchecked")
    private void ga2$update() {
        long now = System.nanoTime();
        Pair<Object, Runnable> entry;
        for (int room = LightTasks.BATCH - this.lightTasks.size(); room > 0 && (entry = this.ga2$queue.poll()) != null; room--) {
            LightTasks.taken(entry, now);
            this.lightTasks.add(entry);
        }
        ((ThreadedLightEngineInvoker) this).ga2$runUpdate();
        // The flag is cleared before the queue is looked at again. A task queued in between either sees the flag clear or is seen here.
        this.scheduled.set(false);
        if (this.ga2$hasTasks()) {
            this.ga2$schedule();
        }
    }

    @Override
    public boolean ga2$hasTasks() {
        return !this.ga2$queue.isEmpty() || !this.lightTasks.isEmpty();
    }

    // The method each task comes from

    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void ga2$kindCheckBlock(CallbackInfo ci) {
        LightTasks.kind(LightTasks.CHECK_BLOCK);
    }

    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void ga2$kindSectionStatus(CallbackInfo ci) {
        LightTasks.kind(LightTasks.SECTION_STATUS);
    }

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void ga2$kindLightSources(CallbackInfo ci) {
        LightTasks.kind(LightTasks.LIGHT_SOURCES);
    }

    @Inject(method = "setLightEnabled", at = @At("HEAD"))
    private void ga2$kindLightEnabled(CallbackInfo ci) {
        LightTasks.kind(LightTasks.LIGHT_ENABLED);
    }

    @Inject(method = "queueSectionData", at = @At("HEAD"))
    private void ga2$kindSectionData(CallbackInfo ci) {
        LightTasks.kind(LightTasks.SECTION_DATA);
    }

    @Inject(method = "retainData", at = @At("HEAD"))
    private void ga2$kindRetainData(CallbackInfo ci) {
        LightTasks.kind(LightTasks.RETAIN_DATA);
    }

    @Inject(method = "updateChunkStatus", at = @At("HEAD"))
    private void ga2$kindChunkStatus(CallbackInfo ci) {
        LightTasks.kind(LightTasks.CHUNK_STATUS);
    }

    @Inject(method = "initializeLight", at = @At("HEAD"))
    private void ga2$kindInitializeLight(CallbackInfoReturnable<?> cir) {
        LightTasks.kind(LightTasks.INITIALIZE_LIGHT);
    }

    @Inject(method = "lightChunk", at = @At("HEAD"))
    private void ga2$kindLightChunk(CallbackInfoReturnable<?> cir) {
        LightTasks.kind(LightTasks.LIGHT_CHUNK);
    }
}
