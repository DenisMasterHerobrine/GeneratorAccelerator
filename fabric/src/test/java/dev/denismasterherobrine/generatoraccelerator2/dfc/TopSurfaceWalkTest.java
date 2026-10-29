package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ProgramCompiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
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
 * A {@code find_top_surface} inside another function ({@code TopSurfaceWalk}). It must lower when its density reads no chunk state,
 * and give vanilla's values in a point program and inside a NoiseChunk, whose wrappers in the density are asked about every step's
 * position. A density with a {@code flat_cache} keeps vanilla's code.
 */
public class TopSurfaceWalkTest {
    private static HolderLookup.Provider lookup;
    private static HolderGetter<NormalNoise.NoiseParameters> noises;
    private static RandomState vanillaState;

    @BeforeAll
    static void setUp() {
        lookup = VanillaRegistries.createLookup();
        noises = lookup.lookupOrThrow(Registries.NOISE);
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        vanillaState = RandomState.create(settings, noises, 1234567L);
    }

    private static DensityFunction noise(ResourceKey<NormalNoise.NoiseParameters> key, double xz, double y) {
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

    /** A walk over a 3D density under a varying upper bound, with the cache wrappers the chunk answers at any position. */
    private static DensityFunction walk(boolean flat, boolean interpolatedInside) {
        DensityFunction t = noise(Noises.TEMPERATURE, 1.0, 1.0);
        DensityFunction c = noise(Noises.CONTINENTALNESS, 0.25, 0.0);
        DensityFunction e = noise(Noises.EROSION, 0.5, 0.0);
        DensityFunction columnPart = flat ? DensityFunctions.flatCache(c) : DensityFunctions.cache2d(c);
        DensityFunction density = DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 256, 1.5, -1.5),
            DensityFunctions.add(DensityFunctions.mul(DensityFunctions.constant(0.6), DensityFunctions.cacheOnce(t)),
                interpolatedInside ? DensityFunctions.interpolated(columnPart) : columnPart));
        DensityFunction upper = DensityFunctions.add(DensityFunctions.constant(96.0), DensityFunctions.mul(DensityFunctions.constant(120.0), e));
        return DensityFunctions.findTopSurface(density, upper, -64, 8);
    }

    private static String fallback(IrBuilder builder, CoverageReport report, DensityFunction fn) {
        assertNotNull(builder.build("test", fn));
        for (CoverageReport.Entry e : report.entries()) {
            if (e.kind == CoverageReport.Kind.FALLBACK) {
                return e.line();
            }
        }
        return null;
    }

    @Test
    void nestedWalkLowersAndMatches() {
        DensityFunction fn = bound(DensityFunctions.add(DensityFunctions.mul(DensityFunctions.constant(0.01), walk(false, true)), noise(Noises.VEGETATION, 1.0, 0.0)));
        CoverageReport report = new CoverageReport();
        IrBuilder builder = new IrBuilder(report);
        Node node = builder.build("test", fn);
        for (CoverageReport.Entry e : report.entries()) {
            assertTrue(e.kind != CoverageReport.Kind.FALLBACK, "fell back: " + e.line());
        }
        PointProgram program = ProgramCompiler.compilePoint("top-surface-test", List.of(node), builder.specs(), builder.blendedSpecs());
        double[] scratch = new double[Math.max(1, program.scratchSize)];
        double[] out = new double[1];
        Random random = new Random(11);
        for (int i = 0; i < 4000; i++) {
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(random.nextInt(60000) - 30000, random.nextInt(384) - 64,
                random.nextInt(60000) - 30000);
            program.computeAll(ctx, scratch, out);
            double expected = fn.compute(ctx);
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(out[0]), "at " + ctx + ": " + expected + " vs " + out[0]);
        }
    }

    @Test
    void flatCacheInTheDensityDeclines() {
        CoverageReport report = new CoverageReport();
        String why = fallback(new IrBuilder(report), report, bound(walk(true, true)));
        assertTrue(why != null && why.contains("flat_cache"), "expected a fallback naming flat_cache: " + why);
    }

    /**
     * Inside a NoiseChunk the walk evaluates the chunk's copy of the density. Its wrappers compute at each step's position. With
     * {@code interpolatedInside} the chunk also has an interpolator that only the walk reads, off the chunk's corners. GA2's column
     * program does not fill that one, so the chunk keeps vanilla's fill, as it did when the walk was not compiled.
     */
    private static void compareWalk(String label, boolean interpolatedInside) throws Exception {
        NoiseGeneratorSettings settings = withFinalDensity(fd -> DensityFunctions.add(fd,
            DensityFunctions.interpolated(DensityFunctions.mul(DensityFunctions.constant(0.002), walk(false, interpolatedInside)))));
        RandomState state = RandomState.create(settings, noises, 4242L);
        boolean compiledFill = !interpolatedInside;
        long n = ChunkCompare.compareChunk(label, settings, state, 3, -5, DensityFunctions.BeardifierMarker.INSTANCE,
            DensityFunctions.BeardifierMarker.INSTANCE, compiledFill, compiledFill);
        System.out.println("[TopSurfaceWalkTest] " + label + ": " + n + " values bit-identical to vanilla");
    }

    @Test
    void walkInsideANoiseChunkMatchesVanilla() throws Exception {
        compareWalk("walk over cache_once and cache_2d", false);
    }

    @Test
    void walkOverAnInterpolatorMatchesVanilla() throws Exception {
        compareWalk("walk over an interpolator", true);
    }

    private interface Change {
        DensityFunction apply(DensityFunction finalDensity);
    }

    /** The overworld's settings with its final density changed. */
    private static NoiseGeneratorSettings withFinalDensity(Change change) throws Exception {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        JsonObject json;
        try (var in = NoiseGeneratorSettings.class.getResourceAsStream("/data/minecraft/worldgen/noise_settings/overworld.json")) {
            assertNotNull(in);
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        NoiseGeneratorSettings s = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, json).getOrThrow(IllegalStateException::new);
        NoiseRouter r = s.noiseRouter();
        NoiseRouter changed = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(),
            r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(), r.preliminarySurfaceLevel(), change.apply(r.finalDensity()), r.veinToggle(),
            r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), changed, s.surfaceRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }
}
