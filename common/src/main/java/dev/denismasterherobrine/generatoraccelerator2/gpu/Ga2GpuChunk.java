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

    /** The log of block writes into the chunk while the GPU keeps a copy of it ({@code gpu.decor}), or null. */
    dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DeltaLog ga2$deltaLog();

    void ga2$setDeltaLog(dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DeltaLog log);

    /** The GPU's features for the chunk, requested when the chunk's FEATURES step was scheduled, or null. */
    dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DecorJob ga2$decorJob();

    void ga2$setDecorJob(dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DecorJob job);

    static Ga2GpuChunk of(Object chunk) {
        return chunk instanceof Ga2GpuChunk c ? c : null;
    }
}
