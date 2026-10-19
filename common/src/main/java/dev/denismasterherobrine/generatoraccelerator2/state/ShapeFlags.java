package dev.denismasterherobrine.generatoraccelerator2.state;

/** What a voxel shape remembers about itself ({@code VoxelShapeFlagsMixin}). */
public final class ShapeFlags {
    private ShapeFlags() {}

    /** Implemented by every voxel shape. */
    public interface Holder {
        /** {@code Block.isShapeFullBlock}: 0 not known yet, 1 full, 2 not. */
        byte ga2$full();

        void ga2$setFull(byte full);
    }
}
