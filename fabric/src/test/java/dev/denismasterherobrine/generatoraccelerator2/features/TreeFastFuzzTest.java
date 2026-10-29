package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import dev.denismasterherobrine.generatoraccelerator2.features.tree.TreeBees;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;

/**
 * The tree fast path ({@code TreeFast}: its leaf distance sets, its free-height scan) against vanilla's {@code TreeFeature} for
 * every configured tree without a beehive. Each trial places three trees close enough that their leaves touch, so the distance
 * pass meets other trees' leaves and logs. Blocks, ticks, side effects and the random's state must be the same.
 */
public class TreeFastFuzzTest {
    private static final int TRIALS = 12;

    @Test
    void treesMatchVanilla() {
        compareTrees(false, 12);
    }

    /**
     * Trees with beehive decorators (forest trees at 0.2%, meadow trees always), whose leaves the fast path keeps packed until a
     * decorator's chance succeeds ({@code TreeBees}). Vanilla shuffles nest candidates with the JDK's shared random, so each run
     * seeds that random the same way first.
     */
    @Test
    void beeTreesMatchVanilla() {
        long before = TreeBees.TREES.sum();
        compareTrees(true, 6);
        assertTrue(TreeBees.TREES.sum() > before, "the beehive path placed trees");
    }

    private static void compareTrees(boolean bees, int minimum) {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<FeatureRun.ConfiguredCase> trees = new ArrayList<>();
        for (FeatureRun.ConfiguredCase c : FeatureRun.configuredCases(r)) {
            if (c.originKind() == 0 && c.kind() == HarnessWorld.Kind.OVERWORLD && c.feature().value().feature() == Feature.TREE
                && FeatureRun.hasBeehiveDecorator(c.feature().value()) == bees) {
                trees.add(c);
            }
        }
        trees.sort(Comparator.comparing(FeatureRun.ConfiguredCase::name));
        assertTrue(trees.size() >= minimum, "too few tree features: " + trees.size());
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int placed = 0;
        for (long terrainSeed : new long[] { 20261002L, 777L }) {
            for (FeatureRun.ConfiguredCase c : trees) {
                for (int t = 0; t < TRIALS; t++) {
                    FeatureRun.Result ref = run(r, c, terrainSeed, t, true);
                    if (ref.error() != null) {
                        failures.add(c.name() + " trial " + t + ": vanilla threw " + ref.error());
                        break;
                    }
                    FeatureRun.Result fast = run(r, c, terrainSeed, t, false);
                    List<String> diff = FeatureRun.diff(ref, fast);
                    if (fast.error() != null || !diff.isEmpty()) {
                        failures.add(c.name() + " seed " + terrainSeed + " trial " + t + ": " + (fast.error() != null ? fast.error().toString() : String.join("\n    ", diff)));
                    }
                    compared++;
                    if (ref.placed()) {
                        placed++;
                    }
                }
            }
        }
        System.out.println("TreeFastFuzzTest: " + (bees ? "bee trees, " : "") + compared + " trials compared over " + trees.size() + " trees, " + placed
            + " placed a last tree");
        assertTrue(placed > compared / 4, "too few trials placed anything: " + placed + " of " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " differ:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    /** Seeds the random {@code Collections.shuffle(List)} uses, which vanilla's beehive decorator shuffles its candidates with. */
    private static void seedShuffle(long seed) {
        try {
            Field f = Collections.class.getDeclaredField("r");
            f.setAccessible(true);
            f.set(null, new Random(seed));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("cannot seed Collections.shuffle (the test JVM needs --add-opens java.base/java.util)", e);
        }
    }

    /** Three placements of one tree at trial {@code t}'s surface origins, a few blocks apart. */
    private static FeatureRun.Result run(HarnessWorld.Registries2 r, FeatureRun.ConfiguredCase c, long terrainSeed, int t, boolean vanilla) {
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, c.kind(), terrainSeed), heights);
        Holder<Biome> biome = r.biomes.getOrThrow(Biomes.PLAINS);
        for (ProtoChunk chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
        }
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()),
            terrainSeed, HarnessWorld.randomState(r, c.kind(), terrainSeed), r.access, false);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setSeed(terrainSeed * 31 + t * 0x9E3779B97F4A7C15L + c.name().hashCode());
        seedShuffle(terrainSeed + t);
        boolean[] placed = new boolean[1];
        Throwable[] error = new Throwable[1];
        Runnable body = () -> {
            try {
                for (int k = 0; k < 3; k++) {
                    int x = FeatureRun.CENTER_X * 16 + 4 + (t * 5 + k * 3) % 9;
                    int z = FeatureRun.CENTER_Z * 16 + 4 + (t * 3 + k * 4) % 9;
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    placed[0] = c.feature().value().place(level, generator, random, new BlockPos(x, y, z));
                }
            } catch (Throwable e) {
                error[0] = e;
            }
        };
        if (vanilla) {
            FeatureHooks.runVanilla(body);
        } else {
            body.run();
        }
        return new FeatureRun.Result(placed[0], random.getCount(), random.nextLong(), level.nextSubTickCount(), level.poiChanges(), chunks, level, error[0]);
    }
}
