package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * {@code config/generatoraccelerator2/compat.toml}. Registered so the file exists with its comment.
 * {@link Takeover} reads {@code takeOver} directly from the file, because mixins are applied before configs
 * load. Changing that value needs a restart.
 */
public final class CompatConfig {
    private CompatConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue TAKE_OVER;
    public static final ConfigSpec.ConfigValue<String> WORLDGEN_WARNINGS;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        TAKE_OVER = b.comment("Let GA2 replace the world generation tweaks of other performance mods (C2ME, ModernFix, ...) with its own",
            "exact versions. With C2ME, GA2 also handles chunk saving and loading. Everything else in those mods keeps working.",
            "Set to false to keep their tweaks. GA2 then turns off its own speedup wherever the two overlap. Requires a restart.").define("takeOver", true);
        WORLDGEN_WARNINGS = b.comment("Vanilla's warnings about other mods' features and structures: writes into far chunks, POI data mismatches and",
            "missing block entities. They are not errors, and the world generates fine. A broken feature can log thousands of",
            "them a minute, and every line slows generation down. Real errors and crashes are always logged.",
            "hidden: kept out of the log. The first one of each feature goes to debug.log, and crash reports list how many there were.",
            "once: the first one of each feature is logged. Repeats are counted and summed up once a minute.",
            "all: every one is logged, as vanilla does.").define("worldgenWarnings", "hidden");
        SPEC = b.build();
    }

    /** Read through the loaded config, unlike {@code takeOver}. It is first asked during world generation. */
    public static String worldgenWarnings() {
        try {
            return SPEC.isLoaded() ? WORLDGEN_WARNINGS.get() : "hidden";
        } catch (RuntimeException e) {
            return "hidden";
        }
    }
}
