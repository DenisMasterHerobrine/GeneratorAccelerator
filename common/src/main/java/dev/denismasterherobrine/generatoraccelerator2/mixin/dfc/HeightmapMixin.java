package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A heightmap reads the minimum build height of its chunk on every get and set. For a proto chunk, that
 * read goes through the level to the dimension type holder. The minimum build height of a chunk never
 * changes, so it is read once when the heightmap is made. The noise fill updates two heightmaps per block.
 */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin implements FillHeights.Ga2Heightmap {
    @Shadow @Final private ChunkAccess chunk;
    @Shadow @Final private Predicate<BlockState> isOpaque;

    @Shadow
    private void setHeight(int x, int z, int height) {
        throw new AssertionError();
    }

    @Override
    public Predicate<BlockState> ga2$isOpaque() {
        return this.isOpaque;
    }

    @Override
    public void ga2$setHeight(int x, int z, int height) {
        this.setHeight(x, z, height);
    }

    @Unique
    private int ga2$minY;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$cacheMinY(ChunkAccess chunk, Heightmap.Types type, CallbackInfo ci) {
        this.ga2$minY = chunk.getMinBuildHeight();
    }

    @Redirect(method = { "getFirstAvailable(I)I", "setHeight" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getMinBuildHeight()I"))
    private int ga2$minY(ChunkAccess chunk) {
        return this.ga2$minY;
    }
}
