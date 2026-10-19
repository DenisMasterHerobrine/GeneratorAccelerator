package dev.denismasterherobrine.generatoraccelerator2.config;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;

/**
 * Binds specs to their TOML files under the config folder. Each spec is loaded when registered. A missing file comes from
 * {@code defaultconfigs/} when a modpack ships one, else from the defaults. Entries that are missing or invalid are reset,
 * and the corrected file is written back. A file is reloaded whenever it changes on disk, and the reload listeners run
 * after that.
 */
public final class ConfigManager {
    private static final Logger LOGGER = Ga2Log.get("Config");

    private ConfigManager() {}

    private record Registered(ConfigSpec spec, Path file) {}

    /** By absolute file path. */
    private static final Map<Path, Registered> FILES = new ConcurrentHashMap<>();
    /** What this class last wrote to each file, so its own writes do not count as edits. */
    private static final Map<Path, String> LAST_WRITTEN = new ConcurrentHashMap<>();
    private static final List<Runnable> LOAD_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> RELOAD_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile WatchService watcher;

    /** Registers {@code spec} under {@code name} (relative to the config folder) and loads it. */
    public static synchronized void register(ConfigSpec spec, String name) {
        Path file = Platform.configDir().resolve(name).toAbsolutePath().normalize();
        FILES.put(file, new Registered(spec, file));
        if (!Files.exists(file)) {
            Path preset = Platform.gameDir().resolve("defaultconfigs").resolve(name);
            if (Files.exists(preset)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.copy(preset, file);
                    LOGGER.info("copied modpack default {} -> {}", preset, file);
                } catch (IOException e) {
                    LOGGER.warn("copy of modpack default {} failed", preset, e);
                }
            }
        }
        load(spec, file);
        spec.setLoaded();
        LOAD_LISTENERS.forEach(ConfigManager::runListener);
        watch(file.getParent());
    }

    /** Runs after a config has loaded for the first time. */
    public static void onLoad(Runnable listener) {
        LOAD_LISTENERS.add(listener);
    }

    /** Runs after a config file changed on disk and was read again. */
    public static void onReload(Runnable listener) {
        RELOAD_LISTENERS.add(listener);
    }

    private static void runListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            LOGGER.error("listener failed", e);
        }
    }

    private static void load(ConfigSpec spec, Path file) {
        Map<List<String>, Object> parsed = Map.of();
        boolean exists = Files.exists(file);
        if (exists) {
            try {
                parsed = Toml.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("{}: read failed -> defaults", file, e);
            }
        }
        boolean corrected = !exists;
        for (ConfigSpec.ConfigValue<?> value : spec.values()) {
            corrected |= apply(value, parsed.get(value.getPath()), file);
        }
        if (corrected) {
            save(spec, file);
        }
    }

    /** Sets {@code value} from the file's entry. Returns true when the entry was missing or invalid and the default was taken. */
    private static <T> boolean apply(ConfigSpec.ConfigValue<T> value, Object parsed, Path file) {
        T accepted = parsed == null ? null : value.accept(parsed);
        if (accepted == null) {
            if (parsed != null) {
                LOGGER.warn("invalid value {} for {} in {} -> default {}", parsed, String.join(".", value.getPath()), file, value.getDefault());
            }
            value.set(value.getDefault());
            return true;
        }
        value.set(accepted);
        return false;
    }

    private static void save(ConfigSpec spec, Path file) {
        String text = String.join(System.lineSeparator(), Toml.write(spec));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            LAST_WRITTEN.put(file, text);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("{}: write failed", file, e);
        }
    }

    private static synchronized void watch(Path dir) {
        try {
            if (watcher == null) {
                watcher = dir.getFileSystem().newWatchService();
                Thread thread = new Thread(ConfigManager::watchLoop, "GeneratorAccelerator2 config watcher");
                thread.setDaemon(true);
                thread.start();
            }
            dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.warn("{}: file watch unavailable", dir, e);
        }
    }

    private static void watchLoop() {
        try {
            while (true) {
                WatchKey key = watcher.take();
                Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path name) {
                        reloadIfChanged(dir.resolve(name).toAbsolutePath().normalize());
                    }
                }
                key.reset();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reloadIfChanged(Path file) {
        Registered reg = FILES.get(file);
        if (reg == null) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // An editor is mid-write. The next event reads the file.
            return;
        }
        if (Objects.equals(text, LAST_WRITTEN.get(file))) {
            return;
        }
        synchronized (ConfigManager.class) {
            LOGGER.info("reloading {}", file);
            LAST_WRITTEN.put(file, text);
            load(reg.spec(), file);
        }
        RELOAD_LISTENERS.forEach(ConfigManager::runListener);
    }
}
