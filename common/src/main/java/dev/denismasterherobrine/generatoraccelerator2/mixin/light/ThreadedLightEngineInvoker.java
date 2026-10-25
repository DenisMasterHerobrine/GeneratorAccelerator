package dev.denismasterherobrine.generatoraccelerator2.mixin.light;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The light thread's update, called by GA2's scheduling of light updates ({@code ThreadedLightTasksMixin}). */
@Mixin(ThreadedLevelLightEngine.class)
public interface ThreadedLightEngineInvoker {
    @Invoker("runUpdate")
    void ga2$runUpdate();
}
