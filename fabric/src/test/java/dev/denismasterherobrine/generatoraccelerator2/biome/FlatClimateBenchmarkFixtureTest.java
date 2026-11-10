package dev.denismasterherobrine.generatoraccelerator2.biome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks benchmark queries against vanilla and the plain tree walks.
 */
public class FlatClimateBenchmarkFixtureTest {
    /**
     * Checks search results for each input set.
     *
     * @param scenario realistic or absurd
     */
    @ParameterizedTest
    @ValueSource(strings = {"realistic", "absurd"})
    void fixture_matchesVanillaAndColumnWalks(String scenario) {
        assertDoesNotThrow(() -> new FlatClimateWorkload(scenario, "search").validate());
    }

    /**
     * Checks that scattered queries trigger backoff and that invalidated caches can be used again.
     *
     * @param scenario realistic or absurd
     */
    @ParameterizedTest
    @ValueSource(strings = {"realistic", "absurd"})
    void cacheFixtures_activateBackoffAndRebuildWithoutFailure(String scenario) {
        assertDoesNotThrow(() -> new FlatClimateWorkload(scenario, "adaptiveCache").getAsLong());
        assertDoesNotThrow(() -> new FlatClimateWorkload(scenario, "coldCache").getAsLong());
    }
}
