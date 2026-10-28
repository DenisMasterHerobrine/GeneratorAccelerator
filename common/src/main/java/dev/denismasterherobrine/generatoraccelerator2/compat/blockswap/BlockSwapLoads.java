package dev.denismasterherobrine.generatoraccelerator2.compat.blockswap;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadTagEdits;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Block Swap's repair of missing block ids inside {@code ChunkSerializer.read} ({@link BlockSwapMixins#SERIALIZER}). Before a
 * section's block states are parsed, it renames each palette entry whose name its missing-id config maps. Such a chunk is read
 * the checked way ({@link LoadTagEdits}). With the default config, which maps nothing, every chunk is read as prepared.
 */
public final class BlockSwapLoads {
    private BlockSwapLoads() {}

    private static final String CONFIG = "corgitaco.blockswap.config.MissingBlockIDsConfig";

    @Nullable
    private static MethodHandle getConfig;
    @Nullable
    private static MethodHandle idRemapper;

    public static void register() {
        LoadTagEdits.register(BlockSwapLoads::repairs);
        CompatAdapters.register(HookTargets.STORAGE_LOAD_PREP_UNCHANGED, BlockSwapMixins.SERIALIZER, BlockSwapLoads::setUp);
    }

    private static synchronized boolean setUp() {
        if (getConfig != null) {
            return true;
        }
        Class<?> config = Reflect.type(CONFIG);
        if (config == null) {
            return false;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle remapper = lookup.findVirtual(config, "idRemapper", MethodType.methodType(Map.class)).asType(MethodType.methodType(Map.class, Object.class));
            MethodHandle get = lookup.findStatic(config, "getConfig", MethodType.methodType(config, boolean.class)).asType(MethodType.methodType(Object.class, boolean.class));
            idRemapper = remapper;
            getConfig = get;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the repair would change {@code chunk}: some section's palette names a missing id the config maps. */
    static boolean repairs(CompoundTag chunk) {
        Map<?, ?> remap = remapper();
        if (remap == null) {
            // the config cannot be read: every chunk the checked way
            return true;
        }
        if (remap.isEmpty()) {
            return false;
        }
        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            ListTag palette = sections.getCompound(i).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            for (int j = 0; j < palette.size(); j++) {
                if (remap.containsKey(palette.getCompound(j).getString("Name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static Map<?, ?> remapper() {
        MethodHandle get = getConfig;
        if (get == null && !setUp()) {
            return null;
        }
        try {
            Object config = getConfig.invokeExact(false);
            return config == null ? null : (Map<?, ?>) idRemapper.invokeExact(config);
        } catch (Throwable t) {
            return null;
        }
    }
}
