package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import dev.denismasterherobrine.generatoraccelerator2.view.SectionBuffers;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A render chunk keeps when it was last in view, the camera's column then, and whether its meshes were evicted
 * ({@link SectionBuffers#evictHidden}).
 */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class RenderSectionBuffersMixin implements SectionBuffers.Section {
    @Unique
    private long ga2$seen;
    @Unique
    private int ga2$seenX;
    @Unique
    private int ga2$seenZ;
    @Unique
    private boolean ga2$evicted;

    @Override
    public long ga2$seen() {
        return this.ga2$seen;
    }

    @Override
    public int ga2$seenX() {
        return this.ga2$seenX;
    }

    @Override
    public int ga2$seenZ() {
        return this.ga2$seenZ;
    }

    @Override
    public void ga2$seen(long nanos, int cameraX, int cameraZ) {
        this.ga2$seen = nanos;
        this.ga2$seenX = cameraX;
        this.ga2$seenZ = cameraZ;
    }

    @Override
    public boolean ga2$evicted() {
        return this.ga2$evicted;
    }

    @Override
    public void ga2$evicted(boolean evicted) {
        this.ga2$evicted = evicted;
    }
}
