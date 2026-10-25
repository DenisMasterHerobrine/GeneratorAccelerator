package dev.denismasterherobrine.generatoraccelerator2.features.tree;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.features.BeehiveDecoratorAccessor;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

/**
 * Trees whose only decorators are vanilla's beehive decorator (forests, birch forests, flower forests, plains, meadows, cherry
 * groves). Vanilla makes the decorators' context for every such tree, which copies the logs, leaves and roots into lists and
 * sorts them, and the decorator then draws its chance before it reads anything: 0.2% in forests. So such a tree keeps its leaves
 * packed, as a tree without decorators does, and each decorator's draw comes first. Only when one succeeds is the context made,
 * with the leaves unpacked, and the rest of the decorator runs as vanilla's code. That rest reads the leaves only for the lowest
 * one's height, which their order does not change. Hook {@code treeBees}: another mod's change to the decorator keeps vanilla's
 * way.
 */
public final class TreeBees {
    private TreeBees() {}

    private static final FeatureTargets.Hook HOOK = FeatureTargets.hook("treeBees");
    /** Trees whose beehives drew first, for the tests. */
    public static final java.util.concurrent.atomic.LongAdder TREES = new java.util.concurrent.atomic.LongAdder();

    /** Whether every decorator is vanilla's beehive decorator, unchanged by other mods. */
    /** {@code -Dgeneratoraccelerator2.treeBees=false} keeps vanilla's way. Not final so the cost harness can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.treeBees"));

    public static boolean handles(List<TreeDecorator> decorators) {
        if (!ENABLED || decorators.isEmpty() || !HOOK.allowed()) {
            return false;
        }
        for (TreeDecorator d : decorators) {
            if (d.getClass() != BeehiveDecorator.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * The decorators in order, as vanilla runs them on one context made from these sets. The context's lists are the sets as
     * they are after the trunk and foliage, which the decorators do not change, so making it at the first success is the same.
     */
    public static void place(List<TreeDecorator> decorators, LevelSimulatedReader level, BiConsumer<BlockPos, BlockState> setter, RandomSource random,
                             Set<BlockPos> logs, LongOpenHashSet leaves, Set<BlockPos> roots) {
        TREES.increment();
        TreeDecorator.Context context = null;
        for (TreeDecorator d : decorators) {
            if (!(random.nextFloat() >= ((BeehiveDecoratorAccessor) d).ga2$probability())) {
                if (context == null) {
                    context = new TreeDecorator.Context(level, setter, random, logs, unpack(leaves), roots);
                }
                afterDraw(context);
            }
        }
    }

    private static Set<BlockPos> unpack(LongOpenHashSet leaves) {
        Set<BlockPos> out = new HashSet<>(Math.max(16, leaves.size() * 2));
        for (LongIterator it = leaves.iterator(); it.hasNext(); ) {
            out.add(BlockPos.of(it.nextLong()));
        }
        return out;
    }

    /** {@code BeehiveDecorator.place} after its chance succeeded. */
    private static void afterDraw(TreeDecorator.Context context) {
        RandomSource random = context.random();
        List<BlockPos> leaves = context.leaves();
        List<BlockPos> logs = context.logs();
        int y = !leaves.isEmpty()
            ? Math.max(leaves.get(0).getY() - 1, logs.get(0).getY() + 1)
            : Math.min(logs.get(0).getY() + 1 + random.nextInt(3), logs.get(logs.size() - 1).getY());
        Direction[] spawn = BeehiveDecoratorAccessor.ga2$spawnDirections();
        Direction facing = BeehiveDecoratorAccessor.ga2$worldgenFacing();
        List<BlockPos> candidates = logs.stream().filter(p -> p.getY() == y).flatMap(p -> Stream.of(spawn).map(p::relative)).collect(Collectors.toList());
        if (!candidates.isEmpty()) {
            // the JDK's shared random, as vanilla's decorator shuffles them
            Collections.shuffle(candidates);
            Optional<BlockPos> nest = candidates.stream().filter(p -> context.isAir(p) && context.isAir(p.relative(facing))).findFirst();
            if (nest.isPresent()) {
                context.setBlock(nest.get(), Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.FACING, facing));
                context.level().getBlockEntity(nest.get(), BlockEntityType.BEEHIVE).ifPresent(hive -> {
                    int bees = 2 + random.nextInt(2);
                    for (int i = 0; i < bees; i++) {
                        hive.storeBee(BeehiveBlockEntity.Occupant.create(random.nextInt(599)));
                    }
                });
            }
        }
    }
}
