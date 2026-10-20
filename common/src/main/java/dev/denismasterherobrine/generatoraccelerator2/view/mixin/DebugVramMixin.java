package dev.denismasterherobrine.generatoraccelerator2.view.mixin;

import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.view.VramStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * F3's system side shows the VRAM that GA2 can count: the chunk meshes and the GPU generation's region cache. The driver, the
 * textures and the OpenCL context are not in it.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugVramMixin {
    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void ga2$vram(CallbackInfoReturnable<List<String>> cir) {
        List<String> out = new ArrayList<>(cir.getReturnValue());
        out.add("");
        // Short lines. The system side is right-aligned, and a long line runs into the left side's text.
        long regions = GpuWorldgen.REGION_BYTES.sum();
        out.add(String.format(Locale.ROOT, "GA2 VRAM: meshes %,d MB", VramStats.HELD.sum() >> 20)
            + (regions > 0 ? String.format(Locale.ROOT, ", GPU gen %,d MB", regions >> 20) : ""));
        out.add(String.format(Locale.ROOT, "GA2 freed: stale %,d MB, hidden %,d MB", VramStats.FREED.get() >> 20, VramStats.EVICTED.get() >> 20));
        cir.setReturnValue(out);
    }
}
