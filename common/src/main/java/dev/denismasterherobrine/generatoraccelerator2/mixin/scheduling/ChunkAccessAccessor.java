package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {
    @Accessor("noiseChunk")
    NoiseChunk ga2$noiseChunk();

    @Accessor("noiseChunk")
    void ga2$setNoiseChunk(NoiseChunk noiseChunk);

    /** The level the chunk was made for. For chunks the chunk map makes, this is a {@code ServerLevel}. */
    @Accessor("levelHeightAccessor")
    net.minecraft.world.level.LevelHeightAccessor ga2$levelHeightAccessor();

    /** The chunk's own structure starts. {@code getAllStarts} wraps this map in an unmodifiable view. */
    @Accessor("structureStarts")
    java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, net.minecraft.world.level.levelgen.structure.StructureStart> ga2$structureStarts();
}
