package dev.denismasterherobrine.generatoraccelerator2.compat.twilightforest;

import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepHandlers;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Twilight Forest's chunk blanketing ({@code WorldgenHooks.chunkBlanketing(chunk, region)}). Twilight Forest's coremod puts
 * that call right after the SURFACE step's generator call. When GA2 takes the step over ({@link StepHandlers}), GA2 runs the
 * blanketing on the worker right after that call. So the blanketing still sees the built surface and comes before everything
 * vanilla's step would do next.
 *
 * <p>Reviewed for running on several threads at once (4.8.3345). The blanketing runs the processors of the biomes in the
 * step's chunk: the dark forest canopy and the glacier. For those, it reads the region's biomes, the chunk's structure
 * references and worldgen heightmap, registries and the overworld seed. It forks the region's random and writes only the
 * step's own chunk.
 */
public final class TwilightForestSurface {
    private TwilightForestSurface() {}

    public static void register() {
        StepHandlers.register(TwilightForestCoremods.CHANGE, TwilightForestSurface::handlers);
    }

    private static List<StepHandlers.Handler> handlers() throws ReflectiveOperationException {
        Class<?> hooks = Class.forName(TwilightForestCoremods.HOOKS, true, TwilightForestSurface.class.getClassLoader());
        MethodHandle blanketing = MethodHandles.publicLookup().findStatic(hooks, "chunkBlanketing",
            MethodType.methodType(void.class, ChunkAccess.class, WorldGenRegion.class));
        return List.of(new StepHandlers.Handler() {
            @Override
            public String handler() {
                return "chunkBlanketing";
            }

            @Override
            public boolean afterCall() {
                return true;
            }

            @Override
            public void run(StepHandlers.Frame f) throws Throwable {
                blanketing.invoke(f.chunk(), f.region());
            }
        });
    }
}
