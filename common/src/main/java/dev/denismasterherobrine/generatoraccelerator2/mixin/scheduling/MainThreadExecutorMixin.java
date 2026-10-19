package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the light engine work while chunks generate.
 *
 * <p>Vanilla schedules a light update batch from the main thread's task polling, but only when the distance
 * manager had nothing to update. While many chunks are generating, the distance manager nearly always has updates,
 * from chunks reaching new statuses and holders being promoted. Light tasks pile up and are worked off only once
 * generation stops. Every finished chunk then waits for one single-threaded catch-up at the end. Asking for an
 * update every few milliseconds of polling costs a flag check. The update itself runs on the light thread, in the
 * same order as vanilla's would. Asking on every poll made batches of only a few tasks, and every batch copies the
 * light section maps when it ends.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class MainThreadExecutorMixin {
    /**
     * The chunk cache the executor belongs to. This is the outer instance, passed as the constructor's first argument.
     * javac's field for the outer instance has a different name on each loader, so it is not shadowed.
     */
    @Unique
    private ServerChunkCache ga2$chunkCache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$captureChunkCache(ServerChunkCache chunkCache, Level level, CallbackInfo ci) {
        this.ga2$chunkCache = chunkCache;
    }

    @Unique
    private long ga2$lastLightRequest;

    @Inject(method = "pollTask", at = @At("HEAD"))
    private void ga2$keepLightGoing(CallbackInfoReturnable<Boolean> cir) {
        if (SchedulingConfig.steadyLightUpdates()) {
            long now = System.nanoTime();
            if (now - this.ga2$lastLightRequest >= LIGHT_REQUEST_INTERVAL) {
                this.ga2$lastLightRequest = now;
                this.ga2$chunkCache.getLightEngine().tryScheduleUpdate();
            }
        }
    }

    @Unique
    private static final long LIGHT_REQUEST_INTERVAL = 5_000_000L;
}
