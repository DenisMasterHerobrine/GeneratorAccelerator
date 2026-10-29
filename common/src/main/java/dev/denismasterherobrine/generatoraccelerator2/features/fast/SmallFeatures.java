package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.predicate.PredicatePrograms;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import java.util.concurrent.ConcurrentMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.ReplaceBlobsFeature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ReplaceSphereConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import javax.annotation.Nullable;

/**
 * Transcriptions of the small vanilla features. Each keeps the vanilla read/write/random order and
 * only changes how blocks are reached: reads through the section cursor, writes through the exact
 * region writer, no per-block chunk resolution and no throwaway positions. Block-specific logic
 * (survival checks, placement states, spreaders, ticks, block entities) still runs vanilla code on
 * the level.
 */
public final class SmallFeatures {
    private SmallFeatures() {}

    /** Exact {@code DiskFeature.place}. */
    public static boolean disk(FeaturePlaceContext<?> ctx, Ga2Region region) {
        DiskConfiguration config = (DiskConfiguration) ctx.config();
        BlockPos origin = ctx.origin();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.DISK, region);
        boolean placed = false;
        int y = origin.getY();
        int top = y + config.halfHeight();
        int bottom = y - config.halfHeight() - 1;
        int radius = config.radius().sample(random);
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        // the target is tested at every block of the disk's columns. Its program reads by coordinates: no offset position per test
        Object target = TARGETS.computeIfAbsent(config.target(), PredicatePrograms::programFor);
        // BlockPos.betweenClosed's order over one layer: x fastest, then z
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    column.set(origin.getX() + dx, y, origin.getZ() + dz);
                    // DiskFeature.placeColumn: only the top block of each run of placed blocks marks the blocks above it
                    boolean any = false;
                    boolean placedAbove = false;
                    for (int by = top; by > bottom; by--) {
                        column.setY(by);
                        if (PredicatePrograms.test(target, config.target(), level, column)) {
                            BlockState state = config.stateProvider().getOptionalState(level, random, column);
                            if (state != null) {
                                RegionWriter.setBlock(region, column, state, Block.UPDATE_CLIENTS);
                                if (!placedAbove) {
                                    Fast.markAboveForPostProcessing(cursor, column);
                                }
                                any = true;
                                placedAbove = true;
                            }
                        } else {
                            placedAbove = false;
                        }
                    }
                    placed |= any;
                }
            }
        }
        FeatureStats.fast(DiskFeature.class);
        return placed;
    }

    /**
     * Exact {@code ReplaceBlobsFeature.place}: the target found down from the clamped origin, the three radii drawn, then the blocks
     * of {@code BlockPos.withinManhattan} in its order up to the largest radius (depth by depth: x, then y, the z side, then its
     * mirror). Terralith's cave blobs run it in every cave.
     */
    public static boolean replaceBlobs(FeaturePlaceContext<?> ctx, Ga2Region region) {
        ReplaceSphereConfiguration config = (ReplaceSphereConfiguration) ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        Block target = config.targetState.getBlock();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.REPLACE_BLOBS, region);
        BlockPos origin = ctx.origin();
        int ox = origin.getX();
        int oz = origin.getZ();
        int oy = Mth.clamp(origin.getY(), level.getMinY() + 1, level.getMaxY());
        // findTarget
        while (true) {
            if (oy <= level.getMinY() + 1) {
                FeatureStats.fast(ReplaceBlobsFeature.class);
                return false;
            }
            if (cursor.read(ox, oy, oz).is(target)) {
                break;
            }
            oy--;
        }
        int rx = config.radius().sample(random);
        int ry = config.radius().sample(random);
        int rz = config.radius().sample(random);
        int max = Math.max(rx, Math.max(ry, rz));
        BlockState replace = config.replaceState;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean replaced = false;
        for (int depth = 0; depth <= max; depth++) {
            int maxX = Math.min(rx, depth);
            for (int x = -maxX; x <= maxX; x++) {
                int maxY = Math.min(ry, depth - Math.abs(x));
                for (int y = -maxY; y <= maxY; y++) {
                    int z = depth - Math.abs(x) - Math.abs(y);
                    if (z > rz) {
                        continue;
                    }
                    replaced |= replaceBlob(cursor, region, pos, ox + x, oy + y, oz + z, target, replace);
                    if (z != 0) {
                        replaced |= replaceBlob(cursor, region, pos, ox + x, oy + y, oz - z, target, replace);
                    }
                }
            }
        }
        FeatureStats.fast(ReplaceBlobsFeature.class);
        return replaced;
    }

    /** One block of a replace blob: the target becomes the replacement, written as {@code Feature.setBlock} writes (flags 3). */
    private static boolean replaceBlob(SectionCursor cursor, Ga2Region region, BlockPos.MutableBlockPos pos, int x, int y, int z, Block target, BlockState replace) {
        if (!cursor.read(x, y, z).is(target)) {
            return false;
        }
        pos.set(x, y, z);
        RegionWriter.setBlock(region, pos, replace, Block.UPDATE_ALL);
        return true;
    }

    /** Each disk target's program ({@link PredicatePrograms#programFor}), by predicate. */
    private static final ConcurrentMap<BlockPredicate, Object> TARGETS =
        new MapMaker().weakKeys().makeMap();

    /** Exact {@code SpringFeature.place}. */
    public static boolean spring(FeaturePlaceContext<?> ctx, Ga2Region region) {
        return spring((SpringConfiguration) ctx.config(), ctx.level(), ctx.origin(), region);
    }

    /** {@link #spring(FeaturePlaceContext, Ga2Region)} without the context ({@code Fast.place}). */
    static boolean spring(SpringConfiguration config, WorldGenLevel level, BlockPos pos, Ga2Region region) {
        SectionCursor cursor = SectionCursor.mine(SectionCursor.SPRING, region);
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        FeatureStats.fast(SpringFeature.class);
        if (!cursor.read(x, y + 1, z).is(config.validBlocks)) {
            return false;
        }
        if (config.requiresBlockBelow && !cursor.read(x, y - 1, z).is(config.validBlocks)) {
            return false;
        }
        BlockState state = cursor.read(x, y, z);
        if (!state.isAir() && !state.is(config.validBlocks)) {
            return false;
        }
        int rocks = 0;
        if (cursor.read(x - 1, y, z).is(config.validBlocks)) {
            rocks++;
        }
        if (cursor.read(x + 1, y, z).is(config.validBlocks)) {
            rocks++;
        }
        if (cursor.read(x, y, z - 1).is(config.validBlocks)) {
            rocks++;
        }
        if (cursor.read(x, y, z + 1).is(config.validBlocks)) {
            rocks++;
        }
        if (cursor.read(x, y - 1, z).is(config.validBlocks)) {
            rocks++;
        }
        int holes = 0;
        if (cursor.read(x - 1, y, z).isAir()) {
            holes++;
        }
        if (cursor.read(x + 1, y, z).isAir()) {
            holes++;
        }
        if (cursor.read(x, y, z - 1).isAir()) {
            holes++;
        }
        if (cursor.read(x, y, z + 1).isAir()) {
            holes++;
        }
        if (cursor.read(x, y - 1, z).isAir()) {
            holes++;
        }
        if (rocks == config.rockCount && holes == config.holeCount) {
            RegionWriter.setBlock(region, pos, config.state.createLegacyBlock(), Block.UPDATE_CLIENTS);
            level.scheduleTick(pos, config.state.getType(), 0);
            return true;
        }
        return false;
    }

    /** Exact {@code SnowAndFreezeFeature.place}. */
    public static boolean snowAndFreeze(FeaturePlaceContext<?> ctx, Ga2Region region, Feature<?> feature) {
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.SNOW, region);
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        // another mod's snow test may snow where vanilla's temperature says no
        boolean warmSkip = FeatureWraps.snow == null;
        int maxY = level.getMaxY() + 1;
        if (warmSkip && WarmBiomes.everyBiome(region, origin.getX(), origin.getZ(), level.getMinY(), maxY, level.getSeaLevel())) {
            // every biome a column's lookup can answer is too warm to freeze or snow, so no column changes
            FeatureStats.fast(SnowAndFreezeFeature.class);
            return true;
        }
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int x = origin.getX() + i;
                int z = origin.getZ() + j;
                int y = cursor.height(Heightmap.Types.MOTION_BLOCKING, x, z);
                top.set(x, y, z);
                below.set(top).move(Direction.DOWN, 1);
                Biome biome = level.getBiome(top).value();
                if (warmSkip && WarmBiomes.alwaysWarm(biome, maxY, level.getSeaLevel())) {
                    // both of vanilla's tests are false for such a biome
                    continue;
                }
                if (biome.shouldFreeze(level, below, false)) {
                    FeatureWraps.setIce(feature, level, region, below);
                }
                if (FeatureWraps.shouldSnow(feature, biome, level, top)) {
                    RegionWriter.setBlock(region, top, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                    BlockState state = cursor.read(below);
                    if (state.hasProperty(SnowyBlock.SNOWY)) {
                        RegionWriter.setBlock(region, below, state.setValue(SnowyBlock.SNOWY, Boolean.TRUE), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        FeatureStats.fast(SnowAndFreezeFeature.class);
        return true;
    }

    /** Exact {@code SeagrassFeature.place}. */
    public static boolean seagrass(FeaturePlaceContext<?> ctx, Ga2Region region) {
        RandomSource random = ctx.random();
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        ProbabilityFeatureConfiguration config = (ProbabilityFeatureConfiguration) ctx.config();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.SEAGRASS, region);
        FeatureStats.fast(SeagrassFeature.class);
        int dx = random.nextInt(8) - random.nextInt(8);
        int dz = random.nextInt(8) - random.nextInt(8);
        int y = cursor.height(Heightmap.Types.OCEAN_FLOOR, origin.getX() + dx, origin.getZ() + dz);
        BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
        if (!cursor.read(pos).is(Blocks.WATER)) {
            return false;
        }
        boolean tall = random.nextDouble() < (double) config.probability;
        BlockState state = tall ? Blocks.TALL_SEAGRASS.defaultBlockState() : Blocks.SEAGRASS.defaultBlockState();
        if (!state.canSurvive(level, pos)) {
            return false;
        }
        if (tall) {
            BlockState upper = state.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
            BlockPos above = pos.above();
            if (cursor.read(above).is(Blocks.WATER)) {
                RegionWriter.setBlock(region, pos, state, Block.UPDATE_CLIENTS);
                RegionWriter.setBlock(region, above, upper, Block.UPDATE_CLIENTS);
            }
        } else {
            RegionWriter.setBlock(region, pos, state, Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** Exact {@code KelpFeature.place}. */
    public static boolean kelp(FeaturePlaceContext<?> ctx, Ga2Region region) {
        int placed = 0;
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        RandomSource random = ctx.random();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.KELP, region);
        FeatureStats.fast(KelpFeature.class);
        int y = cursor.height(Heightmap.Types.OCEAN_FLOOR, origin.getX(), origin.getZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(origin.getX(), y, origin.getZ());
        if (cursor.read(pos).is(Blocks.WATER)) {
            BlockState kelp = Blocks.KELP.defaultBlockState();
            BlockState plant = Blocks.KELP_PLANT.defaultBlockState();
            int height = 1 + random.nextInt(10);
            for (int l = 0; l <= height; l++) {
                if (cursor.read(pos).is(Blocks.WATER) && cursor.read(pos.getX(), pos.getY() + 1, pos.getZ()).is(Blocks.WATER) && plant.canSurvive(level, pos)) {
                    if (l == height) {
                        RegionWriter.setBlock(region, pos, kelp.setValue(KelpBlock.AGE, Integer.valueOf(random.nextInt(4) + 20)), Block.UPDATE_CLIENTS);
                        placed++;
                    } else {
                        RegionWriter.setBlock(region, pos, plant, Block.UPDATE_CLIENTS);
                    }
                } else if (l > 0) {
                    BlockPos belowPos = pos.below();
                    if (kelp.canSurvive(level, belowPos) && !cursor.read(belowPos.getX(), belowPos.getY() - 1, belowPos.getZ()).is(Blocks.KELP)) {
                        RegionWriter.setBlock(region, belowPos, kelp.setValue(KelpBlock.AGE, Integer.valueOf(random.nextInt(4) + 20)), Block.UPDATE_CLIENTS);
                        placed++;
                    }
                    break;
                }
                pos.move(Direction.UP);
            }
        }
        return placed > 0;
    }

    /** Exact {@code SimpleBlockFeature.place}. */
    public static boolean simpleBlock(Feature<?> feature, FeaturePlaceContext<?> ctx, Ga2Region region) {
        return simpleBlock(feature, ctx, (SimpleBlockConfiguration) ctx.config(), ctx.level(), ctx.random(), ctx.origin(), region);
    }

    /**
     * {@link #simpleBlock(Feature, FeaturePlaceContext, Ga2Region)} with the context made only
     * for another mod's callback ({@code ctx} null: {@code Fast.place}, which comes here only without such a callback).
     */
    static boolean simpleBlock(Feature<?> feature, @Nullable FeaturePlaceContext<?> ctx,
                               SimpleBlockConfiguration config, WorldGenLevel level, RandomSource random, BlockPos pos, Ga2Region region) {
        FeatureStats.fast(SimpleBlockFeature.class);
        BlockState state = config.toPlace().getOptionalState(level, random, pos);
        if (state == null) {
            return false;
        }
        boolean survives = state.canSurvive(level, pos);
        // other mods' callbacks right after that call, whatever it answered
        if (ctx != null) {
            Boolean decided = FeatureWraps.simpleBlockAfterSurvive(feature, ctx, state, pos, level);
            if (decided != null) {
                return decided;
            }
        }
        if (!survives) {
            return false;
        }
        if (state.getBlock() instanceof DoublePlantBlock) {
            if (!level.isEmptyBlock(pos.above())) {
                return false;
            }
            DoublePlantBlock.placeAt(level, state, pos, Block.UPDATE_CLIENTS);
        } else if (state.getBlock() instanceof MossyCarpetBlock) {
            MossyCarpetBlock.placeAt(level, pos, level.getRandom(), Block.UPDATE_CLIENTS);
        } else {
            RegionWriter.setBlock(region, pos, state, Block.UPDATE_CLIENTS);
        }
        if (config.scheduleTick()) {
            level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 1);
        }
        return true;
    }

    /**
     * Exact {@code MultifaceGrowthFeature.place}. Vanilla's inner loop resets the position to
     * {@code origin + direction} on every iteration. So after the first iteration it re-reads the same
     * block and calls {@code placeGrowthIfPossible} again. That call only has side effects when it
     * succeeds, and then the loop exits. The repeats are therefore no-ops and are skipped. The
     * direction lists are still drawn once per direction tried, as vanilla does.
     */
    public static boolean multiface(FeaturePlaceContext<?> ctx, Ga2Region region) {
        return multiface((MultifaceGrowthConfiguration) ctx.config(), ctx.level(), ctx.random(), ctx.origin(), region);
    }

    /** {@link #multiface(FeaturePlaceContext, Ga2Region)} without the context ({@code Fast.place}). */
    static boolean multiface(MultifaceGrowthConfiguration config, WorldGenLevel level, RandomSource random, BlockPos origin, Ga2Region region) {
        SectionCursor cursor = SectionCursor.mine(SectionCursor.MULTIFACE, region);
        FeatureStats.fast(MultifaceGrowthFeature.class);
        if (!isAirOrWater(cursor.read(origin))) {
            return false;
        }
        MultifaceScratch s = MULTIFACE_SCRATCH.get();
        Direction[] directions = s.directions;
        int n = shuffledDirections(config, random, null, directions);
        if (placeGrowthIfPossible(cursor, level, origin, cursor.read(origin), config, random, directions, n, s.neighbour)) {
            return true;
        }
        BlockPos.MutableBlockPos pos = s.pos.set(origin);
        for (int d = 0; d < n; d++) {
            Direction direction = directions[d];
            pos.set(origin);
            int m = shuffledDirections(config, random, direction.getOpposite(), s.others);
            if (config.searchRange > 0) {
                pos.setWithOffset(origin, direction);
                BlockState state = cursor.read(pos);
                if (!isAirOrWater(state) && !state.is(config.placeBlock)) {
                    continue;
                }
                if (placeGrowthIfPossible(cursor, level, pos, state, config, random, s.others, m, s.neighbour)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A multiface placement's direction lists and positions, one per thread (a placement places no other one). */
    private static final class MultifaceScratch {
        final Direction[] directions = new Direction[6];
        final Direction[] others = new Direction[6];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    }

    private static final ThreadLocal<MultifaceScratch> MULTIFACE_SCRATCH = ThreadLocal.withInitial(MultifaceScratch::new);
    private static final FeatureTargets.Hook MULTIFACE_DIRECTIONS = FeatureTargets.hook("multifaceDirections");

    /**
     * {@code config.getShuffledDirections(random)} ({@code except} null) or {@code getShuffledDirectionsExcept(random, except)} into
     * {@code out}: the valid directions in their order without {@code except}, then {@code Util.shuffle}'s swaps with the same draws.
     * Returns how many. A configuration class another mod changes is asked itself.
     */
    private static int shuffledDirections(MultifaceGrowthConfiguration config, RandomSource random, @Nullable Direction except, Direction[] out) {
        int n = 0;
        if (!MULTIFACE_DIRECTIONS.allowed()) {
            for (Direction d : except == null ? config.getShuffledDirections(random) : config.getShuffledDirectionsExcept(random, except)) {
                out[n++] = d;
            }
            return n;
        }
        ObjectArrayList<Direction> valid = config.validDirections;
        for (int i = 0, size = valid.size(); i < size; i++) {
            Direction d = valid.get(i);
            if (d != except) {
                out[n++] = d;
            }
        }
        for (int j = n; j > 1; j--) {
            int k = random.nextInt(j);
            Direction t = out[j - 1];
            out[j - 1] = out[k];
            out[k] = t;
        }
        return n;
    }

    /** Exact {@code MultifaceGrowthFeature.placeGrowthIfPossible} over the first {@code n} of {@code directions}. */
    private static boolean placeGrowthIfPossible(SectionCursor cursor, WorldGenLevel level, BlockPos pos, BlockState state, MultifaceGrowthConfiguration config,
                                                 RandomSource random, Direction[] directions, int n, BlockPos.MutableBlockPos neighbour) {
        for (int d = 0; d < n; d++) {
            Direction direction = directions[d];
            BlockState neighbourState = cursor.read(neighbour.setWithOffset(pos, direction));
            if (neighbourState.is(config.canBePlacedOn)) {
                BlockState placement = config.placeBlock.getStateForPlacement(state, level, pos, direction);
                if (placement == null) {
                    return false;
                }
                RegionWriter.setBlock(cursor.region(), pos, placement, Block.UPDATE_ALL);
                cursor.chunk(pos.getX() >> 4, pos.getZ() >> 4).markPosForPostprocessing(pos);
                if (random.nextFloat() < config.chanceOfSpreading) {
                    config.placeBlock.getSpreader().spreadFromFaceTowardRandomDirection(placement, level, pos, direction, random, true);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isAirOrWater(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER);
    }
}
