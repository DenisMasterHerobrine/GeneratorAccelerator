package dev.denismasterherobrine.generatoraccelerator2.features.tree;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import java.lang.reflect.Method;

/**
 * Transcription of {@code TreeFeature.place}. The trunk, foliage and root placers and the decorators
 * are the configuration's own objects and run unchanged against the real region (NeoForge's dirt hook
 * casts the simulated reader to a level, so no stand-in reader is possible). The position sets the
 * placers and decorators see are vanilla's own {@code HashSet}s, so their order is identical by construction.
 * The leaf distance sets are {@link LeafSets}, which reproduce {@code HashSet}'s order. They are checked
 * against the running JDK once, and trees keep vanilla's sets if the check fails.
 * What changes: every tree write goes through the exact region writer, the free-height scan fetches
 * each state once, and the leaf distance pass reads neighbours through the section cursor.
 */
public final class TreeFast {
    private TreeFast() {}

    /** The flags of the tree's own writes: {@code TreeFeature.setBlockKnownShape}. */
    private static final int KNOWN_SHAPE = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;

    private static final Direction[] DIRECTIONS = Direction.values();
    /** Trunk placer classes (and their supertypes up to the base) that declare neither isFree nor validTreePos. */
    private static final Map<Class<?>, Boolean> BASE_FREE_TEST = new ConcurrentHashMap<>();

    private static boolean usesBaseFreeTest(Class<?> placer) {
        return BASE_FREE_TEST.computeIfAbsent(placer, cls -> {
            try {
                for (Class<?> c = cls; c != null && c != TrunkPlacer.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        String name = Names.mojangMethod(m.getName());
                        if ((name.equals("isFree") || name.equals("validTreePos")) && m.getParameterCount() == 2) {
                            return Boolean.FALSE;
                        }
                    }
                }
            } catch (LinkageError e) {
                return Boolean.FALSE; // a signature names a class this side does not have (a client class on a server): ask the placer
            }
            return Boolean.TRUE;
        });
    }

    public static boolean place(FeaturePlaceContext<?> ctx, Ga2Region region) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        TreeConfiguration config = (TreeConfiguration) ctx.config();
        SectionCursor cursor = SectionCursor.mine(SectionCursor.TREE, region);
        FeatureStats.fast(TreeFeature.class);
        Set<BlockPos> roots = Sets.newHashSet();
        Set<BlockPos> logs = Sets.newHashSet();
        // decorators see the leaves in their set's order. Without them the leaves only answer isSet and the bounding box, so they
        // are kept as packed positions in the thread's set: no position and no map node per leaf. Beehives alone read the
        // leaves only when their chance succeeds (TreeBees)
        boolean setLeaves = !config.decorators.isEmpty() && !TreeBees.handles(config.decorators);
        Set<BlockPos> leaves = setLeaves ? Sets.newHashSet() : null;
        LongOpenHashSet leafKeys = setLeaves ? null : LeafKeys.take();
        Set<BlockPos> decorations = Sets.newHashSet();
        try {
            return place(level, random, origin, config, cursor, region, roots, logs, leaves, leafKeys, decorations);
        } finally {
            if (leafKeys != null) {
                LeafKeys.give(leafKeys);
            }
        }
    }

    private static boolean place(WorldGenLevel level, RandomSource random, BlockPos origin, TreeConfiguration config, SectionCursor cursor, Ga2Region region,
                                 Set<BlockPos> roots, Set<BlockPos> logs, Set<BlockPos> leaves, LongOpenHashSet leafKeys, Set<BlockPos> decorations) {
        BiConsumer<BlockPos, BlockState> rootSetter = (pos, state) -> {
            roots.add(pos.immutable());
            RegionWriter.setBlock(region, pos, state, KNOWN_SHAPE);
        };
        BiConsumer<BlockPos, BlockState> logSetter = (pos, state) -> {
            logs.add(pos.immutable());
            RegionWriter.setBlock(region, pos, state, KNOWN_SHAPE);
        };
        FoliagePlacer.FoliageSetter foliageSetter = new FoliagePlacer.FoliageSetter() {
            @Override
            public void set(BlockPos pos, BlockState state) {
                if (leaves != null) {
                    leaves.add(pos.immutable());
                } else {
                    leafKeys.add(pos.asLong());
                }
                RegionWriter.setBlock(region, pos, state, KNOWN_SHAPE);
            }

            @Override
            public boolean isSet(BlockPos pos) {
                return leaves != null ? leaves.contains(pos) : leafKeys.contains(pos.asLong());
            }
        };
        BiConsumer<BlockPos, BlockState> decorationSetter = (pos, state) -> {
            decorations.add(pos.immutable());
            RegionWriter.setBlock(region, pos, state, KNOWN_SHAPE);
        };
        boolean placed = doPlace(level, cursor, random, origin, rootSetter, logSetter, foliageSetter, config);
        if (placed && (!logs.isEmpty() || (leaves != null ? !leaves.isEmpty() : !leafKeys.isEmpty()))) {
            if (leaves != null) {
                TreeDecorator.Context context = new TreeDecorator.Context(level, decorationSetter, random, logs, leaves, roots);
                config.decorators.forEach(decorator -> decorator.place(context));
            } else if (!config.decorators.isEmpty()) {
                TreeBees.place(config.decorators, level, decorationSetter, random, logs, leafKeys, roots);
            }
            BoundingBox box = encapsulate(roots, logs, leaves, leafKeys, decorations);
            if (box == null) {
                return false;
            }
            DiscreteVoxelShape shape = updateLeaves(cursor, region, box, logs, decorations, roots);
            updateShapeAtEdge(cursor, region, level, shape, box.minX(), box.minY(), box.minZ());
            return true;
        }
        return false;
    }

    /** {@code BoundingBox.encapsulatingPositions} of the four sets (the leaves packed or not), or null for none. Order does not matter. */
    private static BoundingBox encapsulate(Set<BlockPos> roots, Set<BlockPos> logs, Set<BlockPos> leaves, LongOpenHashSet leafKeys, Set<BlockPos> decorations) {
        BoundingBox box = add(add(add(null, roots), logs), decorations);
        if (leaves != null) {
            box = add(box, leaves);
        }
        if (leafKeys != null && !leafKeys.isEmpty()) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (LongIterator it = leafKeys.iterator(); it.hasNext(); ) {
                pos.set(it.nextLong());
                box = box == null ? new BoundingBox(pos) : box.encapsulate(pos);
            }
        }
        return box;
    }

    private static BoundingBox add(BoundingBox box, Set<BlockPos> set) {
        for (BlockPos pos : set) {
            box = box == null ? new BoundingBox(pos) : box.encapsulate(pos);
        }
        return box;
    }

    /** The thread's packed leaf set, lent to one tree at a time. A tree placed while another holds it gets one of its own. */
    private static final class LeafKeys {
        private static final ThreadLocal<LongOpenHashSet[]> MINE = ThreadLocal.withInitial(() -> new LongOpenHashSet[1]);

        static LongOpenHashSet take() {
            LongOpenHashSet[] slot = MINE.get();
            LongOpenHashSet set = slot[0];
            if (set == null) {
                return new LongOpenHashSet();
            }
            slot[0] = null;
            return set;
        }

        static void give(LongOpenHashSet set) {
            set.clear();
            MINE.get()[0] = set;
        }
    }

    /**
     * Exact {@code StructureTemplate.updateShapeAtEdge(level, 3, shape, x, y, z)}. It runs the three passes of
     * {@code DiscreteVoxelShape.forAllFaces} in their order: z faces by x, y, z, then y faces by z, x, y, then x faces by y, z, x.
     * At each face it does the two reads, the two {@code updateShape} calls (the blocks' own code, with the region) and the
     * writes when a state changes. Reads go through the cursor, and writes go through the region writer (flags are ignored by
     * the region). No position is allocated per face.
     */
    private static void updateShapeAtEdge(SectionCursor cursor, Ga2Region region, WorldGenLevel level, DiscreteVoxelShape shape, int ox, int oy, int oz) {
        int sx = shape.getXSize(), sy = shape.getYSize(), sz = shape.getZSize();
        LeafUpdates.Edge edge = LeafUpdates.allowed() ? LeafUpdates.Edge.start() : null;
        BlockPos.MutableBlockPos inside = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos outside = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                boolean prev = false;
                for (int z = 0; z <= sz; z++) {
                    boolean full = z != sz && shape.isFull(x, y, z);
                    if (!prev && full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.NORTH, ox + x, oy + y, oz + z);
                    }
                    if (prev && !full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.SOUTH, ox + x, oy + y, oz + z - 1);
                    }
                    prev = full;
                }
            }
        }
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                boolean prev = false;
                for (int y = 0; y <= sy; y++) {
                    boolean full = y != sy && shape.isFull(x, y, z);
                    if (!prev && full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.DOWN, ox + x, oy + y, oz + z);
                    }
                    if (prev && !full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.UP, ox + x, oy + y - 1, oz + z);
                    }
                    prev = full;
                }
            }
        }
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                boolean prev = false;
                for (int x = 0; x <= sx; x++) {
                    boolean full = x != sx && shape.isFull(x, y, z);
                    if (!prev && full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.WEST, ox + x, oy + y, oz + z);
                    }
                    if (prev && !full) {
                        edgeFace(edge, cursor, region, level, inside, outside, Direction.EAST, ox + x - 1, oy + y, oz + z);
                    }
                    prev = full;
                }
            }
        }
    }

    /** {@code updateShapeAtEdge}'s face consumer at block {@code (x, y, z)} facing {@code d}. */
    private static void edgeFace(LeafUpdates.Edge edge, SectionCursor cursor, Ga2Region region, WorldGenLevel level, BlockPos.MutableBlockPos inside,
                                 BlockPos.MutableBlockPos outside,                                 Direction d, int x, int y, int z) {
        inside.set(x, y, z);
        outside.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
        BlockState state = cursor.read(x, y, z);
        BlockState neighbour = cursor.read(outside.getX(), outside.getY(), outside.getZ());
        BlockState updated = edge != null ? edge.update(state, d, neighbour, level, inside, outside) : state.updateShape(level, level, inside, d, outside, neighbour, level.getRandom());
        if (state != updated) {
            RegionWriter.setBlock(region, inside, updated, Block.UPDATE_CLIENTS);
        }
        BlockState neighbourUpdated = edge != null ? edge.update(neighbour, d.getOpposite(), updated, level, outside, inside)
            : neighbour.updateShape(level, level, outside, d.getOpposite(), inside, updated, level.getRandom());
        if (neighbour != neighbourUpdated) {
            RegionWriter.setBlock(region, outside, neighbourUpdated, Block.UPDATE_CLIENTS);
        }
    }

    /** Exact {@code TreeFeature.doPlace}. */
    private static boolean doPlace(WorldGenLevel level, SectionCursor cursor, RandomSource random, BlockPos origin, BiConsumer<BlockPos, BlockState> rootSetter,
                                   BiConsumer<BlockPos, BlockState> logSetter, FoliagePlacer.FoliageSetter foliageSetter, TreeConfiguration config) {
        int height = config.trunkPlacer.getTreeHeight(random);
        int foliageHeight = config.foliagePlacer.foliageHeight(random, height, config);
        int trunkHeight = height - foliageHeight;
        int radius = config.foliagePlacer.foliageRadius(random, trunkHeight);
        BlockPos trunkOrigin = config.rootPlacer.<BlockPos>map(placer -> placer.getTrunkOrigin(origin, random)).orElse(origin);
        int minY = Math.min(origin.getY(), trunkOrigin.getY());
        int maxY = Math.max(origin.getY(), trunkOrigin.getY()) + height + 1;
        if (minY >= level.getMinY() + 1 && maxY <= level.getMaxY() + 1) {
            OptionalInt clipped = config.minimumSize.minClippedHeight();
            int free = getMaxFreeTreeHeight(level, cursor, height, trunkOrigin, config);
            if (free >= height || !clipped.isEmpty() && free >= clipped.getAsInt()) {
                if (config.rootPlacer.isPresent() && !config.rootPlacer.get().placeRoots(level, rootSetter, random, origin, trunkOrigin, config)) {
                    return false;
                }
                List<FoliagePlacer.FoliageAttachment> attachments = config.trunkPlacer.placeTrunk(level, logSetter, random, free, trunkOrigin, config);
                attachments.forEach(attachment -> config.foliagePlacer.createFoliage(level, foliageSetter, random, config, free, attachment, foliageHeight, radius));
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Exact {@code TreeFeature.getMaxFreeTreeHeight}. Vanilla asks the trunk placer whether a position
     * is free and then whether it holds a vine, reading the state up to three times. For placers that
     * keep the base test, the state is fetched once. A placer that overrides the test (vanilla's
     * mangrove placer, or a modded one) is asked exactly as vanilla asks it.
     */
    private static int getMaxFreeTreeHeight(WorldGenLevel level, SectionCursor cursor, int height, BlockPos origin, TreeConfiguration config) {
        TrunkPlacer trunk = config.trunkPlacer;
        boolean vanillaTest = usesBaseFreeTest(trunk.getClass());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= height + 1; i++) {
            int size = config.minimumSize.getSizeAtHeight(height, i);
            for (int dx = -size; dx <= size; dx++) {
                for (int dz = -size; dz <= size; dz++) {
                    pos.setWithOffset(origin, dx, i, dz);
                    boolean blocked;
                    if (vanillaTest) {
                        BlockState state = cursor.read(pos);
                        boolean free = state.isAir() || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.LOGS);
                        blocked = !free || !config.ignoreVines && state.is(Blocks.VINE);
                    } else {
                        blocked = !trunk.isFree(level, pos) || !config.ignoreVines && level.isStateAtPosition(pos, s -> s.is(Blocks.VINE));
                    }
                    if (blocked) {
                        return i - 2;
                    }
                }
            }
        }
        return height;
    }

    /**
     * Exact {@code TreeFeature.updateLeaves}, with cursor reads and writer writes. The distance sets are {@link LeafSets}: the
     * same elements taken in the same order as vanilla's {@code HashSet}s give them, without rescanning their tables.
     */
    private static DiscreteVoxelShape updateLeaves(SectionCursor cursor, Ga2Region region, BoundingBox box, Set<BlockPos> logs, Set<BlockPos> decorations,
                                                   Set<BlockPos> roots) {
        DiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(box.getXSpan(), box.getYSpan(), box.getZSpan());
        // vanilla fills the union of the two. Filling is idempotent, so each set is filled in turn.
        for (BlockPos pos : decorations) {
            if (box.isInside(pos)) {
                shape.fill(pos.getX() - box.minX(), pos.getY() - box.minY(), pos.getZ() - box.minZ());
            }
        }
        for (BlockPos pos : roots) {
            if (box.isInside(pos)) {
                shape.fill(pos.getX() - box.minX(), pos.getY() - box.minY(), pos.getZ() - box.minZ());
            }
        }
        LeafSets levels = LeafSets.get();
        if (!LeafSets.ENABLED || !levels.begin(box, logs)) {
            return updateLeavesWithSets(cursor, region, box, logs, shape);
        }
        boolean rules = LeafUpdates.allowed();
        int minX = box.minX(), minY = box.minY(), minZ = box.minZ();
        int k1 = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (true) {
            while (k1 >= 7 || !levels.isEmpty(k1)) {
                if (k1 >= 7) {
                    return shape;
                }
                levels.poll(k1);
                int x = levels.x, y = levels.y, z = levels.z;
                if (box.isInside(x, y, z)) {
                    if (k1 != 0) {
                        BlockState state = cursor.read(x, y, z);
                        // the writer keeps no position (ServerLevel.updatePOIOnBlockStateChange copies its own)
                        RegionWriter.setBlock(region, at.set(x, y, z), state.setValue(BlockStateProperties.DISTANCE, Integer.valueOf(k1)), KNOWN_SHAPE);
                    }
                    shape.fill(x - minX, y - minY, z - minZ);
                    for (Direction direction : DIRECTIONS) {
                        int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                        if (box.isInside(nx, ny, nz) && !shape.isFull(nx - minX, ny - minY, nz - minZ)) {
                            int distance = distance(cursor.read(nx, ny, nz), rules);
                            if (distance >= 0) {
                                int j1 = Math.min(distance, k1 + 1);
                                if (j1 < 7) {
                                    levels.add(j1, nx, ny, nz);
                                    k1 = Math.min(k1, j1);
                                }
                            }
                        }
                    }
                }
            }
            k1++;
        }
    }

    /** {@code LeavesBlock.getOptionalDistanceAt(state)}, -1 for none. Remembered per state while the leaves' rules are vanilla's. */
    private static int distance(BlockState state, boolean rules) {
        if (rules) {
            return LeafUpdates.distance(state);
        }
        OptionalInt d = LeavesBlock.getOptionalDistanceAt(state);
        return d.isEmpty() ? -1 : d.getAsInt();
    }

    /** {@link #updateLeaves} with vanilla's own sets (the decorations and roots already filled). */
    private static DiscreteVoxelShape updateLeavesWithSets(SectionCursor cursor, Ga2Region region, BoundingBox box, Set<BlockPos> logs, DiscreteVoxelShape shape) {
        boolean rules = LeafUpdates.allowed();
        List<Set<BlockPos>> levels = Lists.newArrayList();
        for (int j = 0; j < 7; j++) {
            levels.add(Sets.newHashSet());
        }
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        int k1 = 0;
        levels.get(0).addAll(logs);
        while (true) {
            while (k1 >= 7 || !levels.get(k1).isEmpty()) {
                if (k1 >= 7) {
                    return shape;
                }
                Iterator<BlockPos> iterator = levels.get(k1).iterator();
                BlockPos pos = iterator.next();
                iterator.remove();
                if (box.isInside(pos)) {
                    if (k1 != 0) {
                        BlockState state = cursor.read(pos);
                        RegionWriter.setBlock(region, pos, state.setValue(BlockStateProperties.DISTANCE, Integer.valueOf(k1)), KNOWN_SHAPE);
                    }
                    shape.fill(pos.getX() - box.minX(), pos.getY() - box.minY(), pos.getZ() - box.minZ());
                    for (Direction direction : DIRECTIONS) {
                        neighbour.setWithOffset(pos, direction);
                        if (box.isInside(neighbour)) {
                            int lx = neighbour.getX() - box.minX();
                            int ly = neighbour.getY() - box.minY();
                            int lz = neighbour.getZ() - box.minZ();
                            if (!shape.isFull(lx, ly, lz)) {
                                int distance = distance(cursor.read(neighbour), rules);
                                if (distance >= 0) {
                                    int j1 = Math.min(distance, k1 + 1);
                                    if (j1 < 7) {
                                        levels.get(j1).add(neighbour.immutable());
                                        k1 = Math.min(k1, j1);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            k1++;
        }
    }
}
