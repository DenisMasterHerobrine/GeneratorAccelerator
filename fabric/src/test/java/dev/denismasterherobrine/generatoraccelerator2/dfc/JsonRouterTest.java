package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/**
 * The real game decodes noise settings from datapack JSON, which produces a different object shape
 * than the Java-built registries used by the other tests (constants wrapped in holders, so
 * add/mul stay two-argument nodes until NoiseChunk wraps and folds them). This test runs the whole
 * chain on JSON-decoded settings.
 */
public class JsonRouterTest {
    private static final String[] FILES = { "overworld", "nether", "end", "caves", "amplified" };

    @Test
    void jsonDecodedRoutersCompileAndMatch() throws Exception {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        Random rng = new Random(99L);
        long compared = 0;
        for (String file : FILES) {
            String path = "/data/minecraft/worldgen/noise_settings/" + file + ".json";
            JsonElement json;
            try (var in = NoiseGeneratorSettings.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing " + path);
                json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json).getOrThrow(false, error -> {});
            assertTrue(hasHolderWrappedConstant(settings.noiseRouter().finalDensity()) || !file.equals("overworld"),
                "expected the JSON-decoded overworld router to contain holder-wrapped constants");
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 31337L);
            ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
            assertNotNull(set);

            // Point-level exactness against the JSON-decoded objects.
            NoiseRouter router = state.router();
            Map<String, DensityFunction> fields = Map.of("final_density", router.finalDensity(), "initial_density_without_jaggedness", router.initialDensityWithoutJaggedness(),
                "vein_toggle", router.veinToggle(), "vein_ridged", router.veinRidged(), "depth", router.depth());
            for (Map.Entry<String, DensityFunction> e : fields.entrySet()) {
                Node root = set.root(e.getKey());
                for (int i = 0; i < 2000; i++) {
                    int x = rng.nextInt(8192) - 4096, z = rng.nextInt(8192) - 4096, y = rng.nextInt(400) - 70;
                    DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
                    double expected = e.getValue().compute(ctx);
                    double actual = set.interpreter().eval(root, ctx);
                    if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                        throw new AssertionError(file + " " + e.getKey() + " at (" + x + "," + y + "," + z + "): vanilla=" + expected + " dfc=" + actual);
                    }
                    compared++;
                }
            }
            // Chunk-level: corners and cells, with and without structure pieces.
            compared += ChunkCompare.compareChunk(file + " json", settings, state, 7, -9, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE);
            compared += ChunkCompare.compareChunk(file + " json+pieces", settings, state, 7, -9, CellProgramTest.beardifier(112, -144), CellProgramTest.beardifier(112, -144));
        }
        System.out.println("DFC json routers: " + compared + " values bit-identical to vanilla");
    }

    private static boolean hasHolderWrappedConstant(DensityFunction fn) {
        if (fn instanceof DensityFunctions.HolderHolder h) {
            return h.function().value() instanceof DensityFunctions.Constant || hasHolderWrappedConstant(h.function().value());
        }
        if (fn instanceof DensityFunctions.Ap2 a) {
            return hasHolderWrappedConstant(a.argument1()) || hasHolderWrappedConstant(a.argument2());
        }
        if (fn instanceof DensityFunctions.Mapped m) {
            return hasHolderWrappedConstant(m.input());
        }
        if (fn instanceof DensityFunctions.MarkerOrMarked m) {
            return hasHolderWrappedConstant(m.wrapped());
        }
        if (fn instanceof DensityFunctions.BlendDensity b) {
            return hasHolderWrappedConstant(b.input());
        }
        return false;
    }
}
