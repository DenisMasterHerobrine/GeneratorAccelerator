package dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.PresentChunks;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;

/**
 * Alex's Caves' handler at the head of {@code ServerChunkCache.getChunk}, run by GA2 before a chunk it answers off the server
 * thread ({@link PresentChunks}): the biome source learns the level's seed and dimension, as the handler does.
 */
public final class AlexsCavesChunks {
    private AlexsCavesChunks() {}

    private static final String ACCESSOR = "com.github.alexmodguy.alexscaves.server.level.biome.MultiNoiseBiomeSourceAccessor";

    public static void register() {
        CompatAdapters.register(HookTargets.CHUNK_PRESENT_OFF_THREAD, AlexsCavesMixins.CHUNK_CACHE, AlexsCavesChunks::setUp);
    }

    private static boolean setUp() {
        Class<?> accessor = Reflect.type(ACCESSOR);
        if (accessor == null) {
            return false;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle seed = lookup.findVirtual(accessor, "setLastSampledSeed", MethodType.methodType(void.class, long.class));
            MethodHandle dimension = lookup.findVirtual(accessor, "setLastSampledDimension", MethodType.methodType(void.class, ResourceKey.class));
            PresentChunks.beforeAnswer = cache -> {
                BiomeSource source = cache.getGenerator().getBiomeSource();
                if (accessor.isInstance(source)) {
                    try {
                        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) cache.getLevel();
                        seed.invoke(source, level.getSeed());
                        dimension.invoke(source, level.dimension());
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                }
            };
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
