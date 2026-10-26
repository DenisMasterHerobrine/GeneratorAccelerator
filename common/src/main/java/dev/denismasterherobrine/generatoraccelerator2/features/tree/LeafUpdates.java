package dev.denismasterherobrine.generatoraccelerator2.features.tree;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.state.StateIndex;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import java.lang.reflect.Method;

/**
 * The leaf rules of a tree's two passes, cheaper than asking each state ({@code TreeFast}).
 * <ul>
 * <li>{@link #distance}: {@code LeavesBlock.getOptionalDistanceAt}, remembered per state. It reads only the state and the logs tag.
 * So the answers are kept until tags are bound again ({@link TagEpoch}).</li>
 * <li>{@link Edge#update}: a state's {@code updateShape} in the edge update. A block whose classes declare no {@code updateShape}
 * returns the state unchanged, so it is not asked. Vanilla's leaves schedule their ticks as vanilla's code does. A tick this pass
 * already scheduled at the same position for the same block or fluid is only counted ({@code nextSubTickCount}), since the
 * chunk's tick list keeps one tick per position and type.</li>
 * </ul>
 * Only while no other mod changes the leaves' rules or the shape update (hook {@code treeLeaves}).
 */
final class LeafUpdates {
    private LeafUpdates() {}

    private static final FeatureTargets.Hook HOOK = FeatureTargets.hook("treeLeaves");

    /** Whether the rules may be answered here. Otherwise every call asks the state. */
    static boolean allowed() {
        return HOOK.allowed();
    }

    // ---- leaf distances

    /** Distances by state id: 0-7, -1 for none, {@link #UNKNOWN} not asked yet. */
    private static final int UNKNOWN = -2;

    private record Distances(int epoch, StateIndex.Table table) {}

    private static volatile Distances distances = new Distances(TagEpoch.current(), new StateIndex.Table(UNKNOWN));

    /** {@code getOptionalDistanceAt(state)}: the distance, or -1 when it is empty. */
    static int distance(BlockState state) {
        int id = StateIndex.id(state);
        if (id < 0) {
            return of(LeavesBlock.getOptionalDistanceAt(state));
        }
        Distances d = distances;
        if (d.epoch != TagEpoch.current()) {
            d = distances = new Distances(TagEpoch.current(), new StateIndex.Table(UNKNOWN));
        }
        int v = d.table.get(id);
        if (v == UNKNOWN) {
            v = of(LeavesBlock.getOptionalDistanceAt(state));
            d.table.put(id, v);
        }
        return v;
    }

    private static int of(OptionalInt d) {
        return d.isEmpty() ? -1 : d.getAsInt();
    }

    // ---- shape updates

    private static final int NONE = 0, LEAVES = 1, OTHER = 2;

    /** By block class: whether its updateShape is BlockBehaviour's own (NONE), LeavesBlock's (LEAVES), or another (OTHER). */
    private static final ClassValue<Integer> KIND = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            try {
                for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
                    if (declaresUpdateShape(c)) {
                        return c == LeavesBlock.class ? LEAVES : OTHER;
                    }
                }
                return NONE;
            } catch (LinkageError e) {
                // a signature names a class this side does not have: ask the block
                return OTHER;
            }
        }
    };

    private static boolean declaresUpdateShape(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 6 && p[0] == BlockState.class && p[1] == Direction.class && p[2] == BlockState.class && p[3] == LevelAccessor.class
                && p[4] == BlockPos.class && p[5] == BlockPos.class && Names.mojangMethod(m.getName()).equals("updateShape")) {
                return true;
            }
        }
        return false;
    }

    /** One edge update's ticks so far (one per thread, cleared per tree). */
    static final class Edge {
        private final Long2ObjectOpenHashMap<Block> blockTicks = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet waterTicks = new LongOpenHashSet();

        private static final ThreadLocal<Edge> MINE = ThreadLocal.withInitial(Edge::new);

        static Edge start() {
            Edge e = MINE.get();
            e.blockTicks.clear();
            e.waterTicks.clear();
            return e;
        }

        /** {@code state.updateShape(direction, neighbour, level, pos, neighbourPos)}. */
        BlockState update(BlockState state, Direction direction, BlockState neighbour, WorldGenLevel level, BlockPos pos, BlockPos neighbourPos) {
            int kind = KIND.get(state.getBlock().getClass());
            if (kind == NONE) {
                return state;
            }
            if (kind == OTHER) {
                return state.updateShape(direction, neighbour, level, pos, neighbourPos);
            }
            // LeavesBlock.updateShape
            if (state.getValue(BlockStateProperties.WATERLOGGED)) {
                if (waterTicks.add(pos.asLong())) {
                    level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
                } else {
                    level.nextSubTickCount();
                }
            }
            int d = distance(neighbour);
            int i = (d < 0 ? 7 : d) + 1;
            if (i != 1 || state.getValue(BlockStateProperties.DISTANCE) != i) {
                Block block = state.getBlock();
                if (blockTicks.put(pos.asLong(), block) != block) {
                    level.scheduleTick(pos, block, 1);
                } else {
                    level.nextSubTickCount();
                }
            }
            return state;
        }
    }
}
