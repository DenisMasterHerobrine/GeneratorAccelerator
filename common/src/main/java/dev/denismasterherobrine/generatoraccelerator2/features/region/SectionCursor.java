package dev.denismasterherobrine.generatoraccelerator2.features.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Block access for fast paths: remembers the last chunk and section so consecutive reads in the
 * same section cost a palette lookup and nothing else. Reads reproduce {@code WorldGenRegion.getBlockState}
 * exactly. For a plain {@code ProtoChunk} that is {@code ProtoChunk.getBlockState}: VOID_AIR outside the
 * build height, AIR for an all-air section even where cave air is stored. Any other chunk class is
 * asked directly. The cursor is write-through by construction (it holds no copies).
 */
public final class SectionCursor {
    private Ga2Region region;
    private int minY;
    private int maxY;
    private int minSection;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private ChunkAccess chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;
    private LevelChunkSection section;
    private int sectionIndex = -1;
    /** The last chunks met, by the low bits of their coordinates (reads that cross a chunk border and back skip the region lookup). */
    private final ChunkAccess[] near = new ChunkAccess[4];
    private final long[] nearKey = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };

    public SectionCursor(Ga2Region region) {
        this.region = region;
        this.minY = region.ga2$minY();
        this.maxY = region.ga2$maxY();
        this.minSection = minY >> 4;
    }

    /** A cursor for nothing yet, to {@link #reset} (a caller's reused cursor). */
    public SectionCursor() {
    }

    /** Kinds of fast path with a cursor of their own on each thread ({@link #mine}). */
    public static final int DISK = 0, SPRING = 1, SNOW = 2, SEAGRASS = 3, KELP = 4, MULTIFACE = 5, TREE = 6, GEODE = 7, MONSTER_ROOM = 8;
    private static final ThreadLocal<SectionCursor[]> MINE = ThreadLocal.withInitial(() -> new SectionCursor[MONSTER_ROOM + 1]);

    /**
     * This thread's cursor of the fast path {@code kind}, as a new one over {@code region}, instead of a new cursor per placement. For
     * fast paths that place no feature of their own kind while they run and never wait, so no other placement runs on the thread
     * in between. The region the cursor last read stays referenced until the kind's next placement on the thread, or until the end
     * of the thread's step ({@link #releaseMine}).
     */
    public static SectionCursor mine(int kind, Ga2Region region) {
        SectionCursor[] mine = MINE.get();
        SectionCursor cursor = mine[kind];
        if (cursor == null) {
            cursor = mine[kind] = new SectionCursor();
        }
        return cursor.reset(region);
    }

    /** Clears this thread's cursors ({@link #mine}), so they no longer hold their last region and its chunks ({@link RegionRefs}). */
    public static void releaseMine() {
        for (SectionCursor cursor : MINE.get()) {
            if (cursor != null && cursor.region != null) {
                cursor.clear();
            }
        }
    }

    /** This cursor as a new one over {@code region}: nothing of the last region remembered. */
    public SectionCursor reset(Ga2Region region) {
        this.region = region;
        this.minY = region.ga2$minY();
        this.maxY = region.ga2$maxY();
        this.minSection = minY >> 4;
        this.chunk = null;
        java.util.Arrays.fill(this.near, null);
        java.util.Arrays.fill(this.nearKey, Long.MIN_VALUE);
        invalidate();
        return this;
    }

    /** Lets go of the region and its chunks (a reused cursor between uses). */
    public void clear() {
        this.region = null;
        this.chunk = null;
        this.section = null;
        java.util.Arrays.fill(this.near, null);
        java.util.Arrays.fill(this.nearKey, Long.MIN_VALUE);
        invalidate();
    }

    public Ga2Region region() {
        return region;
    }

    public int minY() {
        return minY;
    }

    /** Max build height, exclusive. */
    public int maxY() {
        return maxY;
    }

    public boolean outsideBuildHeight(int y) {
        return y < minY || y >= maxY;
    }

    public ChunkAccess chunk(int cx, int cz) {
        if (cx != chunkX || cz != chunkZ) {
            // a region's chunks stay the same objects while it lives
            int k = (cx & 1) | (cz & 1) << 1;
            long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
            ChunkAccess c = nearKey[k] == key ? near[k] : null;
            if (c == null) {
                c = region.ga2$chunk(cx, cz);
                near[k] = c;
                nearKey[k] = key;
            }
            chunk = c;
            chunkX = cx;
            chunkZ = cz;
            section = null;
            sectionIndex = -1;
        }
        return chunk;
    }

    /** The section holding block {@code (x, y, z)}. The caller guarantees {@code y} is inside the build height. */
    public LevelChunkSection section(int x, int y, int z) {
        ChunkAccess c = chunk(x >> 4, z >> 4);
        int idx = (y >> 4) - minSection;
        if (idx != sectionIndex) {
            section = c.getSection(idx);
            sectionIndex = idx;
        }
        return section;
    }

    /** Exact {@code WorldGenRegion.getBlockState}. */
    public BlockState read(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            ChunkAccess c = chunk(x >> 4, z >> 4);
            return c.getClass() == ProtoChunk.class ? Blocks.VOID_AIR.defaultBlockState() : c.getBlockState(scratch.set(x, y, z));
        }
        ChunkAccess c = chunk(x >> 4, z >> 4);
        if (c.getClass() != ProtoChunk.class) {
            return c.getBlockState(scratch.set(x, y, z));
        }
        LevelChunkSection s = section(x, y, z);
        return s.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(s, x & 15, y & 15, z & 15);
    }

    public BlockState read(BlockPos pos) {
        return read(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Exact {@code WorldGenRegion.getFluidState}. */
    public FluidState readFluid(int x, int y, int z) {
        ChunkAccess c = chunk(x >> 4, z >> 4);
        if (c.getClass() != ProtoChunk.class) {
            return c.getFluidState(scratch.set(x, y, z));
        }
        if (y < minY || y >= maxY) {
            return Fluids.EMPTY.defaultFluidState();
        }
        LevelChunkSection s = section(x, y, z);
        return s.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState() : s.getFluidState(x & 15, y & 15, z & 15);
    }

    public FluidState readFluid(BlockPos pos) {
        return readFluid(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Exact {@code WorldGenRegion.getHeight(type, x, z)}. */
    public int height(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
        return chunk(x >> 4, z >> 4).getHeight(type, x & 15, z & 15) + 1;
    }

    /** Forgets the cached section (call after another writer may have replaced sections, which vanilla never does mid-stage). */
    public void invalidate() {
        chunkX = Integer.MIN_VALUE;
        chunkZ = Integer.MIN_VALUE;
        section = null;
        sectionIndex = -1;
    }
}
