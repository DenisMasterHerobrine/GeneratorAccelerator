package dev.denismasterherobrine.generatoraccelerator2.mixin.join;

import dev.denismasterherobrine.generatoraccelerator2.join.JoinWait;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A dedicated server's "Preparing spawn area" percentage covers the area it waits for ({@link JoinWait}, with {@code dedicatedServers}). */
@Mixin(LoggerChunkProgressListener.class)
public abstract class LoggerChunkProgressListenerMixin {
    @Inject(method = "createFromGameruleRadius", at = @At("HEAD"), cancellable = true)
    private static void ga2$joinArea(int spawnChunkRadius, CallbackInfoReturnable<LoggerChunkProgressListener> cir) {
        int radius = JoinWait.radius(false, spawnChunkRadius);
        if (radius != JoinWait.vanillaRadius(spawnChunkRadius)) {
            cir.setReturnValue(radius > 0 ? LoggerChunkProgressListener.create(radius) : LoggerChunkProgressListener.createCompleted());
        }
    }
}
