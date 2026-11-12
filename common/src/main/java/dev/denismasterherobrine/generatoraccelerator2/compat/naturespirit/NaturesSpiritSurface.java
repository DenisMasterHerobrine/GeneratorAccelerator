package dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.BlockColumn;

/**
 * Nature's Spirit's pillars ({@code SurfaceBuilderMixin.injectPillars}, injected before vanilla's eroded badlands test),
 * run by GA2's surface builder at that place. Nature's Spirit's own handler is called with the values that vanilla's
 * column loop holds there: the biome, the column's section-relative and absolute position, and the block column. The
 * handler reads and writes only that column, through the block column and the chunk's ocean floor heightmap.
 */
public final class NaturesSpiritSurface {
    private NaturesSpiritSurface() {}

    public static void register() {
        ColumnHooks.register(NaturesSpiritMixins.SURFACE, NaturesSpiritSurface::hooks);
        // The pillars go up only in five biomes. A chunk whose biome window holds none of them gets the GPU's surface, where the
        // handler would change nothing. The others build it on the CPU, with the pillars.
        SurfaceSpec.declareStepWriter(NaturesSpiritMixins.SURFACE, Set.of(biome("sugi_forest"), biome("blooming_sugi_forest"),
            biome("stratified_desert"), biome("lively_dunes"), biome("blooming_dunes")));
        CompatAdapters.register(HookTargets.GPU_SURFACE, NaturesSpiritMixins.SURFACE, () -> true);
    }

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(NaturesSpiritMixins.MOD_ID, path));
    }

    private static List<ColumnHooks.Hook> hooks() throws ReflectiveOperationException {
        // (... buildSurface's arguments, CallbackInfo, @Local Holder, @Local(ordinal = 2..5) int k, l, i1, j1, @Local BlockColumn)
        MethodHandle pillars = ColumnHooks.handler("injectPillars", void.class,
            ColumnHooks.injectionParams(Holder.class, int.class, int.class, int.class, int.class, BlockColumn.class));
        return List.of(new ColumnHooks.Hook() {
            @Override
            public String handler() {
                return "injectPillars";
            }

            @Override
            public ColumnHooks.Place place() {
                return ColumnHooks.Place.TOP;
            }

            @Override
            public boolean columnLocal() {
                return true;
            }

            @Override
            public boolean readsNoiseChunk() {
                // the handler takes the NoiseChunk among buildSurface's arguments and never reads it
                return false;
            }

            @Override
            public void run(ColumnHooks.Frame f) throws Throwable {
                pillars.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci,
                    f.biome, f.localX, f.localZ, f.x, f.z, f.column);
            }
        });
    }
}
