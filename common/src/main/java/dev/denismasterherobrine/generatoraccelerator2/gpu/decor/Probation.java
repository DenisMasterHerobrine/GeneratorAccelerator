package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * The features probation took off the GPU because a GPU placement differed from the CPU's ({@code DecorJob}). The list is kept
 * across runs of the same pack in {@code <game dir>/.ga2/gpu-cache/probation-<pack>.properties}. The pack is the set of loaded mods
 * and their versions. A feature listed there stays on the CPU from the start ({@link DecorSpec#build}). Another mod list (an update,
 * a mod added) starts afresh.
 */
public final class Probation {
    private static final Logger LOGGER = Ga2Log.get("GPU");
    private static final Map<String, String> OFF = new ConcurrentHashMap<>();
    private static volatile Path file;

    private Probation() {}

    /** The pack's file, read once. */
    private static synchronized Path file() {
        if (file == null) {
            Path f = dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu.dataDir().resolve("gpu-cache").resolve("probation-" + pack() + ".properties");
            if (Files.isRegularFile(f)) {
                try (var in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    Properties p = new Properties();
                    p.load(in);
                    for (String k : p.stringPropertyNames()) {
                        OFF.put(k, p.getProperty(k));
                    }
                } catch (Exception e) {
                    LOGGER.warn("{}: read failed", f, e);
                }
            }
            file = f;
        }
        return file;
    }

    /** The loaded mods and their versions, hashed. */
    private static String pack() {
        StringBuilder b = new StringBuilder();
        try {
            var mods = new java.util.ArrayList<>(dev.denismasterherobrine.generatoraccelerator2.platform.Platform.mods());
            mods.sort(java.util.Comparator.comparing(m -> m.id()));
            for (var m : mods) {
                b.append(m.id()).append('@').append(m.version()).append(';');
            }
        } catch (Throwable e) {
            b.append("unknown");
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < b.length(); i++) {
            h = (h ^ b.charAt(i)) * 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    /** Why the feature was taken off the GPU in an earlier run of this pack, or null. */
    public static String off(String feature) {
        file();
        return OFF.get(feature);
    }

    /** Takes the feature off the GPU for this pack's later runs too. */
    public static void reject(String feature, String why) {
        Path f = file();
        if (OFF.putIfAbsent(feature, why) != null) {
            return;
        }
        synchronized (Probation.class) {
            try {
                Files.createDirectories(f.getParent());
                Properties p = new Properties();
                p.putAll(OFF);
                try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    p.store(out, "Generator Accelerator 2: features GPU decoration places on the CPU in this pack (a GPU placement differed); delete to try again");
                }
            } catch (Exception e) {
                LOGGER.warn("{}: write failed", f, e);
            }
        }
    }
}
