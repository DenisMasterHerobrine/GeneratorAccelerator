package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceCopies;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The surface build asks this thread's surface system ({@link SurfaceCopies}). */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class SurfaceCopiesMixin {
    @WrapOperation(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
        + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/biome/BiomeManager;"
        + "Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/blending/Blender;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;surfaceSystem()Lnet/minecraft/world/level/levelgen/SurfaceSystem;"))
    private SurfaceSystem ga2$threadSystem(RandomState randomState, Operation<SurfaceSystem> original) {
        return SurfaceCopies.forThread(original.call(randomState), randomState);
    }
}
