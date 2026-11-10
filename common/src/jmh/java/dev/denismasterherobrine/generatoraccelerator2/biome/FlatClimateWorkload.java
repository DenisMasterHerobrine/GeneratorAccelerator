package dev.denismasterherobrine.generatoraccelerator2.biome;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatModules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Builds world-sampled and synthetic inputs, then runs the selected FlatClimateTree operation.
 */
public final class FlatClimateWorkload implements LongSupplier {
    /**
     * Caps synthetic trees at 4096 leaves to keep stress runs short.
     */
    public static final int MAX_LEAVES = 4096;
    /**
     * Operation selected for this trial.
     */
    private final String system;
    /**
     * Vanilla parameter lists and their flat trees.
     */
    private final List<Fixture> fixtures = new ArrayList<>();
    /**
     * Queries prepared during setup, outside the timed loop.
     */
    private final List<Query> queries = new ArrayList<>();
    /**
     * Previous leaf ids for each tree, used to seed the next search.
     */
    private final int[][] previous;
    /**
     * Scratch array for the two nearest squared distances.
     */
    private final long[] distances = new long[2];
    /**
     * Tree used to test cache backoff when columns are rarely reused.
     */
    private final Fixture adaptive;
    /**
     * Scattered world samples or synthetic points for the cache-backoff test.
     */
    private Climate.TargetPoint[] adaptiveTargets;
    /**
     * Position in the query list.
     */
    private int cursor;
    /**
     * Position in the adaptive-cache query array; wraps at the end.
     */
    private int sequence;
    /**
     * Keeps the last built tree reachable so the JVM cannot remove its allocation.
     */
    private volatile FlatClimateTree constructed;

    /**
     * A vanilla tree, its flat copy and the biome keys used by two-nearest searches.
     */
    private static final class Fixture {
        /**
         * Vanilla parameter list used to check search results.
         */
        final Climate.ParameterList<?> list;
        /**
         * Flat tree under test.
         */
        final FlatClimateTree flat;
        /**
         * Biome ids start at 1; keys[nodes] is the zero sentinel for a missing result.
         */
        final int[] keys;

        /**
         * Gets the flat tree and assigns an id to each distinct biome value.
         *
         * @param list vanilla parameter list
         */
        Fixture(Climate.ParameterList<?> list) {
            this.list = list;
            flat = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
            keys = new int[flat.nodes() + 1];
            var numbers = new java.util.IdentityHashMap<Object, Integer>();
            for (int i = 0; i < flat.nodes(); i++) {
                if (flat.leaf(i) != null) {
                    keys[i] = numbers.computeIfAbsent(flat.leaf(i).value, k -> numbers.size() + 1);
                }
            }
        }
    }

    /**
     * A query and its nearest leaf ids, calculated during setup.
     */
    private record Query(int tree, Climate.TargetPoint target, int first, int second) {
    }

    /**
     * Prepares and checks the inputs before timing starts.
     *
     * @param scenario realistic or absurd
     * @param system   operation to measure
     */
    public FlatClimateWorkload(String scenario, String system) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CompatModules.init();
        this.system = system;
        if (scenario.equals("realistic")) {
            realistic();
        } else if (scenario.equals("absurd")) {
            absurd();
        } else {
            throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
        previous = new int[fixtures.size()][2];
        for (int[] pair : previous) {
            Arrays.fill(pair, -1);
        }
        adaptive = fixtures.get(scenario.equals("realistic") ? 0 : 5);
        validate();
        if (system.equals("fittest") || system.equals("fittestColumns") || system.equals("walkFittestColumns")
                || system.equals("reusedFittest")) {
            // Two-nearest searches need a subtree. Other operations still test single-leaf roots.
            queries.removeIf(q -> !fixtures.get(q.tree).flat.hasSubtrees());
        }
        FlatClimateTree.forgetWorlds();
        if (system.equals("adaptiveCache")) {
            if (adaptiveTargets == null) {
                adaptiveTargets = new Climate.TargetPoint[8192];
                Random random = new Random(98123L);
                for (int i = 0; i < adaptiveTargets.length; i++) {
                    adaptiveTargets[i] = new Climate.TargetPoint(random.nextInt(200001), random.nextInt(200001),
                            random.nextInt(200001), random.nextInt(200001), 0, random.nextInt(200001));
                }
            }
            long before = FlatClimateTree.COLUMNS_OFF.sum();
            FlatClimateTree.enterFill();
            try {
                for (int i = 0; i < 8192 && FlatClimateTree.COLUMNS_OFF.sum() == before; i++) {
                    adaptiveSearch();
                }
            } finally {
                FlatClimateTree.exitFill();
            }
            if (FlatClimateTree.COLUMNS_OFF.sum() == before) {
                throw new IllegalStateException("Adaptive column backoff was not exercised");
            }
        }
    }

    /**
     * Samples four chunks per seed and dimension in vanilla's section/x/y/z fill order.
     */
    private void realistic() {
        var registries = VanillaRegistries.createLookup();
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        for (boolean nether : new boolean[]{false, true}) {
            int tree = fixtures.size();
            fixtures.add(new Fixture(new MultiNoiseBiomeSourceParameterList(nether
                    ? MultiNoiseBiomeSourceParameterList.Preset.NETHER : MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, biomes).parameters()));
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD).value();
            for (long seed : new long[]{1234567L, -918273645L}) {
                var sampler = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed).sampler();
                if (system.equals("adaptiveCache") && adaptiveTargets == null) {
                    adaptiveTargets = new Climate.TargetPoint[8192];
                    Random scattered = new Random(98123L);
                    for (int i = 0; i < adaptiveTargets.length; i++) {
                        adaptiveTargets[i] = sampler.sample(scattered.nextInt(2000001) - 1000000,
                                scattered.nextInt(96) - 16, scattered.nextInt(2000001) - 1000000);
                    }
                }
                for (int chunk = 0; chunk < 4; chunk++) {
                    int baseX = (chunk % 2) * 4 + (seed > 0 ? -1200 : 18000);
                    int baseZ = (chunk / 2) * 4 + (seed > 0 ? 3200 : -24000);
                    int minSection = nether ? 0 : -4, maxSection = nether ? 8 : 20;
                    for (int section = minSection; section < maxSection; section++) {
                        for (int x = 0; x < 4; x++) {
                            for (int y = 0; y < 4; y++) {
                                for (int z = 0; z < 4; z++) {
                                    add(tree, sampler.sample(baseX + x, section * 4 + y, baseZ + z));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the stress cases: ties, 64/65 depth groups, overflowing distances and small trees.
     */
    private void absurd() {
        fixtures.add(new Fixture(synthetic(MAX_LEAVES, 64, true, false)));
        fixtures.add(new Fixture(synthetic(MAX_LEAVES, 65, false, false)));
        fixtures.add(new Fixture(synthetic(256, 4, false, true)));
        fixtures.add(new Fixture(synthetic(1, 1, true, false)));
        fixtures.add(new Fixture(synthetic(8, 4, false, false)));
        fixtures.add(new Fixture(synthetic(MAX_LEAVES, 4, false, false)));
        Random random = new Random(0xA85D12L);
        for (int tree = 0; tree < fixtures.size(); tree++) {
            for (int i = 0; i < 1024; i++) {
                long[] q = new long[6];
                for (int d = 0; d < 6; d++) {
                    q[d] = i % 4 == 0 ? 0 : random.nextInt(200001) - 100000;
                }
                if (i % 16 == 15) {
                    q[0] = 1L << 32; // Forces squared-distance overflow, well beyond normal climate values.
                }
                add(tree, new Climate.TargetPoint(q[0], q[1], q[2], q[3], q[4], q[5]));
            }
        }
        if (fixtures.get(0).flat.groupCount() != 64 || fixtures.get(1).flat.groupCount() != -1
                || fixtures.get(2).flat.groupCount() != -1 || fixtures.get(3).flat.hasSubtrees()) {
            throw new IllegalStateException("Stress fixture did not reach the intended group/root branches");
        }
    }

    /**
     * Creates a synthetic vanilla tree with the requested depth groups.
     *
     * @param leaves leaf count, 1..4096
     * @param groups number of distinct depth ranges
     * @param ties   whether all non-depth intervals are identical
     * @param huge   whether to put bounds outside the range supported by distance pruning
     * @return parameter list with at most MAX_LEAVES entries
     */
    private static Climate.ParameterList<Integer> synthetic(int leaves, int groups, boolean ties, boolean huge) {
        if (leaves < 1 || leaves > MAX_LEAVES) {
            throw new IllegalArgumentException("Unbounded stress fixture");
        }
        List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>(leaves);
        Random random = new Random(456L);
        for (int i = 0; i < leaves; i++) {
            Climate.Parameter[] p = new Climate.Parameter[6];
            for (int d = 0; d < 6; d++) {
                long lo = ties ? 0 : (random.nextInt(17) - 8) * 2500L;
                if (huge) {
                    lo += 1L << 32;
                }
                p[d] = new Climate.Parameter(lo, lo + (ties ? 0 : 5000));
            }
            long depth = (i % groups) * 100L;
            p[4] = new Climate.Parameter(depth, depth + 50);
            entries.add(Pair.of(new Climate.ParameterPoint(p[0], p[1], p[2], p[3], p[4], p[5], 0), i % 16));
        }
        return new Climate.ParameterList<>(entries);
    }

    /**
     * Adds a query and calculates its expected leaf ids during setup.
     *
     * @param tree index in the fixture list
     * @param t    climate point to look up
     */
    private void add(int tree, Climate.TargetPoint t) {
        Fixture f = fixtures.get(tree);
        int[] ids = {-1, -1};
        if (f.flat.hasSubtrees()) {
            f.flat.searchFittest(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness(),
                    f.keys, -1, -1, ids, distances);
        } else {
            ids[0] = 0;
        }
        queries.add(new Query(tree, t, ids[0], ids[1]));
    }

    /**
     * Checks sampled queries against vanilla, then compares the flat and column search variants.
     */
    public void validate() {
        int[] totals = new int[fixtures.size()];
        for (Query query : queries) {
            totals[query.tree]++;
        }
        int[] seen = new int[fixtures.size()];
        int[] checked = new int[fixtures.size()];
        for (Query query : queries) {
            if (seen[query.tree]++ % Math.max(1, totals[query.tree] / 32) != 0 || checked[query.tree] >= 32) {
                continue;
            }
            checked[query.tree]++;
            Fixture f = fixtures.get(query.tree);
            Climate.TargetPoint t = query.target;
            long a = t.temperature(), b = t.humidity(), c = t.continentalness(), d = t.erosion(), e = t.depth(), g = t.weirdness();
            f.list.index.lastResult.remove();
            Object expected = ClimateSearch.vanilla(() -> f.list.findValueIndex(t));
            int id = f.flat.search(a, b, c, d, e, g, -1);
            require(f.flat.leaf(id).value == expected, "vanilla leaf value");
            require(id == ((Ga2ClimateLeaf) (Object) f.list.index.lastResult.get()).ga2$index(), "vanilla leaf identity");
            FlatClimateTree.enterFill();
            try {
                require(f.flat.searchColumns(a, b, c, d, e, g, -1) == id, "column search");
                require(f.flat.walkColumns(a, b, c, d, e, g, -1) == id, "column walk");
                if (!f.flat.hasSubtrees()) {
                    continue;
                }
                int[] reference = {-1, -1}, actual = {-1, -1};
                long[] refDistance = new long[2], actualDistance = new long[2];
                f.flat.searchFittest(a, b, c, d, e, g, f.keys, -1, -1, reference, refDistance);
                f.flat.searchFittestColumns(a, b, c, d, e, g, f.keys, -1, -1, actual, actualDistance);
                require(Arrays.equals(reference, actual) && Arrays.equals(refDistance, actualDistance), "fittest columns");
                f.flat.walkFittestColumns(a, b, c, d, e, g, f.keys, -1, -1, actual, actualDistance);
                require(Arrays.equals(reference, actual) && Arrays.equals(refDistance, actualDistance), "fittest column walk");
                var reused = f.flat.fittestColumns(a, b, c, d, e, g, f.keys, -1, -1);
                require(Arrays.equals(reference, reused.ids) && Arrays.equals(refDistance, reused.distance), "reused result");
                require(reference[0] >= 0 && f.flat.distanceTo(reference[0], a, b, c, d, e, g) == refDistance[0], "nearest distance");
                validateTies(f, t, reference, refDistance);
            } finally {
                FlatClimateTree.exitFill();
            }
        }
        require(queries.size() <= 32768, "bounded query count");
    }

    /**
     * Checks ties by scanning every leaf. Skips inputs whose squared distances could overflow.
     * <p>For each leaf, {@code D = sum(distance(parameter[d], q[d])^2)}, d=0..6.
     * Here parameter[d] is the leaf interval, q[0..5] are the target climate coordinates,
     * and q[6]=0 is the offset. Counting equal D values avoids relying on tree pruning.
     *
     * @param fixture  tree to check
     * @param target   climate point
     * @param ids      nearest leaf ids returned by the plain tree walk
     * @param distance squared distances returned by that walk
     */
    private static void validateTies(Fixture fixture, Climate.TargetPoint target, int[] ids, long[] distance) {
        long[] q = {target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness(), 0};
        for (long value : q) {
            if (value < -(1L << 29) || value > (1L << 29)) {
                return;
            }
        }
        boolean uniqueLeaf = true, uniqueValue = true, uniqueSecond = true;
        for (int i = 0; i < fixture.flat.nodes(); i++) {
            var leaf = fixture.flat.leaf(i);
            if (leaf == null) {
                continue;
            }
            long sum = 0;
            for (int d = 0; d < 7; d++) {
                var p = leaf.parameterSpace[d];
                if (p.min() < -(1L << 29) || p.max() > (1L << 29)) {
                    return;
                }
                long delta = p.distance(q[d]);
                sum += delta * delta;
            }
            if (sum == distance[0] && i != ids[0]) {
                uniqueLeaf = false;
                if (leaf.value != fixture.flat.leaf(ids[0]).value) {
                    uniqueValue = false;
                }
            }
            if (ids[1] >= 0 && sum == distance[1] && fixture.keys[i] != fixture.keys[ids[0]]
                    && fixture.keys[i] != fixture.keys[ids[1]]) {
                uniqueSecond = false;
            }
        }
        var flat = fixture.flat;
        require(flat.alone(q[0], q[1], q[2], q[3], q[4], q[5], ids[0]) == uniqueLeaf, "leaf ties");
        require(flat.alone(q[0], q[1], q[2], q[3], q[4], q[5], ids[0], true) == uniqueValue, "value ties");
        require(flat.secondAlone(q[0], q[1], q[2], q[3], q[4], q[5], ids[1], fixture.keys, fixture.keys[ids[0]]) == uniqueSecond,
                "second biome ties");
    }

    /**
     * Stops setup if a query or reference result is wrong.
     *
     * @param condition check that must pass
     * @param name      description included in the failure message
     */
    private static void require(boolean condition, String name) {
        if (!condition) {
            throw new IllegalStateException("FlatClimateTree fixture failed: " + name);
        }
    }

    /**
     * Runs 64 operations using the prepared queries and scratch arrays.
     *
     * @return checksum of the results, so the JVM cannot discard the work
     */
    @Override
    public long getAsLong() {
        boolean inside = !system.equals("outsideFill") && !system.equals("coldCache");
        if (inside) {
            FlatClimateTree.enterFill();
        }
        long sum = 0;
        try {
            for (int i = 0; i < 64; i++) {
                Query query = queries.get(cursor++ % queries.size());
                Fixture f = fixtures.get(query.tree);
                FlatClimateTree flat = f.flat;
                Climate.TargetPoint t = query.target;
                long a = t.temperature(), b = t.humidity(), c = t.continentalness(), d = t.erosion(), e = t.depth(), g = t.weirdness();
                int[] ids = previous[query.tree];
                switch (system) {
                    case "search" -> sum += ids[0] = flat.search(a, b, c, d, e, g, ids[0]);
                    case "columns", "outsideFill" -> sum += ids[0] = flat.searchColumns(a, b, c, d, e, g, ids[0]);
                    case "walkColumns" -> sum += ids[0] = flat.walkColumns(a, b, c, d, e, g, ids[0]);
                    case "fittest", "fittestColumns", "walkFittestColumns" -> {
                        switch (system) {
                            case "fittest" ->
                                    flat.searchFittest(a, b, c, d, e, g, f.keys, ids[0], ids[1], ids, distances);
                            case "fittestColumns" ->
                                    flat.searchFittestColumns(a, b, c, d, e, g, f.keys, ids[0], ids[1], ids, distances);
                            default ->
                                    flat.walkFittestColumns(a, b, c, d, e, g, f.keys, ids[0], ids[1], ids, distances);
                        }
                        sum += ids[0] + ids[1] + distances[0] + distances[1];
                    }
                    case "reusedFittest" -> {
                        var result = flat.fittestColumns(a, b, c, d, e, g, f.keys, ids[0], ids[1]);
                        ids[0] = result.ids[0];
                        ids[1] = result.ids[1];
                        sum += ids[0] + ids[1] + result.distance[0] + result.distance[1];
                    }
                    case "alone" -> sum += flat.alone(a, b, c, d, e, g, query.first) ? 1 : 0;
                    case "aloneByValue" -> sum += flat.alone(a, b, c, d, e, g, query.first, true) ? 1 : 0;
                    case "secondAlone" -> sum += flat.secondAlone(a, b, c, d, e, g, query.second, f.keys,
                            query.first < 0 ? 0 : f.keys[query.first]) ? 1 : 0;
                    case "distance" -> sum += flat.distanceTo(query.first, a, b, c, d, e, g);
                    case "build" -> {
                        constructed = FlatClimateTree.build(f.list.index, (leaf, id) -> {
                        });
                        sum += constructed.nodes();
                    }
                    case "coldCache" -> {
                        FlatClimateTree.forgetWorlds();
                        FlatClimateTree.enterFill();
                        try {
                            sum += flat.searchColumns(a, b, c, d, e, g, -1);
                        } finally {
                            FlatClimateTree.exitFill();
                        }
                    }
                    case "adaptiveCache" -> {
                        sum += adaptiveSearch();
                    }
                    case "lifecycle" -> {
                        FlatClimateTree.enterFill();
                        try {
                            FlatClimateTree.flushGroupCounts();
                            sum += flat.groupCount() + flat.nodes() + (flat.hasSubtrees() ? 1 : 0);
                            sum += flat.leaf(query.first) == null ? 0 : 1;
                            sum += FlatClimateTree.all().size();
                        } finally {
                            FlatClimateTree.exitFill();
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown subsystem " + system);
                }
            }
        } finally {
            if (inside) {
                FlatClimateTree.exitFill();
            }
        }
        return sum;
    }

    /**
     * Looks up the next scattered point without resetting the cache or its backoff timer.
     *
     * @return nearest leaf id
     */
    private int adaptiveSearch() {
        Climate.TargetPoint t = adaptiveTargets[sequence++ & (adaptiveTargets.length - 1)];
        return adaptive.flat.searchColumns(t.temperature(), t.humidity(), t.continentalness(), t.erosion(),
                t.depth(), t.weirdness(), -1);
    }
}
