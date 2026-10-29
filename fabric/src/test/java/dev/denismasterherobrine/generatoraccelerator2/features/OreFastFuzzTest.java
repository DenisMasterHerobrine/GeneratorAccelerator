package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import dev.denismasterherobrine.generatoraccelerator2.state.StateFast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The ore fast path ({@code OreFast}: its row masks, its bounded sphere culling, its per-state target table) against vanilla's
 * {@code OreFeature} for every configured ore, at many origins (every column offset, heights from the bottom to above ground)
 * and random seeds. Blocks, side effects and the random's state must be the same.
 */
public class OreFastFuzzTest {
    private static final int TRIALS = 48;

    @Test
    void oresMatchVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<FeatureRun.ConfiguredCase> ores = new ArrayList<>();
        for (FeatureRun.ConfiguredCase c : FeatureRun.configuredCases(r)) {
            if (c.originKind() == 0 && c.feature().value().feature() == Feature.ORE) {
                ores.add(c);
            }
        }
        // by name, seeded by name: the registry's listing order (and so the cases' indices) can change between runs
        ores.sort(Comparator.comparing(FeatureRun.ConfiguredCase::name));
        assertTrue(ores.size() >= 20, "too few ore features: " + ores.size());
        List<String> failures = new ArrayList<>();
        int compared = 0;
        int placed = 0;
        long checksum = 0;
        for (long terrainSeed : new long[] { 20261002L, 777L }) {
            for (FeatureRun.ConfiguredCase c : ores) {
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
                    checksum = checksum * 31 + blocksHash(ref, t) + (ref.placed() ? 1 : 0) + ref.randomCount() * 7L + ref.randomProbe();
                    compared++;
                    if (ref.placed()) {
                        placed++;
                    }
                }
            }
        }
        System.out.println("OreFastFuzzTest: " + compared + " placements compared over " + ores.size() + " ores, " + placed + " placed something; vanilla checksum " + Long.toHexString(checksum) + "; state shortcuts " + StateFast.ENABLED);
        assertTrue(placed > compared / 4, "too few placements placed anything: " + placed + " of " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " differ:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    /**
     * Ores with no targets (Macabre's, of sizes 32 and 64): the fast path returns after the spheres' draws. Vanilla visits the blob
     * and places nothing. The random's state must be the same, at every origin of the fuzz.
     */
    @Test
    void emptyTargetsMatchVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<String> failures = new ArrayList<>();
        int compared = 0;
        for (int size : new int[] { 32, 64, 9 }) {
            ConfiguredFeature<?, ?> ore = new ConfiguredFeature<>(Feature.ORE,
                new OreConfiguration(List.of(), size, 0.0F));
            FeatureRun.ConfiguredCase c = new FeatureRun.ConfiguredCase("empty ore " + size, Holder.direct(ore), 0, HarnessWorld.Kind.OVERWORLD, 0);
            for (int t = 0; t < TRIALS; t++) {
                FeatureRun.Result ref = run(r, c, 20261026L, t, true);
                FeatureRun.Result fast = run(r, c, 20261026L, t, false);
                List<String> diff = FeatureRun.diff(ref, fast);
                if (ref.error() != null || fast.error() != null || !diff.isEmpty()) {
                    failures.add(c.name() + " trial " + t + ": " + (ref.error() != null ? "vanilla threw " + ref.error()
                        : fast.error() != null ? fast.error().toString() : String.join("\n    ", diff)));
                }
                compared++;
            }
        }
        System.out.println("OreFastFuzzTest: " + compared + " placements of ores without targets compared");
        assertTrue(failures.isEmpty(), failures.size() + " differ:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    /** The blocks around trial t's origin as the run left them (states by identity hash: stable within one JVM run). */
    private static long blocksHash(FeatureRun.Result r, int t) {
        int x0 = FeatureRun.CENTER_X * 16 + (t * 7) % 16, z0 = FeatureRun.CENTER_Z * 16 + (t * 11 + 3) % 16;
        long h = 0;
        for (ProtoChunk c : r.chunks()) {
            if (Math.abs(c.getPos().x() * 16 + 8 - x0) > 24 || Math.abs(c.getPos().z() * 16 + 8 - z0) > 24) {
                continue;
            }
            for (LevelChunkSection s : c.getSections()) {
                for (int i = 0; i < 4096; i++) {
                    h = h * 1000003 + Block.getId(s.getBlockState(i & 15, i >> 8, (i >> 4) & 15));
                }
            }
        }
        return h;
    }

    /** One ore at an origin and random of trial {@code t} (columns of the centre chunk, heights over the world's range). */
    private static FeatureRun.Result run(HarnessWorld.Registries2 r, FeatureRun.ConfiguredCase c, long terrainSeed, int t, boolean vanilla) {
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, FeatureRun.terrain(r, c.kind(), terrainSeed), heights);
        Holder<Biome> biome = r.biomes.getOrThrow(c.kind() == HarnessWorld.Kind.NETHER ? Biomes.NETHER_WASTES
            : Biomes.PLAINS);
        for (ProtoChunk chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
        }
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, FeatureRun.CENTER_X, FeatureRun.CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()),
            terrainSeed, HarnessWorld.randomState(r, c.kind(), terrainSeed), r.access, false);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setSeed(terrainSeed * 31 + t * 0x9E3779B97F4A7C15L + c.name().hashCode());
        int x = FeatureRun.CENTER_X * 16 + (t * 7) % 16;
        int z = FeatureRun.CENTER_Z * 16 + (t * 11 + 3) % 16;
        int minY = heights.getMinY();
        int y = minY + 2 + (int) ((long) (t * 37) % (heights.getHeight() - 4));
        BlockPos origin = new BlockPos(x, y, z);
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
}
