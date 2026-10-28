package dev.denismasterherobrine.generatoraccelerator2.compat.blockswap;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWrites;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block Swap's section write ({@link BlockSwapMixins#SECTION}). Its callback swaps a state only when the config is not in retro-gen
 * mode and lists the state or its block. GA2 asks the same of the config ({@code BlockSwapConfig.getConfig(false)}, read as the callback
 * reads it) before it writes a state directly ({@link SectionWrites}).
 */
public final class BlockSwapSections {
    private BlockSwapSections() {}

    private static final String CONFIG = "corgitaco.blockswap.config.BlockSwapConfig";

    private static MethodHandle getConfig;
    private static MethodHandle retroGen;
    private static MethodHandle contains;

    public static void register() {
        CompatAdapters.register(HookTargets.NOISE_SECTION_WRITES, BlockSwapMixins.SECTION, BlockSwapSections::setUp);
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
            MethodHandle get = lookup.findStatic(config, "getConfig", MethodType.methodType(config, boolean.class)).asType(MethodType.methodType(Object.class, boolean.class));
            MethodHandle retro = lookup.findVirtual(config, "retroGen", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
            MethodHandle has = lookup.findVirtual(config, "contains", MethodType.methodType(boolean.class, BlockState.class))
                .asType(MethodType.methodType(boolean.class, Object.class, BlockState.class));
            retroGen = retro;
            contains = has;
            getConfig = get;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ga2Log.get("Compat").warn("Block Swap config -> not readable, section writes block by block ({})", e.toString());
            return false;
        }
        SectionWrites.register(BlockSwapSections::swaps);
        return true;
    }

    /** Whether Block Swap's callback would write something else for {@code state}. */
    private static boolean swaps(BlockState state) {
        try {
            Object config = getConfig.invokeExact(false);
            return config != null && !(boolean) retroGen.invokeExact(config) && (boolean) contains.invokeExact(config, state);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }
}
