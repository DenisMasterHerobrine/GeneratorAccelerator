package dev.denismasterherobrine.generatoraccelerator2.features.region;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;

/**
 * A generation region's {@code getBlockState} and {@code getFluidState} for every caller (features, predicates, placement
 * modifiers, other mods' code). Vanilla resolves the chunk on every read ({@code getChunk(x >> 4, z >> 4)}, the region's
 * dependency lookup) and asks it. A plain {@code ProtoChunk} then asks its height accessor (the level, its dimension type)
 * for the build height before reading the section. Here each chunk of the region's dependency grid is resolved once, through
 * the region's own lookup ({@link Ga2Region#ga2$chunk}). The region chunk cache already answers that lookup with the first
 * lookup's chunk for the rest of the task. A plain {@code ProtoChunk} whose build height is the dimension's is read the way its
 * own methods read: void air (no fluid) outside the build height, air (no fluid) for an all-air section, otherwise the
 * section's state. Any other chunk class is asked itself, with the caller's position. A position outside the grid is left to
 * vanilla, whose lookup throws there.
 *
 * <p>Used only with the region chunk cache, for vanilla's region class, when no other mod changes the region's two read
 * methods or {@code ProtoChunk}'s ({@code FeatureTargets} key {@code regionReads}).
 */
public final class RegionReads {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
    private static final FluidState NO_FLUID = Fluids.EMPTY.defaultFluidState();

    public static final LongAdder REGIONS = new LongAdder();
    /**
     * {@code -Dgeneratoraccelerator2.verify.regionReads=N}: every N-th read a region answers in place is also answered the
     * vanilla way (the region's chunk lookup, then the chunk's own read) and compared.
     */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.regionReads", 0);
    public static final LongAdder VERIFIED = new LongAdder();
    public static final LongAdder DIFFERING = new LongAdder();
    private static final Logger LOGGER = Ga2Log.get("Verify");

    private final Ga2Region region;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final int size;
    private final int minY;
    private final int maxY;
    private final int minSection;
    private final ChunkAccess[] chunks;
    /** Per slot read through its sections: the proto chunk's own section array (what {@code getSection} indexes). */
    private final LevelChunkSection[][] sections;
    /** Per slot: 1 read through the sections, 2 asked itself, 0 not resolved yet. */
    private final byte[] kinds;
    private int verifyCount;

    public RegionReads(Ga2Region region, int centerX, int centerZ, int radius) {
        this.region = region;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.size = 2 * radius + 1;
        this.minY = region.ga2$minY();
        this.maxY = region.ga2$maxY();
        this.minSection = minY >> 4;
        this.chunks = new ChunkAccess[size * size];
        this.sections = new LevelChunkSection[size * size][];
        this.kinds = new byte[size * size];
        REGIONS.increment();
    }

    private int slot(int cx, int cz) {
        int dx = cx - centerX + radius;
        int dz = cz - centerZ + radius;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            return -1;
        }
        return dx + dz * size;
    }

    private void resolve(int s, int cx, int cz) {
        ChunkAccess c = region.ga2$chunk(cx, cz);
        chunks[s] = c;
        sections[s] = c.getSections();
        kinds[s] = (byte) (c.getClass() == ProtoChunk.class && c.getMinBuildHeight() == minY && c.getMaxBuildHeight() == maxY ? 1 : 2);
    }

    private static void verified(boolean same, BlockPos pos, String what) {
        VERIFIED.increment();
        if (!same) {
            DIFFERING.increment();
            LOGGER.error("region {} read mismatch at {}", what, pos);
        }
    }

    /** Dimension min build height (inclusive). */
    public int minY() {
        return minY;
    }

    /** Dimension max build height (exclusive). */
    public int maxY() {
        return maxY;
    }

    /** Per slot: the highest {@code OCEAN_FLOOR_WG} answer over the chunk's columns, {@link #UNKNOWN} until asked, {@link #NONE} when not kept. */
    private int[] oceanFloorMax;
    public static final int UNKNOWN = Integer.MIN_VALUE;
    private static final int NONE = Integer.MIN_VALUE + 1;

    /**
     * The highest {@code getHeight(OCEAN_FLOOR_WG, x, z)} over the 256 columns of chunk {@code (cx, cz)}, or {@link #UNKNOWN} when it
     * cannot be kept. It is kept only for a plain proto chunk whose worldgen heightmap is already there and no longer changes. The
     * chunk's status (carvers and later) updates the final heightmaps on writes. So the answer holds for the whole decoration step.
     */
    public int oceanFloorMax(int cx, int cz) {
        int s = slot(cx, cz);
        if (s < 0) {
            return UNKNOWN;
        }
        if (kinds[s] == 0) {
            resolve(s, cx, cz);
        }
        if (oceanFloorMax == null) {
            oceanFloorMax = new int[size * size];
            Arrays.fill(oceanFloorMax, UNKNOWN);
        }
        int v = oceanFloorMax[s];
        if (v == UNKNOWN) {
            ChunkAccess c = chunks[s];
            Heightmap.Types type = Heightmap.Types.OCEAN_FLOOR_WG;
            if (kinds[s] == 1 && c.hasPrimedHeightmap(type) && !c.getPersistedStatus().heightmapsAfter().contains(type)) {
                int max = Integer.MIN_VALUE;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        max = Math.max(max, c.getHeight(type, x, z) + 1);
                    }
                }
                v = max;
            } else {
                v = NONE;
            }
            oceanFloorMax[s] = v;
        }
        return v == NONE ? UNKNOWN : v;
    }

    /**
     * The chunk {@code getBlockState} reads at chunk position {@code (cx, cz)}, when the read goes through the chunk's sections
     * (a plain proto chunk of the dimension's height). Resolved as a read would resolve it. Null for any other chunk or outside
     * the grid.
     */
    @Nullable
    public ChunkAccess sectionChunk(int cx, int cz) {
        int s = slot(cx, cz);
        if (s < 0) {
            return null;
        }
        if (kinds[s] == 0) {
            resolve(s, cx, cz);
        }
        return kinds[s] == 1 ? chunks[s] : null;
    }

    /**
     * {@link #read(BlockPos)} by coordinates, for a plain proto chunk read through its sections. Null when the chunk there
     * is any other kind or outside the grid (the caller then asks with a position).
     */
    @Nullable
    public BlockState readInSection(int x, int y, int z) {
        int s = slot(x >> 4, z >> 4);
        if (s < 0) {
            return null;
        }
        if (kinds[s] == 0) {
            resolve(s, x >> 4, z >> 4);
        }
        if (kinds[s] != 1) {
            return null;
        }
        if (y < minY || y >= maxY) {
            return VOID_AIR;
        }
        LevelChunkSection section = sections[s][(y >> 4) - minSection];
        return section.hasOnlyAir() ? AIR : StateReads.get(section, x & 15, y & 15, z & 15);
    }

    /** Exact {@code WorldGenRegion.getBlockState(pos)}, or null when vanilla must answer (outside the grid). */
    @Nullable
    public BlockState read(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        int s = slot(x >> 4, z >> 4);
        if (s < 0) {
            return null;
        }
        int kind = kinds[s];
        if (kind == 0) {
            resolve(s, x >> 4, z >> 4);
            kind = kinds[s];
        }
        ChunkAccess c = chunks[s];
        if (kind != 1) {
            return c.getBlockState(pos);
        }
        int y = pos.getY();
        BlockState state;
        if (y < minY || y >= maxY) {
            state = VOID_AIR;
        } else {
            LevelChunkSection section = sections[s][(y >> 4) - minSection];
            state = section.hasOnlyAir() ? AIR : StateReads.get(section, x & 15, y & 15, z & 15);
        }
        if (VERIFY_EVERY > 0 && ++verifyCount % VERIFY_EVERY == 0) {
            ChunkAccess vanilla = region.ga2$chunk(x >> 4, z >> 4);
            verified(vanilla == c && vanilla.getBlockState(pos) == state, pos, "block");
        }
        return state;
    }

    /** Exact {@code WorldGenRegion.getFluidState(pos)}, or null when vanilla must answer (outside the grid). */
    @Nullable
    public FluidState readFluid(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        int s = slot(x >> 4, z >> 4);
        if (s < 0) {
            return null;
        }
        int kind = kinds[s];
        if (kind == 0) {
            resolve(s, x >> 4, z >> 4);
            kind = kinds[s];
        }
        ChunkAccess c = chunks[s];
        if (kind != 1) {
            return c.getFluidState(pos);
        }
        int y = pos.getY();
        FluidState state;
        if (y < minY || y >= maxY) {
            state = NO_FLUID;
        } else {
            LevelChunkSection section = sections[s][(y >> 4) - minSection];
            state = section.hasOnlyAir() ? NO_FLUID : section.getFluidState(x & 15, y & 15, z & 15);
        }
        if (VERIFY_EVERY > 0 && ++verifyCount % VERIFY_EVERY == 0) {
            ChunkAccess vanilla = region.ga2$chunk(x >> 4, z >> 4);
            verified(vanilla == c && vanilla.getFluidState(pos) == state, pos, "fluid");
        }
        return state;
    }
}
