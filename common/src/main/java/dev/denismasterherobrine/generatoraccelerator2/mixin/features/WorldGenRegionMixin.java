package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesConfig;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers which chunk every (x, z) of the region resolves to, and exposes the region's cached
 * geometry to the feature fast paths. Applied late (high priority value) so the mixin plugin sees
 * other mods' changes to {@code getChunk} before deciding whether the cache may be used.
 */
@Mixin(value = WorldGenRegion.class, priority = 5000)
public abstract class WorldGenRegionMixin implements Ga2Region {
    private static final Logger GA2_LOGGER = Ga2Log.get("Features");

    @Shadow @Final private ChunkAccess center;
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private ChunkStep generatingStep;
    @Shadow @Final private DimensionType dimensionType;
    @Shadow @Final private BiomeManager biomeManager;

    @Shadow public abstract boolean ensureCanWrite(BlockPos pos);

    @Unique private RegionChunks ga2$chunks;
    @Unique private int ga2$centerX;
    @Unique private int ga2$centerZ;
    @Unique private int ga2$writeRadius;
    @Unique private boolean ga2$upgrading;
    @Unique private int ga2$upgradeMinY;
    @Unique private int ga2$upgradeMaxY;
    @Unique private int ga2$minY;
    @Unique private int ga2$maxY;
    @Unique private boolean ga2$verify;
    @Unique private BiomeZoom ga2$zoom;
    @Unique private boolean ga2$noZoom;
    @Unique private RegionReads ga2$reads;
    /** Reads in place are allowed for this region. The {@link RegionReads} is created on the first read, since most regions read no block. */
    @Unique private boolean ga2$readsAllowed;
    @Unique private Holder<Biome>[] ga2$zoomBiomes;
    @Unique private boolean ga2$zoomBiomesDone;
    @Unique private net.minecraft.world.level.levelgen.placement.PlacementContext ga2$placementContext;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$init(CallbackInfo ci) {
        this.ga2$centerX = this.center.getPos().x;
        this.ga2$centerZ = this.center.getPos().z;
        this.ga2$writeRadius = this.generatingStep.blockStateWriteRadius();
        this.ga2$minY = this.dimensionType.minY();
        this.ga2$maxY = this.dimensionType.minY() + this.dimensionType.height();
        this.ga2$upgrading = this.center.isUpgrading();
        if (this.ga2$upgrading) {
            LevelHeightAccessor h = this.center.getHeightAccessorForGeneration();
            this.ga2$upgradeMinY = h.getMinBuildHeight();
            this.ga2$upgradeMaxY = h.getMaxBuildHeight();
        }
        if (FeatureHooks.enabled() && FeaturesCompat.regionCacheAllowed()) {
            this.ga2$chunks = new RegionChunks(this.center.getPos(), this.generatingStep.directDependencies());
            this.ga2$verify = FeaturesConfig.verifyCache();
            FeatureStats.REGIONS.increment();
            this.ga2$readsAllowed = this.ga2$plain() && FeaturesCompat.regionReadsAllowed();
        }
    }


    @Override
    public BiomeZoom ga2$zoom() {
        BiomeZoom zoom = this.ga2$zoom;
        if (zoom == null) {
            if (this.ga2$noZoom) {
                return null;
            }
            if (this.ga2$chunks == null || !FeaturesConfig.biomeZoom() || !SurfaceHooks.zoomAllowed() || this.biomeManager.getClass() != BiomeManager.class) {
                this.ga2$noZoom = true;
                return null;
            }
            zoom = BiomeZoom.forRegion(this.biomeManager, this.ga2$centerX << 4, this.ga2$centerZ << 4, this.ga2$minY, this.ga2$maxY, this);
            this.ga2$zoom = zoom;
            FeatureStats.REGION_ZOOMS.increment();
        }
        // a later region on this thread may have taken the per-thread instance over
        return zoom.owner() == this ? zoom : null;
    }

    @Override
    public RegionReads ga2$reads() {
        RegionReads reads = this.ga2$reads;
        if (reads == null && this.ga2$readsAllowed) {
            reads = this.ga2$reads = new RegionReads(this, this.ga2$centerX, this.ga2$centerZ, this.ga2$chunks.radius());
        }
        return reads;
    }

    @Override
    public net.minecraft.world.level.levelgen.placement.PlacementContext ga2$placementContext(net.minecraft.world.level.chunk.ChunkGenerator generator) {
        net.minecraft.world.level.levelgen.placement.PlacementContext c = this.ga2$placementContext;
        if (c == null || c.generator() != generator) {
            c = this.ga2$placementContext = new net.minecraft.world.level.levelgen.placement.PlacementContext((WorldGenRegion) (Object) this, generator,
                java.util.Optional.empty());
        }
        return c;
    }

    @Override
    public boolean ga2$plain() {
        return (Object) this.getClass() == WorldGenRegion.class;
    }

    @Override
    public WorldGenLevel ga2$level() {
        return (WorldGenRegion) (Object) this;
    }

    /**
     * The region's chunk at {@code cx, cz} for GA2's fast paths. The first lookup is vanilla's ({@code getChunk(cx, cz, EMPTY, true)})
     * and is remembered for the rest of the task. The cache exists only when no other mod changes {@code getChunk}. Vanilla's own
     * callers go through vanilla's lookup. A hook on that lookup made a callback object per call, which cost more than the lookup
     * it saved.
     */
    @Override
    public ChunkAccess ga2$chunk(int cx, int cz) {
        RegionChunks cache = this.ga2$chunks;
        if (cache == null) {
            return ((WorldGenRegion) (Object) this).getChunk(cx, cz, ChunkStatus.EMPTY, true);
        }
        ChunkAccess cached = cache.get(cx, cz, ChunkStatus.EMPTY);
        if (cached != null && !this.ga2$verify) {
            if (FeatureStats.DETAILED) {
                FeatureStats.CHUNK_CACHE_HITS.increment();
            }
            return cached;
        }
        ChunkAccess chunk = ((WorldGenRegion) (Object) this).getChunk(cx, cz, ChunkStatus.EMPTY, true);
        if (cached != null && cached != chunk) {
            GA2_LOGGER.error("region chunk cache mismatch at {}, {}: cached {}, vanilla {}", cx, cz, cached.getClass().getSimpleName(),
                chunk.getClass().getSimpleName());
        }
        if (FeatureStats.DETAILED) {
            FeatureStats.CHUNK_CACHE_MISSES.increment();
        }
        cache.put(cx, cz, chunk);
        return chunk;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Holder<Biome>[] ga2$zoomBiomes() {
        if (!this.ga2$zoomBiomesDone) {
            this.ga2$zoomBiomesDone = true;
            java.util.List<Holder<Biome>> found = new java.util.ArrayList<>(4);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // Looked up as getNoiseBiome looks it up: at BIOMES, or none, in which case the zoom asks the biome source.
                    // A chunk that getNoiseBiome would not read stored biomes from leaves the set unknown. For example, Distant
                    // Horizons' region hands out blank chunks past its batch.
                    ChunkAccess chunk = ((WorldGenRegion) (Object) this).getChunk(this.ga2$centerX + dx, this.ga2$centerZ + dz, ChunkStatus.BIOMES, false);
                    if (chunk == null) {
                        return null;
                    }
                    // Every palette entry. This is a superset of the biomes the section stores, read without scanning the storage.
                    for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
                        if (!(section.getBiomes() instanceof net.minecraft.world.level.chunk.PalettedContainer<Holder<Biome>> container)) {
                            return null;
                        }
                        net.minecraft.world.level.chunk.Palette<Holder<Biome>> palette = container.data.palette();
                        if (palette instanceof net.minecraft.world.level.chunk.GlobalPalette) {
                            return null;
                        }
                        next:
                        for (int i = 0; i < palette.getSize(); i++) {
                            Holder<Biome> b = palette.valueFor(i);
                            for (Holder<Biome> f : found) {
                                if (f == b) {
                                    continue next;
                                }
                            }
                            found.add(b);
                        }
                    }
                }
            }
            this.ga2$zoomBiomes = found.toArray(new Holder[0]);
        }
        return this.ga2$zoomBiomes;
    }

    @Override
    public RegionChunks ga2$chunks() {
        return this.ga2$chunks;
    }

    @Override
    public boolean ga2$inWriteRadius(int cx, int cz) {
        return Math.abs(this.ga2$centerX - cx) <= this.ga2$writeRadius && Math.abs(this.ga2$centerZ - cz) <= this.ga2$writeRadius;
    }

    @Override
    public boolean ga2$ensureCanWrite(BlockPos pos) {
        if (this.ga2$inWriteRadius(pos.getX() >> 4, pos.getZ() >> 4)) {
            if (this.ga2$upgrading) {
                int y = pos.getY();
                return y >= this.ga2$upgradeMinY && y < this.ga2$upgradeMaxY;
            }
            return true;
        }
        return this.ensureCanWrite(pos); // vanilla logs the far-chunk write and returns false
    }

    @Override
    public boolean ga2$upgrading() {
        return this.ga2$upgrading;
    }

    @Override
    public int ga2$upgradeMinY() {
        return this.ga2$upgradeMinY;
    }

    @Override
    public int ga2$upgradeMaxY() {
        return this.ga2$upgradeMaxY;
    }

    @Override
    public int ga2$minY() {
        return this.ga2$minY;
    }

    @Override
    public int ga2$maxY() {
        return this.ga2$maxY;
    }

    @Override
    public void ga2$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
        // POI bookkeeping, which does nothing when neither state has a POI type (PoiStates)
        if (!dev.denismasterherobrine.generatoraccelerator2.state.PoiStates.skip(this.level, oldState, newState)) {
            this.level.onBlockStateChange(pos, oldState, newState);
        }
    }
}
