package dev.denismasterherobrine.generatoraccelerator2.compat.saturn;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithium.LithiumMixins;
import java.util.List;

/** Saturn mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class SaturnMixins {
    private SaturnMixins() {}

    public static final String MOD_ID = "saturn";

    /**
     * Saturn's {@code leaks.biome_temperature_cache}. It overwrites {@code Biome.getTemperature} to return
     * {@code getHeightAdjustedTemperature}, and drops vanilla's per-thread cache of that value at the end of the constructor.
     * Lithium's {@code world.temperature_cache} does the same. Every temperature therefore stays vanilla's, and GA2's GPU surface,
     * which computes the height-adjusted temperature itself, stays exact.
     */
    public static final String TEMPERATURE = "com.abdelaziz.saturn.mixin.leaks.biome_temperature_cache.BiomeMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, TEMPERATURE, LithiumMixins.GET_TEMPERATURE,
            "Saturn's biome temperature change still works. It gives the same values, so GA2's GPU surface stays exact."));

    /** Nothing to set up. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
