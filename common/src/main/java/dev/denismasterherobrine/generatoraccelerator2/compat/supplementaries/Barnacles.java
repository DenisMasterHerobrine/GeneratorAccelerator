package dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.slf4j.Logger;

/**
 * Supplementaries' barnacle growth ({@code BarnaclesMultifaceGrowthFeature}), a copy of vanilla's multiface growth. After the
 * origin, it searches each direction {@code search_range} times (20 for its shore and ocean barnacles). Each round sets the
 * position to the origin plus the direction, the same block every time, reads it and asks {@code placeGrowthIfPossible} there,
 * which reads up to five neighbours. That call writes and draws only when it places, and then the search ends. So when the first
 * round finds nothing, the other rounds read the same blocks and find nothing either. Its shore barnacles try 100 times per
 * chunk, mostly in open water: about 720 block reads for a try that places nothing, where 36 give the same answer. 8% of the
 * workers' time in a 575-mod pack.
 *
 * <p>This class runs {@code place} with one round per direction. It makes the same calls in the same order, the mod's own
 * {@code placeGrowthIfPossible} and direction shuffles included, so every random draw and every write is the mod's. Applies
 * only to the mod's class itself, with the code it was written against ({@link SupplementariesMixins#codeKnown}, by
 * fingerprint), while no other mod changes those methods (hook {@code supplementaries.barnacles}).
 *
 * <p>{@code -Dgeneratoraccelerator2.verify.barnacles=N}: every N-th placement runs the other rounds too, as the mod does, and
 * checks that none of them places.
 */
public final class Barnacles {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.SUPPLEMENTARIES_BARNACLES);
    private static final VerifyCounter VERIFY = new VerifyCounter("barnacles");

    /** Placements searched here, and rounds left out (each one a block read and a placement test). */
    public static final LongAdder PLACEMENTS = new LongAdder(), ROUNDS_SKIPPED = new LongAdder();

    private Barnacles() {}

    private static final class Handles {
        static final Class<?> FEATURE;
        static final MethodHandle PLACE_BLOCK, SEARCH_RANGE, SHUFFLED, SHUFFLED_EXCEPT, GROW;

        static {
            try {
                ClassLoader loader = Barnacles.class.getClassLoader();
                FEATURE = Class.forName(SupplementariesMixins.FEATURE, false, loader);
                Class<?> config = Class.forName(SupplementariesMixins.CONFIG, false, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                PLACE_BLOCK = lookup.findVirtual(config, "placeBlock", MethodType.methodType(MultifaceBlock.class))
                    .asType(MethodType.methodType(MultifaceBlock.class, Object.class));
                SEARCH_RANGE = lookup.findVirtual(config, "searchRange", MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
                SHUFFLED = lookup.findVirtual(config, "getShuffledDirections", MethodType.methodType(Collection.class, RandomSource.class))
                    .asType(MethodType.methodType(Collection.class, Object.class, RandomSource.class));
                SHUFFLED_EXCEPT = lookup.findVirtual(config, "getShuffledDirectionsExcept", MethodType.methodType(List.class, RandomSource.class, Direction.class))
                    .asType(MethodType.methodType(List.class, Object.class, RandomSource.class, Direction.class));
                GROW = lookup.findStatic(FEATURE, "placeGrowthIfPossible", MethodType.methodType(boolean.class, WorldGenLevel.class, BlockPos.class,
                        BlockState.class, config, RandomSource.class, Collection.class))
                    .asType(MethodType.methodType(boolean.class, WorldGenLevel.class, BlockPos.class, BlockState.class, Object.class, RandomSource.class,
                        Collection.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static volatile boolean broken;

    /** {@code place(ctx)} for {@code self}, or null when the mod's own method is to run: another class, the hook off, or the handles not found. */
    @Nullable
    public static Boolean place(Object self, FeaturePlaceContext<?> ctx) {
        if (broken) {
            return null;
        }
        try {
            if (self.getClass() != Handles.FEATURE || !HOOK.allowed()) {
                return null;
            }
            return search(ctx.level(), ctx.origin(), ctx.random(), ctx.config());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            broken = true;
            LOGGER.warn("Barnacle growth unreachable -> own search", e);
            return null;
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean search(WorldGenLevel level, BlockPos origin, RandomSource random, Object config) throws Throwable {
        PLACEMENTS.increment();
        MultifaceBlock placeBlock = (MultifaceBlock) Handles.PLACE_BLOCK.invokeExact(config);
        BlockState state = level.getBlockState(origin);
        if (state.is(placeBlock)) {
            placeBlock.getSpreader().spreadFromRandomFaceTowardRandomDirection(state, level, origin, random);
            return true;
        }
        if (!isAirOrWater(state)) {
            return false;
        }
        Collection<Direction> directions = (Collection<Direction>) Handles.SHUFFLED.invokeExact(config, random);
        if ((boolean) Handles.GROW.invokeExact(level, origin, level.getBlockState(origin), config, random, directions)) {
            return true;
        }
        BlockPos.MutableBlockPos pos = origin.mutable();
        boolean verify = VERIFY.sample();
        for (Direction direction : directions) {
            pos.set(origin);
            Collection<Direction> others = (List<Direction>) Handles.SHUFFLED_EXCEPT.invokeExact(config, random, direction.getOpposite());
            // the mod reads the range at every round. A record's field does not change
            int range = (int) Handles.SEARCH_RANGE.invokeExact(config);
            if (range <= 0) {
                continue;
            }
            pos.setWithOffset(origin, direction);
            BlockState at = level.getBlockState(pos);
            if (!isAirOrWater(at) && !at.is(placeBlock)) {
                continue;
            }
            if ((boolean) Handles.GROW.invokeExact(level, (BlockPos) pos, at, config, random, others)) {
                return true;
            }
            if (verify) {
                if (rounds(level, origin, direction, pos, placeBlock, config, random, others, range)) {
                    return true;
                }
            } else {
                ROUNDS_SKIPPED.add(range - 1);
            }
        }
        return false;
    }

    /** The mod's other {@code range - 1} rounds in that direction, as it runs them. True when one places, which the search left out. */
    private static boolean rounds(WorldGenLevel level, BlockPos origin, Direction direction, BlockPos.MutableBlockPos pos, MultifaceBlock placeBlock,
                                  Object config, RandomSource random, Collection<Direction> others, int range) throws Throwable {
        for (int i = 1; i < range; i++) {
            pos.setWithOffset(origin, direction);
            BlockState at = level.getBlockState(pos);
            if (!isAirOrWater(at) && !at.is(placeBlock)) {
                VERIFY.result(false, pos.immutable(), "a round as the first", "stopped at " + at);
                return false;
            }
            if ((boolean) Handles.GROW.invokeExact(level, (BlockPos) pos, at, config, random, others)) {
                VERIFY.result(false, pos.immutable(), "a round as the first", "placed in round " + i);
                return true;
            }
        }
        VERIFY.result(true, origin, null, null);
        return false;
    }

    /** The mod's {@code isAirOrWater}. */
    private static boolean isAirOrWater(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER);
    }
}
