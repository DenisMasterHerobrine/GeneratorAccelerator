package dev.denismasterherobrine.generatoraccelerator2.dfc;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;

/**
 * Decides which optional hooks must stay off because another mod patches the same vanilla code.
 * Evaluated while mixins are applied, so it only uses the loader's mod list and system properties.
 */
public final class Compat {
    private Compat() {}

    /** Mods that patch NoiseChunk / FlatCache / the climate sampler themselves. */
    private static final Set<String> NOISE_CHUNK_PATCHERS = Set.of("c2me");
    /** Mods that replace climate sampling. */
    private static final Set<String> CLIMATE_PATCHERS = Set.of("c2me");

    public static boolean isLoaded(String modId) {
        try {
            return Platform.isModLoaded(modId);
        } catch (Throwable ignored) {
            // not in a mod loading context
            return false;
        }
    }

    private static String firstLoaded(Set<String> ids) {
        for (String id : ids) {
            if (isLoaded(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * @return the mod id that forces this mixin off, or {@code null} if it may apply. Mixins are
     *         grouped by the vanilla class they touch: names containing "NoiseChunk" or
     *         "FlatCache" are NoiseChunk hooks. Names containing "ClimateSampler" are climate hooks.
     */
    public static String conflictFor(String mixinClassName) {
        if (Boolean.getBoolean("generatoraccelerator2.dfc.forceCompat")) {
            return null;
        }
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (simple.contains("noisechunk") || simple.contains("flatcache")) {
            return firstLoaded(NOISE_CHUNK_PATCHERS);
        }
        if (simple.contains("climatesampler")) {
            return firstLoaded(CLIMATE_PATCHERS);
        }
        return null;
    }
}
