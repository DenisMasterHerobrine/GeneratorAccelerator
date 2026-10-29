package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/**
 * Every block doFill would place, and the fluid-update flag after every block, must match vanilla
 * with the aquifer fast path and the beardifier index on: with the compiled noise path, with the
 * NoiseChunk hooks off (the compatibility setup), and with classification disabled. Carver-style
 * point queries and out-of-range queries are compared afterwards on the same aquifers.
 */
public class AquiferExactnessTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES, NoiseGeneratorSettings.CAVES,
        NoiseGeneratorSettings.FLOATING_ISLANDS, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END);
    private static final int[][] CHUNKS = { { 0, 0 }, { 5, -3 }, { -7, 9 }, { 40, -60 }, { -123, 77 }, { 1000, 1000 }, { -31250, 12345 } };
    private static final long[] SEEDS = { 20261002L, 42L };

    enum Variant { COMPILED, HOOKS_OFF, SEARCH_ONLY }

    @Test
    void fillMatchesVanilla() throws Exception {
        assertTrue(MixinTargets.recorded(), "the mixin plugin did not report other configs' targets");
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long blocks = 0;
        long queries = 0;
        int[] modeSeen = new int[4];
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            for (long seed : SEEDS) {
                for (int c = 0; c < CHUNKS.length; c++) {
                    int cx = CHUNKS[c][0];
                    int cz = CHUNKS[c][1];
                    Variant variant = Variant.values()[(c + (int) seed) % 3];
                    boolean withPieces = c % 3 == 0;
                    Aquifer.FluidPicker picker = c % 2 == 0 ? FillHarness.vanillaPicker(settings) : FillHarness.allocatingPicker(settings);
                    String where = preset.identifier() + " seed " + seed + " chunk " + cx + "," + cz + " " + variant + (withPieces ? " with pieces" : "");

                    RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    NoiseChunk[] vanilla = new NoiseChunk[1];
                    FillHarness.Fill[] expected = new FillHarness.Fill[1];
                    DfcHooks.runVanilla(() -> {
                        DensityFunctions.BeardifierOrMarker beard = withPieces ? FillHarness.village(cx, cz, settings.seaLevel() + 4, seed)
                            : DensityFunctions.BeardifierMarker.INSTANCE;
                        vanilla[0] = FillHarness.chunk(vanillaState, settings, cx, cz, beard, picker);
                        expected[0] = FillHarness.fill(vanilla[0], settings, cx, cz, true);
                    });

                    RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    if (variant == Variant.SEARCH_ONLY) {
                        AquiferFastPath.registerRandomState(state.aquiferRandom(), false);
                    }
                    DensityFunctions.BeardifierOrMarker beard = withPieces ? FillHarness.village(cx, cz, settings.seaLevel() + 4, seed)
                        : DensityFunctions.BeardifierMarker.INSTANCE;
                    NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, beard, picker);
                    int mode = aquiferMode(fast.aquifer());
                    if (fast.aquifer() instanceof Aquifer.NoiseBasedAquifer) {
                        assertTrue(mode != AquiferFastPath.OFF, where + ": fast path not active");
                        if (variant == Variant.SEARCH_ONLY) {
                            assertEquals(AquiferFastPath.SEARCH, mode, where);
                        }
                    }
                    modeSeen[mode]++;
                    FillHarness.Fill[] actual = new FillHarness.Fill[1];
                    if (variant == Variant.HOOKS_OFF) {
                        DfcHooks.runVanilla(() -> actual[0] = FillHarness.fill(fast, settings, cx, cz, true));
                    } else {
                        actual[0] = FillHarness.fill(fast, settings, cx, cz, true);
                    }
                    blocks += compare(where, settings, expected[0], actual[0]);
                    queries += compareQueries(where, settings, cx, cz, vanilla[0].aquifer(), fast.aquifer(), seed ^ c);
                }
            }
        }
        assertTrue(modeSeen[AquiferFastPath.CLASSIFY] > 0 && modeSeen[AquiferFastPath.SEARCH] > 0, "both aquifer modes must be exercised");
        System.out.println("AquiferExactnessTest: " + blocks + " blocks and " + queries + " point queries identical to vanilla");
    }

    /**
     * A router whose surface level function reads a {@code cache_2d} over a y gradient that the final density shares. There
     * is one Cache2D per chunk, so the surface levels depend on what the fill evaluated last. The aquifer classifies a
     * neighbourhood only once the surface levels read by its missing statuses are memoised. Every block must still match
     * vanilla.
     */
    @Test
    void orderSensitiveSurfaceLevelsClassifyAhead() throws Exception {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long blocks = 0;
        long aheadBefore = AquiferFastPath.AHEAD_CLASSIFIED.sum();
        for (ResourceKey<NoiseGeneratorSettings> preset : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES)) {
            NoiseGeneratorSettings settings = withSharedYCache(lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value());
            for (long seed : SEEDS) {
                for (int c = 0; c < CHUNKS.length; c++) {
                    int cx = CHUNKS[c][0];
                    int cz = CHUNKS[c][1];
                    boolean withPieces = c % 3 == 1;
                    Aquifer.FluidPicker picker = FillHarness.vanillaPicker(settings);
                    String where = preset.identifier() + " (shared y cache) seed " + seed + " chunk " + cx + "," + cz + (withPieces ? " with pieces" : "");
                    RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    NoiseChunk[] vanilla = new NoiseChunk[1];
                    FillHarness.Fill[] expected = new FillHarness.Fill[1];
                    DfcHooks.runVanilla(() -> {
                        DensityFunctions.BeardifierOrMarker beard = withPieces ? FillHarness.village(cx, cz, settings.seaLevel() + 4, seed)
                            : DensityFunctions.BeardifierMarker.INSTANCE;
                        vanilla[0] = FillHarness.chunk(vanillaState, settings, cx, cz, beard, picker);
                        expected[0] = FillHarness.fill(vanilla[0], settings, cx, cz, true);
                    });
                    RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    DensityFunctions.BeardifierOrMarker beard = withPieces ? FillHarness.village(cx, cz, settings.seaLevel() + 4, seed)
                        : DensityFunctions.BeardifierMarker.INSTANCE;
                    NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, beard, picker);
                    assertEquals(AquiferFastPath.COLUMNS, aquiferMode(fast.aquifer()), where);
                    blocks += compare(where, settings, expected[0], FillHarness.fill(fast, settings, cx, cz, true));
                    blocks += compareQueries(where, settings, cx, cz, vanilla[0].aquifer(), fast.aquifer(), seed ^ c);
                }
            }
        }
        assertTrue(AquiferFastPath.AHEAD_CLASSIFIED.sum() > aheadBefore, "no neighbourhood was classified ahead");
        System.out.println("AquiferExactnessTest: " + blocks + " blocks and queries identical to vanilla with order-sensitive surface levels ("
            + (AquiferFastPath.AHEAD_CLASSIFIED.sum() - aheadBefore) + " neighbourhoods classified ahead)");
    }

    /**
     * Regions Unexplored 0.6's floodedness: {@code range_choice(flat_cache(cache_2d(f)), 0.001, 64, 1, floodedness)} with {@code f}
     * a weight below y = 0 and 0 above. The chunk's Cache2D answers the aquifer's cell centres in vanilla's order. The aquifer
     * classifies ahead only where no two of its cells share a block column with different weights at their heights. Elsewhere
     * it keeps vanilla's order. Weights that are positive in a few columns or in most columns give both outcomes. Every block
     * and query must match vanilla either way.
     */
    @Test
    void floodednessColumnCachesAreCheckedPerChunk() throws Exception {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long blocks = 0;
        long checkedBefore = AquiferFastPath.CELLS_CHECKED.sum();
        long conflictBefore = AquiferFastPath.CELLS_CONFLICT.sum();
        int[] modeSeen = new int[4];
        for (double threshold : new double[] { 0.45, -0.3 }) {
            NoiseGeneratorSettings settings = withColumnCachedFloodedness(
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(), lookup, threshold);
            for (long seed : SEEDS) {
                for (int c = 0; c < CHUNKS.length; c++) {
                    int cx = CHUNKS[c][0];
                    int cz = CHUNKS[c][1];
                    Aquifer.FluidPicker picker = FillHarness.vanillaPicker(settings);
                    String where = "column-cached floodedness (threshold " + threshold + ") seed " + seed + " chunk " + cx + "," + cz;
                    RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    NoiseChunk[] vanilla = new NoiseChunk[1];
                    FillHarness.Fill[] expected = new FillHarness.Fill[1];
                    DfcHooks.runVanilla(() -> {
                        vanilla[0] = FillHarness.chunk(vanillaState, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker);
                        expected[0] = FillHarness.fill(vanilla[0], settings, cx, cz, true);
                    });
                    RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
                    NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker);
                    modeSeen[aquiferMode(fast.aquifer())]++;
                    blocks += compare(where, settings, expected[0], FillHarness.fill(fast, settings, cx, cz, true));
                    blocks += compareQueries(where, settings, cx, cz, vanilla[0].aquifer(), fast.aquifer(), seed ^ c);
                }
            }
        }
        long checked = AquiferFastPath.CELLS_CHECKED.sum() - checkedBefore;
        long conflicts = AquiferFastPath.CELLS_CONFLICT.sum() - conflictBefore;
        assertTrue(checked > 0, "no aquifer checked its cells");
        assertTrue(modeSeen[AquiferFastPath.CLASSIFY] > 0 && modeSeen[AquiferFastPath.SEARCH] > 0 && conflicts > 0,
            "both outcomes of the check must be exercised: modes " + Arrays.toString(modeSeen) + ", conflicts " + conflicts);
        System.out.println("AquiferExactnessTest: " + blocks + " blocks and queries identical to vanilla with a column-cached floodedness ("
            + checked + " aquifers checked, " + conflicts + " kept vanilla's order)");
    }

    /** The preset's floodedness wrapped as Regions Unexplored 0.6 does, with a weight that is positive where an xz noise exceeds {@code threshold}. */
    static NoiseGeneratorSettings withColumnCachedFloodedness(NoiseGeneratorSettings s, HolderLookup.Provider lookup, double threshold) {
        NoiseRouter r = s.noiseRouter();
        DensityFunction weight = DensityFunctions.add(DensityFunctions.constant(-threshold), DensityFunctions.noise(
            lookup.lookupOrThrow(Registries.NOISE).getOrThrow(Noises.SURFACE), 1.0, 0.0));
        DensityFunction below = DensityFunctions.rangeChoice(DensityFunctions.yClampedGradient(-4064, 4062, -4064.0, 4062.0), -4064.0, 0.0,
            DensityFunctions.mul(DensityFunctions.constant(5.0), weight), DensityFunctions.constant(0.0));
        DensityFunction inferno = DensityFunctions.flatCache(DensityFunctions.cache2d(below.clamp(0.0, 1.0)));
        DensityFunction flood = DensityFunctions.rangeChoice(inferno, 0.001, 64.0, DensityFunctions.constant(1.0), r.fluidLevelFloodednessNoise());
        NoiseRouter router = new NoiseRouter(r.barrierNoise(),
            flood, r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(),
            r.depth(), r.ridges(), r.preliminarySurfaceLevel(), r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), router, s.surfaceRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }

    /** The preset with the same y-reading {@code cache_2d} added to its surface level function and (weakly) to its final density. */
    public static NoiseGeneratorSettings withSharedYCache(NoiseGeneratorSettings s) {
        return withYCache(s, true);
    }

    /** The y-reading {@code cache_2d} in the surface level function, and in the final density too when {@code shared}. */
    public static NoiseGeneratorSettings withYCache(NoiseGeneratorSettings s, boolean shared) {
        NoiseRouter r = s.noiseRouter();
        // equal markers: NoiseChunk.wrap gives both the same Cache2D, so the fill and the surface levels share it
        // in the walked density of the preset's find_top_surface
        SurfaceLevels.Top top = SurfaceLevels.Top.of(r.preliminarySurfaceLevel());
        DensityFunction initial = DensityFunctions.findTopSurface(DensityFunctions.add(top.density(),
            DensityFunctions.mul(DensityFunctions.constant(0.25), DensityFunctions.cache2d(DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0)))),
            top.upperBound(), top.lowerBound(), top.cellHeight());
        DensityFunction fin = !shared ? r.finalDensity() : DensityFunctions.add(r.finalDensity(),
            DensityFunctions.mul(DensityFunctions.constant(0.02), DensityFunctions.cache2d(DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0))));
        NoiseRouter router = new NoiseRouter(r.barrierNoise(),
            r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(),
            r.depth(), r.ridges(), initial, fin, r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), router, s.surfaceRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }

    private static long compare(String where, NoiseGeneratorSettings settings, FillHarness.Fill expected, FillHarness.Fill actual) {
        int minY = settings.noiseSettings().minY();
        long n = 0;
        for (int i = 0; i < expected.states.length; i++) {
            if (expected.states[i] == null) {
                continue;
            }
            if (expected.states[i] != actual.states[i] || expected.flags[i] != actual.flags[i]) {
                int lx = i & 15;
                int lz = (i >> 4) & 15;
                int ly = (i >> 8) + minY;
                throw new AssertionError(where + " block " + lx + "," + ly + "," + lz + ": vanilla " + expected.states[i] + " flag " + expected.flags[i]
                    + ", fast " + actual.states[i] + " flag " + actual.flags[i]);
            }
            n++;
        }
        return n;
    }

    /**
     * Neighbouring chunks of one RandomState share preliminary surface levels. Every chunk must still
     * place exactly what vanilla places, filled in an order where most levels come from neighbours.
     */
    @Test
    void neighbouringChunksShareSurfaceLevels() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        long blocks = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES)) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            Aquifer.FluidPicker picker = FillHarness.vanillaPicker(settings);
            RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 99L);
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 99L);
            long hitsBefore = SurfaceLevelCache.HITS.sum();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    int cx = -2 + (i * 3) % 4;
                    int cz = 7 + (j * 3) % 4;
                    String where = preset.identifier() + " chunk " + cx + "," + cz + " (shared surface levels)";
                    FillHarness.Fill[] expected = new FillHarness.Fill[1];
                    DfcHooks.runVanilla(() -> expected[0] = FillHarness.fill(
                        FillHarness.chunk(vanillaState, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker), settings, cx, cz, true));
                    NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker);
                    blocks += compare(where, settings, expected[0], FillHarness.fill(fast, settings, cx, cz, true));
                    blocks += compareQueries(where, settings, cx, cz, vanillaAquifer(vanillaState, settings, cx, cz, picker),
                        fast.aquifer(), cx * 31L + cz);
                }
            }
            assertTrue(SurfaceLevelCache.HITS.sum() > hitsBefore, preset.identifier() + ": no surface level was shared");
        }
        System.out.println("AquiferExactnessTest: " + blocks + " blocks and queries identical to vanilla with shared surface levels");
    }

    private static Aquifer vanillaAquifer(RandomState state, NoiseGeneratorSettings settings, int cx, int cz, Aquifer.FluidPicker picker) {
        NoiseChunk[] chunk = new NoiseChunk[1];
        DfcHooks.runVanilla(() -> chunk[0] = FillHarness.chunk(state, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker));
        return chunk[0].aquifer();
    }

    /**
     * A query outside the chunk's aquifer cell grid runs vanilla's own code, which throws or reads an
     * aliased cache slot. Checked on fresh aquifers, where both instances hold identical caches.
     */
    @Test
    void outOfGridQueriesRunVanilla() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        int minY = settings.noiseSettings().minY();
        int height = settings.noiseSettings().height();
        Random r = new Random(5);
        int compared = 0;
        for (int q = 0; q < 60; q++) {
            int cx = r.nextInt(200) - 100;
            int cz = r.nextInt(200) - 100;
            RandomState vanillaState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), q);
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), q);
            Aquifer.FluidPicker picker = FillHarness.vanillaPicker(settings);
            NoiseChunk[] vanilla = new NoiseChunk[1];
            DfcHooks.runVanilla(() -> vanilla[0] = FillHarness.chunk(vanillaState, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker));
            NoiseChunk fast = FillHarness.chunk(state, settings, cx, cz, DensityFunctions.BeardifierMarker.INSTANCE, picker);
            int x = cx * 16 + switch (q % 4) { case 0 -> -12 - r.nextInt(20); case 1 -> 28 + r.nextInt(40); default -> r.nextInt(16); };
            int z = cz * 16 + (q % 4 == 2 ? -14 - r.nextInt(30) : r.nextInt(16));
            int y = q % 4 == 3 ? (r.nextBoolean() ? minY - 13 - r.nextInt(30) : minY + height + 13 + r.nextInt(30)) : minY + r.nextInt(height);
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            Object[] expected = new Object[2];
            DfcHooks.runVanilla(() -> outcome(vanilla[0].aquifer(), ctx, -0.1, expected));
            Object[] actual = new Object[2];
            outcome(fast.aquifer(), ctx, -0.1, actual);
            assertTrue(same(expected, actual), "out-of-grid query " + x + "," + y + "," + z + ": vanilla " + expected[0] + "/" + expected[1]
                + ", fast " + actual[0] + "/" + actual[1]);
            compared++;
        }
        System.out.println("AquiferExactnessTest: " + compared + " out-of-grid queries identical to vanilla");
    }

    /** Carver-style calls (point context, density 0 and others) at random positions inside the chunk. */
    private static long compareQueries(String where, NoiseGeneratorSettings settings, int cx, int cz, Aquifer vanilla, Aquifer fast, long seed) {
        Random r = new Random(seed);
        int minY = settings.noiseSettings().minY();
        int height = settings.noiseSettings().height();
        double[] densities = { 0.0, -0.0, -0.05, -0.4, 0.02, -1.0E-9 };
        long n = 0;
        for (int q = 0; q < 6000; q++) {
            int x = cx * 16 + r.nextInt(16);
            int z = cz * 16 + r.nextInt(16);
            int y = minY + r.nextInt(height);
            double density = densities[r.nextInt(densities.length)];
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            Object[] expected = new Object[2];
            DfcHooks.runVanilla(() -> outcome(vanilla, ctx, density, expected));
            Object[] actual = new Object[2];
            outcome(fast, ctx, density, actual);
            if (!same(expected, actual)) {
                throw new AssertionError(where + " query " + x + "," + y + "," + z + " density " + density + ": vanilla " + expected[0] + "/" + expected[1]
                    + ", fast " + actual[0] + "/" + actual[1]);
            }
            n++;
        }
        return n;
    }

    private static boolean same(Object[] expected, Object[] actual) {
        boolean result = expected[0] == actual[0] || expected[0] instanceof String s && s.equals(actual[0]);
        return result && expected[1].equals(actual[1]);
    }

    private static void outcome(Aquifer aquifer, DensityFunction.FunctionContext ctx, double density, Object[] out) {
        try {
            BlockState state = aquifer.computeSubstance(ctx, density);
            out[0] = state;
            out[1] = aquifer.shouldScheduleFluidUpdate();
        } catch (RuntimeException e) {
            out[0] = e.getClass().getName();
            out[1] = aquifer.shouldScheduleFluidUpdate();
        }
    }

    static int aquiferMode(Aquifer aquifer) throws ReflectiveOperationException {
        if (!(aquifer instanceof Aquifer.NoiseBasedAquifer)) {
            return AquiferFastPath.OFF;
        }
        Field f = Aquifer.NoiseBasedAquifer.class.getDeclaredField("ga2$mode");
        f.setAccessible(true);
        return f.getInt(aquifer);
    }
}
