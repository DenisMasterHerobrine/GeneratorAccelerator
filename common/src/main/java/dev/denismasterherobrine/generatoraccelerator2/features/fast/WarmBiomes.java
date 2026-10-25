package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.mixin.features.ClimateInspectMixin;
import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Biomes too warm to freeze or snow anywhere in a level's height. {@code Biome.shouldFreeze} and {@code shouldSnow} return false
 * at once when {@code warmEnoughToRain} holds, and that reads only the height-adjusted temperature. Without a temperature
 * modifier that temperature is the base temperature up to y 80. Above it the temperature falls with height and with the
 * temperature noise, whose 2D simplex value stays within 1 (2 is the bound used here). So a biome whose lowest possible
 * temperature at the level's top is still 0.15 or more never freezes or snows. Its columns skip both tests and the
 * temperature cache they fill.
 */
final class WarmBiomes {
    private WarmBiomes() {}

    private static final FeatureTargets.Hook CLIMATE = FeatureTargets.hook("climate");
    /** The noise's bound times 8, as {@code getHeightAdjustedTemperature} scales it. */
    private static final float NOISE_BOUND = 16.0F;
    /** Kept between the bound and vanilla's 0.15, against float rounding. */
    private static final float MARGIN = 0.01F;

    /**
     * True when every biome the region's lookups can answer for the 16x16 columns from {@code x0, z0} is {@link #alwaysWarm}: the
     * biomes stored around the centre chunk, a superset of the zoom's answers inside its box ({@code Ga2Region.ga2$zoomBiomes}).
     */
    static boolean everyBiome(Ga2Region region, int x0, int z0, int minY, int maxY) {
        BiomeZoom zoom = region.ga2$zoom();
        if (zoom == null || !zoom.covers(x0, minY, z0) || !zoom.covers(x0 + 15, maxY, z0 + 15)) {
            return false;
        }
        Holder<Biome>[] biomes = region.ga2$zoomBiomes();
        if (biomes == null || biomes.length == 0) {
            return false;
        }
        for (Holder<Biome> b : biomes) {
            if (!alwaysWarm(b.value(), maxY)) {
                return false;
            }
        }
        return true;
    }

    /** True when nothing changed the climate code and {@code biome} cannot freeze or snow below {@code maxY}. */
    static boolean alwaysWarm(Biome biome, int maxY) {
        if (!CLIMATE.allowed()) {
            return false;
        }
        Biome.ClimateSettings c = ((ClimateInspectMixin) (Object) biome).ga2$climateSettings();
        if (c.temperatureModifier() != Biome.TemperatureModifier.NONE) {
            return false;
        }
        float t = biome.getBaseTemperature();
        float coldest = maxY > 80 ? t - (NOISE_BOUND + maxY - 80.0F) * 0.05F / 40.0F : t;
        return coldest >= 0.15F + MARGIN;
    }
}
