package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/** The IR + reference interpreter must reproduce every vanilla router field bit for bit, on every built-in preset. */
public class DfcExactnessTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES,
        NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.CAVES, NoiseGeneratorSettings.FLOATING_ISLANDS);
    private static final long[] SEEDS = { 1234567L, -42L, 0x5DEECE66DL };
    private static final int POINTS = 4000;

    @Test
    void interpreterMatchesVanillaOnAllPresets() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        Random rng = new Random(0xF00DL);
        long comparisons = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            for (long seed : SEEDS) {
                NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
                RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
                assertNotNull(set, "RandomState mixin did not compile " + preset.location());
                NoiseRouter router = state.router();
                Climate.Sampler sampler = state.sampler();
                Map<String, DensityFunction> vanilla = Map.ofEntries(
                    Map.entry("barrier", router.barrierNoise()),
                    Map.entry("fluid_level_floodedness", router.fluidLevelFloodednessNoise()),
                    Map.entry("fluid_level_spread", router.fluidLevelSpreadNoise()),
                    Map.entry("lava", router.lavaNoise()),
                    Map.entry("temperature", router.temperature()),
                    Map.entry("vegetation", router.vegetation()),
                    Map.entry("continents", router.continents()),
                    Map.entry("erosion", router.erosion()),
                    Map.entry("depth", router.depth()),
                    Map.entry("ridges", router.ridges()),
                    Map.entry("initial_density_without_jaggedness", router.initialDensityWithoutJaggedness()),
                    Map.entry("final_density", router.finalDensity()),
                    Map.entry("vein_toggle", router.veinToggle()),
                    Map.entry("vein_ridged", router.veinRidged()),
                    Map.entry("vein_gap", router.veinGap()),
                    Map.entry("sampler.temperature", sampler.temperature()),
                    Map.entry("sampler.humidity", sampler.humidity()),
                    Map.entry("sampler.continentalness", sampler.continentalness()),
                    Map.entry("sampler.erosion", sampler.erosion()),
                    Map.entry("sampler.depth", sampler.depth()),
                    Map.entry("sampler.weirdness", sampler.weirdness()));
                for (Map.Entry<String, DensityFunction> e : vanilla.entrySet()) {
                    Node root = set.root(e.getKey());
                    assertNotNull(root, "no IR root for " + e.getKey());
                    for (int i = 0; i < POINTS; i++) {
                        int x, y, z;
                        switch (rng.nextInt(4)) {
                            case 0 -> { x = rng.nextInt(60_000_000) - 30_000_000; z = rng.nextInt(60_000_000) - 30_000_000; }
                            case 1 -> { x = (rng.nextInt(2000) - 1000) * 4; z = (rng.nextInt(2000) - 1000) * 4; }
                            default -> { x = rng.nextInt(8192) - 4096; z = rng.nextInt(8192) - 4096; }
                        }
                        y = rng.nextInt(3) == 0 ? -64 + rng.nextInt(49) * 8 : rng.nextInt(400) - 70;
                        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
                        double expected = e.getValue().compute(ctx);
                        double actual = set.interpreter().eval(root, ctx);
                        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                            throw new AssertionError(preset.location() + " seed " + seed + " field " + e.getKey() + " at (" + x + "," + y + "," + z + "): vanilla=" + expected + " dfc=" + actual);
                        }
                        comparisons++;
                    }
                }
                for (CoverageReport.Entry entry : set.report().entries()) {
                    boolean endIslandsPending = entry.kind == CoverageReport.Kind.PENDING_PORT;
                    assertTrue(endIslandsPending, "unexpected coverage entry on " + preset.location() + ": " + entry.line());
                }
            }
        }
        System.out.println("DFC exactness: " + comparisons + " comparisons bit-identical across " + PRESETS.size() + " presets x " + SEEDS.length + " seeds");
        assertEquals(PRESETS.size() * SEEDS.length * 21L * POINTS, comparisons);
    }
}
