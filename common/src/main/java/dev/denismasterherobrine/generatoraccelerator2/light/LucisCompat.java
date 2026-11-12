package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;

/**
 * Lucis runs light as jobs per column on vanilla's engines. GA2's own light work (direct tasks, layers at once, parallel
 * propagation, the GPU's light, light snapshots) then stands down by itself instead of through five system properties. Generation
 * work stays GA2's. {@code -Dgeneratoraccelerator2.lucis.keepGa2Light=true} keeps GA2's light hooks on next to Lucis.
 */
public final class LucisCompat {
    private LucisCompat() {}

    public static final String MOD_ID = "lucis";

    /** True when Lucis is loaded and GA2's light hooks give way to it. */
    public static final boolean ACTIVE = Compat.isLoaded(MOD_ID) && !Boolean.getBoolean("generatoraccelerator2.lucis.keepGa2Light");
}
