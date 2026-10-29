package dev.denismasterherobrine.generatoraccelerator2.platform.fabric;

import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** {@link Platform} on Fabric. */
public final class FabricPlatform implements Platform.Service {
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public List<Platform.ModEntry> mods() {
        List<Platform.ModEntry> out = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            out.add(new Platform.ModEntry(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        }
        return out;
    }

    /** By the jar (or directory) the type was loaded from. */
    @Override
    public String modIdOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        Path path;
        try {
            path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH) {
                continue;
            }
            for (Path p : origin.getPaths()) {
                if (p.toAbsolutePath().normalize().equals(path)) {
                    return mod.getMetadata().getId();
                }
            }
        }
        return null;
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public String name() {
        return "fabric";
    }

    /** The game runs under Mojang names, so no name table is needed. */
    @Override
    public InputStream openNameTable() {
        return null;
    }
}
