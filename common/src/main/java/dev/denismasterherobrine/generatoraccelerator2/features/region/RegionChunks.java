package dev.denismasterherobrine.generatoraccelerator2.features.region;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The chunks a {@code WorldGenRegion} has resolved so far, by position. A slot is filled with the
 * result of vanilla's lookup and the status that chunk had then. Later requests for a status at or
 * before that status get the same object vanilla would return. The reason: the region's chunk list
 * is fixed for the whole task, and a chunk's status only ever moves forward.
 *
 * <p>Also holds the per-chunk heightmap arrays the writer uses, so a write does not walk an
 * {@code EnumSet} and an {@code EnumMap} four times.
 */
public final class RegionChunks {
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final int size;
    /** Made on the first chunk remembered: most regions read a chunk or two, or none (a structure reference step's grid is 17 x 17). */
    private ChunkAccess[] chunks;
    private ChunkStatus[] served;
    private Heightmap[][] finalHeightmaps;

    /** {@code radius}: the region's chunks around its center on each side. */
    public RegionChunks(ChunkPos center, int radius) {
        this.centerX = center.x;
        this.centerZ = center.z;
        this.radius = Math.max(0, radius);
        this.size = 2 * this.radius + 1;
    }

    /** Slot index, or -1 when outside the region. */
    public int slot(int cx, int cz) {
        int dx = cx - centerX + radius;
        int dz = cz - centerZ + radius;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            return -1;
        }
        return dx + dz * size;
    }

    /** The cached chunk for {@code requested}, or {@code null} when vanilla's lookup must run. */
    public ChunkAccess get(int cx, int cz, ChunkStatus requested) {
        int s = slot(cx, cz);
        if (s < 0 || served == null) {
            return null;
        }
        ChunkStatus st = served[s];
        return st != null && st.isOrAfter(requested) ? chunks[s] : null;
    }

    /** Records vanilla's successful lookup. */
    public void put(int cx, int cz, ChunkAccess chunk) {
        int s = slot(cx, cz);
        if (s < 0 || chunk == null) {
            return;
        }
        if (served == null) {
            chunks = new ChunkAccess[size * size];
            served = new ChunkStatus[size * size];
        }
        ChunkStatus st = chunk.getStatus();
        if (served[s] == null || !served[s].isOrAfter(st)) {
            chunks[s] = chunk;
            served[s] = st;
        }
    }

    public Heightmap[] finalHeightmaps(int slot) {
        return finalHeightmaps == null ? null : finalHeightmaps[slot];
    }

    public void setFinalHeightmaps(int slot, Heightmap[] maps) {
        if (finalHeightmaps == null) {
            finalHeightmaps = new Heightmap[size * size][];
        }
        finalHeightmaps[slot] = maps;
    }

    public int radius() {
        return radius;
    }
}
