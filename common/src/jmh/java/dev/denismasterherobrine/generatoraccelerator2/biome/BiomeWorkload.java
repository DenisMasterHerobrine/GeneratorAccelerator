package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatModules;

import java.util.Random;
import java.util.function.LongSupplier;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Runs biome searches and cache lookups inside Fabric's game class loader.
 */
public final class BiomeWorkload implements LongSupplier, java.util.function.Supplier<Object> {
    /**
     * Query points prepared during setup, in quantized climate units.
     */
    private final Climate.TargetPoint[] queries = new Climate.TargetPoint[4096];
    /**
     * Vanilla tree shared by the reference and flat searches.
     */
    private final Climate.RTree<Holder<Biome>> tree;
    /**
     * Parameter list used to call vanilla's search with its protected distance metric.
     */
    private final Climate.ParameterList<Holder<Biome>> parameters;
    /**
     * Reused callback for the vanilla batch; returning Boolean.TRUE avoids boxing the checksum.
     */
    private final java.util.function.Supplier<Boolean> vanillaBatch = this::runVanilla;
    /**
     * Checksum from the last vanilla batch.
     */
    private long vanillaSum;
    /**
     * CPU climate sampler for the fused and sample-then-search tests; null for other operations.
     */
    private final Climate.Sampler sampler;
    /**
     * Flat tree created by GA2's tree mixin.
     */
    private final FlatClimateTree flat;
    /**
     * Operation selected for this trial.
     */
    private final String operation;
    /**
     * True for unrelated query points; false for successive depths in a column.
     */
    private final boolean randomDistribution;
    /**
     * Biome ids by node index, with a zero sentinel at nodes().
     */
    private final int[] keys;
    /**
     * Scratch array for the two nearest leaf ids.
     */
    private final int[] ids = {-1, -1};
    /**
     * Scratch array for their squared distances.
     */
    private final long[] distances = new long[2];
    /**
     * Cache whose backing lookup always returns the same biome.
     */
    private final BiomeAnswers answers;
    /**
     * Cache whose backing lookup always reports a tie, so answers cannot be kept.
     */
    private final BiomeAnswers tied;
    /**
     * Previous flat-search result, or -1 before the first query.
     */
    private int last = -1;
    /**
     * Position in the query array; wraps at the end.
     */
    private int cursor;
    /**
     * Next quart x coordinate for cache misses, kept within the packed coordinate range.
     */
    private int miss;

    /**
     * Builds the vanilla parameter list and checks the flat search results before timing starts.
     *
     * @param preset       vanilla preset name, OVERWORLD or NETHER
     * @param distribution columns or random query order
     * @param operation    operation to measure
     */
    public BiomeWorkload(String preset, String distribution, String operation) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CompatModules.init();
        this.operation = operation;
        randomDistribution = distribution.equals("random");
        var registries = VanillaRegistries.createLookup();
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        var list = new MultiNoiseBiomeSourceParameterList(preset.equals("OVERWORLD")
                ? MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD : MultiNoiseBiomeSourceParameterList.Preset.NETHER, biomes).parameters();
        parameters = list;
        if (operation.equals("sampleAndSearch") || operation.equals("fusedLookup")) {
            var settingsKey = preset.equals("OVERWORLD")
                    ? net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD
                    : net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER;
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey).value();
            sampler = net.minecraft.world.level.levelgen.RandomState.create(settings,
                    registries.lookupOrThrow(Registries.NOISE), 0x6A2B10L).sampler();
            if (!FusedLookup.ready(true)) {
                throw new IllegalStateException("Fused biome lookup is disabled in the benchmark environment");
            }
            for (int i = 0; i < 256; i++) {
                var expected = parameters.findValue(sampler.sample(i, i % 96 - 16, -i));
                var actual = FusedLookup.lookup(parameters, i, i % 96 - 16, -i, sampler);
                if (actual == null || actual != expected) {
                    throw new IllegalStateException("Fused lookup fixture differs from sample and search");
                }
            }
        } else {
            sampler = null;
        }
        tree = list.index;
        flat = ((Ga2ClimateTree) (Object) tree).ga2$flat();
        keys = new int[flat.nodes() + 1];
        var numbers = new java.util.IdentityHashMap<Object, Integer>();
        for (int id = 0; id < flat.nodes(); id++) {
            if (flat.leaf(id) != null) {
                keys[id] = numbers.computeIfAbsent(flat.leaf(id).value, k -> numbers.size() + 1);
            }
        }
        Random random = new Random(0x6A2B10L);
        long[] q = new long[6];
        for (int i = 0; i < queries.length; i++) {
            if (distribution.equals("random") || i % 96 == 0) {
                for (int d = 0; d < 6; d++) {
                    q[d] = random.nextInt(24001) - 12000;
                }
            }
            if (distribution.equals("columns")) {
                q[4] = (i % 96) * 250L - 2000;
            }
            queries[i] = new Climate.TargetPoint(q[0], q[1], q[2], q[3], q[4], q[5]);
        }
        int referenceLast = -1;
        for (Climate.TargetPoint t : queries) {
            Holder<Biome> expected = ClimateSearch.vanilla(() -> parameters.findValueIndex(t));
            int walked = flat.search(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness(), referenceLast);
            int column = flat.searchColumns(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness(), referenceLast);
            if (flat.leaf(walked).value != expected || column != walked) {
                throw new IllegalStateException("Benchmark fixture differs from vanilla");
            }
            referenceLast = walked;
        }
        tree.lastResult.remove();
        Holder<Biome> biome = list.values().getFirst().getSecond();
        answers = new BiomeAnswers((x, y, z) -> biome);
        tied = new BiomeAnswers((x, y, z) -> {
            SearchTies.unsure(SearchTies.TIE);
            return biome;
        });
        answers.get(0, 0, 0);
    }

    /**
     * Runs 256 operations using the prepared query points.
     *
     * @return checksum of result ids or object identities, consumed by JMH
     */
    @Override
    public long getAsLong() {
        if (operation.equals("vanilla")) {
            ClimateSearch.vanilla(vanillaBatch);
            return vanillaSum;
        }
        long sum = 0;
        FlatClimateTree.enterFill();
        try {
            for (int i = 0; i < 256; i++) {
                Climate.TargetPoint t = queries[cursor++ & (queries.length - 1)];
                long a = t.temperature(), b = t.humidity(), c = t.continentalness();
                long d = t.erosion(), e = t.depth(), f = t.weirdness();
                switch (operation) {
                    case "flat" -> sum += last = flat.search(a, b, c, d, e, f, last);
                    case "columns" -> sum += last = flat.searchColumns(a, b, c, d, e, f, last);
                    case "climateSearch" ->
                            sum += System.identityHashCode(ClimateSearch.search(tree, a, b, c, d, e, f));
                    case "sampleAndSearch", "fusedLookup" -> {
                        int at = (cursor - 1) & (queries.length - 1);
                        int x = at / 96 * 13, y = at % 96 - 16, z = -x;
                        if (randomDistribution) {
                            x = (int) a;
                            z = (int) b;
                        }
                        Holder<Biome> biome = operation.equals("fusedLookup")
                                ? FusedLookup.lookup(parameters, x, y, z, sampler)
                                : parameters.findValue(sampler.sample(x, y, z));
                        sum += System.identityHashCode(biome);
                    }
                    case "fittest", "fittestColumns" -> {
                        if (operation.equals("fittest")) {
                            flat.searchFittest(a, b, c, d, e, f, keys, ids[0], ids[1], ids, distances);
                        } else {
                            flat.searchFittestColumns(a, b, c, d, e, f, keys, ids[0], ids[1], ids, distances);
                        }
                        sum += ids[0] + ids[1];
                    }
                    case "cacheHit" -> sum += System.identityHashCode(answers.get(0, 0, 0));
                    case "cacheMiss" -> sum += System.identityHashCode(answers.get(1 + (miss++ & 0x3FFFFF), 0, 0));
                    case "cacheTie" -> sum += System.identityHashCode(tied.get(0, 0, 0));
                    case "cacheBypass" -> sum += System.identityHashCode(answers.get(1 << 24, 0, 0));
                    case "watch" -> {
                        SearchTies.Watch watch = SearchTies.start();
                        sum += SearchTies.watching() && SearchTies.end(watch) ? 1 : 0;
                    }
                    default -> throw new IllegalArgumentException("Unknown operation " + operation);
                }
            }
        } finally {
            FlatClimateTree.exitFill();
        }
        return sum;
    }

    /**
     * Runs a vanilla batch under a single flat-search override.
     *
     * @return Boolean.TRUE; the checksum is stored separately to avoid boxing it
     */
    private Boolean runVanilla() {
        long sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += System.identityHashCode(parameters.findValueIndex(queries[cursor++ & (queries.length - 1)]));
        }
        vanillaSum = sum;
        return Boolean.TRUE;
    }

    /**
     * Builds a flat tree for the construction benchmark.
     *
     * @return a new tree, leaving the original leaves' mixin indices unchanged
     */
    @Override
    public Object get() {
        return FlatClimateTree.build(tree, (leaf, id) -> {
        });
    }
}
