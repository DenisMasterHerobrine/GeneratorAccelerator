package dev.denismasterherobrine.generatoraccelerator2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import org.slf4j.Logger;

/**
 * Where GA2's config files live: {@code config/generatoraccelerator2/<part>.toml}. Older versions wrote
 * {@code config/fastestnoisereworked-<part>.toml}. The first start that finds such a file moves it into the folder before the
 * configs load, so its settings stay. A modpack's {@code defaultconfigs/fastestnoisereworked-<part>.toml} still gives the
 * defaults of a new install, as NeoForge's own copy from {@code defaultconfigs} would.
 */
public final class ConfigFiles {
    private ConfigFiles() {}

    public static final String FOLDER = "generatoraccelerator2";
    /** The files, by part name. {@code common} was NeoForge's default name for the first config ({@code fastestnoisereworked-common.toml}). */
    static final String[] PARTS = {"common", "features", "scheduling", "surface", "carvers", "biomes", "compat", "storage", "light", "join",
        "parallel-light", "gpu"};

    /** The name to register a config under (relative to the config folder). */
    public static String name(String part) {
        return FOLDER + "/" + part + ".toml";
    }

    private static Path gameDir() {
        try {
            return Platform.gameDir();
        } catch (Throwable t) {
            // Loader paths are not available (tests). Use the working directory.
            return Path.of("");
        }
    }

    private static Path configDir() {
        try {
            return Platform.configDir();
        } catch (Throwable t) {
            return Path.of("config");
        }
    }

    private static Path oldName(Path dir, String part) {
        return dir.resolve("fastestnoisereworked-" + part + ".toml");
    }

    /**
     * The file holding the settings of {@code part}, for code that reads them before the configs load (mixin plugins). Returns
     * the folder's file, or the old file while it has not been moved yet. If neither exists, returns a modpack's old default.
     * The returned file may not exist.
     */
    public static Path existing(String part) {
        Path dir = configDir();
        Path now = dir.resolve(FOLDER).resolve(part + ".toml");
        if (Files.exists(now)) {
            return now;
        }
        Path old = oldName(dir, part);
        if (Files.exists(old)) {
            return old;
        }
        Path oldDefault = oldName(gameDir().resolve("defaultconfigs"), part);
        return Files.exists(oldDefault) && !Files.exists(gameDir().resolve("defaultconfigs").resolve(FOLDER).resolve(part + ".toml")) ? oldDefault : now;
    }

    /**
     * Moves files written by older versions into the folder. A part with no file at all gets a modpack's old default. Called
     * before the configs are registered. A file already in the folder is never replaced.
     */
    static void migrate(Logger logger) {
        Path dir = configDir();
        Path folder = dir.resolve(FOLDER);
        Path defaults = gameDir().resolve("defaultconfigs");
        for (String part : PARTS) {
            Path now = folder.resolve(part + ".toml");
            if (Files.exists(now)) {
                continue;
            }
            Path old = oldName(dir, part);
            try {
                if (Files.exists(old)) {
                    Files.createDirectories(folder);
                    Files.move(old, now);
                    logger.info("moved config/{} -> config/{}", old.getFileName(), name(part));
                } else if (Files.exists(oldName(defaults, part)) && !Files.exists(defaults.resolve(FOLDER).resolve(part + ".toml"))) {
                    Files.createDirectories(folder);
                    Files.copy(oldName(defaults, part), now);
                    logger.info("config/{} created from defaultconfigs/{}", name(part), oldName(defaults, part).getFileName());
                }
            } catch (IOException e) {
                logger.warn("move {} -> config/{} failed -> defaults", old.getFileName(), FOLDER, e);
            }
        }
    }
}
