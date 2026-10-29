package dev.denismasterherobrine.generatoraccelerator2.platform.neoforge;

import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/** {@link Platform} on NeoForge. */
public final class NeoForgePlatform implements Platform.Service {
    /** The loading mod list: complete while mixins are applied, before {@link ModList} exists. */
    @Override
    public boolean isModLoaded(String modId) {
        LoadingModList list = loadingMods();
        if (list == null) {
            return false;
        }
        for (ModInfo info : list.getMods()) {
            if (info.getModId().equals(modId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Platform.ModEntry> mods() {
        List<Platform.ModEntry> out = new ArrayList<>();
        ModList list = ModList.get();
        if (list != null) {
            for (IModInfo info : list.getMods()) {
                out.add(new Platform.ModEntry(info.getModId(), info.getVersion().toString()));
            }
            return out;
        }
        LoadingModList loading = loadingMods();
        if (loading != null) {
            for (ModInfo info : loading.getMods()) {
                out.add(new Platform.ModEntry(info.getModId(), info.getVersion().toString()));
            }
        }
        return out;
    }

    /** By the mod file that holds the type's class file. */
    @Override
    public String modIdOf(Class<?> type) {
        ModList list = ModList.get();
        if (list == null) {
            return null;
        }
        String classFile = type.getName().replace('.', '/') + ".class";
        for (IModFileInfo info : list.getModFiles()) {
            if (!info.getMods().isEmpty() && info.getFile().getContents().containsFile(classFile)) {
                return info.getMods().get(0).getModId();
            }
        }
        return null;
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDevelopment() {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public String name() {
        return "neoforge";
    }

    /** The game runs under Mojang names, so no name table is needed. */
    @Override
    public InputStream openNameTable() {
        return null;
    }

    private static LoadingModList loadingMods() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        return loader != null ? loader.getLoadingModList() : null;
    }
}
