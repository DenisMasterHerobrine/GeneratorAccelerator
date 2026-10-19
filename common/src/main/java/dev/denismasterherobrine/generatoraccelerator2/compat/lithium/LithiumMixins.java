package dev.denismasterherobrine.generatoraccelerator2.compat.lithium;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Lithium mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class LithiumMixins {
    private LithiumMixins() {}

    public static final String MOD_ID = "lithium";
    /**
     * In {@code LevelChunkSection.setBlockState(x, y, z, state)}, redirects the call of the locking form to the form without
     * the container's thread check. GA2's exact writer makes that same call in place of the method.
     */
    public static final String NO_LOCKING = "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.LevelChunkSectionMixin";

    /**
     * Lithium's {@code world.temperature_cache}. It overwrites {@code Biome.getTemperature} to return
     * {@code getHeightAdjustedTemperature} without vanilla's per-thread cache of that value. ModernFix's
     * {@code remove_biome_temperature_cache} does the same. Every temperature therefore stays vanilla's. GA2's GPU surface
     * computes the height-adjusted temperature itself, and it stays exact because the values match.
     */
    public static final String TEMPERATURE = "net.caffeinemc.mods.lithium.mixin.world.temperature_cache.BiomeMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.SECTION_WRITE_UNLOCKED, NO_LOCKING, Set.of("setBlockStateNoLocking"),
            "Lithium's faster block writes still work. GA2's own block writer makes the same call."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_SURFACE, TEMPERATURE, Set.of(KnownMixins.overwrite("getTemperature")),
            "Lithium's biome temperature change still works. It gives the same values, so GA2's GPU surface stays exact."));

    /** Nothing to set up. GA2's writer makes the same call that Lithium's redirect makes. */
    public static void register() {
        for (KnownMixins.Entry e : ENTRIES) {
            CompatAdapters.register(e.hook(), e.mixin(), () -> true);
        }
    }
}
