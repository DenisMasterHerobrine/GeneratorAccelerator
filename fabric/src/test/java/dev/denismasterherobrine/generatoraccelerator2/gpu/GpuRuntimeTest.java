package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ChunkCompare;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.SelfTest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The production GPU path (gpu.cl's loader and binding, gpu.runtime's batches and apply step) against GA2's CPU path on whole chunks:
 * biomes, sections as stored, counters, heightmaps and marks. Chunks carry synthetic structures and lie across region borders and far
 * out. Then measures the throughput of the GPU stages. Certified fp32 (the default precision) is also checked block by block. Every
 * float density's bound must hold against the exact density. Decisions made on densities moved to the edge of their bounds must still
 * come out exact.
 * {@code gradlew test --tests "*GpuRuntimeTest*" -Dga2.gpu=true --no-configuration-cache [-Dga2.gpu.datapack=<dir>]
 * [-Dgeneratoraccelerator2.gpu.precision=fp64]}
 */
@EnabledIfSystemProperty(named = "ga2.gpu", matches = "true")
public class GpuRuntimeTest {
    private static void log(String fmt, Object... args) {
        System.out.println("[GpuRuntime] " + String.format(Locale.ROOT, fmt, args));
    }

    private static GpuDevice device;
    private static GpuProbe.Setup setup;

    private static synchronized GpuDevice device() throws Exception {
        if (device == null) {
            ClLoader.Loaded loaded = ClLoader.load(Path.of("build", "gpu-lib").toAbsolutePath());
            log("binding: %s", loaded.description());
            ClDeviceInfo info = loaded.api().devices(false).stream().filter(ClDeviceInfo::hasFp64).findFirst().orElseThrow();
            device = GpuRuntime.open(loaded, info);
            log("device: %s", device.describe());
            String bad = SelfTest.arithmetic(device, Gpu.BUILD_OPTIONS);
            log("arithmetic: %s", bad.isEmpty() ? "every pattern bit-identical to Java" : bad);
        }
        return device;
    }

    private static synchronized GpuProbe.Setup setup() throws Exception {
        if (setup == null) {
            setup = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
        }
        return setup;
    }

    /** A dimension's GPU path. {@code precision} is the config's ({@code auto} or {@code fp64}). {@code defines} adds build options. */
    private static GpuWorldgen open(String name, String precision, String defines) throws Exception {
        return open(setup(), name, precision, defines);
    }

    /** As {@link #open(String, String, String)}, for the world of {@code s}. */
    private static GpuWorldgen open(GpuProbe.Setup s, String name, String precision, String defines) throws Exception {
        GpuDevice d = device();
        String old = System.getProperty("generatoraccelerator2.gpu.precision");
        System.setProperty("generatoraccelerator2.gpu.precision", precision);
        try {
            GpuWorldgen wg = GpuRuntime.worldgen(d, name, s.world.settings, s.world.randomState);
            wg.buildDefines = defines;
            long t0 = System.nanoTime();
            GpuRuntime.prepare(wg, s.world.biomeSource);
            d.addPump(wg);
            GpuRuntime.ready(wg);
            log("%s prepared in %.1f s: %s", name, (System.nanoTime() - t0) / 1e9, wg.status());
            return wg;
        } finally {
            if (old == null) {
                System.clearProperty("generatoraccelerator2.gpu.precision");
            } else {
                System.setProperty("generatoraccelerator2.gpu.precision", old);
            }
        }
    }

    private static GpuWorldgen.Beard beard(Structures st, int cx, int cz) {
        List<Integer> p = new ArrayList<>();
        for (Beardifier.Rigid r : st.piecesFor(cx, cz)) {
            BoundingBox b = r.box();
            p.addAll(List.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), r.terrainAdjustment().ordinal(), r.groundLevelDelta()));
        }
        List<Integer> j = new ArrayList<>();
        for (JigsawJunction jj : st.junctionsFor(cx, cz)) {
            j.addAll(List.of(jj.getSourceX(), jj.getSourceGroundY(), jj.getSourceZ()));
        }
        return new GpuWorldgen.Beard(p.stream().mapToInt(Integer::intValue).toArray(), j.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The chunk's climate sampler, as the BIOMES step makes it for the quarts the GPU leaves to the CPU (ties). */
    private static java.util.function.Supplier<net.minecraft.world.level.biome.Climate.Sampler> tieSampler(ProtoChunk chunk, Beardifier beardifier) {
        return tieSampler(setup, chunk, beardifier);
    }

    private static java.util.function.Supplier<net.minecraft.world.level.biome.Climate.Sampler> tieSampler(GpuProbe.Setup s, ProtoChunk chunk, Beardifier beardifier) {
        return () -> ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkSamplerInvoker) chunk.getOrCreateNoiseChunk(
            c -> net.minecraft.world.level.levelgen.NoiseChunk.forChunk(c, s.world.randomState, beardifier, s.world.settings, s.world.fluidPicker,
                net.minecraft.world.level.levelgen.blending.Blender.empty())))
            .ga2$cachedClimateSampler(s.world.randomState.router(), s.world.settings.spawnTarget());
    }

    /** 4x4 chunks at each origin (across region borders and far out), with synthetic structures, against GA2's CPU path. */
    private static long exactness(GpuWorldgen wg, String what) throws Exception {
        return exactness(setup(), wg, what);
    }

    /** As {@link #exactness(GpuWorldgen, String)}, for the world of {@code s}. */
    private static long exactness(GpuProbe.Setup s, GpuWorldgen wg, String what) throws Exception {
        int[][] origins = { { -2, -2 }, { 5, 6 }, { -1953, 771 }, { 117187, -117187 }, { 1048574, 1048574 } };
        // -Dga2.gpu.origins="x,z;x,z": other 4x4 origins (an in-game difference's place)
        String extra = System.getProperty("ga2.gpu.origins");
        if (extra != null) {
            origins = java.util.Arrays.stream(extra.split(";")).map(p -> java.util.Arrays.stream(p.trim().split(",")).mapToInt(Integer::parseInt).toArray())
                .toArray(int[][]::new);
        }
        long chunks = 0, ties = 0;
        long rc0 = GpuWorldgen.RECHECKED_CELLS.sum(), cc0 = GpuWorldgen.CERT_CELLS.sum();
        for (int[] o : origins) {
            Structures st = Structures.synthetic(99L + o[0], o[0], o[1], 4, 4, 128);
            List<CompletableFuture<GpuWorldgen.BiomeResult>> bf = new ArrayList<>();
            List<CompletableFuture<GpuWorldgen.NoiseResult>> nf = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    ChunkPos pos = new ChunkPos(o[0] + i, o[1] + j);
                    bf.add(wg.decidesBiomes() ? wg.biomes(pos) : CompletableFuture.completedFuture(null));
                    nf.add(wg.noise(pos, beard(st, pos.x, pos.z)));
                }
            }
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    int cx = o[0] + i, cz = o[1] + j;
                    ProtoChunk gpu = s.world.emptyChunk(cx, cz);
                    GpuWorldgen.BiomeResult b = bf.get(i * 4 + j).get(120, TimeUnit.SECONDS);
                    if (b != null) {
                        ties += b.ties();
                        if (!Apply.biomes(wg, gpu, b, tieSampler(s, gpu, st.beardifierFor(cx, cz)))) {
                            throw new AssertionError("biomes not applicable");
                        }
                    }
                    GpuWorldgen.NoiseResult n = nf.get(i * 4 + j).get(120, TimeUnit.SECONDS);
                    if (n == null) {
                        throw new AssertionError("chunk " + cx + "," + cz + " handed to the CPU (recheck list overflow)");
                    }
                    if (!Apply.noise(wg, gpu, n)) {
                        throw new AssertionError("noise not applicable");
                    }
                    ProtoChunk cpu = GpuProbe.refChunk(s, cx, cz, st);
                    String diff = ChunkCompare.blocks(cpu, gpu);
                    if (diff == null && b != null) {
                        diff = ChunkCompare.biomes(cpu, gpu, b::tie);
                    }
                    if (diff != null) {
                        // which side gives another answer when asked again (the GPU request alone, the CPU reference again)
                        ProtoChunk gpu2 = s.world.emptyChunk(cx, cz);
                        GpuWorldgen.NoiseResult n2 = wg.noise(new ChunkPos(cx, cz), beard(st, cx, cz)).get(120, TimeUnit.SECONDS);
                        String g2 = n2 == null || !Apply.noise(wg, gpu2, n2) ? "not applicable" : String.valueOf(ChunkCompare.blocks(gpu, gpu2));
                        String c2 = String.valueOf(ChunkCompare.blocks(cpu, GpuProbe.refChunk(s, cx, cz, st)));
                        GpuProbe.Setup fresh = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
                        String c3 = String.valueOf(ChunkCompare.blocks(cpu, GpuProbe.refChunk(fresh, cx, cz, st)));
                        String g3 = String.valueOf(ChunkCompare.blocks(GpuProbe.refChunk(fresh, cx, cz, st), gpu));
                        throw new AssertionError(what + ": chunk " + cx + "," + cz + ": " + diff + " (CPU vs GPU); the GPU again: " + g2 + "; the CPU again: " + c2
                            + "; the CPU in a new world: " + c3 + "; that against the GPU: " + g3);
                    }
                    chunks++;
                }
            }
        }
        long rc = GpuWorldgen.RECHECKED_CELLS.sum() - rc0, cc = GpuWorldgen.CERT_CELLS.sum() - cc0;
        log("EXACT (%s): %d chunks with structures identical to GA2's CPU path (sections as stored, counters, heightmaps, marks%s; %d tie quarts asked on the CPU)%s",
            what, chunks, wg.decidesBiomes() ? ", biomes" : "", ties,
            cc > 0 ? String.format(Locale.ROOT, "; certified fp32: %,d of %,d cells rechecked in fp64 (%.4f%%)", rc, cc, 100.0 * rc / cc) : "");
        return chunks;
    }

    private static void throughput(GpuWorldgen wg, int originBase) throws Exception {
        GpuProbe.Setup s = setup();
        int side = Integer.getInteger("ga2.gpu.square", 32);
        for (int round = 0; round < 3; round++) {
            int ox = originBase + round * 100;
            long w0 = System.nanoTime();
            List<CompletableFuture<GpuWorldgen.BiomeResult>> bf = new ArrayList<>();
            List<CompletableFuture<GpuWorldgen.NoiseResult>> nf = new ArrayList<>();
            for (int i = 0; i < side; i++) {
                for (int j = 0; j < side; j++) {
                    ChunkPos pos = new ChunkPos(ox + i, ox + j);
                    if (wg.decidesBiomes()) {
                        bf.add(wg.biomes(pos));
                    }
                    nf.add(wg.noise(pos, GpuWorldgen.Beard.NONE));
                }
            }
            CompletableFuture.allOf(nf.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
            CompletableFuture.allOf(bf.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
            long gpuNs = System.nanoTime() - w0;
            long a0 = System.nanoTime();
            int k = 0;
            for (int i = 0; i < side; i++) {
                for (int j = 0; j < side; j++, k++) {
                    ProtoChunk c = s.world.emptyChunk(ox + i, ox + j);
                    if (wg.decidesBiomes()) {
                        GpuWorldgen.BiomeResult br = bf.get(k).get();
                        if (br == null) {
                            throw new AssertionError("chunk " + (ox + i) + "," + (ox + j) + ": biomes handed to the CPU");
                        }
                        Apply.biomes(wg, c, br, tieSampler(c, Structures.none(ox + i, ox + j, 1, 1).beardifierFor(ox + i, ox + j)));
                    }
                    GpuWorldgen.NoiseResult n = nf.get(k).get();
                    if (n != null) {
                        Apply.noise(wg, c, n);
                    }
                }
            }
            long applyNs = System.nanoTime() - a0;
            int n = side * side;
            log("%s round %d: %d chunks; GPU stages (requests to results) %.0f chunks/s (%.1f us/chunk wall); apply on one CPU thread %.1f us/chunk", wg.name,
                round, n, n / (gpuNs / 1e9), gpuNs / 1e3 / n, applyNs / 1e3 / n);
            StringBuilder dev = new StringBuilder();
            long devTotal = 0;
            for (var e : new java.util.TreeMap<>(wg.deviceNanos).entrySet()) {
                long v = e.getValue().sumThenReset();
                devTotal += v;
                dev.append(String.format(Locale.ROOT, " %s %.1f", e.getKey(), v / 1e3 / n));
            }
            String[] host = { "launch batches", "finish batches", "finish regions", "launch regions", "of which allocating" };
            StringBuilder hs = new StringBuilder();
            for (int i = 0; i < host.length; i++) {
                hs.append(String.format(Locale.ROOT, " %s %.1f", host[i], GpuWorldgen.HOST_NS[i].sumThenReset() / 1e3 / n));
            }
            if (devTotal > 0) {
                log("  device %.1f us/chunk:%s; GPU thread us/chunk:%s", devTotal / 1e3 / n, dev, hs);
            } else {
                log("  GPU thread us/chunk:%s", hs);
            }
        }
    }

    @Test
    void exactAndFast() throws Exception {
        String precision = System.getProperty("generatoraccelerator2.gpu.precision", "auto");
        // -Dga2.gpu.defines=" -D X": build options for timing experiments (-Dga2.gpu.skipExact=true when they are not exact)
        GpuWorldgen wg = open("test-" + precision, precision, System.getProperty("ga2.gpu.defines", ""));
        if (!Boolean.getBoolean("ga2.gpu.skipExact")) {
            exactness(wg, precision);
        }
        throughput(wg, 20000);
    }

    /**
     * Lithostitched's functions on the GPU ({@code compat.lithostitched.LithostitchedFunctions}), on stand-ins with the mod's class
     * names and code. As in a pack, the aquifer's floodedness is read at a shifted position, and the terrain adds a mix of a shifted
     * noise and a select. The program must compile them all, and its chunks must be GA2's CPU chunks.
     */
    @Test
    void lithostitchedFunctions() throws Exception {
        dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedFunctions.register();
        var reg = dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld.registries();
        var noises = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
        var base = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
            .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value();
        var r = base.noiseRouter();
        var t = net.minecraft.world.level.levelgen.DensityFunctions.noise(noises.getOrThrow(net.minecraft.world.level.levelgen.Noises.TEMPERATURE), 1.0, 1.0);
        var e = net.minecraft.world.level.levelgen.DensityFunctions.noise(noises.getOrThrow(net.minecraft.world.level.levelgen.Noises.EROSION), 0.5, 0.0);
        var c = net.minecraft.world.level.levelgen.DensityFunctions.noise(noises.getOrThrow(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS), 0.25, 0.0);
        java.util.function.DoubleFunction<net.minecraft.world.level.levelgen.DensityFunction> k = net.minecraft.world.level.levelgen.DensityFunctions::constant;
        var flood = new dev.worldgen.lithostitched.impl.worldgen.densityfunction.ShiftDensityFunction(r.fluidLevelFloodednessNoise(),
            net.minecraft.world.level.levelgen.DensityFunctions.mul(k.apply(64.0), e), net.minecraft.world.level.levelgen.DensityFunctions.zero(),
            net.minecraft.world.level.levelgen.DensityFunctions.mul(k.apply(64.0), c));
        var shifted = new dev.worldgen.lithostitched.impl.worldgen.densityfunction.ShiftDensityFunction(t, net.minecraft.world.level.levelgen.DensityFunctions.mul(k.apply(40.0), c),
            k.apply(-3.0), net.minecraft.world.level.levelgen.DensityFunctions.zero());
        var select = dev.worldgen.lithostitched.impl.worldgen.densityfunction.SelectDensityFunction.create(c, t, List.of(
            new dev.worldgen.lithostitched.impl.worldgen.densityfunction.SelectDensityFunction.Selection(new net.minecraft.util.InclusiveRange<>(-0.2, 0.1), e)));
        var mix = new dev.worldgen.lithostitched.impl.worldgen.densityfunction.MixDensityFunction(
            net.minecraft.world.level.levelgen.DensityFunctions.add(k.apply(0.5), e), shifted, select);
        dev.worldgen.lithostitched.impl.LithostitchedInternalHooks.WORLD_SEED = 1234567L;
        var cellular = new dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction(java.util.Optional.empty(),
            new dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction.GridDimensions(48, 0), 0.75f,
            dev.worldgen.lithostitched.impl.worldgen.densityfunction.CellularDensityFunction.ReturnType.DISTANCE_2_SUB, 17);
        var terrain = net.minecraft.world.level.levelgen.DensityFunctions.add(r.finalDensity(), net.minecraft.world.level.levelgen.DensityFunctions.add(
            net.minecraft.world.level.levelgen.DensityFunctions.mul(k.apply(0.15), mix), net.minecraft.world.level.levelgen.DensityFunctions.mul(k.apply(-0.004), cellular)));
        var router = new net.minecraft.world.level.levelgen.NoiseRouter(r.barrierNoise(), flood, r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(),
            r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(), r.initialDensityWithoutJaggedness(), terrain, r.veinToggle(), r.veinRidged(), r.veinGap());
        var settings = new net.minecraft.world.level.levelgen.NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(), router,
            base.surfaceRule(), base.spawnTarget(), base.seaLevel(), base.disableMobGeneration(), base.aquifersEnabled(), base.oreVeinsEnabled(),
            base.useLegacyRandomSource());
        var dim = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        var source = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(net.minecraft.core.Holder.direct(
            new net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
                reg.biomes.asLookup())));
        var world = new dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness.World(reg, net.minecraft.core.Holder.direct(settings), source, noises, dim,
            1234567L);
        GpuProbe.Setup s = new GpuProbe.Setup(world);
        String precision = System.getProperty("generatoraccelerator2.gpu.precision", "auto");
        GpuWorldgen wg = open(s, "test-lithostitched-" + precision, precision, "");
        if (!wg.ready()) {
            throw new AssertionError("the GPU program did not build: " + wg.status());
        }
        exactness(s, wg, "lithostitched");
    }

    /**
     * Lithostitched's biome injectors over the GPU's biomes ({@code compat.lithostitched.LithostitchedGpuBiomes}), on a stand-in of
     * the injector source. The GPU gives each quart's base biome and climate point. The stand-in's own code then replaces target
     * biomes by the point. The chunks must be GA2's CPU chunks, where the stand-in samples and searches by itself.
     */
    @Test
    void lithostitchedInjectors() throws Exception {
        var reg = dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld.registries();
        var noises = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
        var settings = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
            .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var dim = reg.lookup.lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
            .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        var multi = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(net.minecraft.core.Holder.direct(
            new net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD,
                reg.biomes.asLookup())));
        var targets = java.util.Set.<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>of(
            reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS), reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.FOREST),
            reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.OCEAN), reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.TAIGA),
            reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.DEEP_OCEAN));
        var injector = new dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource(multi, targets,
            reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.CHERRY_GROVE));
        var world = new dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness.World(reg, settings, injector, noises, dim, 1234567L);
        GpuProbe.Setup s = new GpuProbe.Setup(world);
        GpuWorldgen wg = open(s, "test-injectors", "auto", "");
        if (!wg.decidesBiomes() || wg.biomeInjector() != injector) {
            throw new AssertionError("the injectors are not over the GPU's biomes: " + wg.status());
        }
        long handed = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes.HANDED_POINTS.sum();
        exactness(s, wg, "injectors");
        handed = dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes.HANDED_POINTS.sum() - handed;
        // the replacement must happen somewhere, or the test proves nothing about the handed points
        int replaced = 0;
        var cherry = reg.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.CHERRY_GROVE);
        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                ProtoChunk c = s.world.emptyChunk(cx * 37, cz * 37);
                GpuWorldgen.BiomeResult b = wg.biomes(c.getPos()).get(120, TimeUnit.SECONDS);
                Apply.biomes(wg, c, b, tieSampler(s, c, Structures.none(cx * 37, cz * 37, 1, 1).beardifierFor(cx * 37, cz * 37)));
                for (var section : c.getSections()) {
                    for (int q = 0; q < 64; q++) {
                        replaced += section.getNoiseBiome(q & 3, q >> 4, (q >> 2) & 3) == cherry ? 1 : 0;
                    }
                }
            }
        }
        log("injectors: %d climate points handed from the GPU, %d replaced quarts in 64 more chunks", handed, replaced);
        if (handed == 0 || replaced == 0) {
            throw new AssertionError("the injectors were not exercised");
        }
    }

    /** Vanilla's {@code RTree.Node.distance}, which is protected: the squared distances of the seven parameters. */
    private static final net.minecraft.world.level.biome.Climate.DistanceMetric<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> DISTANCE =
        (node, values) -> {
            long d = 0;
            for (int i = 0; i < 7; i++) {
                long p = node.parameterSpace[i].distance(values[i]);
                d += p * p;
            }
            return d;
        };

    /**
     * Regions that pick a climate tree per quart column, as TerraBlender's do, against the same lookup on the CPU. Region 0 is a copy of
     * the dimension's tree. Region 1 has placeholder leaves (the quart goes to region 0's tree) and other biomes. Region 2 is a single
     * leaf. The region of a quart column changes inside chunks. Biomes and every tree's last result after each chunk must match.
     */
    @Test
    @SuppressWarnings("unchecked")
    void biomeRegions() throws Exception {
        GpuProbe.Setup s = setup();
        var list = ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.GpuBiomeSourceInspectMixin) s.world.biomeSource).ga2$gpuParameters();
        var values = list.values();
        var placeholder = net.minecraft.core.Holder.direct(values.get(0).getSecond().value());
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.level.biome.Climate.ParameterPoint, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> region1 =
            new ArrayList<>();
        for (int k = 0; k < values.size(); k++) {
            var p = values.get(k);
            region1.add(k % 3 == 0 ? p.mapSecond(b -> placeholder) : k % 3 == 1 ? p.mapSecond(b -> values.get((p.getFirst().hashCode() & 0x7fffffff) % values.size()).getSecond()) : p);
        }
        net.minecraft.world.level.biome.Climate.RTree<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>[] trees = new net.minecraft.world.level.biome.Climate.RTree[] {
            net.minecraft.world.level.biome.Climate.RTree.create(values), net.minecraft.world.level.biome.Climate.RTree.create(region1),
            net.minecraft.world.level.biome.Climate.RTree.create(List.of(values.get(5))) };
        dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeRegions regions = new dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeRegions() {
            @Override
            public net.minecraft.world.level.biome.Climate.RTree<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>[] trees() {
                return trees;
            }

            @Override
            public int region(int qx, int qz) {
                return Math.floorMod(Math.floorDiv(qx, 5) + 3 * Math.floorDiv(qz, 7), 3);
            }

            @Override
            public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> find(net.minecraft.world.level.biome.Climate.TargetPoint target, int x, int y, int z) {
                // TerraBlender's findValuePositional
                var biome = trees[region(x, z)].search(target, DISTANCE);
                return deferred(biome) ? trees[0].search(target, DISTANCE) : biome;
            }

            @Override
            public boolean deferred(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
                return biome == placeholder;
            }
        };
        GpuWorldgen.testRegions = l -> regions;
        GpuWorldgen wg;
        try {
            wg = open("test-regions", "auto", "");
        } finally {
            GpuWorldgen.testRegions = null;
        }
        if (!wg.decidesBiomes() || !wg.biomeRegions()) {
            throw new AssertionError("regions not on the GPU: " + wg.status());
        }
        int[][] origins = { { -2, -2 }, { 13, 14 }, { -1953, 771 }, { 117187, -117187 } };
        long chunks = 0, ties = 0, deferred = 0;
        long[] perRegion = new long[trees.length];
        for (int[] o : origins) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    int cx = o[0] + i, cz = o[1] + j;
                    Beardifier beard = Structures.none(cx, cz, 1, 1).beardifierFor(cx, cz);
                    GpuWorldgen.BiomeResult b = wg.biomes(new ChunkPos(cx, cz)).get(120, TimeUnit.SECONDS);
                    if (b == null || b.fallbacks() == null) {
                        throw new AssertionError("chunk " + cx + "," + cz + ": no region answers");
                    }
                    ties += b.ties();
                    for (int q = 0; q < b.quarts().length; q++) {
                        if (b.quarts()[q] >= 0 && wg.deferred(b.quarts()[q])) {
                            deferred++;
                        }
                    }
                    for (int lx = 0; lx < 4; lx++) {
                        for (int lz = 0; lz < 4; lz++) {
                            perRegion[regions.region(cx * 4 + lx, cz * 4 + lz)]++;
                        }
                    }
                    var start = wg.lastResults();
                    ProtoChunk gpu = s.world.emptyChunk(cx, cz);
                    if (!Apply.biomes(wg, gpu, b, tieSampler(gpu, beard))) {
                        throw new AssertionError("biomes not applicable");
                    }
                    var afterGpu = wg.lastResults();
                    wg.setLastResults(start);
                    ProtoChunk cpu = s.world.emptyChunk(cx, cz);
                    cpu.fillBiomesFromNoise((x, y, z, sampler) -> regions.find(sampler.sample(x, y, z), x, y, z), tieSampler(cpu, beard).get());
                    String diff = ChunkCompare.biomes(cpu, gpu, q -> false);
                    if (diff == null && !java.util.Arrays.equals(afterGpu, wg.lastResults())) {
                        diff = "the trees' last results after the fill differ";
                    }
                    if (diff != null) {
                        throw new AssertionError("regions: chunk " + cx + "," + cz + ": " + diff + " (CPU vs GPU)");
                    }
                    chunks++;
                }
            }
        }
        if (deferred == 0 || perRegion[0] == 0 || perRegion[1] == 0 || perRegion[2] == 0) {
            throw new AssertionError("the regions were not all met: " + java.util.Arrays.toString(perRegion) + " quart columns, " + deferred + " placeholders");
        }
        log("EXACT (regions): %d chunks identical to the regions' CPU lookup (biomes and last results); quart columns per region %s; %d placeholder quarts; %d ties",
            chunks, java.util.Arrays.toString(perRegion), deferred, ties);
    }

    /**
     * Certified fp32's bounds against the exact densities: one batch of chunks through the certified kernels and through the double
     * kernels, each block's float density within its bound of the double one.
     */
    @Test
    void certifiedBounds() throws Exception {
        GpuWorldgen c = open("bounds-fp32", "auto", " -D WRITE_DENSITY");
        if (!c.certified) {
            log("certified fp32 not available for this router: %s", c.certifiedOff);
            return;
        }
        GpuWorldgen d = open("bounds-fp64", "fp64", " -D WRITE_DENSITY");
        // the debug densities are indexed by the chunk's place in its region (side x side chunks)
        int side = c.side;
        int regionChunks = side * side;
        long perChunk = 256L * c.height;
        long blocks = regionChunks * perChunk;
        ClApi cl = device.cl;
        long bufC = cl.createBuffer(device.context, ClApi.MEM_READ_WRITE, blocks * 12);
        long bufD = cl.createBuffer(device.context, ClApi.MEM_READ_WRITE, blocks * 8);
        c.densBuffer = bufC;
        d.densBuffer = bufD;
        long total = 0, violations = 0, close = 0, uncertain = 0; long[] reasons = new long[ClEmitter.RECHECK_REASONS.length];
        double worst = 0, sumBound = 0;
        // near the origin, far out, across the 2^24 wrap of octaves with input factor 1 (block 16,777,216), at the world border
        int[][] origins = { { 0, 0 }, { -1953, 771 }, { 117187, -117187 }, { 4000, -4000 }, { 1048570, 1048570 }, { 1874990, -1874990 } };
        for (int[] o : origins) {
            Structures st = Structures.synthetic(7L + o[0], o[0], o[1], 4, 4, 64);
            List<CompletableFuture<GpuWorldgen.NoiseResult>> fc = new ArrayList<>(), fd = new ArrayList<>();
            // one region-aligned 4x4 block of chunks: one batch each (batches hold up to batchChunks chunks of one region)
            int bx = Math.floorDiv(o[0], side) * side, bz = Math.floorDiv(o[1], side) * side;
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    ChunkPos pos = new ChunkPos(bx + i, bz + j);
                    fc.add(c.noise(pos, beard(st, pos.x, pos.z)));
                }
            }
            CompletableFuture.allOf(fc.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            ByteBuffer hc = readOnGpuThread(bufC, blocks * 12);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    ChunkPos pos = new ChunkPos(bx + i, bz + j);
                    fd.add(d.noise(pos, beard(st, pos.x, pos.z)));
                }
            }
            CompletableFuture.allOf(fd.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            ByteBuffer hd = readOnGpuThread(bufD, blocks * 8);
            var fcv = hc.asFloatBuffer();
            var fdv = hd.asDoubleBuffer();
            for (int ch = 0; ch < 16; ch++) {
              long b0 = (long) ((ch / 4) * side + ch % 4) * perChunk;
              for (long gi = 0; gi < perChunk; gi++) {
                int g = (int) (b0 + gi);
                float v = fcv.get(3 * g), e = fcv.get(3 * g + 1); int why = Float.floatToRawIntBits(fcv.get(3 * g + 2)); for (int bit = 0; bit < reasons.length; bit++) { if ((why >> bit & 1) != 0) reasons[bit]++; }
                double exact = fdv.get(g);
                double err = Math.abs((double) v - exact);
                if (e == Float.POSITIVE_INFINITY) {
                    uncertain++; // a discontinuity near its inputs: the cell is rechecked, the float value never used
                    continue;
                }
                total++;
                sumBound += e;
                if (Math.abs(exact) <= e) {
                    close++;
                }
                if (e > 0) {
                    worst = Math.max(worst, err / e);
                }
                if (!(err <= e)) {
                    if (violations++ < 10) {
                        log("  BOUND VIOLATED at block %d: float %s, error bound %s, exact %s (off by %s)", g, v, e, exact, err);
                    }
                }
              }
            }
        }
        cl.releaseMem(bufC);
        cl.releaseMem(bufD);
        c.densBuffer = 0;
        d.densBuffer = 0;
        log("CERTIFIED BOUNDS: %,d block densities, %d outside their bounds; largest error/bound %.3f; mean bound %.3g; %.4f%% of blocks within their bound of 0;"
            + " %,d blocks uncertain from a discontinuity", total, violations, worst, sumBound / total, 100.0 * close / total, uncertain);
        StringBuilder sb = new StringBuilder();
        for (int bit = 0; bit < reasons.length; bit++) {
            sb.append(String.format(Locale.ROOT, " %s %,d;", ClEmitter.RECHECK_REASONS[bit], reasons[bit]));
        }
        log("blocks flagged per reason:%s", sb);
        if (violations > 0) {
            throw new AssertionError(violations + " certified bounds violated");
        }
    }

    /** Decisions on densities moved to 0.999 of their bounds (either way) must still be exact: what could flip is rechecked. */
    @Test
    void certifiedPerturbed() throws Exception {
        GpuWorldgen wg = open("perturbed", "auto", " -D PERTURB");
        if (!wg.certified) {
            log("certified fp32 not available for this router: %s", wg.certifiedOff);
            return;
        }
        exactness(wg, "densities perturbed within their bounds");
    }

    private static ByteBuffer readOnGpuThread(long mem, long bytes) throws Exception {
        ByteBuffer out = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
        CompletableFuture<Void> done = new CompletableFuture<>();
        device.submit(() -> {
            try {
                device.cl.read(device.queue, mem, true, 0, out);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(60, TimeUnit.SECONDS);
        return out;
    }
}
