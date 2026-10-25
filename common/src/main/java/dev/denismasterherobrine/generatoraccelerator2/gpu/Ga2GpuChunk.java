package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.util.concurrent.CompletableFuture;

/** Added to {@code ProtoChunk} by mixin. Holds the GPU path's state carried between a chunk's generation steps. */
public interface Ga2GpuChunk {
    /** The GPU's blocks, requested once the chunk's biomes are known (its structures are known by then too), or null. */
    CompletableFuture<GpuWorldgen.NoiseResult> ga2$gpuNoise();

    void ga2$setGpuNoise(CompletableFuture<GpuWorldgen.NoiseResult> noise);

    /** What SURFACE and CARVERS read of the GPU's work instead of a NoiseChunk (from the NOISE step on), or null. */
    GpuTerrain ga2$gpuTerrain();

    void ga2$setGpuTerrain(GpuTerrain terrain);

    /** The GPU's surface for the chunk, or null. Requested at the NOISE step, when the neighbours' biomes are final. */
    GpuSurfaceJob ga2$gpuSurface();

    void ga2$setGpuSurface(GpuSurfaceJob job);

    static Ga2GpuChunk of(Object chunk) {
        return chunk instanceof Ga2GpuChunk c ? c : null;
    }
}
