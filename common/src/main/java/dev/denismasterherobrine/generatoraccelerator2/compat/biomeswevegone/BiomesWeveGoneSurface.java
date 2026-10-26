package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.BlockColumn;

/**
 * Oh The Biomes We've Gone's glacier extension ({@code SurfaceSystemMixin.injectShatteredGlacierExtension}). The mod
 * injects it after vanilla's eroded badlands extension. GA2's surface builder runs the mod's handler at that place, with
 * the values that vanilla's column loop holds there. The handler runs vanilla's eroded badlands extension on the column
 * for two of the mod's biomes. That extension reads and writes only that column, through the block column.
 *
 * <p>The mod's terrain extensions in the SURFACE step itself (crag gardens, basalt barrera) run before the generator
 * call, on the same worker as that call ({@link BiomesWeveGoneTerrain}).
 */
public final class BiomesWeveGoneSurface {
    private BiomesWeveGoneSurface() {}

    public static void register() {
        ColumnHooks.register(BiomesWeveGoneMixins.SURFACE, BiomesWeveGoneSurface::hooks);
        // the GPU's surface leaves the chunks of these two biomes to the CPU, where the handler runs, as it leaves eroded badlands
        SurfaceSpec.addBadlandsExtension(biome("shattered_glacier"));
        SurfaceSpec.addBadlandsExtension(biome("eroded_borealis"));
        CompatAdapters.register(HookTargets.GPU_SURFACE,
            BiomesWeveGoneMixins.SURFACE, () -> true);
    }

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(BiomesWeveGoneMixins.MOD_ID, path));
    }

    private static List<ColumnHooks.Hook> hooks() throws ReflectiveOperationException {
        // (... buildSurface's arguments, CallbackInfo, @Local BlockColumn, @Local(ordinal = 4..6) int i1, j1, k1, @Local Holder)
        MethodHandle glacier = ColumnHooks.handler("injectShatteredGlacierExtension", void.class,
            ColumnHooks.injectionParams(BlockColumn.class, int.class, int.class, int.class, Holder.class));
        return List.of(new ColumnHooks.Hook() {
            @Override
            public String handler() {
                return "injectShatteredGlacierExtension";
            }

            @Override
            public ColumnHooks.Place place() {
                return ColumnHooks.Place.BEFORE_RULES;
            }

            @Override
            public boolean columnLocal() {
                return true;
            }

            /** The handler gets the NoiseChunk as an argument, but the extension it runs reads only the column and the chunk's height. */
            @Override
            public boolean readsNoiseChunk() {
                return false;
            }

            @Override
            public void run(ColumnHooks.Frame f) throws Throwable {
                glacier.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci,
                    f.column, f.x, f.z, f.top, f.biome);
            }
        });
    }
}
