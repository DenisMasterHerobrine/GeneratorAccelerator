package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ChunkCompare;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;

/**
 * {@code -Dgeneratoraccelerator2.verify.gpu=N}: every N-th chunk the GPU filled is filled again by GA2's CPU path in a scratch chunk
 * with the same structures. The two chunks are then compared (sections as stored, counters, heightmaps, post-processing marks). For
 * testing only. Each comparison costs a whole CPU noise fill.
 */
public final class GpuVerify {
    private GpuVerify() {}

    private static final Logger LOGGER = Ga2Log.get("GPU");
    private static final int EVERY = Integer.getInteger("generatoraccelerator2.verify.gpu", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();
    public static final AtomicLong SURFACES = new AtomicLong();
    public static final AtomicLong SURFACES_DIFFERING = new AtomicLong();
    private static final AtomicLong SEEN_SURFACE = new AtomicLong();

    /**
     * The surface half. Before the GPU's surface is written into every N-th chunk, vanilla's own surface build runs on a copy, with GA2's
     * surface builder off. The returned action compares the two once the GPU's surface is written (sections as stored, counters,
     * heightmaps, marks).
     */
    static Runnable surfaceBefore(ChunkAccess chunk, java.util.function.Consumer<ChunkAccess> cpuSurface) {
        if (EVERY <= 0 || cpuSurface == null || !(chunk instanceof ProtoChunk proto) || SEEN_SURFACE.incrementAndGet() % EVERY != 0) {
            return null;
        }
        ServerLevel level = Gpu.level(chunk);
        if (level == null) {
            return null;
        }
        try {
            ProtoChunk copy = dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkCopy.copy(proto, level.registryAccess().registryOrThrow(Registries.BIOME));
            GpuSteps.cpuOnly(() -> {
                dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks.runVanilla(() -> cpuSurface.accept(copy));
                return null;
            });
            return () -> {
                String diff = ChunkCompare.blocks(copy, chunk);
                SURFACES.incrementAndGet();
                if (diff != null) {
                    SURFACES_DIFFERING.incrementAndGet();
                    LOGGER.error("verify surface: chunk {} mismatch vs vanilla: {}", chunk.getPos(), diff);
                }
            };
        } catch (RuntimeException e) {
            LOGGER.warn("verify surface: chunk {} check failed", chunk.getPos(), e);
            return null;
        }
    }

    public static final AtomicLong TERRAINS = new AtomicLong();
    public static final AtomicLong TERRAINS_DIFFERING = new AtomicLong();

    public static boolean active() {
        return EVERY > 0;
    }

    private static final AtomicLong SEEN_BIOMES = new AtomicLong();

    /**
     * The biome half. Compares every N-th chunk's GPU biomes with the CPU biome step's output in a scratch chunk, at every quart. Ties
     * are covered too. The CPU's fill starts from the same last climate search result of the thread that the GPU's fill started from.
     * The GPU's fill leaves that thread state as vanilla's fill does.
     */
    static void biomes(NoiseBasedChunkGenerator generator, RandomState randomState, ChunkAccess chunk, StructureManager structures, GpuWorldgen wg,
                       GpuWorldgen.BiomeResult res, net.minecraft.world.level.biome.Climate.RTree.Leaf<Holder<net.minecraft.world.level.biome.Biome>>[] start) {
        if (EVERY <= 0 || start == null || SEEN_BIOMES.incrementAndGet() % EVERY != 0) {
            return;
        }
        ServerLevel level = Gpu.level(chunk);
        if (level == null) {
            return;
        }
        try {
            ProtoChunk cpu = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            var after = wg.lastResults();
            wg.setLastResults(start);
            GpuSteps.cpuOnly(() -> {
                ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkFactoryInvoker) (Object) generator).ga2$doCreateBiomes(Blender.empty(), randomState, structures, cpu);
                return null;
            });
            String diff = ChunkCompare.biomes(cpu, chunk, i -> false);
            if (diff == null && !java.util.Arrays.equals(wg.lastResults(), after)) {
                // leaves compare by identity
                diff = "the thread's last climate search result after the fill differs";
            }
            wg.setLastResults(after);
            VERIFIED.incrementAndGet();
            if (diff != null) {
                DIFFERING.incrementAndGet();
                LOGGER.error("verify biomes: chunk {} in {} mismatch vs CPU: {}", chunk.getPos(), wg.name, diff);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("verify biomes: chunk {} check failed", chunk.getPos(), e);
        }
    }

    /**
     * This thread's last search results of every tree the GPU searches: vanilla's, and Biolith's two answers when Biolith is loaded.
     * The handed fill and the CPU's fill must leave them alike.
     */
    public static Object[] handState(GpuWorldgen wg) {
        Object[] biolith = null;
        if (dev.denismasterherobrine.generatoraccelerator2.dfc.Compat.isLoaded(dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithMixins.MOD_ID)) {
            biolith = new Object[wg.biomeTreeCount()];
            for (int t = 0; t < biolith.length; t++) {
                var tree = wg.biomeTree(t);
                biolith[t] = tree == null ? null : dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.lastAnswers(tree);
            }
        }
        return new Object[] { wg.lastResults(), biolith };
    }

    @SuppressWarnings("unchecked")
    public static void restoreHandState(GpuWorldgen wg, Object[] state) {
        wg.setLastResults((net.minecraft.world.level.biome.Climate.RTree.Leaf<Holder<net.minecraft.world.level.biome.Biome>>[]) state[0]);
        if (state[1] instanceof Object[] biolith) {
            for (int t = 0; t < biolith.length; t++) {
                var tree = wg.biomeTree(t);
                if (tree != null && biolith[t] != null) {
                    dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch.restoreLastAnswers(tree, (Object[]) biolith[t]);
                }
            }
        }
    }

    /** Whether two {@link #handState}s hold the same leaves (compared by identity). */
    private static boolean sameHandState(Object[] a, Object[] b) {
        if (!java.util.Arrays.equals((Object[]) a[0], (Object[]) b[0])) {
            return false;
        }
        if (a[1] instanceof Object[] x && b[1] instanceof Object[] y) {
            for (int t = 0; t < x.length; t++) {
                Object[] p = (Object[]) x[t], q = (Object[]) y[t];
                if (p != null && q != null && (p[0] != q[0] || p[1] != q[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A chunk whose biome step ran with the GPU's handed answers ({@code gpu.runtime.BiomeHand}): the step again in a scratch chunk
     * without them, from the same last search results ({@code start}). The biomes and the last results after must be the same.
     */
    static void handBiomes(NoiseBasedChunkGenerator generator, RandomState randomState, ChunkAccess chunk, StructureManager structures, GpuWorldgen wg,
                           Object[] start) {
        if (EVERY <= 0 || start == null || SEEN_BIOMES.incrementAndGet() % EVERY != 0) {
            return;
        }
        ServerLevel level = Gpu.level(chunk);
        if (level == null) {
            return;
        }
        try {
            ProtoChunk cpu = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            Object[] after = handState(wg);
            restoreHandState(wg, start);
            GpuSteps.cpuOnly(() -> {
                ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkFactoryInvoker) (Object) generator).ga2$doCreateBiomes(Blender.empty(), randomState, structures, cpu);
                return null;
            });
            String diff = ChunkCompare.biomes(cpu, chunk, i -> false);
            if (diff == null && !sameHandState(handState(wg), after)) {
                diff = "the thread's last climate search results after the fill differ";
            }
            restoreHandState(wg, after);
            VERIFIED.incrementAndGet();
            if (diff != null) {
                DIFFERING.incrementAndGet();
                LOGGER.error("verify handed biomes: chunk {} in {} mismatch vs CPU: {}", chunk.getPos(), wg.name, diff);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("verify handed biomes: chunk {} check failed", chunk.getPos(), e);
        }
    }

    static void noise(NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings, Supplier<Aquifer.FluidPicker> picker, RandomState randomState,
                      ChunkAccess chunk, StructureManager structures, GpuWorldgen wg) {
        if (EVERY <= 0 || SEEN.incrementAndGet() % EVERY != 0) {
            return;
        }
        ServerLevel level = Gpu.level(chunk);
        if (level == null) {
            return;
        }
        try {
            ProtoChunk cpu = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            Beardifier beard = Beardifier.forStructuresInChunk(structures, chunk.getPos());
            cpu.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState, beard, settings.value(), picker.get(), Blender.empty()));
            GpuSteps.cpuOnly(() -> generator.fillFromNoise(Blender.empty(), randomState, structures, cpu)).join();
            String diff = ChunkCompare.blocks(cpu, chunk);
            VERIFIED.incrementAndGet();
            if (diff != null) {
                DIFFERING.incrementAndGet();
                LOGGER.error("verify: chunk {} in {} mismatch vs CPU: {}", chunk.getPos(), wg.name, diff);
            }
            // Compare what SURFACE and CARVERS read instead of a NoiseChunk with the CPU fill's NoiseChunk.
            GpuTerrain terrain = GpuTerrain.of(chunk);
            if (terrain != null) {
                String td = terrain.compare(((dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor) cpu).ga2$noiseChunk(),
                    randomState.aquiferRandom());
                TERRAINS.incrementAndGet();
                if (td != null) {
                    TERRAINS_DIFFERING.incrementAndGet();
                    LOGGER.error("verify terrain: chunk {} in {} mismatch vs CPU: {}", chunk.getPos(), wg.name, td);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("verify: chunk {} check failed", chunk.getPos(), e);
        }
    }
}
