package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;

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
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, 0, -radius), origin.offset(radius, 0, radius))) {
            int dx = pos.getX() - origin.getX();
            int dz = pos.getZ() - origin.getZ();
            if (dx * dx + dz * dz <= radius * radius) {
                column.set(pos);
                boolean any = false;
                for (int by = top; by > bottom; by--) {
                    column.setY(by);
                    if (config.target().test(level, column)) {
                        BlockState state = config.stateProvider().getState(level, random, column);
                        RegionWriter.setBlock(region, column, state);
                        Fast.markAboveForPostProcessing(cursor, column);
                        any = true;
                    }
                }
                placed |= any;
            }
        }
        FeatureStats.fast(DiskFeature.class);
        return placed;
    }

    /** Exact {@code SpringFeature.place}. */
    public static boolean spring(FeaturePlaceContext<?> ctx, Ga2Region region) {
        SpringConfiguration config = (SpringConfiguration) ctx.config();
        WorldGenLevel level = ctx.level();
        BlockPos pos = ctx.origin();
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
            RegionWriter.setBlock(region, pos, config.state.createLegacyBlock());
            level.scheduleTick(pos, config.state.getType(), 0);
            return true;
        }
        return false;
    }

    /** Exact {@code SnowAndFreezeFeature.place}. */
    public static boolean snowAndFreeze(FeaturePlaceContext<?> ctx, Ga2Region region, net.minecraft.world.level.levelgen.feature.Feature<?> feature) {
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.SNOW, region);
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int x = origin.getX() + i;
                int z = origin.getZ() + j;
                int y = cursor.height(Heightmap.Types.MOTION_BLOCKING, x, z);
                top.set(x, y, z);
                below.set(top).move(Direction.DOWN, 1);
                Biome biome = level.getBiome(top).value();
                if (biome.shouldFreeze(level, below, false)) {
                    FeatureWraps.setIce(feature, level, region, below);
                }
                if (FeatureWraps.shouldSnow(feature, biome, level, top)) {
                    RegionWriter.setBlock(region, top, Blocks.SNOW.defaultBlockState());
                    BlockState state = cursor.read(below);
                    if (state.hasProperty(SnowyDirtBlock.SNOWY)) {
                        RegionWriter.setBlock(region, below, state.setValue(SnowyDirtBlock.SNOWY, Boolean.TRUE));
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
                RegionWriter.setBlock(region, pos, state);
                RegionWriter.setBlock(region, above, upper);
            }
        } else {
            RegionWriter.setBlock(region, pos, state);
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
                        RegionWriter.setBlock(region, pos, kelp.setValue(KelpBlock.AGE, Integer.valueOf(random.nextInt(4) + 20)));
                        placed++;
                    } else {
                        RegionWriter.setBlock(region, pos, plant);
                    }
                } else if (l > 0) {
                    BlockPos belowPos = pos.below();
                    if (kelp.canSurvive(level, belowPos) && !cursor.read(belowPos.getX(), belowPos.getY() - 1, belowPos.getZ()).is(Blocks.KELP)) {
                        RegionWriter.setBlock(region, belowPos, kelp.setValue(KelpBlock.AGE, Integer.valueOf(random.nextInt(4) + 20)));
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
    public static boolean simpleBlock(net.minecraft.world.level.levelgen.feature.Feature<?> feature, FeaturePlaceContext<?> ctx, Ga2Region region) {
        SimpleBlockConfiguration config = (SimpleBlockConfiguration) ctx.config();
        WorldGenLevel level = ctx.level();
        BlockPos pos = ctx.origin();
        FeatureStats.fast(SimpleBlockFeature.class);
        BlockState state = config.toPlace().getState(ctx.random(), pos);
        boolean survives = state.canSurvive(level, pos);
        // other mods' callbacks right after that call, whatever it answered
        Boolean decided = FeatureWraps.simpleBlockAfterSurvive(feature, ctx, state, pos, level);
        if (decided != null) {
            return decided;
        }
        if (!survives) {
            return false;
        }
        if (state.getBlock() instanceof DoublePlantBlock) {
            if (!level.isEmptyBlock(pos.above())) {
                return false;
            }
            DoublePlantBlock.placeAt(level, state, pos, 2);
        } else {
            RegionWriter.setBlock(region, pos, state);
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
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        RandomSource random = ctx.random();
        MultifaceGrowthConfiguration config = (MultifaceGrowthConfiguration) ctx.config();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.MULTIFACE, region);
        FeatureStats.fast(MultifaceGrowthFeature.class);
        if (!isAirOrWater(cursor.read(origin))) {
            return false;
        }
        List<Direction> directions = config.getShuffledDirections(random);
        if (placeGrowthIfPossible(cursor, level, origin, cursor.read(origin), config, random, directions)) {
            return true;
        }
        BlockPos.MutableBlockPos pos = origin.mutable();
        for (Direction direction : directions) {
            pos.set(origin);
            List<Direction> others = config.getShuffledDirectionsExcept(random, direction.getOpposite());
            if (config.searchRange > 0) {
                pos.setWithOffset(origin, direction);
                BlockState state = cursor.read(pos);
                if (!isAirOrWater(state) && !state.is(config.placeBlock)) {
                    continue;
                }
                if (placeGrowthIfPossible(cursor, level, pos, state, config, random, others)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Exact {@code MultifaceGrowthFeature.placeGrowthIfPossible}. */
    private static boolean placeGrowthIfPossible(SectionCursor cursor, WorldGenLevel level, BlockPos pos, BlockState state, MultifaceGrowthConfiguration config,
                                                 RandomSource random, List<Direction> directions) {
        BlockPos.MutableBlockPos neighbour = pos.mutable();
        for (Direction direction : directions) {
            BlockState neighbourState = cursor.read(neighbour.setWithOffset(pos, direction));
            if (neighbourState.is(config.canBePlacedOn)) {
                BlockState placement = config.placeBlock.getStateForPlacement(state, level, pos, direction);
                if (placement == null) {
                    return false;
                }
                RegionWriter.setBlock(cursor.region(), pos, placement);
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
