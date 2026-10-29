package dev.denismasterherobrine.generatoraccelerator2.fluid;

import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Water inside a body of water, where vanilla does work that has no effect.
 *
 * <p>Server: the aquifer marks a water block for a fluid update when two aquifer cells are about as close. All ocean cells hold
 * sea level, so the marks fall on about half of the ocean water below sea level. When the chunk becomes ticking,
 * {@code LevelChunk.postProcessGeneration} ticks each marked fluid on the server thread. A water source block above another water
 * source block, with water sources on all four sides, does not change in that tick. The fluid below it is a water source, which
 * water from above cannot replace. Water cannot pass into a side that holds a water source. The tick of such a block is skipped
 * ({@link #tickDoesNothing}). The test reads the world just before the tick. Earlier ticks of the same chunk are seen. Off when
 * another mod changes the call or the fluid's tick (hook {@code fluids.postProcess}), or with
 * {@code -Dgeneratoraccelerator2.fluids.postProcessSkip=false}.
 */
public final class StillWater {
    private StillWater() {}

    private static final boolean POST_PROCESS_SKIP = !"false".equals(System.getProperty("generatoraccelerator2.fluids.postProcessSkip"));
    private static final HookTargets.Hook POST_PROCESS_HOOK = HookTargets.hook(HookTargets.FLUID_POST_PROCESS);
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    public static boolean postProcessSkipOn() {
        return POST_PROCESS_SKIP && POST_PROCESS_HOOK.allowed();
    }

    /**
     * True when the post-processing tick of {@code fluid} at {@code pos} in {@code chunk} changes nothing. Server thread only.
     * Counts every tick asked about and every tick left out.
     */
    public static boolean tickDoesNothing(LevelChunk chunk, Level level, BlockPos pos, FluidState fluid) {
        ChunkLoadStats.FLUID_TICKS.increment();
        if (!postProcessSkipOn() || !stillWater(chunk, chunk.getPos(), level, pos, fluid)) {
            return false;
        }
        ChunkLoadStats.FLUID_TICKS_SKIPPED.increment();
        return true;
    }

    /**
     * True when the tick of {@code fluid} at {@code pos} changes nothing and the block is a LiquidBlock. {@code inside} gives the
     * blocks of chunk {@code chunk}. {@code outside} gives the others.
     */
    public static boolean stillWater(BlockGetter inside, ChunkPos chunk, BlockGetter outside, BlockPos pos, FluidState fluid) {
        if (fluid.getType() != Fluids.WATER) {
            return false;
        }
        // postProcessGeneration skips the shape update of a LiquidBlock only. Other blocks holding water keep their tick.
        if (!read(inside, chunk, outside, pos).is(Blocks.WATER)) {
            return false;
        }
        // water from above replaces water below only when the new fluid is outside the water tag
        if (!Fluids.WATER.is(FluidTags.WATER) || !Fluids.FLOWING_WATER.is(FluidTags.WATER)) {
            return false;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        // a LiquidBlock below, so the new fluid computed for it is water as well
        BlockState below = read(inside, chunk, outside, at.setWithOffset(pos, Direction.DOWN));
        if (!below.is(Blocks.WATER) || below.getFluidState().getType() != Fluids.WATER) {
            return false;
        }
        for (Direction d : HORIZONTAL) {
            if (read(inside, chunk, outside, at.setWithOffset(pos, d)).getFluidState().getType() != Fluids.WATER) {
                return false;
            }
        }
        return true;
    }

    /** The block at {@code pos}, from the chunk's own sections when inside it. The level gives the same block there. */
    private static BlockState read(BlockGetter inside, ChunkPos chunk, BlockGetter outside, BlockPos pos) {
        if (pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z()) {
            return inside.getBlockState(pos);
        }
        return outside.getBlockState(pos);
    }
}
