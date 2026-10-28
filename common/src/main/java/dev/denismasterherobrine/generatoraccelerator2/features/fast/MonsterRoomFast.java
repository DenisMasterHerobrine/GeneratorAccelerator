package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.slf4j.Logger;

/** Exact {@code MonsterRoomFeature.place} with cursor reads and writer writes. Same random calls and order. */
public final class MonsterRoomFast {
    private MonsterRoomFast() {}

    private static final Logger LOGGER = Ga2Log.get("Features");
    private static final BlockState AIR = Blocks.CAVE_AIR.defaultBlockState();

    public static boolean place(Feature<?> feature, FeaturePlaceContext<?> ctx, Ga2Region region) {
        Predicate<BlockState> predicate = Feature.isReplaceable(BlockTags.FEATURES_CANNOT_REPLACE);
        BlockPos origin = ctx.origin();
        RandomSource random = ctx.random();
        WorldGenLevel level = ctx.level();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.MONSTER_ROOM, region);
        FeatureStats.fast(MonsterRoomFeature.class);
        int halfX = random.nextInt(2) + 2;
        int minX = -halfX - 1;
        int maxX = halfX + 1;
        int halfZ = random.nextInt(2) + 2;
        int minZ = -halfZ - 1;
        int maxZ = halfZ + 1;
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int openings = openings(cursor, ox, oy, oz, minX, maxX, minZ, maxZ);
        if (openings < 1 || openings > 5) {
            return false;
        }
        // each loop is a method of its own: the JIT compiles each once, instead of the whole placement at every loop
        carve(cursor, region, random, predicate, ox, oy, oz, minX, maxX, minZ, maxZ);
        chests(cursor, level, random, predicate, ox, oy, oz, halfX, halfZ);
        Fast.safeSetBlock(cursor, origin, Blocks.SPAWNER.defaultBlockState(), predicate);
        if (level.getBlockEntity(origin) instanceof SpawnerBlockEntity spawner) {
            spawner.setEntityId(FeatureWraps.spawnerMob(feature, PlatformHooks.randomMonsterRoomMob(random), origin, random, level),
                random);
        } else {
            LOGGER.error("failed to fetch mob spawner entity at ({}, {}, {})", origin.getX(), origin.getY(), origin.getZ());
        }
        return true;
    }

    /** The openings in the room's walls at floor height, or -1 when a floor or ceiling block is not solid. */
    private static int openings(SectionCursor cursor, int ox, int oy, int oz, int minX, int maxX, int minZ, int maxZ) {
        int openings = 0;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    boolean solid = cursor.read(ox + dx, oy + dy, oz + dz).isSolid();
                    if (dy == -1 && !solid) {
                        return -1;
                    }
                    if (dy == 4 && !solid) {
                        return -1;
                    }
                    if ((dx == minX || dx == maxX || dz == minZ || dz == maxZ) && dy == 0 && cursor.read(ox + dx, oy + dy, oz + dz).isAir()
                        && cursor.read(ox + dx, oy + dy + 1, oz + dz).isAir()) {
                        openings++;
                    }
                }
            }
        }
        return openings;
    }

    /** The walls in cobblestone (mossy on the floor), the inside in air. */
    private static void carve(SectionCursor cursor, Ga2Region region, RandomSource random, Predicate<BlockState> predicate, int ox, int oy, int oz, int minX,
                              int maxX, int minZ, int maxZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minBuild = region.ga2$minY();
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = 3; dy >= -1; dy--) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    pos.set(ox + dx, oy + dy, oz + dz);
                    BlockState state = cursor.read(pos);
                    if (dx == minX || dy == -1 || dz == minZ || dx == maxX || dy == 4 || dz == maxZ) {
                        if (pos.getY() >= minBuild && !cursor.read(pos.getX(), pos.getY() - 1, pos.getZ()).isSolid()) {
                            RegionWriter.setBlock(region, pos, AIR);
                        } else if (state.isSolid() && !state.is(Blocks.CHEST)) {
                            // one write site for both stones (the floor's draw as vanilla's)
                            BlockState wall = dy == -1 && random.nextInt(4) != 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                            Fast.safeSetBlock(cursor, pos, wall, predicate);
                        }
                    } else if (!state.is(Blocks.CHEST) && !state.is(Blocks.SPAWNER)) {
                        Fast.safeSetBlock(cursor, pos, AIR, predicate);
                    }
                }
            }
        }
    }

    /** Up to two chests, each on a floor block with exactly one solid side, three tries each. */
    private static void chests(SectionCursor cursor, WorldGenLevel level, RandomSource random, Predicate<BlockState> predicate, int ox, int oy, int oz, int halfX,
                               int halfZ) {
        for (int chest = 0; chest < 2; chest++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                int cx = ox + random.nextInt(halfX * 2 + 1) - halfX;
                int cz = oz + random.nextInt(halfZ * 2 + 1) - halfZ;
                BlockPos chestPos = new BlockPos(cx, oy, cz);
                if (cursor.read(chestPos).isAir()) {
                    int solidSides = 0;
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        if (cursor.read(cx + direction.getStepX(), oy, cz + direction.getStepZ()).isSolid()) {
                            solidSides++;
                        }
                    }
                    if (solidSides == 1) {
                        Fast.safeSetBlock(cursor, chestPos, StructurePiece.reorient(level, chestPos, Blocks.CHEST.defaultBlockState()), predicate);
                        RandomizableContainerBlockEntity.setLootTable(level, random, chestPos, BuiltInLootTables.SIMPLE_DUNGEON);
                        break;
                    }
                }
            }
        }
    }
}
