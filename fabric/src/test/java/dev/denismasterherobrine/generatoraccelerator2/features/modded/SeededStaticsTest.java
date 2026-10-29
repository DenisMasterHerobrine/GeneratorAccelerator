package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link SeededStatics}: a feature whose only shared state is noise made once from the world's seed (Bumblezone's pattern, and
 * Repurposed Structures' with the noise in the feature's own field) is recognized once its fields are listed. It is ready for unlocked placements only after a placement made the noise for
 * that seed and published it. A feature with any other shared state is not recognized.
 */
public class SeededStaticsTest {
    /** Makes its noise from the seed into a static field, as The Bumblezone's honeycomb caves do. */
    static final class SeededNoise extends Feature<NoneFeatureConfiguration> {
        protected long seed;
        protected static Object noise;

        SeededNoise() {
            super(NoneFeatureConfiguration.CODEC);
        }

        public void setSeed(long seed) {
            if (this.seed != seed || noise == null) {
                noise = new Object();
                this.seed = seed;
            }
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            setSeed(context.level().getSeed());
            return noise != null;
        }
    }

    /** The same, plus a static counter: still shared state. */
    static final class SeededNoiseAndCounter extends Feature<NoneFeatureConfiguration> {
        protected long seed;
        protected static Object noise;
        static int placements;

        SeededNoiseAndCounter() {
            super(NoneFeatureConfiguration.CODEC);
        }

        public void setSeed(long seed) {
            if (this.seed != seed || noise == null) {
                noise = new Object();
                this.seed = seed;
            }
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            setSeed(context.level().getSeed());
            placements++;
            return true;
        }
    }

    /** Makes its noise from the seed into its own field, as Repurposed Structures' powder snow does. */
    static final class SeededOwnNoise extends Feature<NoneFeatureConfiguration> {
        protected long seed;
        private Object noise;

        SeededOwnNoise() {
            super(NoneFeatureConfiguration.CODEC);
        }

        public void setSeed(long seed) {
            if (this.seed != seed || this.noise == null) {
                this.noise = new Object();
                this.seed = seed;
            }
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            setSeed(context.level().getSeed());
            return this.noise != null;
        }
    }

    /** Fills a static map at its first placement and reads it afterwards, as TerraFirmaGreg's mineshaft ores do. */
    static final class FilledOnce extends Feature<NoneFeatureConfiguration> {
        public static final Map<String, Integer> sizes = new HashMap<>();

        FilledOnce() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            if (sizes.isEmpty()) {
                sizes.put("vein", 3);
            }
            return sizes.get("vein") > 0;
        }
    }

    private static WorldGenLevel level(long seed) {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getSeed" -> seed;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void readyOnlyAfterTheNoiseIsMadeAndPublished() {
        SeededStatics.register(SeededNoise.class.getName(), "seed", List.of("noise"));
        SeededStatics.register(SeededNoiseAndCounter.class.getName(), "seed", List.of("noise"));
        assertNotNull(FeatureSafetyScan.sharedState(SeededNoise.class), "the static noise write counts by itself");
        assertNull(FeatureSafetyScan.sharedState(SeededNoise.class, false, FeatureSafetyScan.MAX_METHODS, false, SeededStatics.fields(SeededNoise.class)),
            "nothing else is shared");
        assertNotNull(FeatureSafetyScan.sharedState(SeededNoiseAndCounter.class, false, FeatureSafetyScan.MAX_METHODS, false,
            SeededStatics.fields(SeededNoiseAndCounter.class)), "the counter is still shared");

        SeededStatics.Init init = SeededStatics.init(SeededNoise.class);
        assertNotNull(init);
        SeededNoise feature = new SeededNoise();
        WorldGenLevel world = level(42L);
        assertFalse(init.ready(feature, world), "nothing made yet");
        init.publish(feature, world);
        assertFalse(init.ready(feature, world), "nothing made, so nothing published");
        feature.setSeed(42L);
        assertFalse(init.ready(feature, world), "made but not published");
        init.publish(feature, world);
        assertTrue(init.ready(feature, world), "made for this seed and published");
        assertFalse(init.ready(feature, level(43L)), "another world's seed");
    }

    @Test
    void filledMapIsReadyOnlyAfterItIsFilledAndPublished() {
        SeededStatics.register(FilledOnce.class.getName(), null, List.of("sizes"));
        assertNotNull(FeatureSafetyScan.sharedState(FilledOnce.class), "filling the map counts by itself");
        assertNull(FeatureSafetyScan.sharedState(FilledOnce.class, false, FeatureSafetyScan.MAX_METHODS, false, SeededStatics.fields(FilledOnce.class)),
            "nothing else is shared");
        SeededStatics.Init init = SeededStatics.init(FilledOnce.class);
        assertNotNull(init);
        FilledOnce feature = new FilledOnce();
        WorldGenLevel world = level(5L);
        init.publish(feature, world);
        assertFalse(init.ready(feature, world), "empty, so nothing published");
        FilledOnce.sizes.put("vein", 3);
        assertFalse(init.ready(feature, world), "filled but not published");
        init.publish(feature, world);
        assertTrue(init.ready(feature, world), "filled and published");
        assertTrue(init.ready(feature, level(6L)), "no seed: any world");
    }

    @Test
    void ownFieldNoiseIsReadyOnlyAfterItIsMadeAndPublished() {
        SeededStatics.register(SeededOwnNoise.class.getName(), "seed", List.of("noise"));
        assertNotNull(FeatureSafetyScan.sharedState(SeededOwnNoise.class), "the own field write counts by itself");
        assertNull(FeatureSafetyScan.sharedState(SeededOwnNoise.class, false, FeatureSafetyScan.MAX_METHODS, false, SeededStatics.fields(SeededOwnNoise.class)),
            "nothing else is shared");
        SeededStatics.Init init = SeededStatics.init(SeededOwnNoise.class);
        assertNotNull(init);
        SeededOwnNoise feature = new SeededOwnNoise();
        WorldGenLevel world = level(7L);
        init.publish(feature, world);
        assertFalse(init.ready(feature, world), "nothing made, so nothing published");
        feature.setSeed(7L);
        init.publish(feature, world);
        assertTrue(init.ready(feature, world), "made for this seed and published");
        assertFalse(init.ready(new SeededOwnNoise(), world), "another instance has made nothing");
    }
}
