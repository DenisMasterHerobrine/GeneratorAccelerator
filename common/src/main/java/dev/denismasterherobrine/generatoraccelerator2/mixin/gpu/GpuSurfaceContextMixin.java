package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A surface rule context without a NoiseChunk (a chunk the GPU filled) reads the preliminary surface levels from the GPU's data. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context", priority = 5000)
public abstract class GpuSurfaceContextMixin {
    @Shadow @Final ChunkAccess chunk;

    @WrapOperation(method = "getMinSurfaceLevel()I",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;preliminarySurfaceLevel(II)I"))
    private int ga2$gpuLevels(NoiseChunk noiseChunk, int x, int z, Operation<Integer> original) {
        if (noiseChunk == null) {
            GpuTerrain t = GpuTerrain.of(this.chunk);
            if (t != null) {
                return t.preliminarySurfaceLevel(x, z);
            }
        }
        return original.call(noiseChunk, x, z);
    }
}
