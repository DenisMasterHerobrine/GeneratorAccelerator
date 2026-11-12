package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.CleanLoads;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A clean chunk that starts block ticking is marked, as vanilla's loaded chunks are ({@link CleanLoads}). A ticking chunk's
 * inhabited time, post-processing and upgrade data change without a mark of their own.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkCleanLoadMixin {
    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void ga2$ticksNow(CallbackInfo ci) {
        CleanLoads.Flag flag = (CleanLoads.Flag) this;
        if (flag.ga2$cleanLoad()) {
            flag.ga2$setCleanLoad(false);
            ((LevelChunk) (Object) this).setUnsaved(true);
        }
    }
}
