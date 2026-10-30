package dev.denismasterherobrine.generatoraccelerator2.compat.chunky.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyRequests;
import java.util.concurrent.Semaphore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds Chunky's task thread back before a request while the heap is nearly full ({@link ChunkyRequests#waitForRoom}). */
@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
public abstract class GenerationTaskMixin {
    @Inject(method = "run", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Semaphore;acquire()V"), require = 0)
    private void ga2$roomForRequest(CallbackInfo ci, @Local Semaphore working) {
        ChunkyRequests.waitForRoom(working);
    }
}
