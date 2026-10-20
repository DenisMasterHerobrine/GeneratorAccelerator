package dev.denismasterherobrine.generatoraccelerator2.mixin.logging;

import dev.denismasterherobrine.generatoraccelerator2.logging.RepeatedWarnings;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lists the world generation warnings left out of the log in every crash report ({@link RepeatedWarnings}). A report is made
 * when the crash happens, before the server stops and forgets the counts.
 */
@Mixin(SystemReport.class)
public abstract class SystemReportMixin {
    @Shadow
    public abstract void setDetail(String identifier, String value);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$worldgenWarnings(CallbackInfo ci) {
        String details = RepeatedWarnings.crashDetails();
        if (details != null) {
            this.setDetail("GA2 worldgen warnings", details);
        }
    }
}
