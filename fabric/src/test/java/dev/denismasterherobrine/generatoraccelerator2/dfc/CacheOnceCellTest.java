package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
 * Datapack routers with a {@code cache_once} outside {@code interpolated}, in the part of {@code final_density} evaluated
 * per block. Vanilla's cell fill gives a {@code cache_once} reached through {@code fillArray} (an {@code add} operand) each
 * block's own value. A {@code cache_once} reached through {@code compute} (a {@code mul}'s second operand) gives the first
 * value it computed for the whole cell. The cell programs compile the first kind inline and leave cells with the second
 * kind to vanilla. Both must give vanilla's blocks.
 */
public class CacheOnceCellTest {
    private static final String CACHE_ONCE_NOISE = "{\"type\":\"minecraft:cache_once\",\"argument\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:gravel\","
        + "\"xz_scale\":3.0,\"y_scale\":3.0}}";

    @Test
    void arrayPathCacheOnceCompilesAndMatches() throws Exception {
        RandomState state = router("add");
        ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
        assertNotNull(set);
        assertTrue(set.cells().stream().anyMatch(t -> t.marker == null), "final_density should have a cell program (its cache_once is filled as an array)");
        compare("add(final_density, cache_once(noise))", state, true);
    }

    @Test
    void pointPathCacheOnceStaysOnVanillaAndMatches() throws Exception {
        // the cell left to vanilla is reported as a fallback, which strict mode (on for tests) turns into an error
        String strict = System.getProperty("generatoraccelerator2.dfc.strict");
        System.setProperty("generatoraccelerator2.dfc.strict", "false");
        RandomState state;
        try {
            state = router("mul");
        } finally {
            if (strict == null) {
                System.clearProperty("generatoraccelerator2.dfc.strict");
            } else {
                System.setProperty("generatoraccelerator2.dfc.strict", strict);
            }
        }
        ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
        assertNotNull(set);
        assertFalse(set.cells().stream().anyMatch(t -> t.marker == null), "final_density has a point-path cache_once and should keep vanilla's cell fill");
        compare("mul(final_density, cache_once(noise))", state, false);
    }

    private static NoiseGeneratorSettings settings;

    private static RandomState router(String op) throws Exception {
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
        wrapped.add("argument2", JsonParser.parseString(CACHE_ONCE_NOISE));
        router.add("final_density", wrapped);
        settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json).getOrThrow(false, error -> {});
        return RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 4242L);
    }

    private static void compare(String label, RandomState state, boolean cellPrograms) {
        long n = ChunkCompare.compareChunk(label, settings, state, 3, -5, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE,
            cellPrograms);
        n += ChunkCompare.compareChunk(label + "+pieces", settings, state, -6, 2, CellProgramTest.beardifier(-96, 32), CellProgramTest.beardifier(-96, 32),
            cellPrograms);
        System.out.println("[CacheOnceCellTest] " + label + ": " + n + " values bit-identical to vanilla");
    }
}
