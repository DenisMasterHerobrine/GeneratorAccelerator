package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.CleanLoads;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The clean flag of a chunk read from disk ({@link CleanLoads}). On a clean chunk, {@code setLightCorrect} sets the flag without
 * marking the chunk. The LIGHT step of a load clears it and sets it again ({@code ThreadedLevelLightEngine.lightChunk}, or another
 * light engine's version of it), which changes nothing that is saved. A light change the step makes marks the chunk through
 * {@code ChunkHolder.sectionLightChanged}.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessCleanLoadMixin implements CleanLoads.Flag {
    @Shadow private volatile boolean isLightCorrect;

    @Unique private boolean ga2$cleanLoad;

    @Override
    public boolean ga2$cleanLoad() {
        return this.ga2$cleanLoad;
    }

    @Override
    public void ga2$setCleanLoad(boolean clean) {
        this.ga2$cleanLoad = clean;
    }

    @Inject(method = "setLightCorrect", at = @At("HEAD"), cancellable = true)
    private void ga2$lightUnmarked(boolean correct, CallbackInfo ci) {
        if (this.ga2$cleanLoad) {
            this.isLightCorrect = correct;
            ci.cancel();
        }
    }
}
