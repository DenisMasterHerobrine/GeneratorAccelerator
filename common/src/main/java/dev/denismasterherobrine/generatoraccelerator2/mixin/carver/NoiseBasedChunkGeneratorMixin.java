package dev.denismasterherobrine.generatoraccelerator2.mixin.carver;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.carver.AquiferDiag;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkCopy;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.FrostedCaves;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkFactoryInvoker;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Late mixin on the noise generator. Carving goes to {@link CarverRunner}. When the runner declines,
 * vanilla carves. The mixin plugin also inspects {@code buildSurface(WorldGenRegion, ...)} here for
 * other mods' changes. Those changes decide how the parallel surface step calls the method.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Unique
    private static final boolean FRESH_REFERENCE = !"false".equals(System.getProperty("generatoraccelerator2.verify.carvers.fresh"));

    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void ga2$applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager,
                                  ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        ProtoChunk expected = null;
        if (CarverRunner.enabled() && CarverRunner.VERIFY.sample(chunk)) {
            // With -Dgeneratoraccelerator2.verify.carvers=N, vanilla carves a copy first, including every other mod's changes
            ProtoChunk copy = ChunkCopy.copy((ProtoChunk) chunk, region.registryAccess().registryOrThrow(Registries.BIOME));
            NoiseChunk noiseChunk = ((ChunkAccessAccessor) chunk).ga2$noiseChunk();
            if (FRESH_REFERENCE) {
                // Vanilla carves the copy with its own NoiseChunk, made as for a chunk without one.
                // With -Dgeneratoraccelerator2.verify.carvers.fresh=false, vanilla uses the fill's NoiseChunk instead. The fast path then
                // carves with that NoiseChunk's aquifer too. Vanilla's code on that aquifer can see what the fill left in GA2's aquifer state,
                // and answers differently in about 1% of chunks. Vanilla carving with a fresh NoiseChunk does not differ. Neither does GA2's
                // carving with the fill's NoiseChunk.
                ((ChunkAccessAccessor) copy).ga2$setNoiseChunk(null);
                if (FrostedCaves.active() && noiseChunk != null) {
                    // YUNG's Cave Biomes notes its biome lookup on the chunk's NoiseChunk at the biome step. The game carves with that
                    // NoiseChunk, so the fresh one, made as vanilla's carving makes it, gets the same notes
                    NoiseChunk fill = noiseChunk;
                    NoiseChunk fresh = copy.getOrCreateNoiseChunk(c -> ((NoiseChunkFactoryInvoker) (Object) this).ga2$createNoiseChunk(c, structureManager,
                        Blender.of(region), randomState));
                    FrostedCaves.copyStep(fill, fresh);
                }
                noiseChunk = null;
            }
            Runnable restore = noiseChunk == null ? () -> {} : ChunkCopy.snapshot(noiseChunk.aquifer());
            CarverRunner.runVanilla(() -> ((NoiseBasedChunkGenerator) (Object) this).applyCarvers(region, seed, randomState, biomeManager, structureManager, copy, step));
            restore.run();
            expected = copy;
        }
        if (CarverRunner.apply((NoiseBasedChunkGenerator) (Object) this, region::getChunk, region.registryAccess(), seed, randomState, biomeManager, chunk, step)) {
            if (expected != null && CarverRunner.VERIFY.compare(expected, chunk) && AquiferDiag.ENABLED) {
                NoiseChunk fill = ((ChunkAccessAccessor) chunk).ga2$noiseChunk();
                NoiseChunk fresh = ((ChunkAccessAccessor) expected).ga2$noiseChunk();
                if (fill != null && fresh != null && fill != fresh) {
                    Ga2Log.get("Verify").warn("carvers: chunk {} aquifer, fill vs fresh: {}", chunk.getPos(),
                        AquiferDiag.compare(fill, fresh, randomState, chunk.getPos(), chunk.getMinBuildHeight(), chunk.getHeight()));
                }
            }
            ga2$releaseFill(chunk);
            ci.cancel();
        }
    }

    /** Runs after vanilla carved, because GA2's carving declined or is off. */
    @Inject(method = "applyCarvers", at = @At("RETURN"))
    private void ga2$afterCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager,
                                  ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        ga2$releaseFill(chunk);
    }

    /**
     * Carving is the last step that reads the chunk's NoiseChunk. A NoiseChunk made again for carving never stops interpolating.
     * That happens when the chunk was saved before carving and loaded back. The NoiseChunk's programs and slices would then stay
     * on the chunk until the chunk is complete.
     */
    @Unique
    private static void ga2$releaseFill(ChunkAccess chunk) {
        if (((ChunkAccessAccessor) chunk).ga2$noiseChunk()
            instanceof Ga2NoiseChunk noiseChunk) {
            noiseChunk.ga2$releaseFill();
        }
        // the GPU's surface levels and aquifer cells stand in for the NoiseChunk for SURFACE and CARVERS only
        Ga2GpuChunk gpu = Ga2GpuChunk.of(chunk);
        if (gpu != null && gpu.ga2$gpuTerrain() != null) {
            gpu.ga2$setGpuTerrain(null);
        }
    }
}
