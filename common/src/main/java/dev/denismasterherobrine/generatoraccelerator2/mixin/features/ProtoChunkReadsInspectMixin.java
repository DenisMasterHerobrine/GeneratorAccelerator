package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Applied late so the mixin plugin can record other mods' changes to the block and fluid reads of {@code ProtoChunk}. The region
 * reads ({@code RegionReads}) perform these reads in place for a plain proto chunk. This class also holds replacements of the two
 * reads ({@code compat.LateOverwrites}), used where no mod changes them. The replacements serve code that reads a proto chunk
 * itself, such as a mod's feature reading the chunk it holds.
 * Vanilla's reads ask the chunk's height accessor (the level's dimension type) for the build height two or three times per read.
 * The bounds are fixed for the chunk's lifetime, so they are read once and kept in one {@code long}, written whole. The rest
 * matches vanilla: the same void air outside the build height, the same all-air shortcut, the same section read.
 */
@Mixin(value = ProtoChunk.class, priority = 5000)
public abstract class ProtoChunkReadsInspectMixin {
    @Unique
    private static final BlockState GA2_VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
    @Unique
    private static final BlockState GA2_AIR = Blocks.AIR.defaultBlockState();

    /** Bottom of the build height (high half) and top (low half). Stays 0 until the first read, since no build height is empty. */
    @Unique
    private long ga2$bounds;

    @Unique
    private long ga2$bounds() {
        long b = this.ga2$bounds;
        if (b == 0L) {
            ChunkAccess self = (ChunkAccess) (Object) this;
            b = (long) self.getMinBuildHeight() << 32 | self.getMaxBuildHeight() & 0xFFFFFFFFL;
            this.ga2$bounds = b;
        }
        return b;
    }

    /** {@code ProtoChunk.getBlockState}, with the build height read once. */
    @Unique
    public BlockState ga2$getBlockState(BlockPos pos) {
        long b = ga2$bounds();
        int minY = (int) (b >> 32);
        int y = pos.getY();
        if (y < minY || y >= (int) b) {
            return GA2_VOID_AIR;
        }
        LevelChunkSection section = ((ChunkAccess) (Object) this).getSections()[(y >> 4) - (minY >> 4)];
        return section.hasOnlyAir() ? GA2_AIR : section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }

    /** {@code ProtoChunk.getFluidState}, with the build height read once. */
    @Unique
    public FluidState ga2$getFluidState(BlockPos pos) {
        long b = ga2$bounds();
        int minY = (int) (b >> 32);
        int y = pos.getY();
        if (y < minY || y >= (int) b) {
            return Fluids.EMPTY.defaultFluidState();
        }
        LevelChunkSection section = ((ChunkAccess) (Object) this).getSections()[(y >> 4) - (minY >> 4)];
        return section.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState() : section.getFluidState(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }
}
