package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The modded feature analysis on features written like mods write them: which share state between placements (and so
 * place one at a time) and which do not, and which subclasses of a vanilla feature may use its fast path.
 */
public class ModdedFeaturesTest {
    abstract static class Base extends Feature<NoneFeatureConfiguration> {
        Base() {
            super(NoneFeatureConfiguration.CODEC);
        }
    }

    /** Counts placements in a static field. */
    static final class StaticCounter extends Base {
        static int placements;

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            placements++;
            return true;
        }
    }

    /** Remembers positions in a static map. */
    static final class StaticCache extends Base {
        static final Map<BlockPos, Integer> SEEN = new HashMap<>();

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            SEEN.put(context.origin(), 1);
            return true;
        }
    }

    /** Remembers answers in a static map declared as a {@code Map} but made a {@code ConcurrentHashMap}: thread-safe, not counted. */
    static final class ConcurrentCache extends Base {
        static final Map<BlockPos, Boolean> MEMO = new ConcurrentHashMap<>();

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            return MEMO.computeIfAbsent(context.origin().immutable(), p -> p.getY() > 0);
        }
    }

    /** The same through a synchronized wrapper: thread-safe, not counted. */
    static final class SynchronizedCache extends Base {
        static final Map<BlockPos, Boolean> MEMO = Collections.synchronizedMap(new HashMap<>());

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            return MEMO.computeIfAbsent(context.origin().immutable(), p -> p.getY() > 0);
        }
    }

    /** Reads a static lookup table and fills a local array: nothing shared. */
    static final class LookupTable extends Base {
        static final int[] OFFSETS = { 1, 2, 3, 5, 8 };

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            int[] heights = new int[OFFSETS.length];
            for (int i = 0; i < OFFSETS.length; i++) {
                heights[i] = OFFSETS[i] + context.random().nextInt(4);
            }
            return heights[0] > 0;
        }
    }

    /** Writes a static array element (a shared scratch buffer). */
    static final class SharedScratch extends Base {
        static final int[] SCRATCH = new int[16];

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            int[] buffer = SCRATCH;
            buffer[0] = context.random().nextInt(16);
            return buffer[0] > 4;
        }
    }

    static final class Helper {
        static long last;

        static void remember(long value) {
            last = value;
        }
    }

    /** Shares state through a helper class of the same mod. */
    static final class ThroughHelper extends Base {
        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            Helper.remember(context.origin().asLong());
            return true;
        }
    }

    static int lambdaWrites;

    /** Shares state from inside a lambda. */
    static final class ThroughLambda extends Base {
        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            IntUnaryOperator op = v -> lambdaWrites = v;
            return op.applyAsInt(context.random().nextInt(3)) > 0;
        }
    }

    /** Only uses its arguments. */
    static final class Clean extends Base {
        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            BlockPos pos = context.origin().above(context.random().nextInt(4));
            return context.level().isEmptyBlock(pos);
        }
    }

    /** A mod's ore with only its own constructor. */
    static final class PlainModOre extends OreFeature {
        PlainModOre() {
            super(OreConfiguration.CODEC);
        }
    }

    /** A mod's ore that changes how blobs are placed. */
    static final class CustomModOre extends OreFeature {
        CustomModOre() {
            super(OreConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<OreConfiguration> context) {
            return false;
        }
    }

    /** A mod's ore that changes Feature.setBlock, which the fast path writes around. */
    static final class LoggingModOre extends OreFeature {
        LoggingModOre() {
            super(OreConfiguration.CODEC);
        }

        @Override
        protected void setBlock(LevelWriter level, BlockPos pos, BlockState state) {
            super.setBlock(level, pos, state);
        }
    }

    @Test
    void sharedState() {
        assertNotNull(shared(StaticCounter.class), "static counter");
        assertNotNull(shared(StaticCache.class), "static map");
        assertNull(shared(LookupTable.class), "read-only static table with a local array");
        assertNotNull(shared(SharedScratch.class), "static scratch array through a local");
        assertNotNull(shared(ThroughHelper.class), "static field of a helper class");
        assertNotNull(shared(ThroughLambda.class), "static field from a lambda");
        assertNull(shared(Clean.class), "clean feature");
        assertNull(shared(ConcurrentCache.class), "static Map holding a ConcurrentHashMap");
        assertNull(shared(SynchronizedCache.class), "static Map holding a synchronized map");
        for (Class<?> c : new Class<?>[] { StaticCounter.class, StaticCache.class, LookupTable.class, SharedScratch.class, ThroughHelper.class,
            ThroughLambda.class, Clean.class }) {
            System.out.println("modded features: " + c.getSimpleName() + " -> " + shared(c));
        }
    }

    @Test
    void fastPathEligibility() {
        assertTrue(ModdedFeatures.fastPath(OreFeature.class, OreFeature.class), "vanilla class");
        assertTrue(ModdedFeatures.fastPath(PlainModOre.class, OreFeature.class), "subclass with only a constructor");
        assertFalse(ModdedFeatures.fastPath(CustomModOre.class, OreFeature.class), "subclass with its own place");
        assertFalse(ModdedFeatures.fastPath(LoggingModOre.class, OreFeature.class), "subclass with its own setBlock");
    }

    private static String shared(Class<?> feature) {
        ModdedFeatures.Profile profile = ModdedFeatures.of(feature);
        assertNotNull(profile, feature.getSimpleName());
        return profile.sharedState();
    }
}
