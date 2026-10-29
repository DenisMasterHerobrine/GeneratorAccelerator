package dev.denismasterherobrine.generatoraccelerator2.features.placement;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>Nothing is placed while a call runs, so a column's height and grounds stay the same for the whole call. Each column's scan
 * is kept for the call ({@link Columns}). A column drawn again, in the same layer or a later one, goes on from where its last
 * scan stopped. Vanilla scans it again from the top. This pays when a layer draws many columns, as some mods' features do (40
 * to 100 per layer). Vanilla's own counts of 4 to 8 rarely draw a column twice.
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
    /** Columns drawn again in a call, answered from their kept scan. */
    public static final LongAdder CONTINUED = new LongAdder();
    private static final ThreadLocal<Columns> COLUMNS = ThreadLocal.withInitial(Columns::new);

    /**
     * The scans of one call's columns, by the column's offset from the call's origin. One per thread, cleared at the start of
     * every call. A call reads blocks only, so no other call runs on the thread while it runs.
     */
    static final class Columns {
        /** Columns started in this call, one bit each. */
        private final long[] started = new long[4];
        private final int[] top = new int[256];
        /** The next {@code j} to look at. Below the lowest y once the column is scanned to the bottom. */
        private final int[] next = new int[256];
        /** The state at {@code next}, the block above the next one read. */
        private final BlockState[] above = new BlockState[256];
        /** The grounds found so far, from the top, and how many. */
        private final int[][] grounds = new int[256][];
        private final int[] found = new int[256];

        private void clear() {
            started[0] = 0L;
            started[1] = 0L;
            started[2] = 0L;
            started[3] = 0L;
        }

        private boolean kept(int col, int top) {
            return (started[col >> 6] & 1L << col) != 0 && this.top[col] == top;
        }

        private void start(int col, int top, BlockState above) {
            started[col >> 6] |= 1L << col;
            this.top[col] = top;
            this.next[col] = top;
            this.above[col] = above;
            this.found[col] = 0;
        }

        private void add(int col, int y) {
            int[] g = grounds[col];
            int n = found[col];
            if (g == null) {
                g = grounds[col] = new int[4];
            } else if (n == g.length) {
                g = grounds[col] = Arrays.copyOf(g, n * 2);
            }
            g[n] = y;
            found[col] = n + 1;
        }
    }

    public static List<BlockPos> positions(IntProvider count, PlacementContext ctx, RandomSource random, BlockPos origin, RegionReads reads) {
        CALLS.increment();
        List<BlockPos> out = new ArrayList<>();
        int lowest = ctx.getMinY() + 1;
        Columns columns = COLUMNS.get();
        columns.clear();
        int continued = 0;
        int layer = 0;
        boolean found;
        do {
            found = false;
            for (int j = 0; j < count.sample(random); j++) {
                int dx = random.nextInt(16);
                int dz = random.nextInt(16);
                int x = dx + origin.getX();
                int z = dz + origin.getZ();
                int top = ctx.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                ChunkAccess chunk = reads.sectionChunk(x >> 4, z >> 4);
                int y;
                if (chunk != null) {
                    int col = dx | dz << 4;
                    if (columns.kept(col, top)) {
                        continued++;
                    }
                    y = ground(columns, col, chunk, reads, x, top, z, layer, lowest);
                } else {
                    y = groundVanilla(ctx, x, top, z, layer, lowest);
                }
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
        if (continued > 0) {
            CONTINUED.add(continued);
        }
        return out;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    /**
     * {@code findOnGroundYPosition} on a chunk read through its sections. The column's scan goes on from where it stopped in this
     * call, and only until the layer's ground is found.
     */
    static int ground(Columns columns, int col, ChunkAccess chunk, RegionReads reads, int x, int top, int z, int layer, int lowest) {
        int minY = reads.minY();
        int maxY = reads.maxY();
        int minSection = minY >> 4;
        int lx = x & 15;
        int lz = z & 15;
        if (!columns.kept(col, top)) {
            BlockState first;
            if (top < minY || top >= maxY) {
                first = VOID_AIR;
            } else {
                LevelChunkSection s = chunk.getSection((top >> 4) - minSection);
                first = s.hasOnlyAir() ? AIR : s.getBlockState(lx, top & 15, lz);
            }
            columns.start(col, top, first);
        }
        int found = columns.found[col];
        if (layer < found) {
            return columns.grounds[col][layer];
        }
        BlockState above = columns.above[col];
        int j = columns.next[col];
        while (j >= lowest && found <= layer) {
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
                columns.add(col, j);
                found++;
            }
            above = here;
            j--;
        }
        columns.next[col] = j;
        columns.above[col] = above;
        return layer < found ? columns.grounds[col][layer] : Integer.MAX_VALUE;
    }

    private static void verify(PlacementContext ctx, ChunkAccess chunk, int x, int top, int z, int layer, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        int found = 0;
        int expected = Integer.MAX_VALUE;
        BlockState above = chunk.getBlockState(pos);
        for (int j = top; j >= ctx.getMinY() + 1; j--) {
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
