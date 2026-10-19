package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The noise fill's two worldgen heightmaps, set per column after the fill ({@link FillHeights}). */
@Mixin(value = NoiseBasedChunkGenerator.class, priority = 5000)
public abstract class NoiseFillMixin {
    @Inject(method = "doFill", at = @At("HEAD"))
    private void ga2$beginHeights(Blender blender, StructureManager structures, RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY,
                                  CallbackInfoReturnable<ChunkAccess> cir, @Share("heights") LocalRef<FillHeights> heights) {
        heights.set(FillHeights.ENABLED ? FillHeights.begin() : null);
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Heightmap;update(IIILnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean ga2$recordHeight(Heightmap heightmap, int x, int y, int z, BlockState state, @Share("heights") LocalRef<FillHeights> heights) {
        FillHeights h = heights.get();
        if (h == null) {
            return heightmap.update(x, y, z, state);
        }
        h.update(heightmap, x, y, z, state, (FillHeights.Ga2Heightmap) (Object) heightmap);
        return false;
    }

    @Inject(method = "doFill", at = @At("RETURN"))
    private void ga2$finishHeights(Blender blender, StructureManager structures, RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY,
                                   CallbackInfoReturnable<ChunkAccess> cir, @Share("heights") LocalRef<FillHeights> heights) {
        FillHeights h = heights.get();
        if (h != null) {
            h.finish();
        }
    }
}
