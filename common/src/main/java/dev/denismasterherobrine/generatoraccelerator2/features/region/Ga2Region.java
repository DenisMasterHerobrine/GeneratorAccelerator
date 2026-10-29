package dev.denismasterherobrine.generatoraccelerator2.features.region;

import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/**
 * What the feature fast paths need from a generation region, implemented by the {@code WorldGenRegion}
 * mixin in the game and by the harness level in the tests. Everything here has the exact semantics of
 * the corresponding {@code WorldGenRegion} method. The implementations only make them cheaper.
 */
public interface Ga2Region {
    /**
     * True for vanilla's own {@code WorldGenRegion} class. A subclass (Distant Horizons generates through one) may change how
     * blocks are read and written. The fast paths read and write straight on the chunk sections, so they leave such a
     * subclass to vanilla.
     */
    boolean ga2$plain();

    /** The region as a level, for the vanilla calls the fast paths still make. */
    WorldGenLevel ga2$level();

    /** Same as {@code getChunk(cx, cz)}: the chunk for this region, throwing vanilla's report when unavailable. */
    ChunkAccess ga2$chunk(int cx, int cz);

    /** The resolved chunk grid, or {@code null} when the cache is off (fast paths then go through the level). */
    RegionChunks ga2$chunks();

    /** {@code ensureCanWrite}'s chunk radius test alone (no logging, no upgrading range). */
    boolean ga2$inWriteRadius(int cx, int cz);

    /** Exact {@code ensureCanWrite(pos)}, including the far-chunk log line and the upgrading Y range. */
    boolean ga2$ensureCanWrite(BlockPos pos);

    /** True when the centre chunk is upgrading and writes must also pass the generation height range. */
    boolean ga2$upgrading();

    /** Generation-time min build height (inclusive) and max build height (exclusive) when upgrading. */
    int ga2$upgradeMinY();

    int ga2$upgradeMaxY();

    /** Dimension min build height (inclusive). */
    int ga2$minY();

    /** Dimension max build height (exclusive). */
    int ga2$maxY();

    /** {@code ServerLevel.updatePOIOnBlockStateChange}: POI bookkeeping, a cross-thread side effect that must be kept. */
    void ga2$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState);

    /** The region's zoom cache for {@code BiomeManager.getBiome}, or null when its lookups go the vanilla way. */
    @Nullable
    BiomeZoom ga2$zoom();

    /**
     * Every biome stored in the 3x3 chunks around the centre (each section's palette). This is a superset of what the zoom can
     * answer for a block its box covers ({@code BiomeZoom.covers}). Null when not known.
     */
    @Nullable
    default Holder<Biome>[] ga2$zoomBiomes() {
        return null;
    }

    /**
     * The context {@code PlacedFeature.place} makes for each nested placement ({@code new PlacementContext(this, generator,
     * Optional.empty())}), made once for the region and handed out again (it holds only those three and changes nothing). Null
     * when the region keeps none.
     */
    @Nullable
    default PlacementContext ga2$placementContext(ChunkGenerator generator) {
        return null;
    }

    /** The region's block and fluid reads ({@link RegionReads}), or null when they go the vanilla way. */
    @Nullable
    default RegionReads ga2$reads() {
        return null;
    }
}
