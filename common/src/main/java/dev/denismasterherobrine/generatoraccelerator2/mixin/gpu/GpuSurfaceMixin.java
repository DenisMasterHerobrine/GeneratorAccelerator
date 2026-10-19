package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import java.util.function.Function;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The surface step of a chunk the GPU filled makes no NoiseChunk, so the surface system gets none. The surface system would read
 * the preliminary surface levels from a NoiseChunk. Those levels come from the GPU's data instead ({@code GpuSurfaceContextMixin},
 * GA2's surface builder).
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class GpuSurfaceMixin {
    /** Applies the GPU's surface for a chunk the GPU filled, when one exists ({@code gpu.GpuSurfaceJob}). The CPU then builds nothing. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
        + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;"
        + "Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/Blender;)V", at = @At("HEAD"), cancellable = true)
    private void ga2$gpuSurface(ChunkAccess chunk, net.minecraft.world.level.levelgen.WorldGenerationContext context, net.minecraft.world.level.levelgen.RandomState randomState,
                                net.minecraft.world.level.StructureManager structures, net.minecraft.world.level.biome.BiomeManager biomeManager,
                                net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes, net.minecraft.world.level.levelgen.blending.Blender blender,
                                org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps.cpuOnlyNow()) {
            dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob.discard(chunk);
            return;
        }
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        if (dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob.apply(chunk,
            copy -> self.buildSurface(copy, context, randomState, structures, biomeManager, biomes, blender))) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
        + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;"
        + "Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/Blender;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getOrCreateNoiseChunk(Ljava/util/function/Function;)Lnet/minecraft/world/level/levelgen/NoiseChunk;"))
    private NoiseChunk ga2$gpuTerrain(ChunkAccess chunk, Function<ChunkAccess, NoiseChunk> factory, Operation<NoiseChunk> original) {
        if (GpuTerrain.viewOf(chunk)) {
            return null;
        }
        return original.call(chunk, factory);
    }
}
