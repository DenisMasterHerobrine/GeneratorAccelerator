package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.features.tree.TreeBees;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.ChunkCompare;
import dev.denismasterherobrine.generatoraccelerator2.mixin.features.ChunkGeneratorFeaturesAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.management.ManagementFactory;

/**
 * Whole decorations on real terrain (biomes, noise, surface and carvers of the overworld preset, no structures). A square of chunks
 * is decorated colour by colour, where a chunk's colour is its position modulo 4 on both axes. Decorations of one colour are at
 * least 4 chunks apart, so none reads what another writes.
 * <ul>
 * <li>{@link #fastPathsMatchVanilla}: the square decorated with GA2's fast paths and twice the vanilla way. Chunks the two vanilla
 * runs disagree on (bee nests) are left out. Every other chunk must match: sections as stored, heightmaps, post-processing marks,
 * scheduled ticks and block entities.</li>
 * <li>{@link #cpuCosts} ({@code -Dga2.decor.cpuCosts=true}): each top-level feature timed alone, by configured feature and by
 * feature class.</li>
 * </ul>
 * {@code -Dga2.decor.side=N} sets the square's side and {@code -Dga2.decor.seed=S} the world seed.
 */
public class DecorationGridTest {
    private static void log(String fmt, Object... args) {
        System.out.println("[DecorGrid] " + String.format(Locale.ROOT, fmt, args));
    }

    // ---- the terrain

    /** A square of chunks after CARVERS, x fastest. */
    record Grid(TerrainHarness.World w, int ox, int oz, int side, ProtoChunk[] chunks) {
        ProtoChunk at(int cx, int cz) {
            int dx = cx - ox, dz = cz - oz;
            return dx < 0 || dz < 0 || dx >= side || dz >= side ? null : chunks[dx + dz * side];
        }

        /** The (2r+1)^2 chunks around a chunk, x fastest. */
        ProtoChunk[] around(int cx, int cz, int r) {
            int n = 2 * r + 1;
            ProtoChunk[] out = new ProtoChunk[n * n];
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    out[(dx + r) + (dz + r) * n] = at(cx + dx, cz + dz);
                }
            }
            return out;
        }

        /** The chunks that get decorated: two rings in from the edge, so that a decoration's 5x5 lies inside the square. */
        boolean interior(int cx, int cz) {
            int dx = cx - ox, dz = cz - oz;
            return dx >= 2 && dz >= 2 && dx < side - 2 && dz < side - 2;
        }

        int interiorCount() {
            return (side - 4) * (side - 4);
        }
    }

    private interface Task {
        void run(int i) throws Exception;
    }

    private static void runAll(ExecutorService pool, int n, Task task) throws Exception {
        List<Future<?>> fs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int k = i;
            fs.add(pool.submit(() -> {
                task.run(k);
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
    }

    /** BIOMES, NOISE, SURFACE and CARVERS of every chunk of the square. The same arguments give the same chunks. */
    static Grid grid(TerrainHarness.World w, int ox, int oz, int side, ExecutorService pool) throws Exception {
        ProtoChunk[] chunks = new ProtoChunk[side * side];
        runAll(pool, chunks.length, i -> chunks[i] = w.noise(ox + i % side, oz + i / side));
        Grid g = new Grid(w, ox, oz, side, chunks);
        // the surface reads the biomes of the chunks around it, or the biome source's beyond the square
        BiomeManager manager = new BiomeManager((qx, qy, qz) -> {
            ProtoChunk c = g.at(qx >> 2, qz >> 2);
            return c != null ? c.getNoiseBiome(qx, qy, qz) : w.biomeSource.getNoiseBiome(qx, qy, qz, w.randomState.sampler());
        }, w.zoomSeed);
        runAll(pool, chunks.length, i -> {
            ProtoChunk c = chunks[i];
            w.generator.buildSurface(c, w.generationContext(), w.randomState, null, manager, w.registries.biomes, Blender.empty());
            c.setPersistedStatus(ChunkStatus.SURFACE);
        });
        Map<Long, ProtoChunk> standIns = new ConcurrentHashMap<>();
        runAll(pool, chunks.length, i -> {
            ProtoChunk c = chunks[i];
            TerrainHarness.vanillaCarvers(w, c, TerrainHarness.carverNeighbourhood(w, c, standIns));
            c.setPersistedStatus(ChunkStatus.CARVERS);
        });
        return g;
    }

    /** The colours in order, each with its chunks in z then x order. */
    static List<List<ChunkPos>> waves(Grid g) {
        List<List<ChunkPos>> out = new ArrayList<>();
        for (int c = 0; c < 16; c++) {
            List<ChunkPos> wave = new ArrayList<>();
            for (int dz = 0; dz < g.side; dz++) {
                for (int dx = 0; dx < g.side; dx++) {
                    int cx = g.ox + dx, cz = g.oz + dz;
                    if (g.interior(cx, cz) && Math.floorMod(cx, 4) + 4 * Math.floorMod(cz, 4) == c) {
                        wave.add(new ChunkPos(cx, cz));
                    }
                }
            }
            out.add(wave);
        }
        return out;
    }

    // ---- decoration

    static DimensionType dimension(TerrainHarness.World w) {
        return w.registries.lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
    }

    static HarnessLevel level(Grid g, int cx, int cz) {
        TerrainHarness.World w = g.w;
        return new HarnessLevel(g.around(cx, cz, 2), 2, cx, cz, w.heights, dimension(w), w.seed, w.randomState, w.registries.access, true);
    }

    /** How {@link #decorate} places one top-level feature: {@code place} places it as the decoration loop does. */
    interface Each {
        void place(PlacedFeature pf, int step, Runnable place);
    }

    /** {@code ChunkStatusTasks.generateFeatures} without structures: the final heightmaps primed, then {@code applyBiomeDecoration}'s loop. */
    static void decorate(Grid g, HarnessLevel level, ProtoChunk chunk, Each each) {
        TerrainHarness.World w = g.w;
        HeightmapPriming.prime(chunk, ChunkStatus.FINAL_HEIGHTMAPS, Heightmap::primeHeightmaps);
        ChunkPos cp = chunk.getPos();
        BlockPos origin = new BlockPos(cp.getMinBlockX(), level.getMinY(), cp.getMinBlockZ());
        Set<Holder<Biome>> set = new ObjectArraySet<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (LevelChunkSection s : level.getChunk(cp.x() + dx, cp.z() + dz).getSections()) {
                    s.getBiomes().getAll(set::add);
                }
            }
        }
        set.retainAll(w.biomeSource.possibleBiomes());
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decoration = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        List<FeatureSorter.StepFeatureData> steps = ((ChunkGeneratorFeaturesAccessor) (Object) w.generator).ga2$featuresPerStep().get();
        int n = Math.max(GenerationStep.Decoration.values().length, steps.size());
        for (int j = 0; j < n && j < steps.size(); j++) {
            FeatureSorter.StepFeatureData data = steps.get(j);
            IntOpenHashSet indices = new IntOpenHashSet();
            for (Holder<Biome> b : set) {
                List<HolderSet<PlacedFeature>> features = w.generator.getBiomeGenerationSettings(b).features();
                if (j < features.size()) {
                    for (Holder<PlacedFeature> h : features.get(j)) {
                        indices.add(data.indexMapping().applyAsInt(h.value()));
                    }
                }
            }
            int[] sorted = indices.toIntArray();
            Arrays.sort(sorted);
            for (int idx : sorted) {
                random.setFeatureSeed(decoration, idx, j);
                PlacedFeature pf = data.features().get(idx);
                each.place(pf, j, () -> {
                    try {
                        pf.placeWithBiomeCheck(level, w.generator, random, origin);
                    } catch (UnsupportedOperationException e) {
                        // fossils ask for the server level, which the harness has none of. Every run stops at the same point
                    }
                });
            }
        }
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }

    /** Decorates the square in wave order, each placement through {@code each}. */
    static void decorateAll(Grid g, Each each) {
        for (List<ChunkPos> wave : waves(g)) {
            for (ChunkPos p : wave) {
                decorate(g, level(g, p.x(), p.z()), g.at(p.x(), p.z()), each);
            }
        }
    }

    // ---- comparing

    /** The first difference between two decorated chunks, or null. */
    static String compare(ProtoChunk a, ProtoChunk b, TerrainHarness.World w) {
        String d = ChunkCompare.blocks(a, b);
        if (d != null) {
            return d;
        }
        for (Heightmap.Types type : ChunkStatus.FINAL_HEIGHTMAPS) {
            if (a.hasPrimedHeightmap(type) != b.hasPrimedHeightmap(type)) {
                return "heightmap presence " + type + ": " + a.hasPrimedHeightmap(type) + " vs " + b.hasPrimedHeightmap(type);
            }
            if (a.hasPrimedHeightmap(type) && !Arrays.equals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData())) {
                return "heightmap " + type;
            }
        }
        String ta = ((ProtoChunkTicks<?>) a.getBlockTicks()).scheduledTicks() + " " + ((ProtoChunkTicks<?>) a.getFluidTicks()).scheduledTicks();
        String tb = ((ProtoChunkTicks<?>) b.getBlockTicks()).scheduledTicks() + " " + ((ProtoChunkTicks<?>) b.getFluidTicks()).scheduledTicks();
        if (!ta.equals(tb)) {
            return "scheduled ticks: " + (ta.length() > 600 ? ta.substring(0, 600) + "..." : ta) + " vs " + (tb.length() > 600 ? tb.substring(0, 600) + "..." : tb);
        }
        if (!new TreeMap<>(a.getBlockEntityNbts()).toString().equals(new TreeMap<>(b.getBlockEntityNbts()).toString())) {
            return "block entity tags";
        }
        if (!a.getBlockEntities().keySet().equals(b.getBlockEntities().keySet())) {
            return "block entities";
        }
        for (Map.Entry<BlockPos, BlockEntity> e : a.getBlockEntities().entrySet()) {
            String x = e.getValue().saveWithFullMetadata(w.registries.access).toString();
            String y = b.getBlockEntities().get(e.getKey()).saveWithFullMetadata(w.registries.access).toString();
            if (!x.equals(y)) {
                return "block entity at " + e.getKey() + ": " + x + " vs " + y;
            }
        }
        return null;
    }

    // ---- tests

    @Test
    void fastPathsMatchVanilla() throws Exception {
        long seed = Long.getLong("ga2.decor.seed", 1234567L);
        int side = Integer.getInteger("ga2.decor.side", 14);
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        try {
            Grid vanilla = grid(w, 0, 0, side, pool), again = grid(w, 0, 0, side, pool), fast = grid(w, 0, 0, side, pool);
            FeatureHooks.runVanilla(() -> decorateAll(vanilla, (pf, step, place) -> place.run()));
            FeatureHooks.runVanilla(() -> decorateAll(again, (pf, step, place) -> place.run()));
            decorateAll(fast, (pf, step, place) -> place.run());
            int compared = 0, unstable = 0;
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < side * side; i++) {
                int cx = i % side, cz = i / side;
                if (!vanilla.interior(cx, cz)) {
                    continue;
                }
                if (compare(vanilla.chunks[i], again.chunks[i], w) != null) {
                    unstable++;
                    continue;
                }
                compared++;
                String d = compare(vanilla.chunks[i], fast.chunks[i], w);
                if (d != null) {
                    failures.add("chunk " + cx + "," + cz + ": " + d);
                }
            }
            log("fast paths against vanilla: %d chunks compared, %d differ, %d left out (vanilla differs from itself)", compared, failures.size(), unstable);
            assertTrue(compared > vanilla.interiorCount() / 2, "too few chunks compared: " + compared);
            assertTrue(failures.isEmpty(), String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Where the CPU's decoration time goes: each top-level feature timed alone, by configured feature and by feature class, over
     * {@code ga2.decor.rounds} squares (3 by default). The first round mostly warms the JIT up.
     */
    @Test
    @EnabledIfSystemProperty(named = "ga2.decor.cpuCosts", matches = "true")
    void cpuCosts() throws Exception {
        long seed = Long.getLong("ga2.decor.seed", 1234567L);
        int side = Integer.getInteger("ga2.decor.side", 36);
        boolean vanilla = Boolean.getBoolean("ga2.decor.vanilla");
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        try {
            for (int round = 0; round < Integer.getInteger("ga2.decor.rounds", 3); round++) {
                Grid g = grid(w, 40 * round, -20 * round, side, pool);
                Map<String, Long> byName = new HashMap<>(), byKind = new HashMap<>(), bytesByKind = new HashMap<>(), bytesByName = new HashMap<>();
                long[] sums = new long[4];
                Runnable run = () -> {
                    for (List<ChunkPos> wave : waves(g)) {
                        for (ChunkPos p : wave) {
                            HarnessLevel level = level(g, p.x(), p.z());
                            long t0 = System.nanoTime();
                            decorate(g, level, g.at(p.x(), p.z()), (pf, step, place) -> {
                                long b = ALLOCATED.getCurrentThreadAllocatedBytes();
                                long s = System.nanoTime();
                                place.run();
                                long ns = System.nanoTime() - s;
                                long bytes = ALLOCATED.getCurrentThreadAllocatedBytes() - b;
                                var f = pf.feature().value().feature();
                                String name = pf.feature().unwrapKey().map(k -> k.identifier().getPath()).orElse(f.getClass().getSimpleName());
                                byName.merge(name, ns, Long::sum);
                                byKind.merge(f.getClass().getSimpleName(), ns, Long::sum);
                                bytesByName.merge(name, bytes, Long::sum);
                                bytesByKind.merge(f.getClass().getSimpleName(), bytes, Long::sum);
                                sums[1] += ns;
                                sums[2]++;
                                sums[3] += bytes;
                            });
                            sums[0] += System.nanoTime() - t0;
                        }
                    }
                };
                if (vanilla) {
                    FeatureHooks.runVanilla(run);
                } else {
                    run.run();
                }
                int interior = g.interiorCount();
                log("cpu costs%s, round %d: %d chunks, %.1f features per chunk, %.3f ms per chunk in all, %.3f ms in the features", vanilla ? " (vanilla)" : "",
                    round, interior, (double) sums[2] / interior, sums[0] / 1e6 / interior, sums[1] / 1e6 / interior);
                log("  by feature class, ms per chunk: %s", top(byKind, interior, 40));
                log("  by feature, ms per chunk: %s", top(byName, interior, 40));
                log("  allocated in the features: %.1f KB per chunk; by feature class, KB per chunk: %s", sums[3] / 1024.0 / interior, topBytes(bytesByKind, interior, 25));
                log("  allocated by feature, KB per chunk: %s", topBytes(bytesByName, interior, 30));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A switch off and on over the same terrain ({@code -Dga2.decor.ab=<name>}, one of {@link #SWITCHES}). Each of {@code ga2.decor.rounds}
     * squares (2 by default) is built once, then copied and decorated {@code ga2.decor.reps} times per side (6 by default), the sides
     * in ABBA order. Medians per side, of the whole decoration and of the costliest feature classes. Machine noise between two
     * decorations of a square is several percent, so only medians over many copies tell a few percent apart.
     */
    @Test
    @EnabledIfSystemProperty(named = "ga2.decor.ab", matches = ".+")
    void abCosts() throws Exception {
        long seed = Long.getLong("ga2.decor.seed", 1234567L);
        int side = Integer.getInteger("ga2.decor.side", 36);
        int reps = Integer.getInteger("ga2.decor.reps", 6);
        String ab = System.getProperty("ga2.decor.ab");
        Consumer<Boolean> toggle = SWITCHES.get(ab);
        assertTrue(toggle != null, "no switch " + ab + " among " + SWITCHES.keySet());
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        try {
            for (int round = 0; round < Integer.getInteger("ga2.decor.rounds", 2); round++) {
                Grid base = grid(w, 40 * (round + 1), -20 * (round + 1), side, pool);
                // the JIT's first pass over this square's features
                toggle.accept(true);
                decorateAll(copy(base, pool), (pf, step, place) -> place.run());
                List<List<Long>> totals = List.of(new ArrayList<>(), new ArrayList<>());
                List<Map<String, List<Long>>> kinds = List.of(new HashMap<>(), new HashMap<>());
                for (int rep = 0; rep < 2 * reps; rep++) {
                    boolean on = ((rep & 1) == 1) != (((rep >> 1) & 1) == 1);
                    Grid g = copy(base, pool);
                    toggle.accept(on);
                    Map<String, Long> byKind = new HashMap<>();
                    long t0 = System.nanoTime();
                    decorateAll(g, (pf, step, place) -> {
                        long s = System.nanoTime();
                        place.run();
                        byKind.merge(pf.feature().value().feature().getClass().getSimpleName(), System.nanoTime() - s, Long::sum);
                    });
                    long ns = System.nanoTime() - t0;
                    int arm = on ? 1 : 0;
                    totals.get(arm).add(ns);
                    for (Map.Entry<String, Long> e : byKind.entrySet()) {
                        kinds.get(arm).computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                    }
                }
                int interior = base.interiorCount();
                double off = median(totals.get(0)) / 1e6 / interior, on = median(totals.get(1)) / 1e6 / interior;
                StringBuilder b = new StringBuilder();
                List<Map.Entry<String, List<Long>>> es = new ArrayList<>(kinds.get(0).entrySet());
                es.sort((x, y) -> Double.compare(median(y.getValue()), median(x.getValue())));
                for (int i = 0; i < Math.min(8, es.size()); i++) {
                    String k = es.get(i).getKey();
                    List<Long> onList = kinds.get(1).getOrDefault(k, List.of(0L));
                    b.append(String.format(Locale.ROOT, "%s%s %.3f -> %.3f", i == 0 ? "" : ", ", k, median(es.get(i).getValue()) / 1e6 / interior,
                        median(onList) / 1e6 / interior));
                }
                log("%s, square %d: %.3f -> %.3f ms per chunk (%+.1f%%), medians of %d each; by class: %s", ab, round, off, on, 100.0 * (on - off) / off, reps, b);
            }
            toggle.accept(true);
        } finally {
            pool.shutdownNow();
        }
    }

    static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n == 0 ? 0 : (n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
    }

    /** A copy of the square's chunks as CARVERS left them: block states through the codec, biomes as they are (decoration writes none). */
    static Grid copy(Grid g, ExecutorService pool) throws Exception {
        ProtoChunk[] out = new ProtoChunk[g.chunks.length];
        runAll(pool, out.length, i -> {
            ProtoChunk src = g.chunks[i];
            LevelChunkSection[] sections = new LevelChunkSection[src.getSections().length];
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection sec = src.getSection(s);
                @SuppressWarnings("unchecked")
                var biomes = ((PalettedContainer<Holder<Biome>>) sec.getBiomes()).copy();
                sections[s] = new LevelChunkSection(HarnessWorld.cloneStates(sec.getStates()), biomes);
            }
            ProtoChunk c = new ProtoChunk(src.getPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(), g.w.heights, g.w.registries.containers, null);
            for (Map.Entry<Heightmap.Types, Heightmap> e : src.getHeightmaps()) {
                c.setHeightmap(e.getKey(), e.getValue().getRawData().clone());
            }
            c.setPersistedStatus(src.getPersistedStatus());
            out[i] = c;
        });
        return new Grid(g.w, g.ox, g.oz, g.side, out);
    }

    /** Switches the cost bench compares in one run ({@code -Dga2.decor.ab=<name>}): each square is decorated with the switch off and on. */
    static final Map<String, Consumer<Boolean>> SWITCHES = Map.of("treeBees", on -> TreeBees.ENABLED = on);

    /** Bytes the calling thread allocated so far (HotSpot's per-thread counter, exact up to the TLAB in use). */
    private static final ThreadMXBean ALLOCATED = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** The {@code k} largest of a name-to-bytes map, as KB per chunk. */
    static String topBytes(Map<String, Long> m, int chunks, int k) {
        List<Map.Entry<String, Long>> es = new ArrayList<>(m.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(k, es.size()); i++) {
            b.append(String.format(Locale.ROOT, "%s%s %.1f", i == 0 ? "" : ", ", es.get(i).getKey(), es.get(i).getValue() / 1024.0 / chunks));
        }
        return b.toString();
    }

    /** The {@code k} largest of a name-to-nanoseconds map, as ms per chunk. */
    static String top(Map<String, Long> m, int chunks, int k) {
        List<Map.Entry<String, Long>> es = new ArrayList<>(m.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(k, es.size()); i++) {
            b.append(String.format(Locale.ROOT, "%s%s %.3f", i == 0 ? "" : ", ", es.get(i).getKey(), es.get(i).getValue() / 1e6 / chunks));
        }
        return b.toString();
    }
}
