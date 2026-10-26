package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Ga2GpuChunk;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The GPU path's per-chunk state ({@link Ga2GpuChunk}). */
@Mixin(ProtoChunk.class)
public abstract class GpuProtoChunkMixin implements Ga2GpuChunk {
    @Unique
    private volatile CompletableFuture<GpuWorldgen.NoiseResult> ga2$gpuNoise;
    @Unique
    private volatile GpuTerrain ga2$gpuTerrain;
    @Unique
    private volatile GpuSurfaceJob ga2$gpuSurface;

    @Override
    public GpuSurfaceJob ga2$gpuSurface() {
        return ga2$gpuSurface;
    }

    @Override
    public void ga2$setGpuSurface(GpuSurfaceJob job) {
        this.ga2$gpuSurface = job;
    }

    @Override
    public CompletableFuture<GpuWorldgen.NoiseResult> ga2$gpuNoise() {
        return ga2$gpuNoise;
    }

    @Override
    public void ga2$setGpuNoise(CompletableFuture<GpuWorldgen.NoiseResult> noise) {
        this.ga2$gpuNoise = noise;
    }

    @Override
    public GpuTerrain ga2$gpuTerrain() {
        return ga2$gpuTerrain;
    }

    @Override
    public void ga2$setGpuTerrain(GpuTerrain terrain) {
        this.ga2$gpuTerrain = terrain;
    }
}
