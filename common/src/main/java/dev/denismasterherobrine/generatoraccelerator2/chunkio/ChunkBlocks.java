package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Block access to one proto chunk during a generation step (SURFACE, CARVERS). The semantics are exactly
 * those of {@code ProtoChunk.getBlockState(pos)} and {@code ProtoChunk.setBlockState(pos, state, false)}:
 * void air outside the build height, air for all-air sections, the all-air shortcut, and the section
 * counters. After every write, the {@code heightmapsAfter()} heightmaps of the persisted status are
 * updated in vanilla order.
 *
 * <p>The fast path covers a plain {@code ProtoChunk} below INITIALIZE_LIGHT whose step heightmaps
 * all exist. Vanilla would prime missing heightmaps lazily. Chunks with missing heightmaps, any other
 * chunk class, and later statuses go through the chunk's own methods. While {@link #lock() locked},
 * the chunk's sections are acquired once, as {@code fillFromNoise} does, and written unchecked.
 * Unlocked writes take the per-write lock like vanilla. Release the lock before running code that may
 * write through the chunk itself.
 */
public final class ChunkBlocks {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
    private static final ThreadLocal<int[]> REWRITE_IDS = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<int[]> REWRITE_TARGETS = ThreadLocal.withInitial(() -> new int[16]);

    private final ChunkAccess chunk;
    private final LevelChunkSection[] sections;
    private final int minY;
    private final int maxY;
    private final int minSection;
    private final Heightmap[] heightmaps;
    private final boolean fast;
    private boolean locked;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private ChunkBlocks(ChunkAccess chunk, Heightmap[] heightmaps, boolean fast) {
        this.chunk = chunk;
        this.sections = chunk.getSections();
        this.minY = chunk.getMinBuildHeight();
        this.maxY = chunk.getMaxBuildHeight();
        this.minSection = chunk.getMinSection();
        this.heightmaps = heightmaps;
        this.fast = fast;
    }

    public static ChunkBlocks of(ChunkAccess chunk) {
        if (chunk.getClass() != ProtoChunk.class) {
            return new ChunkBlocks(chunk, null, false);
        }
        ChunkStatus status = chunk.getPersistedStatus();
        if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            return new ChunkBlocks(chunk, null, false);
        }
        EnumSet<Heightmap.Types> types = status.heightmapsAfter();
        Heightmap[] maps = new Heightmap[types.size()];
        int i = 0;
        for (Heightmap.Types type : types) {
            if (!chunk.hasPrimedHeightmap(type)) {
                return new ChunkBlocks(chunk, null, false);
            }
            maps[i++] = chunk.getOrCreateHeightmapUnprimed(type);
        }
        return new ChunkBlocks(chunk, maps, true);
    }

    public ChunkAccess chunk() {
        return chunk;
    }

    public boolean fast() {
        return fast;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    /** Acquires every section for unchecked writes. No-op for the delegating path. */
    public void lock() {
        if (!fast || locked) {
            return;
        }
        int acquired = 0;
        try {
            for (LevelChunkSection section : sections) {
                section.acquire();
                acquired++;
            }
        } catch (RuntimeException e) {
            for (int i = 0; i < acquired; i++) {
                sections[i].release();
            }
            throw e;
        }
        locked = true;
    }

    public void unlock() {
        if (!locked) {
            return;
        }
        locked = false;
        for (LevelChunkSection section : sections) {
            section.release();
        }
    }

    public boolean locked() {
        return locked;
    }

    /** True when writing any opaque or non-opaque state at or below {@code y} in the column leaves every step heightmap unchanged. */
    public boolean heightmapsIgnore(int lx, int lz, int y) {
        if (!fast) {
            return false;
        }
        for (Heightmap map : heightmaps) {
            if (y > map.getFirstAvailable(lx, lz) - 2) {
                return false;
            }
        }
        return true;
    }

    public int sectionCount() {
        return sections.length;
    }

    public int minSection() {
        return minSection;
    }

    /** {@code chunk.getBlockState(pos)}. {@code lx} and {@code lz} are section-relative (0..15). */
    public BlockState get(int lx, int y, int lz) {
        if (!fast) {
            return chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz));
        }
        if (y < minY || y >= maxY) {
            return VOID_AIR;
        }
        LevelChunkSection section = sections[(y >> 4) - minSection];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        // PalettedContainer.get with the section-states index (y << 8 | z << 4 | x) spelled out
        net.minecraft.world.level.chunk.PalettedContainer.Data<BlockState> data = section.getStates().data;
        return StateReads.get(data, ((y & 15) << 8) | (lz << 4) | lx);
    }

    /** {@code chunk.setBlockState(pos, state, false)}. Returns the previous state like vanilla. */
    public BlockState set(int lx, int y, int lz, BlockState state) {
        if (!fast) {
            return chunk.setBlockState(pos.set(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz), state, false);
        }
        if (y < minY || y >= maxY) {
            return VOID_AIR;
        }
        LevelChunkSection section = sections[(y >> 4) - minSection];
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
            return state;
        }
        BlockState old = locked ? section.setBlockState(lx, y & 15, lz, state, false) : section.setBlockState(lx, y & 15, lz, state);
        for (Heightmap map : heightmaps) {
            map.update(lx, y, lz, state);
        }
        return old;
    }

    /**
     * {@link #set} for a state the caller knows is already at that position. The palette and the
     * section counters cannot change. Only the all-air shortcut and the heightmap updates remain.
     */
    public void setSame(int lx, int y, int lz, BlockState state) {
        if (!fast) {
            set(lx, y, lz, state);
            return;
        }
        if (y < minY || y >= maxY) {
            return;
        }
        LevelChunkSection section = sections[(y >> 4) - minSection];
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
            return;
        }
        for (Heightmap map : heightmaps) {
            map.update(lx, y, lz, state);
        }
    }

    /**
     * Replaces every {@code from} entry of the section at block y {@code sectionMinY} whose local y has a
     * non-null {@code targets[localY]} with that target, in one pass over the section's storage.
     *
     * <p>Equivalent to writing those blocks one by one only under guarantees from the caller. Each
     * target must affect the section counters as {@code from} does and have no fluid. Every heightmap
     * update must be a no-op, i.e. the blocks lie at least two below each column's heightmap top. The
     * palette may end up ordered differently from per-block writes. The block states are the same, and
     * saving re-derives the palette from them.
     */
    public void rewriteSection(int sectionMinY, BlockState from, BlockState[] targets) {
        LevelChunkSection section = sections[(sectionMinY >> 4) - minSection];
        net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = section.getStates();
        if (!states.data.palette().maybeHas(s -> s == from)) {
            return;
        }
        boolean any = false;
        for (BlockState t : targets) {
            if (t != null) {
                states.data.palette().idFor(t);
                any = true;
            }
        }
        if (!any) {
            return;
        }
        net.minecraft.world.level.chunk.PalettedContainer.Data<BlockState> data = states.data;
        int fromId = data.palette().idFor(from);
        // Thread-local buffers for the section's ids and its 16 targets. unpack writes all ids before they are read.
        int[] ids = REWRITE_IDS.get();
        int[] targetIds = REWRITE_TARGETS.get();
        for (int ly = 0; ly < 16; ly++) {
            targetIds[ly] = targets[ly] == null ? -1 : data.palette().idFor(targets[ly]);
        }
        net.minecraft.util.BitStorage storage = data.storage();
        storage.unpack(ids);
        for (int index = 0; index < 4096; index++) {
            if (ids[index] == fromId) {
                int target = targetIds[index >> 8];
                if (target >= 0) {
                    storage.set(index, target);
                }
            }
        }
    }

    /** {@code chunk.markPosForPostprocessing(pos)} at a world position. */
    public void markForPostprocessing(int x, int y, int z) {
        chunk.markPosForPostprocessing(pos.set(x, y, z));
    }
}
