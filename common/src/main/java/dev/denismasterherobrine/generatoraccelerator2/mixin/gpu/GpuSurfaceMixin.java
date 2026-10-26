package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSurfaceJob;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The surface step of a chunk the GPU filled makes no NoiseChunk, so the surface system gets none. The surface system would read
 * the preliminary surface levels from a NoiseChunk. Those levels come from the GPU's data instead ({@code GpuSurfaceContextMixin},
 * GA2's surface builder).
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class GpuSurfaceMixin {
    /** Applies the GPU's surface for a chunk the GPU filled, when one exists ({@code gpu.GpuSurfaceJob}). The CPU then builds nothing. */
    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
        + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;"
        + "Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/Blender;)V", at = @At("HEAD"), cancellable = true)
    private void ga2$gpuSurface(ChunkAccess chunk, WorldGenerationContext context, RandomState randomState,
                                StructureManager structures, BiomeManager biomeManager,
                                Registry<Biome> biomes, Blender blender,
                                CallbackInfo ci) {
        if (GpuSteps.cpuOnlyNow()) {
            GpuSurfaceJob.discard(chunk);
            return;
        }
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        NoiseGeneratorSettings settings = self.generatorSettings().value();
        if (GpuSurfaceJob.apply(chunk,
            copy -> self.buildSurface(copy, context, randomState, structures, biomeManager, biomes, blender),
            () -> ColumnHooks.records(randomState.surfaceSystem(), randomState, biomeManager, biomes,
                settings.useLegacyRandomSource(), context, chunk, settings.surfaceRule()))) {
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
