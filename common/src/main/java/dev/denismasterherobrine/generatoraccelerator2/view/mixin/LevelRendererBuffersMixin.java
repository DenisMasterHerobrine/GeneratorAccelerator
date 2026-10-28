package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import dev.denismasterherobrine.generatoraccelerator2.view.SectionBuffers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A rebuilt section's layers without blocks give their VRAM back ({@link SectionBuffers}). A layer with blocks gets the new mesh
 * anyway. So after each rebuild a buffer holds the section's current mesh or nothing. A section moved by the view area is
 * rebuilt at its new place. It is not emptied at the move. Emptying every moved section costs GL calls on each chunk border the
 * camera crosses. The rebuild hands its section here once its compiled state is set.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBuffersMixin {
    @Inject(method = "addRecentlyCompiledChunk", at = @At("HEAD"))
    private void ga2$freeEmpty(ChunkRenderDispatcher.RenderChunk section, CallbackInfo ci) {
        SectionBuffers.freeEmpty(section);
    }
}
