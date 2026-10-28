package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/**
 * Datapack routers with a {@code cache_2d} in the part of {@code final_density} evaluated per block. Tectonic's lava
 * tunnels have one: a shifted noise whose offsets are noises, none of them reading y. Vanilla's wrapper only remembers the
 * last column, and the cell fill moves to another column at every block. So vanilla evaluates such a child for every
 * block. The cell programs keep one value per column instead. That value is the same when the child reads only x and z.
 * A child that reads y keeps the vanilla wrapper. A child holding a {@code cache_once} keeps the wrapper on the point path,
 * and keeps vanilla's whole cell fill on the array path. Every case must give vanilla's values.
 */
public class Cache2DCellTest {
    private static final String XZ_ONLY = "{\"type\":\"minecraft:cache_2d\",\"argument\":{\"type\":\"minecraft:shifted_noise\",\"noise\":\"minecraft:gravel\","
        + "\"xz_scale\":1.0,\"y_scale\":0.0,"
        + "\"shift_x\":{\"type\":\"minecraft:mul\",\"argument1\":24.0,\"argument2\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:ice\",\"xz_scale\":1.0,\"y_scale\":0.0}},"
        + "\"shift_y\":0.0,"
        + "\"shift_z\":{\"type\":\"minecraft:mul\",\"argument1\":24.0,\"argument2\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:calcite\",\"xz_scale\":1.0,\"y_scale\":0.0}}}}";
    private static final String READS_Y = "{\"type\":\"minecraft:cache_2d\",\"argument\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:gravel\","
        + "\"xz_scale\":2.0,\"y_scale\":1.0}}";
    private static final String HOLDS_CACHE_ONCE = "{\"type\":\"minecraft:cache_2d\",\"argument\":{\"type\":\"minecraft:cache_once\",\"argument\":"
        + "{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:gravel\",\"xz_scale\":2.0,\"y_scale\":0.0}}}";

    @Test
    void xzOnlyCache2DUsesColumnCachesAndMatches() throws Exception {
        RandomState state = router("add", XZ_ONLY);
        assertEquals(1, finalDensityCell(state).columnCaches, "the x/z-only cache_2d should be a column cache");
        assertTrue(finalDensityCell(state).statefulMarkers.isEmpty(), "no wrapper should be left in the cell program");
        compare("add(final_density, cache_2d(shifted_noise of x, z))", state);
    }

    /** Children like the ones a Terralith and Tectonic pack has: a spline over an x/z coordinate, and a sum of x/z noises. */
    @Test
    void xzOnlySplineAndSumChildrenUseColumnCaches() throws Exception {
        String spline = "{\"type\":\"minecraft:cache_2d\",\"argument\":{\"type\":\"minecraft:spline\",\"spline\":{\"coordinate\":"
            + "{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:ice\",\"xz_scale\":1.0,\"y_scale\":0.0},"
            + "\"points\":[{\"location\":-1.0,\"value\":-0.5,\"derivative\":0.0},{\"location\":0.0,\"value\":0.25,\"derivative\":1.0},"
            + "{\"location\":1.0,\"value\":0.5,\"derivative\":0.0}]}}}";
        String sum = "{\"type\":\"minecraft:cache_2d\",\"argument\":{\"type\":\"minecraft:add\",\"argument1\":"
            + "{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:calcite\",\"xz_scale\":1.0,\"y_scale\":0.0},\"argument2\":"
            + "{\"type\":\"minecraft:flat_cache\",\"argument\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:gravel\",\"xz_scale\":1.0,\"y_scale\":0.0}}}}";
        RandomState state = router("add", "{\"type\":\"minecraft:add\",\"argument1\":" + spline + ",\"argument2\":" + sum + "}");
        CellTemplate cell = finalDensityCell(state);
        assertEquals(2, cell.columnCaches, "both x/z-only cache_2d should be column caches; stateful: " + cell.statefulMarkers);
        compare("add(final_density, cache_2d(spline) + cache_2d(noise + flat_cache))", state);
    }

    @Test
    void xzOnlyCache2DOnThePointPathMatches() throws Exception {
        // a mul's second operand: vanilla asks the wrapper with compute (its last-column memo), not with fillArray
        RandomState state = router("mul", XZ_ONLY);
        assertEquals(1, finalDensityCell(state).columnCaches, "the x/z-only cache_2d should be a column cache");
        compare("mul(final_density, cache_2d(shifted_noise of x, z))", state);
    }

    @Test
    void cache2DReadingYKeepsTheWrapperAndMatches() throws Exception {
        RandomState state = lenient(() -> router("add", READS_Y));
        CellTemplate cell = finalDensityCell(state);
        assertEquals(0, cell.columnCaches, "a cache_2d whose child reads y must not be a column cache");
        assertTrue(cell.statefulMarkers.stream().anyMatch(m -> m.type() == Node.MarkerType.CACHE_2D), "the wrapper should stay");
        compare("add(final_density, cache_2d(noise of x, y, z))", state);
    }

    /**
     * Vanilla fills an array-path wrapper as an array, bypassing the wrapper's memo. So the cache_once below gives every block
     * its own value. Asked block by block, the cache_once would give one value per cell. That cell keeps vanilla's fill.
     */
    @Test
    void cache2DHoldingCacheOnceOnTheArrayPathKeepsVanillaFillAndMatches() throws Exception {
        RandomState state = lenient(() -> router("add", HOLDS_CACHE_ONCE));
        ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
        assertNotNull(set);
        assertFalse(set.cells().stream().anyMatch(t -> t.marker == null), "final_density should keep vanilla's cell fill");
        compare("add(final_density, cache_2d(cache_once(noise of x, z)))", state, false);
    }

    /** On the point path vanilla asks the wrapper block by block as well: the program keeps the wrapper. */
    @Test
    void cache2DHoldingCacheOnceOnThePointPathKeepsTheWrapperAndMatches() throws Exception {
        RandomState state = lenient(() -> router("mul", HOLDS_CACHE_ONCE));
        CellTemplate cell = finalDensityCell(state);
        assertEquals(0, cell.columnCaches, "a cache_2d over a cache_once must not be a column cache");
        assertTrue(cell.statefulMarkers.stream().anyMatch(m -> m.type() == Node.MarkerType.CACHE_2D), "the wrapper should stay");
        compare("mul(final_density, cache_2d(cache_once(noise of x, z)))", state);
    }

    private static CellTemplate finalDensityCell(RandomState state) {
        ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
        assertNotNull(set);
        return set.cells().stream().filter(t -> t.marker == null).findFirst()
            .orElseThrow(() -> new AssertionError("final_density should have a cell program"));
    }

    private interface Build {
        RandomState get() throws Exception;
    }

    /** The wrapper a cell program keeps is reported as not ported, which strict mode (on for tests) turns into an error. */
    private static RandomState lenient(Build build) throws Exception {
        String strict = System.getProperty("generatoraccelerator2.dfc.strict");
        System.setProperty("generatoraccelerator2.dfc.strict", "false");
        try {
            return build.get();
        } finally {
            if (strict == null) {
                System.clearProperty("generatoraccelerator2.dfc.strict");
            } else {
                System.setProperty("generatoraccelerator2.dfc.strict", strict);
            }
        }
    }

    private static NoiseGeneratorSettings settings;

    private static RandomState router(String op, String extra) throws Exception {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        JsonObject json;
        try (var in = NoiseGeneratorSettings.class.getResourceAsStream("/data/minecraft/worldgen/noise_settings/overworld.json")) {
            assertNotNull(in);
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject router = json.getAsJsonObject("noise_router");
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("type", "minecraft:" + op);
        wrapped.add("argument1", router.get("final_density"));
        wrapped.add("argument2", JsonParser.parseString(extra));
        router.add("final_density", wrapped);
        settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json).getOrThrow(false, error -> {});
        return RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 4242L);
    }

    private static void compare(String label, RandomState state) {
        compare(label, state, true);
    }

    private static void compare(String label, RandomState state, boolean cellPrograms) {
        long n = ChunkCompare.compareChunk(label, settings, state, 3, -5, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE,
            cellPrograms);
        n += ChunkCompare.compareChunk(label + "+pieces", settings, state, -6, 2, CellProgramTest.beardifier(-96, 32), CellProgramTest.beardifier(-96, 32),
            cellPrograms);
        System.out.println("[Cache2DCellTest] " + label + ": " + n + " values bit-identical to vanilla");
    }
}
