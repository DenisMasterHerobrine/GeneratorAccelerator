package dev.denismasterherobrine.generatoraccelerator2.mixin.logging;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.logging.RepeatedWarnings;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Logs POI data mismatches as {@code worldgenWarnings} says ({@link RepeatedWarnings}). The source is the pair of types: the
 * type registered and the type added.
 */
@Mixin(PoiSection.class)
public abstract class PoiSectionWarningsMixin {
    @Shadow @Final private Short2ObjectMap<PoiRecord> records;

    @WrapOperation(method = "add(Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V"), require = 0)
    private void ga2$mismatchOnce(String message, Operation<Void> original, @Local(argsOnly = true) PoiRecord added) {
        PoiRecord registered = this.records.get(SectionPos.sectionRelativePos(added.getPos()));
        String source = (registered != null ? registered.getPoiType().getRegisteredName() : "?") + " -> " + added.getPoiType().getRegisteredName();
        switch (RepeatedWarnings.decide(RepeatedWarnings.Kind.POI_MISMATCH, source)) {
            case LOG -> original.call(message);
            case DEBUG -> RepeatedWarnings.debug("{}", message);
            case COUNT -> {}
        }
    }
}
