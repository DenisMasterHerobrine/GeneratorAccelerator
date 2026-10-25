package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightLayers;
import dev.denismasterherobrine.generatoraccelerator2.light.LightLayers;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every update of a server light engine records its block light calls until the propagation, which then runs the two layers at
 * once ({@link LightLayers}, {@code LevelLightEngineMixin}). Each engine decides once, when it is made.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLightLayersMixin {
    @Unique private final boolean ga2$layers = LightLayers.on();

    @Inject(method = "runUpdate", at = @At("HEAD"))
    private void ga2$beginLayers(CallbackInfo ci) {
        if (this.ga2$layers) {
            ((Ga2LightLayers) (Object) this).ga2$beginLayers();
        }
    }
}
