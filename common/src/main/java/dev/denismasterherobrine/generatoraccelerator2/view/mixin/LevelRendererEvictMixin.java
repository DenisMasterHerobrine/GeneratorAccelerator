package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import dev.denismasterherobrine.generatoraccelerator2.view.SectionBuffers;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Before the frame's sections are compiled: sections long out of view give their meshes back, and those back in view are
 * rebuilt ({@link SectionBuffers#evictHidden}). Not applied with {@code -Dgeneratoraccelerator2.evictHiddenSections=0}.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEvictMixin {
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow @Nullable private ViewArea viewArea;

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void ga2$evictHidden(Camera camera, CallbackInfo ci) {
        ViewArea area = this.viewArea;
        SectionBuffers.evictHidden(this.visibleSections, area != null ? area.sections : null, camera.getPosition());
    }
}
