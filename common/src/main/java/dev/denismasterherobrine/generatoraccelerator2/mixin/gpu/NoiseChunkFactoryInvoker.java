package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The generator's own NoiseChunk factory. After the GPU fills a chunk, the NOISE step leaves the chunk the NoiseChunk that vanilla's
 * fill would have left. The SURFACE and CARVERS steps read that NoiseChunk.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseChunkFactoryInvoker {
    @Invoker("createNoiseChunk")
    NoiseChunk ga2$createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState randomState);

    /** The biome step's work on the calling thread. Used for verification, since the climate search's last result is per thread. */
    @Invoker("doCreateBiomes")
    void ga2$doCreateBiomes(Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk);
}
