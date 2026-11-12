package dev.denismasterherobrine.generatoraccelerator2.memory;

import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Takes a chunk's NoiseChunk off the chunk once no step reads it again. Vanilla never clears the field, so the NoiseChunk and its
 * wrapped router stay on the chunk until the chunk is full. A large pack's NoiseChunk holds about 115 KB. One pregeneration kept
 * about 33,000 of them alive, a third of a 12 GB heap.
 *
 * <p>The last reader is CARVERS on the CPU path. A chunk whose terrain and surface come from the GPU reads it at BIOMES only.
 * A step that asks later gets a new NoiseChunk from {@code getOrCreateNoiseChunk}, as a chunk loaded back between steps does.
 * A new NoiseChunk computes the same values, since a NoiseChunk keeps no state between steps.
 * {@code -Dgeneratoraccelerator2.releaseNoiseChunks=false} keeps vanilla's behaviour.
 */
public final class NoiseChunkRelease {
    private NoiseChunkRelease() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.releaseNoiseChunks"));
    /** NoiseChunks taken off their chunks. */
    public static final LongAdder RELEASED = new LongAdder();

    /** Takes the chunk's NoiseChunk off it. The caller knows no step of this chunk reads it again. */
    public static void release(ChunkAccess chunk) {
        if (!ENABLED) {
            return;
        }
        ChunkAccessAccessor access = (ChunkAccessAccessor) chunk;
        if (access.ga2$noiseChunk() != null) {
            access.ga2$setNoiseChunk(null);
            RELEASED.increment();
        }
    }
}
