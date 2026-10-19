package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedFunctions;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.AxisDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.CeilDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.CosDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.FloorDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.MixDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.SelectDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.ShiftDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.SinDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.SqrtDensityFunction;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Lithostitched's density functions ({@link LithostitchedFunctions}) on stand-ins with the mod's class names and code. Each one
 * must lower, and the compiler's own check holds the lowering to the object at 3000 points. {@code select} is also asked at both
 * zeros and NaN, where a range compares as {@code Double.compareTo} does. A {@code shift} is then compiled inside routers and
 * compared with vanilla's NoiseChunk, which asks the cache wrappers of its input about other positions.
 */
public class LithostitchedFunctionsTest {
    private static HolderLookup.Provider lookup;
    private static HolderGetter<NormalNoise.NoiseParameters> noises;
    private static RandomState vanillaState;

    @BeforeAll
    static void setUp() {
        LithostitchedFunctions.register();
        lookup = VanillaRegistries.createLookup();
        noises = lookup.lookupOrThrow(Registries.NOISE);
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        vanillaState = RandomState.create(settings, noises, 1234567L);
    }

    private static DensityFunction noise(net.minecraft.resources.ResourceKey<NormalNoise.NoiseParameters> key, double xz, double y) {
        return DensityFunctions.noise(noises.getOrThrow(key), xz, y);
    }

    /** {@code fn} with its noises bound, as a router's functions are. */
    private static DensityFunction bound(DensityFunction fn) {
        return fn.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction f) {
                return f;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                return new DensityFunction.NoiseHolder(holder.noiseData(), vanillaState.getOrCreateNoise(holder.noiseData().unwrapKey().orElseThrow()));
            }
        });
    }

    /** Lowers {@code fn} and returns the reason it fell back, or null when it compiled. */
    private static String fallback(DensityFunction fn) {
        CoverageReport report = new CoverageReport();
        Node node = new IrBuilder(report).build("test", bound(fn));
        assertNotNull(node);
        for (CoverageReport.Entry e : report.entries()) {
            if (e.kind == CoverageReport.Kind.FALLBACK) {
                return e.line();
            }
        }
        return null;
    }

    private static DensityFunction shift(DensityFunction input, DensityFunction x, DensityFunction y, DensityFunction z) {
        return new ShiftDensityFunction(input, x, y, z);
    }

    private static Map<String, DensityFunction> functions() {
        DensityFunction t = noise(Noises.TEMPERATURE, 1.0, 1.0);
        DensityFunction e = noise(Noises.EROSION, 0.5, 0.0);
        DensityFunction c = noise(Noises.CONTINENTALNESS, 0.25, 0.0);
        DensityFunction spline = new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(
                new DensityFunctions.Spline.Coordinate(Holder.direct(c)))
            .addPoint(-0.5f, -1.0f, 0.0f).addPoint(0.0f, 0.25f, 0.5f).addPoint(0.6f, 1.0f, 0.0f).build());
        Map<String, DensityFunction> f = new LinkedHashMap<>();
        f.put("axis x", new AxisDensityFunction(Direction.Axis.X));
        f.put("axis y", new AxisDensityFunction(Direction.Axis.Y));
        f.put("axis z", new AxisDensityFunction(Direction.Axis.Z));
        f.put("shift of a 3D noise", shift(t, DensityFunctions.constant(37.5), DensityFunctions.zero(), DensityFunctions.mul(DensityFunctions.constant(64.0), e)));
        f.put("shift in y of a gradient", shift(DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0), t), DensityFunctions.zero(),
            DensityFunctions.constant(-12.7), DensityFunctions.zero()));
        f.put("shift of a spline and offsets", shift(DensityFunctions.add(spline, DensityFunctions.shiftedNoise2d(DensityFunctions.cache2d(DensityFunctions.shiftA(
            noises.getOrThrow(Noises.SHIFT))), DensityFunctions.cache2d(DensityFunctions.shiftB(noises.getOrThrow(Noises.SHIFT))), 0.25, noises.getOrThrow(Noises.VEGETATION))),
            DensityFunctions.mul(DensityFunctions.constant(-300.0), c), DensityFunctions.zero(), DensityFunctions.constant(1e9)));
        f.put("shift of markers", shift(DensityFunctions.add(DensityFunctions.interpolated(t), DensityFunctions.add(DensityFunctions.cacheOnce(e),
            DensityFunctions.cache2d(c))), e, DensityFunctions.constant(3.0), c));
        f.put("shift of axes", shift(DensityFunctions.add(new AxisDensityFunction(Direction.Axis.X), new AxisDensityFunction(Direction.Axis.Y)),
            DensityFunctions.constant(-0.5), DensityFunctions.constant(0.9), t));
        f.put("nested shifts", shift(shift(t, e, DensityFunctions.zero(), c), c, DensityFunctions.zero(), e));
        f.put("mix", new MixDensityFunction(DensityFunctions.add(DensityFunctions.constant(0.5), t), spline, e));
        f.put("select", SelectDensityFunction.create(t, c, List.of(new SelectDensityFunction.Selection(new InclusiveRange<>(-0.4, -0.1), e),
            new SelectDensityFunction.Selection(new InclusiveRange<>(0.0, 0.3), spline), new SelectDensityFunction.Selection(new InclusiveRange<>(-1e9, -0.0), t))));
        f.put("floor", new FloorDensityFunction(DensityFunctions.mul(DensityFunctions.constant(20.0), t)));
        f.put("ceil", new CeilDensityFunction(DensityFunctions.mul(DensityFunctions.constant(20.0), t)));
        f.put("sqrt", new SqrtDensityFunction(t));
        f.put("sin", new SinDensityFunction(DensityFunctions.mul(DensityFunctions.constant(9.0), t)));
        f.put("cos", new CosDensityFunction(DensityFunctions.mul(DensityFunctions.constant(9.0), t)));
        f.put("merged", new MergedDensityFunction(t, e, DensityFunctions.add(t, e)));
        dev.worldgen.lithostitched.impl.LithostitchedInternalHooks.WORLD_SEED = 1234567L;
        for (CellularDensityFunction.ReturnType type : CellularDensityFunction.ReturnType.values()) {
            f.put("cellular 2D " + type, new CellularDensityFunction(java.util.Optional.empty(), new CellularDensityFunction.GridDimensions(48, 0), 0.75f, type, 17));
            f.put("cellular 3D " + type, new CellularDensityFunction(java.util.Optional.empty(), new CellularDensityFunction.GridDimensions(20, 7), 1.0f, type, -3));
        }
        f.put("cellular with a condition", new CellularDensityFunction(java.util.Optional.of(new CellularDensityFunction.CellCondition(t, new InclusiveRange<>(-0.2, 0.4),
            -1.0)), new CellularDensityFunction.GridDimensions(32, 0), 0.5f, CellularDensityFunction.ReturnType.DISTANCE_2_SUB, 5));
        return f;
    }

    @Test
    void everyFunctionLowers() {
        for (Map.Entry<String, DensityFunction> e : functions().entrySet()) {
            String why = fallback(e.getValue());
            assertTrue(why == null, e.getKey() + " fell back: " + why);
        }
    }

    /** {@code select} at the values where {@code Double.compareTo} differs from {@code <=} and {@code >=}. */
    @Test
    void selectRangesCompareAsCompareTo() {
        double[] values = { -0.0, 0.0, Double.NaN, -1.0, 0.3, 1e300, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.MIN_VALUE, -Double.MIN_VALUE };
        double[][] ranges = { { -0.0, 0.0 }, { 0.0, 0.0 }, { -0.0, -0.0 }, { 0.0, 0.3 }, { -1.0, -0.0 }, { 0.3, Double.NaN }, { Double.NaN, Double.NaN },
            { Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY } };
        int cases = 0;
        for (double v : values) {
            for (double[] r : ranges) {
                DensityFunction fn = SelectDensityFunction.create(DensityFunctions.constant(v), DensityFunctions.constant(7.0),
                    List.of(new SelectDensityFunction.Selection(new InclusiveRange<>(r[0], r[1]), DensityFunctions.constant(1.0))));
                String why = fallback(fn);
                assertTrue(why == null, "select of " + v + " in [" + r[0] + ", " + r[1] + "] fell back: " + why);
                cases++;
            }
        }
        System.out.println("[LithostitchedFunctionsTest] select: " + cases + " value and range pairs as compareTo decides");
    }

    /** A flat cache inside a shift returns the chunk's stored values inside the chunk. The shift keeps its own code. */
    @Test
    void shiftOverFlatCacheDeclines() {
        String why = fallback(shift(DensityFunctions.flatCache(noise(Noises.EROSION, 1.0, 0.0)), DensityFunctions.constant(5.0), DensityFunctions.zero(),
            DensityFunctions.zero()));
        assertTrue(why != null && why.contains("flat_cache"), "expected a fallback naming flat_cache: " + why);
    }

    // ---- inside a NoiseChunk ---------------------------------------------------------------------

    /** The overworld's settings with {@code extra} added to its final density. */
    private static NoiseGeneratorSettings withFinalDensity(java.util.function.UnaryOperator<DensityFunction> change) throws Exception {
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        JsonObject json;
        try (var in = NoiseGeneratorSettings.class.getResourceAsStream("/data/minecraft/worldgen/noise_settings/overworld.json")) {
            assertNotNull(in);
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        NoiseGeneratorSettings s = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
        NoiseRouter r = s.noiseRouter();
        NoiseRouter changed = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(),
            r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(), r.initialDensityWithoutJaggedness(), change.apply(r.finalDensity()), r.veinToggle(),
            r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), changed, s.surfaceRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }

    private interface Build {
        RandomState get() throws Exception;
    }

    /** Builds with strict mode off, for a router expected to keep some functions' own code. */
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

    /**
     * Compares GA2's NoiseChunk with vanilla's. With {@code compiledFill}, every interpolator and cell cache must be filled by GA2's
     * programs. Otherwise some may keep vanilla's fill (an interpolator only a shift reads, or a function kept as its own code).
     */
    private static void compareWithVanilla(String label, DensityFunction extra, boolean compiledFill) throws Exception {
        NoiseGeneratorSettings settings = withFinalDensity(fd -> DensityFunctions.add(fd, DensityFunctions.mul(DensityFunctions.constant(0.25), extra)));
        RandomState state = compiledFill ? RandomState.create(settings, noises, 4242L) : lenient(() -> RandomState.create(settings, noises, 4242L));
        long n = ChunkCompare.compareChunk(label, settings, state, 3, -5, DensityFunctions.BeardifierMarker.INSTANCE, DensityFunctions.BeardifierMarker.INSTANCE,
            compiledFill, compiledFill);
        n += ChunkCompare.compareChunk(label + "+pieces", settings, state, -6, 2, CellProgramTest.beardifier(-96, 32), CellProgramTest.beardifier(-96, 32),
            compiledFill, compiledFill);
        System.out.println("[LithostitchedFunctionsTest] " + label + ": " + n + " values bit-identical to vanilla");
    }

    @Test
    void shiftOfNoisesMatchesVanilla() throws Exception {
        DensityFunction t = noise(Noises.TEMPERATURE, 1.0, 1.0);
        DensityFunction e = noise(Noises.EROSION, 0.5, 0.0);
        compareWithVanilla("shift of noises", shift(t, DensityFunctions.mul(DensityFunctions.constant(48.0), e), DensityFunctions.constant(-7.5), DensityFunctions.zero()),
            true);
    }

    @Test
    void shiftOfCacheWrappersMatchesVanilla() throws Exception {
        DensityFunction t = noise(Noises.TEMPERATURE, 1.0, 1.0);
        DensityFunction e = noise(Noises.EROSION, 0.5, 0.0);
        DensityFunction c = noise(Noises.CONTINENTALNESS, 0.25, 0.0);
        // NoiseChunk's wrappers inside the input are asked about the shifted position: cache_once and cache_2d compute there
        compareWithVanilla("shift of cache_once and cache_2d", shift(DensityFunctions.add(DensityFunctions.cacheOnce(t), DensityFunctions.cache2d(c)),
            DensityFunctions.mul(DensityFunctions.constant(30.0), e), DensityFunctions.zero(), c), true);
    }

    @Test
    void shiftOfAnInterpolatedFunctionMatchesVanilla() throws Exception {
        DensityFunction t = noise(Noises.TEMPERATURE, 1.0, 1.0);
        DensityFunction e = noise(Noises.EROSION, 0.5, 0.0);
        // the chunk's interpolator, asked about another position, computes its function there
        compareWithVanilla("shift of interpolated", shift(DensityFunctions.interpolated(t), DensityFunctions.mul(DensityFunctions.constant(30.0), e),
            DensityFunctions.zero(), DensityFunctions.zero()), false);
    }

    @Test
    void shiftOfAFlatCacheKeepsItsCodeAndMatchesVanilla() throws Exception {
        DensityFunction e = noise(Noises.EROSION, 1.0, 0.0);
        compareWithVanilla("shift of flat_cache (own code)", shift(DensityFunctions.flatCache(e), DensityFunctions.constant(5.0), DensityFunctions.zero(),
            DensityFunctions.zero()), false);
    }

    @Test
    void unchangedRouterHasNoFallback() {
        assertFalse(fallback(DensityFunctions.add(noise(Noises.TEMPERATURE, 1.0, 1.0), DensityFunctions.zero())) != null);
    }
}
