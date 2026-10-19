package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.structure.HeightProbes;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembered column heights for structure starts ({@link HeightProbes}). Applied late (priority 5000), so the mixin plugin
 * sees other mods' changes to {@code getBaseHeight} and {@code iterateNoiseColumn} first ({@code structures.heightProbe}).
 */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class NoiseHeightProbeMixin {
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void ga2$rememberedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random, CallbackInfoReturnable<Integer> cir) {
        int height = HeightProbes.lookup((NoiseBasedChunkGenerator) (Object) this, x, z, type, level, random);
        if (height != Integer.MIN_VALUE) {
            cir.setReturnValue(height);
        }
    }

    @Inject(method = "getBaseHeight", at = @At("RETURN"))
    private void ga2$rememberHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random, CallbackInfoReturnable<Integer> cir) {
        HeightProbes.remember((NoiseBasedChunkGenerator) (Object) this, x, z, type, level, random, cir.getReturnValueI());
    }
}
