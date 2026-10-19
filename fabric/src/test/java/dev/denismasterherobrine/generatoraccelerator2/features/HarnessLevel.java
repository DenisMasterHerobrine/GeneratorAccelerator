package dev.denismasterherobrine.generatoraccelerator2.features;

import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionChunks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.WorldGenTickAccess;

/**
 * A {@code WorldGenLevel} over a square grid of real {@code ProtoChunk}s, mirroring the semantics of
 * {@code WorldGenRegion} line by line (chunk lookup by radius, write radius 1, the block entity DUMMY
 * NBT, post-processing marks, positional random, sub-tick counter, a real BiomeManager). It also
 * implements {@link Ga2Region} so the fast paths run on it exactly as they do on a region in the game.
 * Methods no feature needs throw, so an unexpected dependency shows up as a failure, not as a wrong
 * result. POI transitions are recorded instead of being sent to a server.
 */
public final class HarnessLevel implements WorldGenLevel, Ga2Region {
    public record PoiChange(BlockPos pos, Optional<Holder<PoiType>> before, Optional<Holder<PoiType>> after) {}

    private final ProtoChunk[] chunks;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final int writeRadius;
    private final LevelHeightAccessor heights;
    private final DimensionType dimension;
    private final long seed;
    private final RandomSource random;
    private final BiomeManager biomeManager;
    private final WorldGenTickAccess<Block> blockTicks = new WorldGenTickAccess<>(pos -> this.getChunk(pos).getBlockTicks());
    private final WorldGenTickAccess<Fluid> fluidTicks = new WorldGenTickAccess<>(pos -> this.getChunk(pos).getFluidTicks());
    private final AtomicLong subTickCount = new AtomicLong();
    private final RegistryAccess registryAccess;
    private final List<PoiChange> poiChanges = new ArrayList<>();
    private final RegionChunks regionChunks;
    private final boolean cacheHeightmaps;
    private final LevelLightEngine lightEngine = new LevelLightEngine(new net.minecraft.world.level.chunk.LightChunkGetter() {
        @Override
        public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) {
            return null;
        }

        @Override
        public net.minecraft.world.level.BlockGetter getLevel() {
            return HarnessLevel.this;
        }
    }, false, false);
    /** Game time 0: scheduled tick times only need to agree between the two runs. */
    private static final LevelData LEVEL_DATA = new LevelData() {
        @Override public BlockPos getSpawnPos() { return BlockPos.ZERO; }
        @Override public float getSpawnAngle() { return 0.0F; }
        @Override public long getGameTime() { return 0L; }
        @Override public long getDayTime() { return 0L; }
        @Override public boolean isThundering() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public void setRaining(boolean raining) {}
        @Override public boolean isHardcore() { return false; }
        @Override public net.minecraft.world.level.GameRules getGameRules() { throw new UnsupportedOperationException("getGameRules"); }
        @Override public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
    };

    public HarnessLevel(ProtoChunk[] chunks, int radius, int centerX, int centerZ, LevelHeightAccessor heights, DimensionType dimension, long seed,
                        RandomState randomState, RegistryAccess registryAccess, boolean cacheHeightmaps) {
        this.chunks = chunks;
        this.radius = radius;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.writeRadius = 1;
        this.heights = heights;
        this.dimension = dimension;
        this.seed = seed;
        this.random = randomState.getOrCreateRandomFactory(net.minecraft.resources.ResourceLocation.withDefaultNamespace("worldgen_region_random"))
            .at(new ChunkPos(centerX, centerZ).getWorldPosition());
        this.biomeManager = new BiomeManager(this, BiomeManager.obfuscateSeed(seed));
        this.registryAccess = registryAccess;
        this.cacheHeightmaps = cacheHeightmaps;
        this.regionChunks = cacheHeightmaps ? new RegionChunks(new ChunkPos(centerX, centerZ), HarnessWorld.FEATURE_DEPENDENCIES) : null;
    }

    public List<PoiChange> poiChanges() {
        return poiChanges;
    }

    public ProtoChunk[] chunks() {
        return chunks;
    }

    public int radius() {
        return radius;
    }

    public ProtoChunk center() {
        return (ProtoChunk) getChunk(centerX, centerZ);
    }

    // ---- chunk lookup (WorldGenRegion.getChunk)

    @Override
    public ChunkAccess getChunk(int x, int z) {
        return getChunk(x, z, ChunkStatus.EMPTY, true);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        int dx = x - centerX + radius;
        int dz = z - centerZ + radius;
        int size = 2 * radius + 1;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            throw new IllegalStateException("Requested chunk unavailable during world generation: " + x + ", " + z);
        }
        return chunks[dx + dz * size];
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) <= radius;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        // the region's in-place reads, as WorldGenRegionReadsMixin answers them in the game
        dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads reads = ga2$reads();
        if (reads != null) {
            BlockState state = reads.read(pos);
            if (state != null) {
                return state;
            }
        }
        return getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads reads = ga2$reads();
        if (reads != null) {
            FluidState state = reads.readFluid(pos);
            if (state != null) {
                return state;
            }
        }
        return getChunk(pos).getFluidState(pos);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    // ---- writes (WorldGenRegion.setBlock)

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        int i = SectionPos.blockToSectionCoord(pos.getX());
        int j = SectionPos.blockToSectionCoord(pos.getZ());
        return Math.abs(centerX - i) <= writeRadius && Math.abs(centerZ - j) <= writeRadius;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (!ensureCanWrite(pos)) {
            return false;
        }
        ChunkAccess chunk = getChunk(pos);
        BlockState old = chunk.setBlockState(pos, state, false);
        if (old != null) {
            ga2$onBlockStateChange(pos, old, state);
        }
        if (state.hasBlockEntity()) {
            if (chunk.getPersistedStatus().getChunkType() == ChunkType.LEVELCHUNK) {
                BlockEntity be = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                if (be != null) {
                    chunk.setBlockEntity(be);
                } else {
                    chunk.removeBlockEntity(pos);
                }
            } else {
                CompoundTag tag = new CompoundTag();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                tag.putString("id", "DUMMY");
                chunk.setBlockEntityNbt(tag);
            }
        } else if (old != null && old.hasBlockEntity()) {
            chunk.removeBlockEntity(pos);
        }
        if (state.hasPostProcess(this, pos)) {
            getChunk(pos).markPosForPostprocessing(pos);
        }
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean isMoving) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft) {
        BlockState state = getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (dropBlock) {
            throw new UnsupportedOperationException("destroyBlock with drops");
        }
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 3, recursionLeft);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        ChunkAccess chunk = getChunk(pos);
        BlockEntity be = chunk.getBlockEntity(pos);
        if (be != null) {
            return be;
        }
        CompoundTag tag = chunk.getBlockEntityNbt(pos);
        BlockState state = chunk.getBlockState(pos);
        if (tag != null) {
            if ("DUMMY".equals(tag.getString("id"))) {
                if (!state.hasBlockEntity()) {
                    return null;
                }
                be = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
            } else {
                be = BlockEntity.loadStatic(pos, state, tag, registryAccess);
            }
            if (be != null) {
                chunk.setBlockEntity(be);
                return be;
            }
        }
        return null;
    }

    // ---- geometry, biomes, random

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
    }

    @Override
    public int getMinBuildHeight() {
        return heights.getMinBuildHeight();
    }

    @Override
    public int getHeight() {
        return heights.getHeight();
    }

    @Override
    public DimensionType dimensionType() {
        return dimension;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return biomeManager;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        throw new UnsupportedOperationException("getUncachedNoiseBiome");
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public long nextSubTickCount() {
        return subTickCount.getAndIncrement();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return blockTicks;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return fluidTicks;
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.VANILLA_SET;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0F;
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntities(Entity entity, AABB box, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}

    @Override
    public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {}

    @Override
    public void levelEvent(Player player, int type, BlockPos pos, int data) {}

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}

    // ---- unsupported in the harness: a feature touching these is excluded from the matrix

    @Override
    public ServerLevel getLevel() {
        throw new UnsupportedOperationException("getLevel");
    }

    @Override
    public MinecraftServer getServer() {
        throw new UnsupportedOperationException("getServer");
    }

    @Override
    public ChunkSource getChunkSource() {
        throw new UnsupportedOperationException("getChunkSource");
    }

    /** Dark everywhere: during FEATURES the centre chunk has not been lit yet, so vanilla reads 0 from its engine too. */
    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public WorldBorder getWorldBorder() {
        throw new UnsupportedOperationException("getWorldBorder");
    }

    @Override
    public LevelData getLevelData() {
        return LEVEL_DATA;
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        throw new UnsupportedOperationException("getCurrentDifficultyAt");
    }

    // ---- Ga2Region

    /** The harness stands in for vanilla's own region (blocks live in its proto chunks' sections). */
    @Override
    public boolean ga2$plain() {
        return true;
    }

    @Override
    public WorldGenLevel ga2$level() {
        return this;
    }

    @Override
    public ChunkAccess ga2$chunk(int cx, int cz) {
        return getChunk(cx, cz, ChunkStatus.EMPTY, true);
    }

    @Override
    public RegionChunks ga2$chunks() {
        return regionChunks;
    }

    private dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads reads;

    /**
     * With the region cache, as in the game. The harness stands in for the region, and the tests never load the region's
     * own hooks, so only the reads' switch and inspection count. Not in the vanilla run (hooks off on this thread).
     */
    @Override
    public dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads ga2$reads() {
        if (regionChunks == null || !FeatureHooks.enabled() || !FeaturesConfig.regionReads()
            || !dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets.allowed(dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets.REGION_READS)) {
            return null;
        }
        if (reads == null) {
            reads = new dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads(this, centerX, centerZ, radius);
        }
        return reads;
    }

    @Override
    public boolean ga2$inWriteRadius(int cx, int cz) {
        return Math.abs(centerX - cx) <= writeRadius && Math.abs(centerZ - cz) <= writeRadius;
    }

    @Override
    public boolean ga2$ensureCanWrite(BlockPos pos) {
        return ensureCanWrite(pos);
    }

    @Override
    public boolean ga2$upgrading() {
        return false;
    }

    @Override
    public int ga2$upgradeMinY() {
        return heights.getMinBuildHeight();
    }

    @Override
    public int ga2$upgradeMaxY() {
        return heights.getMaxBuildHeight();
    }

    @Override
    public int ga2$minY() {
        return heights.getMinBuildHeight();
    }

    @Override
    public int ga2$maxY() {
        return heights.getMaxBuildHeight();
    }

    @Override
    public dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom ga2$zoom() {
        return null; // the harness level has no biome manager. Biome lookups go through its own getBiome
    }

    @Override
    public void ga2$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
        Optional<Holder<PoiType>> before = PoiTypes.forState(oldState);
        Optional<Holder<PoiType>> after = PoiTypes.forState(newState);
        if (!before.equals(after)) {
            poiChanges.add(new PoiChange(pos.immutable(), before, after));
        }
    }

    public boolean cachesHeightmaps() {
        return cacheHeightmaps;
    }
}
