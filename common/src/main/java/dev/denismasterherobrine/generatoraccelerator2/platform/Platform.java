package dev.denismasterherobrine.generatoraccelerator2.platform;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * What GA2 asks of the mod loader. Called from mixin plugins while classes are still being transformed, so it must not
 * mention Minecraft types. Those go in {@link PlatformHooks}. Each platform module provides a {@link Service}
 * ({@code META-INF/services}). Without one, as in unit tests of the common code, every call fails with an error.
 */
public final class Platform {
    private Platform() {}

    /** A loaded mod, as the loader lists it. */
    public record ModEntry(String id, String version) {}

    /** The loader's side of {@link Platform}. */
    public interface Service {
        /** Whether a mod with this id is loaded. Usable while mixins are applied. */
        boolean isModLoaded(String modId);

        /** Every loaded mod. */
        List<ModEntry> mods();

        /** The id of the mod whose jar holds {@code type}, or null when no loaded mod does. */
        String modIdOf(Class<?> type);

        Path gameDir();

        Path configDir();

        /** True in a development environment (the loader's dev runs). */
        boolean isDevelopment();

        /** {@code "forge"} or {@code "fabric"}. */
        String name();

        /** The Minecraft name table ({@link Names#RESOURCE}), or null when the game runs under Mojang names. */
        InputStream openNameTable();
    }

    private static final Service SERVICE = load(Service.class);

    /** The one implementation of {@code type} the platform module registers. */
    static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No " + type.getName() + ": GA2's common code runs without its platform module"));
    }

    public static boolean isModLoaded(String modId) {
        return SERVICE.isModLoaded(modId);
    }

    public static List<ModEntry> mods() {
        return SERVICE.mods();
    }

    public static String modIdOf(Class<?> type) {
        return SERVICE.modIdOf(type);
    }

    public static Path gameDir() {
        return SERVICE.gameDir();
    }

    public static Path configDir() {
        return SERVICE.configDir();
    }

    public static boolean isDevelopment() {
        return SERVICE.isDevelopment();
    }

    public static String name() {
        return SERVICE.name();
    }

    static InputStream openNameTable() {
        return SERVICE.openNameTable();
    }

    /** True on Forge. False on Fabric and without a platform, where unit tests run against the vanilla game. */
    public static boolean isForge() {
        try {
            return "forge".equals(name());
        } catch (Throwable e) {
            return false;
        }
    }
}
