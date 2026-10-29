package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F3's system group shows the VRAM of the GPU generation's region cache while there is any. */
@Mixin(DebugEntrySystemSpecs.class)
public abstract class DebugVramMixin {
    @Shadow @Final private static Identifier GROUP;

    @Inject(method = "display", at = @At("TAIL"))
    private void ga2$vram(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel, @Nullable LevelChunk clientChunk,
                          @Nullable LevelChunk serverChunk, CallbackInfo ci) {
        long regions = GpuWorldgen.REGION_BYTES.sum();
        if (regions > 0) {
            displayer.addToGroup(GROUP, String.format(Locale.ROOT, "GA2 VRAM: GPU gen %,d MB", regions >> 20));
        }
    }
}
