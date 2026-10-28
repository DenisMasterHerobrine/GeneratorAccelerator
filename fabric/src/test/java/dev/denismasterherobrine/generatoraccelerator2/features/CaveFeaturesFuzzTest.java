package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.Fuzz;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.junit.jupiter.api.Test;

/**
 * The sculk patch transcription ({@code SculkFast}) and the geode fast path (with its noise-free culling) against vanilla, at
 * many origins: sculk patches on cave floors, and geodes through the stone. Each trial places two sculk patches, so the second
 * patch spreads over the first patch's sculk, veins and growths. Blocks, ticks, side effects and the random's state must be
 * the same.
 */
public class CaveFeaturesFuzzTest {
    private static final int TRIALS = Fuzz.size(40, 8);

    @Test
    void sculkAndGeodesMatchVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<FeatureRun.ConfiguredCase> cases = new ArrayList<>();
        for (FeatureRun.ConfiguredCase c : FeatureRun.configuredCases(r)) {
            Feature<?> f = c.feature().value().feature();
            if (c.originKind() == 0 && c.kind() == HarnessWorld.Kind.OVERWORLD && (f == Feature.SCULK_PATCH || f == Feature.GEODE)) {
                cases.add(c);
            }
        }
        cases.sort(Comparator.comparing(FeatureRun.ConfiguredCase::name));
        assertTrue(cases.size() >= 3, "too few cases: " + cases);
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int placed = 0;
        long sculkBefore = fast("SculkPatchFeature");
        long geodeBefore = fast("GeodeFeature");
        for (long terrainSeed : Fuzz.seeds(20261002L, 777L)) {
            for (FeatureRun.ConfiguredCase c : cases) {
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
        System.out.println("CaveFeaturesFuzzTest: " + compared + " trials over " + cases.size() + " features, " + placed + " placed; fast sculk "
            + (fast("SculkPatchFeature") - sculkBefore) + ", fast geodes " + (fast("GeodeFeature") - geodeBefore));
        assertTrue(fast("SculkPatchFeature") > sculkBefore, "the sculk transcription never ran");
        assertTrue(placed > compared / 4, "too few trials placed anything: " + placed + " of " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " differ:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    /**
     * The monster room fast path against vanilla. A room needs a solid floor and ceiling and 1 to 5 openings in its walls, which
     * plain stone and the harness's caves rarely give. Each trial carves corridors through the room's spot at floor height first:
     * one along x (2 openings), one along z too (4), or a third beside the first (6, too many). Both runs carve the same.
     */
    @Test
    void monsterRoomsMatchVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<FeatureRun.ConfiguredCase> cases = new ArrayList<>();
        for (FeatureRun.ConfiguredCase c : FeatureRun.configuredCases(r)) {
            if (c.originKind() == 0 && c.kind() == HarnessWorld.Kind.OVERWORLD && c.feature().value().feature() == Feature.MONSTER_ROOM) {
                cases.add(c);
            }
        }
        cases.sort(Comparator.comparing(FeatureRun.ConfiguredCase::name));
        assertTrue(!cases.isEmpty(), "no monster room case");
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int placed = 0;
        for (FeatureRun.ConfiguredCase c : cases) {
            for (int t = 0; t < TRIALS; t++) {
                FeatureRun.Result ref = room(r, c, t, true);
                if (ref.error() != null) {
                    failures.add(c.name() + " trial " + t + ": vanilla threw " + ref.error());
                    break;
                }
                FeatureRun.Result fast = room(r, c, t, false);
                List<String> diff = FeatureRun.diff(ref, fast);
                if (fast.error() != null || !diff.isEmpty()) {
                    failures.add(c.name() + " trial " + t + ": " + (fast.error() != null ? fast.error().toString() : String.join("\n    ", diff)));
                }
                compared++;
                if (ref.placed()) {
                    placed++;
                }
            }
        }
        System.out.println("CaveFeaturesFuzzTest: " + compared + " monster room trials, " + placed + " placed");
        assertTrue(placed > compared / 4, "too few rooms placed: " + placed + " of " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " differ:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    private static FeatureRun.Result room(HarnessWorld.Registries2 r, FeatureRun.ConfiguredCase c, int t, boolean vanilla) {
        long terrainSeed = 20261026L;
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, c.kind(), terrainSeed), heights);
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()),
            terrainSeed, HarnessWorld.randomState(r, c.kind(), terrainSeed), r.access, false);
        int x = FeatureRun.CENTER_X * 16 + 4 + (t * 7) % 8;
        int z = FeatureRun.CENTER_Z * 16 + 4 + (t * 11 + 3) % 8;
        BlockPos origin = new BlockPos(x, -45 + (t * 13) % 30, z);
        BlockState air = Blocks.AIR.defaultBlockState();
        int corridors = 1 + t % 3;
        for (int d = -6; d <= 6; d++) {
            for (int dy = 0; dy <= 1; dy++) {
                level.setBlock(origin.offset(d, dy, 0), air, 2);
                if (corridors >= 2) {
                    level.setBlock(origin.offset(0, dy, d), air, 2);
                }
                if (corridors >= 3) {
                    level.setBlock(origin.offset(d, dy, 2), air, 2);
                }
            }
        }
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setSeed(terrainSeed * 31 + t * 0x9E3779B97F4A7C15L + c.name().hashCode());
        boolean[] placed = new boolean[1];
        Throwable[] error = new Throwable[1];
        Runnable body = () -> {
            try {
                placed[0] = c.feature().value().place(level, generator, random, origin);
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

    private static long fast(String feature) {
        LongAdder a = FeatureStats.fastByFeature().get(feature);
        return a == null ? 0 : a.sum();
    }

    /** One case at trial {@code t}: a sculk patch twice on cave floors near each other, a geode once in the stone. */
    private static FeatureRun.Result run(HarnessWorld.Registries2 r, FeatureRun.ConfiguredCase c, long terrainSeed, int t, boolean vanilla) {
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, c.kind(), terrainSeed), heights);
        Holder<Biome> biome = r.biomes.getHolderOrThrow(Biomes.DEEP_DARK);
        for (ProtoChunk chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
        }
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()),
            terrainSeed, HarnessWorld.randomState(r, c.kind(), terrainSeed), r.access, false);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setSeed(terrainSeed * 31 + t * 0x9E3779B97F4A7C15L + c.name().hashCode());
        boolean sculk = c.feature().value().feature() == Feature.SCULK_PATCH;
        List<BlockPos> origins = new ArrayList<>();
        if (sculk) {
            for (int k = 0; k < 2; k++) {
                int x = FeatureRun.CENTER_X * 16 + (t * 7 + k * 3) % 16;
                int z = FeatureRun.CENTER_Z * 16 + (t * 11 + 3 + k * 2) % 16;
                origins.add(caveFloor(level, x, z, t + k, heights.getMinBuildHeight()));
            }
        } else {
            int x = FeatureRun.CENTER_X * 16 + (t * 7) % 16;
            int z = FeatureRun.CENTER_Z * 16 + (t * 11 + 3) % 16;
            origins.add(new BlockPos(x, -50 + (t * 13) % 70, z));
        }
        boolean[] placed = new boolean[1];
        Throwable[] error = new Throwable[1];
        Runnable body = () -> {
            try {
                for (BlockPos origin : origins) {
                    placed[0] = c.feature().value().place(level, generator, random, origin);
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

    /** The {@code k}-th air block over a solid one in the column below y 50 (a cave floor), or a stone depth when there is none. */
    private static BlockPos caveFloor(HarnessLevel level, int x, int z, int k, int minY) {
        List<Integer> floors = new ArrayList<>();
        for (int y = minY + 2; y < 50; y++) {
            if (level.getBlockState(new BlockPos(x, y, z)).isAir() && level.getBlockState(new BlockPos(x, y - 1, z)).blocksMotion()) {
                floors.add(y);
            }
        }
        return new BlockPos(x, floors.isEmpty() ? -40 : floors.get(k % floors.size()), z);
    }
}
