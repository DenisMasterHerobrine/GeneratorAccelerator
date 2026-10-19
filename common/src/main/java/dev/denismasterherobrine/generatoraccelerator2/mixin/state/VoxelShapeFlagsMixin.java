package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.state.ShapeFlags;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A shape's remembered answers ({@link ShapeFlags}). */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeFlagsMixin implements ShapeFlags.Holder {
    @Unique
    private byte ga2$full;

    @Override
    public byte ga2$full() {
        return ga2$full;
    }

    @Override
    public void ga2$setFull(byte full) {
        this.ga2$full = full;
    }
}
