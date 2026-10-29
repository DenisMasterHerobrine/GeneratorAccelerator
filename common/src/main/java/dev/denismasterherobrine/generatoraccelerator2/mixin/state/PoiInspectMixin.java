package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code ServerLevel.updatePOIOnBlockStateChange} and
 * {@code PoiTypes.forState}. The POI skip relies on both methods (hook {@code state.poiSkip}, {@code state.PoiStates}).
 */
@Mixin(value = { ServerLevel.class, PoiTypes.class }, priority = 5000)
public abstract class PoiInspectMixin {
}
