package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.PatchRepeats;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A random patch's try at a position that gave no position before, with nothing run since, is answered at once ({@link PatchRepeats}). */
@Mixin(value = RandomPatchFeature.class, priority = 5000)
public abstract class RandomPatchRepeatsMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;place("
        + "Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean ga2$tryOnce(PlacedFeature inner, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos,
                                Operation<Boolean> original, @Share("ga2$patchStamp") LocalLongRef stamp) {
        return PatchRepeats.place(inner, level, generator, random, pos, original, stamp);
    }
}
