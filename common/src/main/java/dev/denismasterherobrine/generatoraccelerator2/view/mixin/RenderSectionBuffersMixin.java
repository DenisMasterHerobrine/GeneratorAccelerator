package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.denismasterherobrine.generatoraccelerator2.view.SectionBuffers;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A rebuilt section's layers without blocks give their VRAM back ({@link SectionBuffers}). A layer with blocks gets the new mesh
 * anyway. So after each rebuild a buffer holds the section's current mesh or nothing. A section moved by the view area is
 * rebuilt at its new place. It is not emptied at the move. Emptying every moved section costs GL calls on each chunk border the
 * camera crosses. The section also keeps when it was last in view ({@link SectionBuffers#evictHidden}).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionBuffersMixin implements SectionBuffers.Section {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;

    @Unique
    private long ga2$seen;
    @Unique
    private boolean ga2$evicted;

    @Inject(method = "setCompiled", at = @At("RETURN"))
    private void ga2$freeEmpty(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
        for (Map.Entry<RenderType, VertexBuffer> e : this.buffers.entrySet()) {
            if (compiled.isEmpty(e.getKey())) {
                SectionBuffers.free(e.getValue());
            }
        }
    }

    @Override
    public long ga2$seen() {
        return this.ga2$seen;
    }

    @Override
    public void ga2$seen(long nanos) {
        this.ga2$seen = nanos;
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
