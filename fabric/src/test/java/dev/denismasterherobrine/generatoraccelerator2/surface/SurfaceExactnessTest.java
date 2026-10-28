package dev.denismasterherobrine.generatoraccelerator2.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.worldgen.lithostitched.impl.worldgen.surface.condition.AllOfCondition;
import dev.worldgen.lithostitched.impl.worldgen.surface.condition.AnyOfCondition;
import dev.worldgen.lithostitched.impl.worldgen.surface.condition.BiomeCondition;
import dev.worldgen.lithostitched.impl.worldgen.surface.condition.SlopeCondition;
import dev.worldgen.lithostitched.worldgen.surface.LithostitchedSurfaceRules;
import net.minecraft.util.InclusiveRange;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Builds chunk surfaces on real terrain (noise filled by the vanilla generator with the real biome
 * source) twice from identical copies: once through vanilla's {@code SurfaceSystem.buildSurface}, once
 * through GA2's builder, and compares every block, the section counters, the heightmaps and the
 * post-processing lists. Sites cover the vanilla presets, several seeds and chunks picked for the
 * biomes whose rules or extensions are special (badlands bands and pillars, frozen ocean icebergs,
 * steep peaks, swamps, beaches, deserts, deep dark).
 */
class SurfaceExactnessTest {
    private static final long[] SEEDS = { 1234567L, -42L, 8_675_309L };

    @AfterEach
    void reset() {
        SurfaceConfig.compileOverride = null;
        SurfaceConfig.deepBandsOverride = null;
        SurfaceConfig.sectionBulkOverride = null;
        SurfaceConfig.biomeProgramsOverride = null;
    }

    @Test
    void hooksApplied() {
        assertTrue(HookTargets.inspected(HookTargets.SURFACE_BUILD), "SurfaceSystem mixin was not applied");
        assertTrue(HookTargets.allowed(HookTargets.SURFACE_BUILD), "surface hook stood down: " + HookTargets.foreign(HookTargets.SURFACE_BUILD));
    }

    @Test
    void overworldSurfacesMatch() {
        for (long seed : SEEDS) {
            checkSites(TerrainHarness.Preset.OVERWORLD, seed, sites(TerrainHarness.Preset.OVERWORLD, seed));
        }
    }

    /**
     * The rules compiled per biome met (biome conditions answered, docs/SURFACE.md) against vanilla, and the whole program
     * against vanilla, on the same sites.
     */
    @Test
    void biomeProgramsMatch() {
        List<int[]> at = sites(TerrainHarness.Preset.OVERWORLD, SEEDS[1]);
        long before = SurfaceProgram.BIOME_PICKS.sum();
        SurfaceConfig.biomeProgramsOverride = true;
        checkSites(TerrainHarness.Preset.OVERWORLD, SEEDS[1], at);
        assertTrue(SurfaceProgram.BIOME_PICKS.sum() > before, "the builder never picked a biome's program");
        SurfaceConfig.biomeProgramsOverride = false;
        checkSites(TerrainHarness.Preset.OVERWORLD, SEEDS[1], at);
    }

    @Test
    void otherPresetsMatch() {
        checkSites(TerrainHarness.Preset.LARGE_BIOMES, SEEDS[0], List.of(new int[] { 0, 0 }, new int[] { 37, -12 }, new int[] { -80, 55 }));
        checkSites(TerrainHarness.Preset.AMPLIFIED, SEEDS[1], List.of(new int[] { 3, 3 }, new int[] { -20, 41 }, new int[] { 66, -7 }));
        checkSites(TerrainHarness.Preset.NETHER, SEEDS[0], List.of(new int[] { 0, 0 }, new int[] { 11, -5 }, new int[] { -23, 17 }, new int[] { 40, 40 }));
        checkSites(TerrainHarness.Preset.END, SEEDS[2], List.of(new int[] { 0, 0 }, new int[] { 70, 3 }, new int[] { -64, -80 }, new int[] { 120, 120 }));
    }

    /** Eroded badlands (pillar extension) are rare: search wider for a few of their chunks. */
    @Test
    void erodedBadlandsMatch() {
        for (long seed : SEEDS) {
            TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed);
            List<int[]> found = new ArrayList<>();
            for (int r = 0; r < 900 && found.size() < 3; r += 6) {
                for (int i = -r; i <= r && found.size() < 3; i += 6) {
                    for (int[] c : new int[][] { { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                        if (found.size() < 3 && w.biomeAt(c[0] * 16 + 8, 70, c[1] * 16 + 8).is(Biomes.ERODED_BADLANDS)) {
                            found.add(c);
                            found.add(new int[] { c[0] + 1, c[1] });
                        }
                    }
                }
            }
            System.out.println("[SurfaceExactnessTest] seed " + seed + " eroded badlands chunks: " + found.stream().map(c -> c[0] + "," + c[1]).toList());
            checkSites(TerrainHarness.Preset.OVERWORLD, seed, found);
        }
    }

    @Test
    void interpreterAndDeepBandsOffMatch() {
        SurfaceConfig.compileOverride = false;
        checkSites(TerrainHarness.Preset.OVERWORLD, SEEDS[0], sites(TerrainHarness.Preset.OVERWORLD, SEEDS[0]).subList(0, 6));
        SurfaceConfig.deepBandsOverride = false;
        checkSites(TerrainHarness.Preset.OVERWORLD, SEEDS[1], sites(TerrainHarness.Preset.OVERWORLD, SEEDS[1]).subList(0, 6));
    }

    /**
     * TerraBlender wraps every dimension's rules in a namespaced source (test copy in {@code terrablender.worldgen.surface}).
     * Per block, the source applies the rule of the biome's namespace when that rule gives a block, otherwise the base rule.
     * GA2 lowers the namespaced source to biome namespace tests. Here the "minecraft" namespace puts sand on floors above the
     * preliminary surface. It gives nothing below, so the deep blocks come from the base rules. A namespace no biome has
     * would put gold everywhere.
     */
    @Test
    void terraBlenderNamespacedRulesMatch() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, SEEDS[0]);
        Map<String, SurfaceRules.RuleSource> sources = new LinkedHashMap<>();
        sources.put("ga2test", SurfaceRules.state(Blocks.GOLD_BLOCK.defaultBlockState()));
        sources.put("minecraft", SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
            SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.SAND.defaultBlockState()))));
        SurfaceRules.RuleSource namespaced = new NamespacedSurfaceRuleSource(w.settings.surfaceRule(), sources);
        assertFalse(SurfaceBuilder.program(namespaced).hasOpaque, "the namespaced source was not lowered");
        List<int[]> at = sites(TerrainHarness.Preset.OVERWORLD, SEEDS[0]).subList(0, 8);
        checkRule(w, namespaced, at);
        SurfaceConfig.compileOverride = false;
        checkRule(w, namespaced, at.subList(0, 4));
    }

    /**
     * Lithostitched's all-of, any-of, biome-set and slope conditions (test copies in {@code dev.worldgen.lithostitched})
     * lower to GA2's IR. Here they are mixed with vanilla conditions, negated, nested, and empty (all-of: true, any-of:
     * false). The second rule also holds a condition GA2 does not know. That condition is still asked through its own
     * objects, in order.
     */
    @Test
    void lithostitchedConditionsMatch() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, SEEDS[0]);
        HolderSet<Biome> withO = HolderSet.direct(w.registries.biomes.holders()
            .filter(h -> h.key().location().getPath().contains("o")).<Holder<Biome>>map(h -> h).toList());
        SurfaceRules.ConditionSource inSet = new BiomeCondition(withO);
        SurfaceRules.RuleSource lowered = SurfaceRules.sequence(
            SurfaceRules.ifTrue(new AllOfCondition(List.of(SurfaceRules.abovePreliminarySurface(), new SlopeCondition(new InclusiveRange<>(3, Integer.MAX_VALUE)))),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.COBBLESTONE.defaultBlockState()))),
            SurfaceRules.ifTrue(new AllOfCondition(List.of(SurfaceRules.abovePreliminarySurface(), SurfaceRules.ON_FLOOR, inSet)),
                SurfaceRules.state(Blocks.GOLD_BLOCK.defaultBlockState())),
            SurfaceRules.ifTrue(new AnyOfCondition(List.of(SurfaceRules.steep(), new SlopeCondition(new InclusiveRange<>(0, 0)),
                    SurfaceRules.not(new AllOfCondition(List.of(inSet, SurfaceRules.abovePreliminarySurface()))))),
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, SurfaceRules.state(Blocks.SAND.defaultBlockState()))),
            SurfaceRules.ifTrue(new AllOfCondition(List.of()),
                SurfaceRules.ifTrue(new AnyOfCondition(List.of()), SurfaceRules.state(Blocks.DIAMOND_BLOCK.defaultBlockState()))),
            w.settings.surfaceRule());
        assertFalse(SurfaceBuilder.program(lowered).hasOpaque, "the Lithostitched conditions were not lowered");
        List<int[]> at = sites(TerrainHarness.Preset.OVERWORLD, SEEDS[0]).subList(0, 8);
        checkRule(w, lowered, at);

        SurfaceRules.RuleSource mixed = SurfaceRules.sequence(
            SurfaceRules.ifTrue(new AnyOfCondition(List.of(new Unknown(SurfaceRules.isBiome(Biomes.PLAINS, Biomes.FOREST, Biomes.DESERT)), inSet)),
                SurfaceRules.ifTrue(new AllOfCondition(List.of(SurfaceRules.ON_FLOOR, new Unknown(SurfaceRules.abovePreliminarySurface()))),
                    SurfaceRules.state(Blocks.GOLD_BLOCK.defaultBlockState()))),
            w.settings.surfaceRule());
        assertTrue(SurfaceBuilder.program(mixed).hasOpaque, "the unknown condition should stay opaque");
        checkRule(w, mixed, at.subList(0, 4));

        SurfaceConfig.compileOverride = false;
        checkRule(w, lowered, at.subList(0, 4));
        checkRule(w, mixed, at.subList(0, 2));
    }

    /**
     * Lithostitched 1.20.1's merged rule (test copy in {@code dev.worldgen.lithostitched.worldgen.surface}). One rule alone is
     * that rule. More rules are followed by the original once more. The mod's own sequence ends with the original, another one
     * does not.
     */
    @Test
    void lithostitchedMergedRulesMatch() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, SEEDS[0]);
        SurfaceRules.RuleSource original = w.settings.surfaceRule();
        SurfaceRules.RuleSource sand = SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
            SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.SAND.defaultBlockState())));
        SurfaceRules.RuleSource gold = SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, SurfaceRules.state(Blocks.GOLD_BLOCK.defaultBlockState()));
        List<int[]> at = sites(TerrainHarness.Preset.OVERWORLD, SEEDS[0]).subList(0, 6);
        for (SurfaceRules.RuleSource merged : List.of(
            new LithostitchedSurfaceRules.TransientMergedRuleSource(List.of(sand, original), original),
            new LithostitchedSurfaceRules.TransientMergedRuleSource(List.of(sand), original),
            new LithostitchedSurfaceRules.TransientMergedRuleSource(List.of(sand, gold), original))) {
            assertFalse(SurfaceBuilder.program(merged).hasOpaque, "the merged rule was not lowered");
            checkRule(w, merged, at);
        }
    }

    /** A condition from a mod GA2 does not know (it only wraps a vanilla one). */
    private record Unknown(SurfaceRules.ConditionSource inner) implements SurfaceRules.ConditionSource {
        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
            throw new UnsupportedOperationException("test only");
        }

        @Override
        public SurfaceRules.Condition apply(SurfaceRules.Context context) {
            return inner.apply(context);
        }
    }

    private static void checkRule(TerrainHarness.World w, SurfaceRules.RuleSource rule, List<int[]> sites) {
        List<String> failures = new ArrayList<>();
        for (int[] at : sites) {
            TerrainHarness.Site site = TerrainHarness.site(TerrainHarness.Preset.OVERWORLD, SEEDS[0], at[0], at[1]);
            ProtoChunk expected = site.center();
            ProtoChunk actual = site.center();
            SurfaceHooks.runVanilla(() -> buildWith(w, site, expected, rule));
            long before = SurfaceHooks.CHUNKS_FAST.sum();
            buildWith(w, site, actual, rule);
            assertEquals(before + 1, SurfaceHooks.CHUNKS_FAST.sum(), "GA2 surface builder did not run for chunk " + at[0] + "," + at[1]);
            List<String> diff = FeatureCompare.compare(expected, actual, 12);
            if (!diff.isEmpty()) {
                failures.add("rules chunk " + at[0] + "," + at[1] + ": " + String.join("\n    ", diff));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** {@code NoiseBasedChunkGenerator.buildSurface(ChunkAccess, ...)} with another rule source. */
    private static void buildWith(TerrainHarness.World w, TerrainHarness.Site site, ProtoChunk chunk, SurfaceRules.RuleSource rule) {
        w.randomState.surfaceSystem().buildSurface(w.randomState, site.surfaceBiomeManager(chunk), w.registries.biomes, w.settings.useLegacyRandomSource(),
            w.generationContext(), chunk, w.noiseChunk(chunk), rule);
    }

    private static void checkSites(TerrainHarness.Preset preset, long seed, List<int[]> sites) {
        List<String> failures = new ArrayList<>();
        for (int[] at : sites) {
            TerrainHarness.Site site = TerrainHarness.site(preset, seed, at[0], at[1]);
            ProtoChunk expected = site.center();
            ProtoChunk actual = site.center();
            SurfaceHooks.runVanilla(() -> TerrainHarness.vanillaSurface(site, expected));
            long before = SurfaceHooks.CHUNKS_FAST.sum();
            TerrainHarness.vanillaSurface(site, actual);
            assertEquals(before + 1, SurfaceHooks.CHUNKS_FAST.sum(), "GA2 surface builder did not run for " + preset + " " + at[0] + "," + at[1]);
            List<String> diff = FeatureCompare.compare(expected, actual, 12);
            if (!diff.isEmpty()) {
                failures.add(preset + " seed " + seed + " chunk " + at[0] + "," + at[1] + ": " + String.join("\n    ", diff));
            }
            if (preset != TerrainHarness.Preset.END) {
                changedSites++;
                if (FeatureCompare.compare(site.center(), expected, 1).isEmpty()) {
                    failures.add(preset + " chunk " + at[0] + "," + at[1] + ": vanilla surface changed nothing (harness problem)");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static int changedSites;

    private static final List<ResourceKey<Biome>> WANTED = List.of(Biomes.ERODED_BADLANDS, Biomes.BADLANDS, Biomes.WOODED_BADLANDS, Biomes.FROZEN_OCEAN,
        Biomes.DEEP_FROZEN_OCEAN, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.SNOWY_SLOPES, Biomes.STONY_PEAKS, Biomes.SWAMP, Biomes.MANGROVE_SWAMP,
        Biomes.DESERT, Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.WARM_OCEAN, Biomes.STONY_SHORE, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.GROVE,
        Biomes.MUSHROOM_FIELDS, Biomes.PLAINS, Biomes.RIVER, Biomes.DEEP_DARK, Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES);

    private static final Map<Long, List<int[]>> SITES = new LinkedHashMap<>();

    /** A few fixed chunks plus the first chunk found (on a coarse grid) for each wanted biome. */
    static synchronized List<int[]> sites(TerrainHarness.Preset preset, long seed) {
        return SITES.computeIfAbsent(seed, s -> {
            TerrainHarness.World w = TerrainHarness.world(preset, seed);
            List<int[]> out = new ArrayList<>();
            out.add(new int[] { 0, 0 });
            out.add(new int[] { 5, -3 });
            out.add(new int[] { -17, 22 });
            Map<ResourceKey<Biome>, int[]> found = new LinkedHashMap<>();
            for (int r = 0; r < 160 && found.size() < WANTED.size(); r += 3) {
                for (int i = -r; i <= r && found.size() < WANTED.size(); i += 3) {
                    for (int[] c : new int[][] { { i, -r }, { i, r }, { -r, i }, { r, i } }) {
                        for (int y : new int[] { 64, -30 }) {
                            Holder<Biome> b = w.biomeAt(c[0] * 16 + 8, y, c[1] * 16 + 8);
                            b.unwrapKey().filter(WANTED::contains).ifPresent(k -> found.putIfAbsent(k, c));
                        }
                    }
                }
            }
            out.addAll(found.values());
            System.out.println("[SurfaceExactnessTest] seed " + seed + " biome sites: " + found.keySet().stream().map(k -> k.location().getPath()).toList());
            return out;
        });
    }
}
