package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferExactnessTest;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/**
 * {@code NoiseChunk.preliminarySurfaceLevel} on the compiled surface level walk ({@link SurfaceLevels}) against vanilla's
 * walk. Columns lie inside the chunk and up to three chunks around it, where the aquifer asks, both quart-aligned and not.
 * They are asked before the fill, between fills and after. So a {@code cache_2d} that the surface level function shares
 * with the final density holds whatever the fill left in it. Covers vanilla presets and two routers with an
 * order-sensitive {@code cache_2d}.
 */
public class SurfaceLevelsTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 5, -3 }, { -123, 77 }, { 1000, 1000 } };

    @Test
    void levelsMatchVanilla() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long programColumns = SurfaceLevels.COLUMNS.sum();
        long compared = 0;
        List<NoiseGeneratorSettings> settingsList = new ArrayList<>();
        for (ResourceKey<NoiseGeneratorSettings> preset : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES,
            NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.FLOATING_ISLANDS)) {
            settingsList.add(lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value());
        }
        NoiseGeneratorSettings overworld = settingsList.get(0);
        settingsList.add(AquiferExactnessTest.withYCache(overworld, true));
        settingsList.add(AquiferExactnessTest.withYCache(overworld, false));
        for (int s = 0; s < settingsList.size(); s++) {
            NoiseGeneratorSettings settings = settingsList.get(s);
            for (long seed : new long[] { 20261005L, -9L }) {
                RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                for (int[] c : CHUNKS) {
                    int cx = c[0];
                    int cz = c[1];
                    Aquifer.FluidPicker picker = FillHarness.vanillaPicker(settings);
                    NoiseChunk[] vanilla = new NoiseChunk[1];
                    DfcHooks.runVanilla(() -> vanilla[0] = FillHarness.chunk(vanillaState, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker));
                    NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker);
                    Random r = new Random(seed ^ cx * 31L ^ cz);
                    String where = "settings " + s + " seed " + seed + " chunk " + cx + "," + cz;
                    for (int phase = 0; phase < 3; phase++) {
                        if (phase == 1) {
                            // the fill evaluates the final density, which may share a cache_2d with the surface level function
                            DfcHooks.runVanilla(() -> FillHarness.fill(vanilla[0], settings, cx, cz, false));
                            FillHarness.fill(fast, settings, cx, cz, false);
                        }
                        for (int q = 0; q < 60; q++) {
                            int x = cx * 16 - 48 + r.nextInt(112);
                            int z = cz * 16 - 16 + r.nextInt(48);
                            if (r.nextBoolean()) {
                                x &= ~3;
                                z &= ~3;
                            }
                            int fx = x;
                            int fz = z;
                            int[] expected = new int[1];
                            DfcHooks.runVanilla(() -> expected[0] = vanilla[0].preliminarySurfaceLevel(fx, fz));
                            assertEquals(expected[0], fast.preliminarySurfaceLevel(x, z), where + " phase " + phase + " column " + x + "," + z);
                            compared++;
                        }
                    }
                }
            }
        }
        assertTrue(SurfaceLevels.COLUMNS.sum() > programColumns, "the compiled walk was never used");
        System.out.println("SurfaceLevelsTest: " + compared + " surface levels identical to vanilla; " + (SurfaceLevels.COLUMNS.sum() - programColumns)
            + " columns computed by the compiled walk");
    }
}
