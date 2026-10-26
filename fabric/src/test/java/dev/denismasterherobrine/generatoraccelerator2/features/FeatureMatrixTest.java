package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * Every vanilla placed feature, run through the harness on the vanilla path and on the optimized
 * path, must leave identical chunks, heightmaps, side effects and random state. A vanilla-vs-vanilla
 * pass first finds features that are not deterministic even in vanilla (or that need a real server
 * level). Those features are reported and excluded from the equality assertion.
 */
public class FeatureMatrixTest {
    private static final long[] SEEDS = { 20261002L, 777L };

    @Test
    void optimizedPathMatchesVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        assertTrue(Blocks.STONE.defaultBlockState().is(BlockTags.STONE_ORE_REPLACEABLES), "vanilla block tags are not bound");
        List<FeatureRun.Case> cases = FeatureRun.cases(r);
        assertTrue(cases.size() > 150, "too few placed features found: " + cases.size());
        Set<String> nonDeterministic = new TreeSet<>();
        Set<String> unsupported = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int placed = 0;
        Set<String> neverPlaced = new TreeSet<>();
        Set<String> everPlaced = new TreeSet<>();
        for (FeatureRun.Case c : cases) {
            if (FeatureRun.hasBeehiveDecorator(c.feature().value().feature().value())) {
                nonDeterministic.add(c.name() + " (beehive)");
                continue;
            }
            for (long seed : SEEDS) {
                FeatureRun.Result ref1 = FeatureRun.run(r, c, seed, true, false);
                if (ref1.error() != null) {
                    unsupported.add(c.name() + " (" + ref1.error().getClass().getSimpleName() + ": " + ref1.error().getMessage() + ")");
                    break;
                }
                FeatureRun.Result ref2 = FeatureRun.run(r, c, seed, true, false);
                if (!FeatureRun.diff(ref1, ref2).isEmpty()) {
                    nonDeterministic.add(c.name());
                    break;
                }
                FeatureRun.Result fast = FeatureRun.run(r, c, seed, false, true);
                if (fast.error() != null) {
                    failures.add(c.name() + " seed " + seed + ": optimized path threw " + fast.error());
                    continue;
                }
                List<String> diff = FeatureRun.diff(ref1, fast);
                if (!diff.isEmpty()) {
                    failures.add(c.name() + " seed " + seed + ":\n    " + String.join("\n    ", diff));
                }
                compared++;
                if (ref1.placed()) {
                    placed++;
                    everPlaced.add(c.name());
                } else {
                    neverPlaced.add(c.name());
                }
            }
        }
        // Second pass: every configured feature placed directly at three heights, so rarity and biome
        // filters cannot hide a feature (and therefore a fast path) from the comparison.
        List<FeatureRun.ConfiguredCase> direct = FeatureRun.configuredCases(r);
        int directCompared = 0;
        int directPlaced = 0;
        for (FeatureRun.ConfiguredCase c : direct) {
            if (FeatureRun.hasBeehiveDecorator(c.feature().value())) {
                nonDeterministic.add(c.name() + " (beehive)");
                continue;
            }
            long seed = SEEDS[c.originKind() % SEEDS.length];
            FeatureRun.Result ref1 = FeatureRun.runConfigured(r, c, seed, true, false);
            if (ref1.error() != null) {
                unsupported.add(c.name() + " (" + ref1.error().getClass().getSimpleName() + ": " + ref1.error().getMessage() + ")");
                continue;
            }
            FeatureRun.Result ref2 = FeatureRun.runConfigured(r, c, seed, true, false);
            if (!FeatureRun.diff(ref1, ref2).isEmpty()) {
                nonDeterministic.add(c.name());
                continue;
            }
            FeatureRun.Result fast = FeatureRun.runConfigured(r, c, seed, false, true);
            if (fast.error() != null) {
                failures.add(c.name() + ": optimized path threw " + fast.error());
                continue;
            }
            List<String> diff = FeatureRun.diff(ref1, fast);
            if (!diff.isEmpty()) {
                failures.add(c.name() + ":\n    " + String.join("\n    ", diff));
            }
            directCompared++;
            if (ref1.placed()) {
                directPlaced++;
            }
        }
        System.out.println("FeatureMatrixTest: direct configured runs " + directCompared + ", placed " + directPlaced);
        assertTrue(FeatureStats.PLACEMENTS_FAST.sum() > 0, "the placement pipeline was never used");
        for (String fast : new String[] { "OreFeature", "GeodeFeature", "DiskFeature", "SpringFeature", "SnowAndFreezeFeature", "SeagrassFeature", "KelpFeature", "MultifaceGrowthFeature", "SimpleBlockFeature", "MonsterRoomFeature", "TreeFeature", "SculkPatchFeature" }) {
            LongAdder hits = FeatureStats.fastByFeature().get(fast);
            assertTrue(hits != null && hits.sum() > 0, "fast path never used: " + fast);
        }
        System.out.println("FeatureMatrixTest: fast paths " + FeatureStats.fastByFeature());
        System.out.println("FeatureMatrixTest: " + compared + " runs compared, " + placed + " placed something; vanilla non-deterministic: " + nonDeterministic
            + "; unsupported in harness: " + unsupported);
        neverPlaced.removeAll(everPlaced);
        System.out.println("FeatureMatrixTest: never placed: " + neverPlaced);
        assertTrue(failures.isEmpty(), failures.size() + " mismatches:\n" + String.join("\n", failures));
        assertTrue(placed > compared / 4, "too few features actually placed: " + placed + " of " + compared);
    }
}
