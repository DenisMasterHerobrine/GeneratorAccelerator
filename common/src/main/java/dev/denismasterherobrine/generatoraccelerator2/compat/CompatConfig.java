package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * {@code config/generatoraccelerator2/compat.toml}. Registered so the file exists with its comment.
 * {@link Takeover} reads the value directly from the file, because mixins are applied before configs
 * load. Changing the value needs a restart.
 */
public final class CompatConfig {
    private CompatConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue TAKE_OVER;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        TAKE_OVER = b.comment("Let GA2 replace the world generation tweaks of other performance mods (C2ME, ModernFix, ...) with its own",
            "exact versions. With C2ME, GA2 also handles chunk saving and loading. Everything else in those mods keeps working.",
            "Set to false to keep their tweaks. GA2 then turns off its own speedup wherever the two overlap. Requires a restart.").define("takeOver", true);
        SPEC = b.build();
    }
}
