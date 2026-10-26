package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.slf4j.Logger;

/**
 * {@code CountOnEveryLayerPlacement.getPositions} with the column scans read in place. Vanilla runs layer after layer until
 * a layer finds nothing. Before every column it asks {@code count.sample(random)} again, so a random count draws each time.
 * For each column it draws two {@code nextInt(16)}, reads the column's {@code MOTION_BLOCKING} height, then scans from that
 * height down to the minimum build height + 1 for the layer's ground. The layer's ground is the layer-th block (counted from
 * 0) that is neither empty nor bedrock, with an empty block above it. Empty means air, water or lava. Every position found
 * is collected before any is handed on. This class does the same. When the region reads in place ({@link RegionReads}), the
 * scan reads the column's sections directly and sees the same states {@code getBlockState} returns. An all-air section is
 * passed in one step: all its blocks read as air, none of them is ground, and the block above the next one down is air. The
 * minimum build height is asked once per call.
 */
public final class EveryLayer {
    private EveryLayer() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    public static final LongAdder CALLS = new LongAdder();
    /**
     * {@code -Dgeneratoraccelerator2.verify.layers=N}: every N-th column scan read in place is also run as vanilla runs it (the
     * chunk's own reads, the minimum build height asked on every step) and compared.
     */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.layers", 0);
    private static final AtomicLong VERIFY_COUNTER = new AtomicLong();
    public static final LongAdder VERIFIED = new LongAdder();
    public static final LongAdder DIFFERING = new LongAdder();
    private static final Logger LOGGER = Ga2Log.get("Verify");

    public static List<BlockPos> positions(IntProvider count, PlacementContext ctx, RandomSource random, BlockPos origin, RegionReads reads) {
        CALLS.increment();
        List<BlockPos> out = new ArrayList<>();
        int lowest = ctx.getMinBuildHeight() + 1;
        int layer = 0;
        boolean found;
        do {
            found = false;
            for (int j = 0; j < count.sample(random); j++) {
                int x = random.nextInt(16) + origin.getX();
                int z = random.nextInt(16) + origin.getZ();
                int top = ctx.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                ChunkAccess chunk = reads.sectionChunk(x >> 4, z >> 4);
                int y = chunk != null ? ground(chunk, reads, x, top, z, layer, lowest) : groundVanilla(ctx, x, top, z, layer, lowest);
                if (VERIFY_EVERY > 0 && chunk != null && VERIFY_COUNTER.incrementAndGet() % VERIFY_EVERY == 0) {
                    verify(ctx, chunk, x, top, z, layer, y);
                }
                if (y != Integer.MAX_VALUE) {
                    out.add(new BlockPos(x, y, z));
                    found = true;
                }
            }
            layer++;
        } while (found);
        return out;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    /** {@code findOnGroundYPosition} on a chunk read through its sections. */
    static int ground(ChunkAccess chunk, RegionReads reads, int x, int top, int z, int layer, int lowest) {
        int minY = reads.minY();
        int maxY = reads.maxY();
        int minSection = minY >> 4;
        int lx = x & 15;
        int lz = z & 15;
        BlockState above;
        if (top < minY || top >= maxY) {
            above = VOID_AIR;
        } else {
            LevelChunkSection s = chunk.getSection((top >> 4) - minSection);
            above = s.hasOnlyAir() ? AIR : s.getBlockState(lx, top & 15, lz);
        }
        int found = 0;
        int j = top;
        while (j >= lowest) {
            int y = j - 1;
            BlockState here;
            if (y < minY || y >= maxY) {
                here = VOID_AIR;
            } else {
                LevelChunkSection s = chunk.getSection((y >> 4) - minSection);
                if (s.hasOnlyAir()) {
                    // every block from y down to the section's bottom reads as air. None is ground, and air is above the next.
                    above = AIR;
                    j = Math.max(lowest, (y & ~15) + 1) - 1;
                    continue;
                }
                here = s.getBlockState(lx, y & 15, lz);
            }
            if (!isEmpty(here) && isEmpty(above) && !here.is(Blocks.BEDROCK)) {
                if (found == layer) {
                    return j;
                }
                found++;
            }
            above = here;
            j--;
        }
        return Integer.MAX_VALUE;
    }

    private static void verify(PlacementContext ctx, ChunkAccess chunk, int x, int top, int z, int layer, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        int found = 0;
        int expected = Integer.MAX_VALUE;
        BlockState above = chunk.getBlockState(pos);
        for (int j = top; j >= ctx.getMinBuildHeight() + 1; j--) {
            pos.setY(j - 1);
            BlockState here = chunk.getBlockState(pos);
            if (!isEmpty(here) && isEmpty(above) && !here.is(Blocks.BEDROCK)) {
                if (found == layer) {
                    expected = pos.getY() + 1;
                    break;
                }
                found++;
            }
            above = here;
        }
        VERIFIED.increment();
        if (expected != y) {
            DIFFERING.increment();
            LOGGER.error("layer search mismatch at {}, {} from {} (layer {}): got {}, vanilla {}", x, z, top, layer, y, expected);
        }
    }

    /** {@code findOnGroundYPosition} as vanilla runs it, for a column the region does not read in place. */
    static int groundVanilla(PlacementContext ctx, int x, int top, int z, int layer, int lowest) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        int found = 0;
        BlockState above = ctx.getBlockState(pos);
        for (int j = top; j >= lowest; j--) {
            pos.setY(j - 1);
            BlockState here = ctx.getBlockState(pos);
            if (!isEmpty(here) && isEmpty(above) && !here.is(Blocks.BEDROCK)) {
                if (found == layer) {
                    return pos.getY() + 1;
                }
                found++;
            }
            above = here;
        }
        return Integer.MAX_VALUE;
    }
}
