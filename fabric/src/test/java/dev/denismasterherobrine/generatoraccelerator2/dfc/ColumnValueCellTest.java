package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.CellTemplate;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.Emitter;
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
 * Per-block terms of {@code final_density} with parts that read only x and z, as Tectonic's lava tunnels have: noises with
 * {@code y_scale} 0 (plain and shifted, with noise offsets) inside splines whose other coordinate reads y. Vanilla evaluates
 * these parts at every block. The cell programs compute each x/z-only part once per column. Every case must give vanilla's
 * values, including the lazy paths (a multiplication by zero, min and max bounds, range choice arms, spline points).
 */
public class ColumnValueCellTest {
    private static String noise2d(String noise, double xz) {
        return "{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:" + noise + "\",\"xz_scale\":" + xz + ",\"y_scale\":0.0}";
    }

    /** {@code tectonic:__constants/above_bottom_y}-like: the height above the bottom of the world. */
    private static final String ABOVE_BOTTOM = "{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-64,\"to_y\":0,\"from_value\":0.0,\"to_value\":64.0}";

    /** {@code tectonic:lava_tunnel/ridges}-like: x/z noises around a spline over the height. */
    private static final String RIDGES = "{\"type\":\"minecraft:add\","
        + "\"argument1\":{\"type\":\"minecraft:add\",\"argument1\":-0.3,\"argument2\":{\"type\":\"minecraft:mul\",\"argument1\":0.5,\"argument2\":" + noise2d("gravel", 1.0) + "}},"
        + "\"argument2\":{\"type\":\"minecraft:add\","
        + "\"argument1\":{\"type\":\"minecraft:mul\",\"argument1\":{\"type\":\"minecraft:spline\",\"spline\":{\"coordinate\":" + ABOVE_BOTTOM + ",\"points\":["
        + "{\"location\":4.0,\"value\":0.5,\"derivative\":0.0},{\"location\":4.0,\"value\":0.5,\"derivative\":-0.044},"
        + "{\"location\":16.0,\"value\":0.0,\"derivative\":-0.044},{\"location\":16.0,\"value\":0.0,\"derivative\":0.0}]}},"
        + "\"argument2\":{\"type\":\"minecraft:add\",\"argument1\":0.8,\"argument2\":" + noise2d("ice", 4.0) + "}},"
        + "\"argument2\":{\"type\":\"minecraft:mul\",\"argument1\":-0.5,\"argument2\":{\"type\":\"minecraft:abs\",\"argument\":"
        + "{\"type\":\"minecraft:shifted_noise\",\"noise\":\"minecraft:calcite\",\"xz_scale\":1.0,\"y_scale\":0.0,"
        + "\"shift_x\":{\"type\":\"minecraft:mul\",\"argument1\":24.0,\"argument2\":" + noise2d("jagged", 1.0) + "},"
        + "\"shift_y\":0.0,"
        + "\"shift_z\":{\"type\":\"minecraft:mul\",\"argument1\":24.0,\"argument2\":" + noise2d("pillar", 1.0) + "}}}}}}";

    private static String ridgeSpline(double scale) {
        return "{\"coordinate\":" + RIDGES + ",\"points\":[{\"location\":-1.0,\"value\":0.0,\"derivative\":0.0},"
            + "{\"location\":-0.1,\"value\":" + (-scale) + ",\"derivative\":0.0},{\"location\":0.0,\"value\":0.0,\"derivative\":1.0},"
            + "{\"location\":0.4,\"value\":" + (-0.5 * scale) + ",\"derivative\":0.0}]}";
    }

    /** {@code tectonic:lava_tunnel/total}-like: a spline over the height whose points are splines over the ridges. */
    private static final String LAVA_TUNNEL = "{\"type\":\"minecraft:mul\",\"argument1\":4.0,\"argument2\":{\"type\":\"minecraft:min\",\"argument1\":0.0,"
        + "\"argument2\":{\"type\":\"minecraft:spline\",\"spline\":{\"coordinate\":" + ABOVE_BOTTOM + ",\"points\":["
        + "{\"location\":6.0,\"value\":0.0,\"derivative\":0.0},"
        + "{\"location\":12.0,\"value\":" + ridgeSpline(1.0) + ",\"derivative\":0.0},"
        + "{\"location\":18.0,\"value\":" + ridgeSpline(2.0) + ",\"derivative\":0.0}]}}}}";

    @Test
    void lavaTunnelShapedTermUsesColumnValuesAndMatches() throws Exception {
        RandomState state = router("add", LAVA_TUNNEL);
        CellTemplate cell = finalDensityCell(state);
        assertTrue(cell.columnValues >= 3, "the x/z-only parts of the ridges should be column values, got " + cell.columnValues);
        compare("add(final_density, lava tunnel)", state);
    }

    @Test
    void lavaTunnelOnTheLazyOperandMatches() throws Exception {
        // a multiplication's second operand: vanilla evaluates it only where final_density is not zero
        RandomState state = router("mul", LAVA_TUNNEL);
        assertTrue(finalDensityCell(state).columnValues >= 3);
        compare("mul(final_density, lava tunnel)", state);
    }

    /** A spline evaluated per block (its points read y) whose coordinate reads only x and z: the coordinate is a column value. */
    @Test
    void columnValueAsTheCoordinateOfAPerBlockSplineMatches() throws Exception {
        String perY = "{\"coordinate\":" + ABOVE_BOTTOM + ",\"points\":[{\"location\":0.0,\"value\":-0.2,\"derivative\":0.1},"
            + "{\"location\":40.0,\"value\":0.3,\"derivative\":0.0}]}";
        String spline = "{\"type\":\"minecraft:spline\",\"spline\":{\"coordinate\":{\"type\":\"minecraft:add\",\"argument1\":" + noise2d("gravel", 2.0)
            + ",\"argument2\":" + noise2d("ice", 0.5) + "},\"points\":[{\"location\":-0.5,\"value\":" + perY + ",\"derivative\":0.0},"
            + "{\"location\":0.0,\"value\":0.1,\"derivative\":0.5},{\"location\":0.5,\"value\":" + perY + ",\"derivative\":-0.3}]}}";
        RandomState state = router("add", spline);
        assertTrue(finalDensityCell(state).columnValues >= 1, "the spline's x/z coordinate should be a column value");
        compare("add(final_density, spline(x/z coordinate) with points over y)", state);
    }

    /** Lazy paths: a multiplication by a height gradient that is zero over part of the world, a range choice and min/max bounds. */
    @Test
    void columnValuesOnLazyPathsMatch() throws Exception {
        String gradient = "{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":0,\"to_y\":120,\"from_value\":0.0,\"to_value\":1.0}";
        String term = "{\"type\":\"minecraft:add\","
            + "\"argument1\":{\"type\":\"minecraft:range_choice\",\"input\":" + gradient + ",\"min_inclusive\":0.25,\"max_exclusive\":0.75,"
            + "\"when_in_range\":" + noise2d("gravel", 1.0) + ",\"when_out_of_range\":{\"type\":\"minecraft:mul\",\"argument1\":" + gradient
            + ",\"argument2\":" + noise2d("ice", 2.0) + "}},"
            + "\"argument2\":{\"type\":\"minecraft:max\",\"argument1\":{\"type\":\"minecraft:min\",\"argument1\":" + gradient + ",\"argument2\":"
            + noise2d("calcite", 1.0) + "},\"argument2\":" + noise2d("jagged", 3.0) + "}}";
        RandomState state = router("add", term);
        assertTrue(finalDensityCell(state).columnValues >= 3);
        compare("add(final_density, lazy paths over x/z noises)", state);
    }

    /** The same x/z noise reached at every block and inside a larger x/z part: both are column values, computed once per column. */
    @Test
    void sharedColumnValuesMatch() throws Exception {
        String gradient = "{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-64,\"to_y\":320,\"from_value\":-1.0,\"to_value\":1.0}";
        String gravel = noise2d("gravel", 1.0);
        String term = "{\"type\":\"minecraft:add\",\"argument1\":{\"type\":\"minecraft:mul\",\"argument1\":" + gradient + ",\"argument2\":" + gravel + "},"
            + "\"argument2\":{\"type\":\"minecraft:mul\",\"argument1\":" + gradient + ",\"argument2\":{\"type\":\"minecraft:add\",\"argument1\":" + gravel
            + ",\"argument2\":{\"type\":\"minecraft:flat_cache\",\"argument\":" + noise2d("ice", 1.0) + "}}}}";
        RandomState state = router("add", term);
        assertTrue(finalDensityCell(state).columnValues >= 2);
        compare("add(final_density, shared x/z noise)", state);
    }

    /** On vanilla's array path a cache_once gives every block its child's own value: the child can be a column value. */
    @Test
    void columnValueBelowAnArrayPathCacheOnceMatches() throws Exception {
        String term = "{\"type\":\"minecraft:cache_once\",\"argument\":" + noise2d("gravel", 1.0) + "}";
        RandomState state = router("add", term);
        assertTrue(finalDensityCell(state).columnValues >= 1);
        compare("add(final_density, cache_once(x/z noise))", state);
    }

    @Test
    void termsReadingYStayPerBlockAndMatch() throws Exception {
        RandomState state = router("add", "{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:gravel\",\"xz_scale\":1.0,\"y_scale\":1.0}");
        assertEquals(0, finalDensityCell(state).columnValues, "a noise reading y must stay per block");
        compare("add(final_density, noise of x, y, z)", state);
    }

    @Test
    void switchedOffGivesTheSameValues() throws Exception {
        Emitter.COLUMN_VALUES = false;
        RandomState state;
        try {
            state = router("add", LAVA_TUNNEL);
        } finally {
            Emitter.COLUMN_VALUES = true;
        }
        assertEquals(0, finalDensityCell(state).columnValues);
        compare("add(final_density, lava tunnel), column values off", state);
    }

    private static CellTemplate finalDensityCell(RandomState state) {
        ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
        assertNotNull(set);
        return set.cells().stream().filter(t -> t.marker == null).findFirst()
            .orElseThrow(() -> new AssertionError("final_density should have a cell program"));
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
        long n = ChunkCompare.compareChunk(label, settings, state, 3, -5, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE);
        n += ChunkCompare.compareChunk(label + "+pieces", settings, state, -6, 2, CellProgramTest.beardifier(-96, 32), CellProgramTest.beardifier(-96, 32));
        n += ChunkCompare.compareChunk(label + " far", settings, state, 1250, -3100, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE);
        System.out.println("[ColumnValueCellTest] " + label + ": " + n + " values bit-identical to vanilla");
    }
}
