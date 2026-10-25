package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.denismasterherobrine.generatoraccelerator2.light.Ga2LightTasks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Light tasks queued for the light thread count as chunk work ({@code ThreadedLightTasksMixin}). In vanilla they wait in the
 * chunk priority sorter, which {@code hasWork} asks. The server waits for chunk work to end before it saves on stopping.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLightTasksMixin {
    @Shadow @Final private ThreadedLevelLightEngine lightEngine;

    @ModifyReturnValue(method = "hasWork", at = @At("RETURN"))
    private boolean ga2$lightTasks(boolean work) {
        return work || this.lightEngine instanceof Ga2LightTasks tasks && tasks.ga2$hasTasks();
    }
}
