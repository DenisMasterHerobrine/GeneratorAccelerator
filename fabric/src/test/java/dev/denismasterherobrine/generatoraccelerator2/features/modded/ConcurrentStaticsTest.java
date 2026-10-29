package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link ConcurrentStatics}: a feature that memoizes into a static {@code HashMap} shares state, as Productive Bees' nests do. Once
 * GA2 has made that map concurrent, the scan no longer counts it.
 */
public class ConcurrentStaticsTest {
    /** Memoizes per key in a static map, as {@code SolitaryNest.getSpawningRecipes} does. */
    static final class Memo extends Feature<NoneFeatureConfiguration> {
        static Map<String, List<String>> memo = new HashMap<>();

        Memo() {
            super(NoneFeatureConfiguration.CODEC);
        }

        static List<String> lookup(String key) {
            List<String> found = new ArrayList<>();
            if (!memo.containsKey(key)) {
                found.add(key);
                memo.put(key, found);
            } else {
                found = memo.get(key);
            }
            return found;
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            return !lookup("k").isEmpty();
        }
    }

    @Test
    void madeConcurrentIsNotShared() {
        assertNotNull(FeatureSafetyScan.sharedState(Memo.class), "a HashMap memo is shared state");
        ConcurrentStatics.made(Memo.class.getName().replace('.', '/') + ".memo");
        assertNull(FeatureSafetyScan.sharedState(Memo.class), "the memo is concurrent now");
    }
}
