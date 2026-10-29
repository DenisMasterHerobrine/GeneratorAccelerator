package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.view.RenderThreads;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The section render dispatcher builds meshes on their own threads ({@link RenderThreads#mesh}). The dispatcher is made
 * in {@code allChanged}, and that is the method's only use of the shared pool.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMeshMixin {
    @WrapOperation(method = "allChanged", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/Util;backgroundExecutor()Lnet/minecraft/TracingExecutor;"))
    private TracingExecutor ga2$meshExecutor(Operation<TracingExecutor> original) {
        return RenderThreads.mesh(original.call());
    }
}
