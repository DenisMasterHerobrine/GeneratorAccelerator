package dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline;

import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;

/** Maps a class to the mod that ships it, for coverage reports. */
public final class ModOrigin {
    private ModOrigin() {}

    public static String modIdOf(Class<?> type) {
        if (type.getName().startsWith("net.minecraft.")) {
            return "minecraft";
        }
        try {
            String id = Platform.modIdOf(type);
            if (id != null) {
                return id;
            }
        } catch (Throwable ignored) {
            // the mod list is unavailable in some test contexts
        }
        Module module = type.getModule();
        String moduleName = module != null && module.isNamed() ? module.getName() : null;
        return moduleName != null ? "module:" + moduleName : "unknown";
    }
}
