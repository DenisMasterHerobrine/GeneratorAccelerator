package dev.denismasterherobrine.generatoraccelerator2.parallellight.mixin;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.LightLoad;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightStats;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures the share of time the light thread spends in {@code runUpdate} ({@link LightLoad}). Also records the length of each
 * update, its tasks and the idle time before it ({@link ParallelLightStats#run}).
 * With {@code -Dgeneratoraccelerator2.lightTrace=true}, an update over 50 ms is logged with its parts: the tasks before the
 * propagation, the propagation, the tasks after, and the slowest task of each part. Does not change what {@code runUpdate} does.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin implements LightLoad {
    @Unique private static final long WINDOW = 500_000_000L;
    @Unique private static final boolean TRACE = Boolean.getBoolean("generatoraccelerator2.lightTrace");
    @Unique private static final Logger LOGGER = Ga2Log.get("Light");

    @Shadow @Final private ObjectList<?> lightTasks;

    @Unique private long ga2pl$updateStart;
    @Unique private int ga2pl$updateTasks;
    @Unique private long ga2pl$updateEnd;
    @Unique private long ga2pl$windowStart = System.nanoTime();
    @Unique private long ga2pl$busyNanos;
    @Unique private volatile double ga2pl$busy;

    // lightTrace: the parts of the current update
    @Unique private long ga2pl$propagationStart, ga2pl$propagationEnd;
    @Unique private int ga2pl$preTasks, ga2pl$postTasks;
    @Unique private long ga2pl$preNanos, ga2pl$postNanos, ga2pl$slowPre, ga2pl$slowPost;
    @Unique private Object ga2pl$slowPreTask, ga2pl$slowPostTask;
    @Unique private long[] ga2pl$propagationBefore, ga2pl$propagationAfter;
    @Unique private long ga2pl$gcBefore;

    @Inject(method = "runUpdate", at = @At("HEAD"))
    private void ga2pl$startUpdate(CallbackInfo ci) {
        this.ga2pl$updateStart = System.nanoTime();
        this.ga2pl$updateTasks = Math.min(this.lightTasks.size(), 1000);
        if (TRACE) {
            this.ga2pl$propagationStart = 0L;
            this.ga2pl$propagationEnd = 0L;
            this.ga2pl$propagationBefore = this.ga2pl$propagationAfter = null;
            this.ga2pl$preTasks = this.ga2pl$postTasks = 0;
            this.ga2pl$preNanos = this.ga2pl$postNanos = this.ga2pl$slowPre = this.ga2pl$slowPost = 0L;
            this.ga2pl$slowPreTask = this.ga2pl$slowPostTask = null;
            this.ga2pl$gcBefore = ga2pl$gcMillis();
        }
    }

    @Unique
    private static long ga2pl$gcMillis() {
        long ms = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            // pauses only (G1's concurrent cycles have a bean of their own)
            if (!gc.getName().contains("Concurrent")) {
                ms += Math.max(0L, gc.getCollectionTime());
            }
        }
        return ms;
    }

    @WrapOperation(method = "runUpdate", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"), require = 0)
    private void ga2pl$timeTask(Runnable task, Operation<Void> original) {
        if (!TRACE) {
            original.call(task);
            return;
        }
        long t0 = System.nanoTime();
        original.call(task);
        long d = System.nanoTime() - t0;
        if (this.ga2pl$propagationStart == 0L) {
            this.ga2pl$preTasks++;
            this.ga2pl$preNanos += d;
            if (d > this.ga2pl$slowPre) {
                this.ga2pl$slowPre = d;
                this.ga2pl$slowPreTask = task;
            }
        } else {
            this.ga2pl$postTasks++;
            this.ga2pl$postNanos += d;
            if (d > this.ga2pl$slowPost) {
                this.ga2pl$slowPost = d;
                this.ga2pl$slowPostTask = task;
            }
        }
    }

    @Inject(method = "runUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"), require = 0)
    private void ga2pl$propagationStart(CallbackInfo ci) {
        if (TRACE) {
            this.ga2pl$propagationBefore = ParallelLightStats.propagationSnapshot();
            this.ga2pl$propagationStart = System.nanoTime();
        }
    }

    @Inject(method = "runUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I", shift = At.Shift.AFTER), require = 0)
    private void ga2pl$propagationEnd(CallbackInfo ci) {
        if (TRACE) {
            this.ga2pl$propagationEnd = System.nanoTime();
            this.ga2pl$propagationAfter = ParallelLightStats.propagationSnapshot();
        }
    }

    @Inject(method = "runUpdate", at = @At("RETURN"))
    private void ga2pl$endUpdate(CallbackInfo ci) {
        long now = System.nanoTime();
        long start = this.ga2pl$updateStart;
        this.ga2pl$busyNanos += now - start;
        // Idle time before the update, capped at 50 ms. A longer gap means a dimension with nothing to light.
        ParallelLightStats.run(now - start, this.ga2pl$updateTasks, this.ga2pl$updateEnd == 0L ? 0L : Math.min(start - this.ga2pl$updateEnd, 50_000_000L));
        this.ga2pl$updateEnd = now;
        if (TRACE && now - start > 50_000_000L) {
            long[] b = this.ga2pl$propagationBefore, a = this.ga2pl$propagationAfter;
            String parts = b == null || a == null ? "" : String.format(java.util.Locale.ROOT,
                " (split %d: %d entries queued, %d processed, %d ms waiting for helpers; alone %d: %d queued, %d processed)",
                a[0] - b[0], a[2] - b[2], a[3] - b[3], (a[4] - b[4]) / 1_000_000, a[1] - b[1], a[5] - b[5], a[6] - b[6]);
            LOGGER.info("update {} ms: {} tasks; pre {} tasks / {} ms (slowest {} ms: {}); propagation {} ms{}; post {} tasks / {} ms (slowest {} ms: {}); rest {} ms; GC {} ms",
                (now - start) / 1_000_000, this.ga2pl$updateTasks, this.ga2pl$preTasks, this.ga2pl$preNanos / 1_000_000, this.ga2pl$slowPre / 1_000_000, this.ga2pl$slowPreTask,
                (this.ga2pl$propagationEnd - this.ga2pl$propagationStart) / 1_000_000, parts, this.ga2pl$postTasks, this.ga2pl$postNanos / 1_000_000,
                this.ga2pl$slowPost / 1_000_000, this.ga2pl$slowPostTask,
                ((now - start) - this.ga2pl$preNanos - this.ga2pl$postNanos - (this.ga2pl$propagationEnd - this.ga2pl$propagationStart)) / 1_000_000,
                ga2pl$gcMillis() - this.ga2pl$gcBefore);
        }
        long window = now - this.ga2pl$windowStart;
        if (window >= WINDOW) {
            this.ga2pl$busy = Math.min(1.0, this.ga2pl$busyNanos / (double) window);
            this.ga2pl$busyNanos = 0;
            this.ga2pl$windowStart = now;
        }
    }

    @Override
    public double ga2pl$busy() {
        return this.ga2pl$busy;
    }
}
