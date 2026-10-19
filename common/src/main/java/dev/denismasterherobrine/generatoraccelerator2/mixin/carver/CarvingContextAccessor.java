package dev.denismasterherobrine.generatoraccelerator2.mixin.carver;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes what {@code CarvingContext.topMaterial} passes to {@code SurfaceSystem.topMaterial}. The carver runner uses
 * these values for its top materials ({@code carver.Carving}). The plugin also inspects this class for other mods'
 * changes to {@code topMaterial}.
 */
@Mixin(CarvingContext.class)
public interface CarvingContextAccessor {
    @Accessor("surfaceRule")
    SurfaceRules.RuleSource ga2$surfaceRule();

    @Accessor("noiseChunk")
    NoiseChunk ga2$noiseChunk();
}
