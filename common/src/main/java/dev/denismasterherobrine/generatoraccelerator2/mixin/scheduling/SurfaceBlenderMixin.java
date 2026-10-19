package dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepThreads;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The parallel surface step calls the generator's own {@code buildSurface(WorldGenRegion, ...)} on a worker, so other
 * mods' changes to that method keep working. The step's blender is computed beforehand on the worldgen thread, where
 * vanilla's step computes it. Old-world blending data is computed lazily and is not safe to compute from several
 * threads.
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class SurfaceBlenderMixin {
    @WrapOperation(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/blending/Blender;of(Lnet/minecraft/server/level/WorldGenRegion;)Lnet/minecraft/world/level/levelgen/blending/Blender;"))
    private Blender ga2$stepBlender(WorldGenRegion region, Operation<Blender> original) {
        Blender blender = StepThreads.stepBlender();
        return blender != null ? blender : original.call(region);
    }
}
