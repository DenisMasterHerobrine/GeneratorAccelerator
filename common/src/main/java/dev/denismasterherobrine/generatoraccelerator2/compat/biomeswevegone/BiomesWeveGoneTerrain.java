package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone;

import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepHandlers;
import dev.denismasterherobrine.generatoraccelerator2.surface.NoiseBiomeMemo;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oh The Biomes We've Gone's crag garden and basalt barrera terrain ({@code ChunkStatusTasksMixin.injectExtensions}). The
 * mod injects it before the SURFACE step's generator call. GA2 runs the mod's handler on the same worker as the generator
 * call ({@link StepHandlers}), with the arguments and locals that vanilla's step holds there.
 *
 * <p>Reviewed for running on several threads at once (2.6.0). The handler creates its noises, block state providers,
 * random sources and biome cache per call. It writes only the step's own chunk ({@code ChunkAccess.setBlockState}). It
 * reads that chunk's worldgen heightmap and blocks, registry entries and a constant lookup table ({@code MathUtil}). It
 * reads biomes from the generator's biome source with the level's climate sampler, a function of the position alone.
 * The basalt part also asks a chunk up to eight chunks away for its generator height. The mod keeps that height in a
 * per-chunk cache and fills it without synchronization. GA2 locks the cache ({@code mixin.ChunkAccessHeightsMixin}).
 * Without that lock in place, the step stays on the worldgen thread.
 *
 * <p>The handler asks the biome source for each of the chunk's 256 columns, and around the columns of the mod's two
 * biomes. It does so through its own {@code BiomeManager}. Inside the handler, GA2 remembers that manager's answers per
 * quart point ({@link NoiseBiomeMemo}), so the biome source is asked once per point.
 */
public final class BiomesWeveGoneTerrain {
    private BiomesWeveGoneTerrain() {}

    private static final Object[] HEIGHT_LOCKS = new Object[64];

    static {
        for (int i = 0; i < HEIGHT_LOCKS.length; i++) {
            HEIGHT_LOCKS[i] = new Object();
        }
    }

    public static void register() {
        StepHandlers.register(BiomesWeveGoneMixins.STEP, BiomesWeveGoneTerrain::handlers);
        // the handler writes a column only where the biome at its land height (through the biome source and vanilla's zoom) is one
        // of these two. The GPU's surface leaves chunks near them to the CPU, which builds the surface after the handler
        SurfaceSpec.declareStepWriter(BiomesWeveGoneMixins.STEP, Set.of(
            biome("crag_gardens"), biome("basalt_barrera")));
    }

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath(BiomesWeveGoneMixins.MOD_ID, path));
    }

    /** The lock of a chunk's generator height cache ({@code mixin.ChunkAccessHeightsMixin}). */
    public static Object heightLock(Object chunk) {
        int h = System.identityHashCode(chunk);
        return HEIGHT_LOCKS[(h ^ (h >>> 16)) & (HEIGHT_LOCKS.length - 1)];
    }

    private static List<StepHandlers.Handler> handlers() throws ReflectiveOperationException {
        if (!heightsLocked()) {
            throw new NoSuchMethodException("the lock of ChunkAccess.getHeight(ChunkGenerator, ...) is not in place");
        }
        // (WorldGenContext, ChunkStep, StaticCache2D, ChunkAccess, CallbackInfoReturnable, @Local ServerLevel, @Local WorldGenRegion)
        MethodHandle extensions = StepHandlers.staticHandler("injectExtensions", WorldGenContext.class, ChunkStep.class, StaticCache2D.class,
            ChunkAccess.class, CallbackInfoReturnable.class, ServerLevel.class, WorldGenRegion.class);
        return List.of(new StepHandlers.Handler() {
            @Override
            public String handler() {
                return "injectExtensions";
            }

            @Override
            public void run(StepHandlers.Frame f) throws Throwable {
                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir = new CallbackInfoReturnable<>("generateSurface", false);
                try (NoiseBiomeMemo ignored = NoiseBiomeMemo.open()) {
                    extensions.invoke(f.ctx(), f.step(), f.cache(), f.chunk(), cir, f.level(), f.region());
                }
            }
        });
    }

    /** True when GA2's lock wraps the mod's generator height cache. GA2's mixin is applied only if the mod's method exists. */
    private static boolean heightsLocked() {
        try {
            for (Method m : ChunkAccess.class.getDeclaredMethods()) {
                if (m.getName().endsWith("$ga2$lockedGeneratorHeight")) {
                    return true;
                }
            }
        } catch (LinkageError e) {
            // Another mod's mixin method names a class missing on this side. The cache is not known to be locked.
        }
        return false;
    }
}
