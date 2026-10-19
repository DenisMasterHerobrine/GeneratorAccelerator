package dev.denismasterherobrine.generatoraccelerator2.fabric.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The biome's own climate, read by {@code platform.fabric.FabricPlatformHooks}. This is an accessor rather than an access
 * widener entry because the common access widener also becomes NeoForge's access transformer. NeoForge has a coremod that
 * routes this field through the biome modifiers, and that coremod requires the field to stay private.
 */
@Mixin(Biome.class)
public interface BiomeClimateAccessor {
    @Accessor("climateSettings")
    Biome.ClimateSettings ga2$climateSettings();
}
