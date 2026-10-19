package dev.denismasterherobrine.generatoraccelerator2.mixin.join;

import dev.denismasterherobrine.generatoraccelerator2.join.JoinWait;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The world loading screen's chunk grid and percentage cover the spawn area a singleplayer world waits for
 * ({@link JoinWait}). The grid fills up when the world is ready instead of stopping part way through vanilla's area.
 */
@Mixin(StoringChunkProgressListener.class)
public abstract class StoringChunkProgressListenerMixin {
    @Inject(method = "createFromGameruleRadius", at = @At("HEAD"), cancellable = true)
    private static void ga2$joinArea(int spawnChunkRadius, CallbackInfoReturnable<StoringChunkProgressListener> cir) {
        int radius = JoinWait.radius(true, spawnChunkRadius);
        if (radius != JoinWait.vanillaRadius(spawnChunkRadius)) {
            cir.setReturnValue(radius > 0 ? StoringChunkProgressListener.create(radius) : StoringChunkProgressListener.createCompleted());
        }
    }
}
