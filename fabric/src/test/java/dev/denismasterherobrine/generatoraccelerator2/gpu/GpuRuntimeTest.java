package dev.denismasterherobrine.generatoraccelerator2.gpu;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegions;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegionsTest;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard;
import dev.denismasterherobrine.generatoraccelerator2.biome.ClimateSearch;
import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateLeaf;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWriter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeRegions;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ChunkCompare;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuRuntime;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.SelfTest;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.GpuBiomeSourceInspectMixin;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkSamplerInvoker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
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

    private static GpuWorldgen.Beard beard(GpuWorldgen wg, Structures st, int cx, int cz) {
        List<Integer> p = new ArrayList<>();
        for (Beardifier.Rigid r : st.piecesFor(cx, cz)) {
            BoundingBox b = r.box();
            p.addAll(List.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), r.terrainAdjustment().ordinal(), r.groundLevelDelta()));
        }
        List<Integer> j = new ArrayList<>();
        for (JigsawJunction jj : st.junctionsFor(cx, cz)) {
            j.addAll(List.of(jj.getSourceX(), jj.getSourceGroundY(), jj.getSourceZ()));
        }
        // other mods' pieces, as GpuSteps.beard attaches them (tests set EnhancedBeard.testChunks)
        EnhancedBeard.Chunk enhanced = null;
        if (EnhancedBeard.testChunks != null) {
            enhanced = EnhancedBeard.testChunks.apply(new ChunkPos(cx, cz));
        }
        // the chunk's liquid-region table, as GpuSteps.beard attaches it (tests set LiquidRegions.testTables)
        int[] liquids = null;
        if (wg.liquidRegions) {
            LiquidRegions.Table t = LiquidRegions.table(
                LiquidRegions.regionsOf(setup.world.randomState), cx, cz);
            liquids = t == null ? null : LiquidRegions.gpu(t, wg.states);
        }
        return new GpuWorldgen.Beard(p.stream().mapToInt(Integer::intValue).toArray(), j.stream().mapToInt(Integer::intValue).toArray(), liquids, enhanced, -1);
    }

    /**
     * Other mods' terrain adaptation on the GPU ({@code beardifier.EnhancedBeard}): synthetic YUNG's API and Integrated API pieces and
     * junctions around every test origin, in the block kernels against GA2's CPU path, whose piece index adds the Java form of the
     * mods' density.
     */
    @Test
    void enhancedBeards() throws Exception {
        EnhancedBeard.Chunk chunk = syntheticEnhanced();
        EnhancedBeard.testChunks = pos -> chunk;
        try {
            String precision = System.getProperty("generatoraccelerator2.gpu.precision", "auto");
            GpuWorldgen wg = open("test-enhanced-" + precision, precision, System.getProperty("ga2.gpu.defines", ""));
            long chunks = exactness(wg, "enhanced beards, " + precision);
            if (chunks == 0) {
                throw new AssertionError("no chunk compared");
            }
        } finally {
            EnhancedBeard.testChunks = null;
        }
    }

    /** Pieces and junctions of both kinds near every origin {@link #exactness} uses, with kernels like the mods' (a Gaussian over the distance). */
    private static EnhancedBeard.Chunk syntheticEnhanced() {
        Random r = new Random(20261023L);
        var yung = new EnhancedBeard.Adaptation(
            EnhancedBeard.Kind.YUNG, 24, kernel(24, 6.0, r), 1, -1, false, false, false, 0, null);
        var yungCarve = new EnhancedBeard.Adaptation(
            EnhancedBeard.Kind.YUNG, 16, kernel(16, 4.0, r), -1, 0, false, false, false, 0, null);
        var integrated = new EnhancedBeard.Adaptation(
            EnhancedBeard.Kind.INTEGRATED, 20, kernel(20, 5.0, r), 0, 0, true, true, false, 0, null);
        var integratedUp = new EnhancedBeard.Adaptation(
            EnhancedBeard.Kind.INTEGRATED, 12, kernel(12, 3.0, r), 0, 0, true, false, true, 0, null);
        // YUNG's API 4.0 and Integrated API 1.5 take Mth.invSqrt (1 / sqrt)
        var integratedExact = new EnhancedBeard.Adaptation(
            EnhancedBeard.Kind.INTEGRATED, 18, kernel(18, 4.5, r), 0, 0, true, true, false, 0, null, true);
        var kinds = new EnhancedBeard.Adaptation[] { yung, yungCarve, integrated, integratedUp, integratedExact, null };
        List<EnhancedBeard.Piece> pieces = new ArrayList<>();
        List<EnhancedBeard.Junction> junctions = new ArrayList<>();
        int[][] origins = { { -2, -2 }, { 5, 6 }, { -1953, 771 }, { 117187, -117187 }, { 1048574, 1048574 } };
        for (int[] o : origins) {
            int bx = o[0] * 16, bz = o[1] * 16;
            for (int i = 0; i < 6; i++) {
                int x0 = bx - 8 + r.nextInt(72), z0 = bz - 8 + r.nextInt(72), y0 = 40 + r.nextInt(50);
                pieces.add(new EnhancedBeard.Piece(x0, y0, z0, x0 + 4 + r.nextInt(12), y0 + 3 + r.nextInt(8),
                    z0 + 4 + r.nextInt(12), kinds[r.nextInt(kinds.length)]));
            }
            for (int i = 0; i < 3; i++) {
                junctions.add(new EnhancedBeard.Junction(bx + r.nextInt(64), 50 + r.nextInt(40), bz + r.nextInt(64),
                    kinds[r.nextInt(kinds.length - 1)]));
            }
        }
        // the same list for every chunk: the CPU's index asks by the block's chunk, the GPU by the request's chunk, and corners sit on both
        return new EnhancedBeard.Chunk(EnhancedBeard.Kind.YUNG,
            pieces, junctions, null);
    }

    private static float[] kernel(int size, double spread, Random r) {
        float[] k = new float[size * size * size];
        int rad = size / 2;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    double d = (x - rad) * (x - rad) + (y - rad) * (y - rad) + (z - rad) * (z - rad);
                    k[z * size * size + x * size + y] = (float) Math.pow(Math.E, -d / (spread * spread)) * (0.5f + r.nextFloat());
                }
            }
        }
        return k;
    }

    /** The chunk's climate sampler, as the BIOMES step makes it for the quarts the GPU leaves to the CPU (ties). */
    private static Supplier<Climate.Sampler> tieSampler(ProtoChunk chunk, Beardifier beardifier) {
        return tieSampler(setup, chunk, beardifier);
    }

    private static Supplier<Climate.Sampler> tieSampler(GpuProbe.Setup s, ProtoChunk chunk, Beardifier beardifier) {
        return () -> ((NoiseChunkSamplerInvoker) chunk.getOrCreateNoiseChunk(
            c -> NoiseChunk.forChunk(c, s.world.randomState, beardifier, s.world.settings, s.world.fluidPicker,
                Blender.empty())))
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
            origins = Arrays.stream(extra.split(";")).map(p -> Arrays.stream(p.trim().split(",")).mapToInt(Integer::parseInt).toArray())
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
                    nf.add(wg.noise(pos, beard(wg, st, pos.x, pos.z)));
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
                        throw new AssertionError("noise not applicable: section writes " + SectionWriter.ENABLED
                            + "/" + HookTargets.allowed(HookTargets.NOISE_SECTION_WRITES)
                            + ", fill heights " + FillHeights.ENABLED + ", fits " + Apply.fits(wg, gpu)
                            + ", sections " + gpu.getSections().length + ", meta[0] " + n.meta()[0]);
                    }
                    ProtoChunk cpu = GpuProbe.refChunk(s, cx, cz, st);
                    String diff = ChunkCompare.blocks(cpu, gpu);
                    if (diff == null && b != null) {
                        diff = ChunkCompare.biomes(cpu, gpu, b::tie);
                    }
                    if (diff != null) {
                        // which side gives another answer when asked again (the GPU request alone, the CPU reference again)
                        ProtoChunk gpu2 = s.world.emptyChunk(cx, cz);
                        GpuWorldgen.NoiseResult n2 = wg.noise(new ChunkPos(cx, cz), beard(wg, st, cx, cz)).get(120, TimeUnit.SECONDS);
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
            for (var e : new TreeMap<>(wg.deviceNanos).entrySet()) {
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

    /**
     * Better Caves' liquid regions on the GPU ({@code aquifer.LiquidRegions}): synthetic tables applied in the block kernels, against
     * GA2's CPU path, whose aquifer fast path applies the same tables.
     */
    @Test
    void liquidRegions() throws Exception {
        LiquidRegions.testTables = LiquidRegionsTest::table;
        try {
            String precision = System.getProperty("generatoraccelerator2.gpu.precision", "auto");
            GpuWorldgen wg = open("test-liquids-" + precision, precision, System.getProperty("ga2.gpu.defines", ""));
            if (!wg.liquidRegions) {
                throw new AssertionError("the program does not apply liquid regions: " + wg.status());
            }
            long chunks = exactness(wg, "liquid regions, " + precision);
            if (chunks == 0) {
                throw new AssertionError("no chunk compared");
            }
        } finally {
            LiquidRegions.testTables = null;
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

    /** Vanilla's {@code RTree.Node.distance}, which is protected: the squared distances of the seven parameters. */
    private static final Climate.DistanceMetric<Holder<Biome>> DISTANCE =
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
        var list = ((GpuBiomeSourceInspectMixin) s.world.biomeSource).ga2$gpuParameters();
        var values = list.values();
        var placeholder = Holder.direct(values.get(0).getSecond().value());
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> region1 =
            new ArrayList<>();
        for (int k = 0; k < values.size(); k++) {
            var p = values.get(k);
            region1.add(k % 3 == 0 ? p.mapSecond(b -> placeholder) : k % 3 == 1 ? p.mapSecond(b -> values.get((p.getFirst().hashCode() & 0x7fffffff) % values.size()).getSecond()) : p);
        }
        Climate.RTree<Holder<Biome>>[] trees = new Climate.RTree[] {
            Climate.RTree.create(values), Climate.RTree.create(region1),
            Climate.RTree.create(List.of(values.get(5))) };
        BiomeRegions regions = new BiomeRegions() {
            @Override
            public Climate.RTree<Holder<Biome>>[] trees() {
                return trees;
            }

            @Override
            public int region(int qx, int qz) {
                return Math.floorMod(Math.floorDiv(qx, 5) + 3 * Math.floorDiv(qz, 7), 3);
            }

            @Override
            public Holder<Biome> find(Climate.TargetPoint target, int x, int y, int z) {
                // TerraBlender's findValuePositional
                var biome = trees[region(x, z)].search(target, DISTANCE);
                return deferred(biome) ? trees[0].search(target, DISTANCE) : biome;
            }

            @Override
            public boolean deferred(Holder<Biome> biome) {
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
                    if (diff == null && !Arrays.equals(afterGpu, wg.lastResults())) {
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
            throw new AssertionError("the regions were not all met: " + Arrays.toString(perRegion) + " quart columns, " + deferred + " placeholders");
        }
        log("EXACT (regions): %d chunks identical to the regions' CPU lookup (biomes and last results); quart columns per region %s; %d placeholder quarts; %d ties",
            chunks, Arrays.toString(perRegion), deferred, ties);
    }

    /**
     * The GPU's answers handed to the CPU's own lookups ({@code gpu.runtime.BiomeHand}), as for Biolith or Alex's Caves: the same regions
     * as {@link #biomeRegions}, the GPU not deciding the biomes. The chunk's fill runs a lookup of its own through GA2's searches, once
     * with the GPU's points and nearest leaves handed to it and once without, from the same last results. Biomes and last results must
     * match. Every handed pair of leaves (the nearest and the nearest of another biome) must be what Biolith's walk
     * ({@code FlatClimateTree.searchFittest}) finds from random last answers of two biomes.
     */
    @Test
    @SuppressWarnings("unchecked")
    void handedBiomes() throws Exception {
        GpuProbe.Setup s = setup();
        var list = ((GpuBiomeSourceInspectMixin) s.world.biomeSource).ga2$gpuParameters();
        var values = list.values();
        var placeholder = Holder.direct(values.get(0).getSecond().value());
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> region1 =
            new ArrayList<>();
        for (int k = 0; k < values.size(); k++) {
            var p = values.get(k);
            region1.add(k % 3 == 0 ? p.mapSecond(b -> placeholder) : k % 3 == 1 ? p.mapSecond(b -> values.get((p.getFirst().hashCode() & 0x7fffffff) % values.size()).getSecond()) : p);
        }
        Climate.RTree<Holder<Biome>>[] trees = new Climate.RTree[] {
            Climate.RTree.create(values), Climate.RTree.create(region1),
            Climate.RTree.create(List.of(values.get(5))) };
        BiomeRegions regions = new BiomeRegions() {
            @Override
            public Climate.RTree<Holder<Biome>>[] trees() {
                return trees;
            }

            @Override
            public int region(int qx, int qz) {
                return Math.floorMod(Math.floorDiv(qx, 5) + 3 * Math.floorDiv(qz, 7), 3);
            }

            @Override
            public Holder<Biome> find(Climate.TargetPoint target, int x, int y, int z) {
                var biome = ClimateSearch.search(trees[region(x, z)], target);
                return deferred(biome) ? ClimateSearch.search(trees[0], target) : biome;
            }

            @Override
            public boolean deferred(Holder<Biome> biome) {
                return biome == placeholder;
            }
        };
        GpuWorldgen.testRegions = l -> regions;
        GpuWorldgen.testHand = true;
        GpuWorldgen wg;
        try {
            wg = open("test-hand", "auto", "");
        } finally {
            GpuWorldgen.testRegions = null;
            GpuWorldgen.testHand = false;
        }
        if (wg.decidesBiomes() || !wg.handsBiomes()) {
            throw new AssertionError("answers not handed: " + wg.status());
        }
        BiomeResolver lookup = (x, y, z, sampler) -> regions.find(sampler.sample(x, y, z), x, y, z);
        long points0 = BiomeHand.POINTS.sum();
        long nearest0 = BiomeHand.NEAREST.sum();
        Random rnd = new Random(20261023L);
        int[][] origins = { { -2, -2 }, { 13, 14 }, { -1953, 771 }, { 117187, -117187 } };
        long chunks = 0, pairs = 0, walks = 0, unknown = 0, second = 0;
        for (int[] o : origins) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    int cx = o[0] + i, cz = o[1] + j;
                    Beardifier beard = Structures.none(cx, cz, 1, 1).beardifierFor(cx, cz);
                    GpuWorldgen.BiomeResult b = wg.biomes(new ChunkPos(cx, cz)).get(120, TimeUnit.SECONDS);
                    if (b == null || b.hand() == null || b.targets() == null) {
                        throw new AssertionError("chunk " + cx + "," + cz + ": no handed answers");
                    }
                    int[] hand = b.hand();
                    for (int q = 0; q < b.quarts().length; q++) {
                        Climate.TargetPoint t = wg.target(b, q);
                        for (int k = 0; k < 4; k += 2) {
                            int u = hand[q * 4 + k], p = hand[q * 4 + k + 1];
                            if (k == 0 && u == -1) {
                                unknown++;
                            }
                            if (u < 0 || p == -1 || t == null) {
                                continue;
                            }
                            second += k / 2;
                            pairs++;
                            walks += checkFittest(wg, u, p, t, rnd);
                        }
                    }
                    Object[] start = GpuVerify.handState(wg);
                    ProtoChunk gpu = s.world.emptyChunk(cx, cz);
                    var gpuSampler = tieSampler(gpu, beard).get();
                    BiomeHand.fill(wg, gpu, b, () -> {
                        gpu.fillBiomesFromNoise(lookup, gpuSampler);
                        return null;
                    });
                    var afterGpu = wg.lastResults();
                    GpuVerify.restoreHandState(wg, start);
                    ProtoChunk cpu = s.world.emptyChunk(cx, cz);
                    cpu.fillBiomesFromNoise(lookup, tieSampler(cpu, beard).get());
                    String diff = ChunkCompare.biomes(cpu, gpu, q -> false);
                    if (diff == null && !Arrays.equals(afterGpu, wg.lastResults())) {
                        diff = "the trees' last results after the fill differ";
                    }
                    if (diff != null) {
                        throw new AssertionError("handed biomes: chunk " + cx + "," + cz + ": " + diff + " (CPU vs handed)");
                    }
                    chunks++;
                }
            }
        }
        long points = BiomeHand.POINTS.sum() - points0;
        long nearest = BiomeHand.NEAREST.sum() - nearest0;
        if (points == 0 || nearest == 0 || pairs == 0 || second == 0) {
            throw new AssertionError("nothing handed: " + points + " points, " + nearest + " searches, " + pairs + " pairs (" + second + " in region 0 after a placeholder)");
        }
        log("EXACT (handed biomes): %d chunks identical to the CPU's own lookup (biomes and last results); %,d points and %,d searches handed;"
            + " %,d leaf pairs (%,d after a placeholder) equal to the walk from %,d random last answers; %,d quarts left to the CPU", chunks, points, nearest, pairs,
            second, walks, unknown);
    }

    /** Biolith's walk on the tree of GPU node {@code u} at {@code t} from random last answers of two biomes must end on {@code u} and {@code p}. */
    @SuppressWarnings("unchecked")
    private static int checkFittest(GpuWorldgen wg, int u, int p, Climate.TargetPoint t, Random rnd) {
        var tree = wg.biomeTree(wg.treeOf(u));
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) tree).ga2$flat();
        if (!flat.hasSubtrees()) {
            return 0; // a tree of one leaf: Biolith's own search answers it (BiolithSearch), so nothing is handed there
        }
        int n = flat.nodes();
        int[] keys = new int[n + 1];
        Map<Object, Integer> numbers = new HashMap<>();
        numbers.put("none", 0);
        List<Integer> leafIds = new ArrayList<>();
        for (int id = 0; id < n; id++) {
            var leaf = flat.leaf(id);
            if (leaf != null) {
                var h = (Holder<Biome>) leaf.value;
                keys[id] = numbers.computeIfAbsent(h.unwrapKey().<Object>map(k -> k.location()).orElse(h), k -> numbers.size());
                leafIds.add(id);
            }
        }
        int fu = ((Ga2ClimateLeaf) (Object) wg.leaf(u)).ga2$index();
        int fp = p < 0 ? -1 : ((Ga2ClimateLeaf) (Object) wg.leaf(p)).ga2$index();
        int walks = 0;
        for (int k = 0; k < 4; k++) {
            int su = -1, sp = -1;
            if (k > 0) {
                su = leafIds.get(rnd.nextInt(leafIds.size()));
                if (k > 1) {
                    for (int tries = 0; tries < 32; tries++) {
                        int c = leafIds.get(rnd.nextInt(leafIds.size()));
                        if (keys[c] != keys[su]) {
                            sp = c;
                            break;
                        }
                    }
                }
            }
            int[] ids = new int[2];
            long[] d = new long[2];
            flat.searchFittest(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness(), keys, su, sp, ids, d);
            if (ids[0] != fu || ids[1] != fp) {
                throw new AssertionError("handed leaves " + fu + "," + fp + " but the walk from " + su + "," + sp + " ends on " + ids[0] + "," + ids[1] + " at " + t);
            }
            walks++;
        }
        return walks;
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
                    fc.add(c.noise(pos, beard(c, st, pos.x, pos.z)));
                }
            }
            CompletableFuture.allOf(fc.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
            ByteBuffer hc = readOnGpuThread(bufC, blocks * 12);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    ChunkPos pos = new ChunkPos(bx + i, bz + j);
                    fd.add(d.noise(pos, beard(d, st, pos.x, pos.z)));
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
