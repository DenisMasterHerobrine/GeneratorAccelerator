package dev.denismasterherobrine.generatoraccelerator2.features;

import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Runs one placed feature on a fresh harness level exactly as {@code ChunkGenerator.applyBiomeDecoration}
 * would (decoration seed, feature seed, biome-checked placement at the section origin) and captures
 * everything the run may have changed.
 */
public final class FeatureRun {
    private FeatureRun() {}

    public static final int CENTER_X = 11;
    public static final int CENTER_Z = -7;

    /** A placed feature with two biomes that use it (the second may equal plains when only one does) and its dimension kind. */
    public record Case(String name, Holder<PlacedFeature> feature, int index, Holder<Biome> biomeA, Holder<Biome> biomeB, HarnessWorld.Kind kind) {}

    public record Result(boolean placed, int randomCount, long randomProbe, long subTicks, List<HarnessLevel.PoiChange> poi, ProtoChunk[] chunks,
                         HarnessLevel level, Throwable error) {}

    private static final Map<String, ProtoChunk[]> TERRAIN = new HashMap<>();

    public static synchronized ProtoChunk[] terrain(HarnessWorld.Registries2 r, HarnessWorld.Kind kind, long seed) {
        return TERRAIN.computeIfAbsent(kind + "/" + seed, k -> HarnessWorld.terrain(r, kind, seed, CENTER_X, CENTER_Z, i -> r.biomes.getHolderOrThrow(Biomes.PLAINS)));
    }

    /** Every vanilla placed feature that at least one biome uses, with its dimension kind from that biome. */
    public static List<Case> cases(HarnessWorld.Registries2 r) {
        Map<PlacedFeature, List<Holder<Biome>>> users = new HashMap<>();
        for (Holder.Reference<Biome> biome : r.biomes.holders().toList()) {
            for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> pf : step) {
                    users.computeIfAbsent(pf.value(), k -> new ArrayList<>()).add(biome);
                }
            }
        }
        List<Case> out = new ArrayList<>();
        int index = 0;
        for (Holder.Reference<PlacedFeature> pf : r.lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList()) {
            List<Holder<Biome>> biomes = users.get(pf.value());
            if (biomes == null) {
                continue;
            }
            Holder<Biome> a = biomes.get(0);
            Holder<Biome> b = biomes.size() > 1 ? biomes.get(1) : r.biomes.getHolderOrThrow(Biomes.PLAINS);
            out.add(new Case(pf.key().location().toString(), pf, index++, a, b, kindOf(a)));
        }
        return out;
    }

    /** Trees with a beehive decorator place the nest with the JDK static Random in vanilla. Not deterministic, so excluded. */
    public static boolean hasBeehiveDecorator(net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?> cf) {
        return cf.getFeatures().anyMatch(f -> f.config() instanceof net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration tc
            && tc.decorators.stream().anyMatch(d -> d instanceof net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator));
    }

    public static HarnessWorld.Kind kindOf(Holder<Biome> biome) {
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        return switch (path) {
            case "nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas" -> HarnessWorld.Kind.NETHER;
            case "the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens" -> HarnessWorld.Kind.END;
            default -> HarnessWorld.Kind.OVERWORLD;
        };
    }

    /** Runs the case on a clone of the cached terrain. With {@code vanilla}, the case runs with every hook disabled. */
    public static Result run(HarnessWorld.Registries2 r, Case c, long seed, boolean vanilla, boolean cacheHeightmaps) {
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, terrain(r, c.kind(), seed), heights);
        int size = 2 * HarnessWorld.RADIUS + 1;
        for (int i = 0; i < chunks.length; i++) {
            int dx = i % size;
            int dz = i / size;
            Holder<Biome> biome = ((dx + dz) & 1) == 0 ? c.biomeA() : c.biomeB();
            chunks[i].fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
        }
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, CENTER_X, CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()), seed,
            HarnessWorld.randomState(r, c.kind(), seed), r.access, cacheHeightmaps);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(seed, CENTER_X * 16, CENTER_Z * 16);
        random.setFeatureSeed(decorationSeed, c.index(), c.index() % 11);
        BlockPos origin = new BlockPos(CENTER_X * 16, heights.getMinBuildHeight(), CENTER_Z * 16);
        boolean[] placed = new boolean[1];
        Throwable[] error = new Throwable[1];
        Runnable body = () -> {
            try {
                placed[0] = c.feature().value().placeWithBiomeCheck(level, generator, random, origin);
            } catch (Throwable t) {
                error[0] = t;
            }
        };
        if (vanilla) {
            FeatureHooks.runVanilla(body);
        } else {
            body.run();
        }
        return new Result(placed[0], random.getCount(), random.nextLong(), level.nextSubTickCount(), level.poiChanges(), chunks, level, error[0]);
    }

    /** A configured feature run directly (no placement modifiers) at one of three origin heights, so rare features are exercised. */
    public record ConfiguredCase(String name, net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> feature, int index,
                                 HarnessWorld.Kind kind, int originKind) {}

    public static List<ConfiguredCase> configuredCases(HarnessWorld.Registries2 r) {
        List<ConfiguredCase> out = new ArrayList<>();
        int index = 0;
        for (Holder.Reference<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> cf
            : r.lookup.lookupOrThrow(Registries.CONFIGURED_FEATURE).listElements().toList()) {
            String path = cf.key().location().getPath();
            HarnessWorld.Kind kind = path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("basalt") || path.contains("delta")
                || path.contains("glowstone") || path.contains("soul") || path.contains("weeping") || path.contains("twisting") || path.contains("fungus")
                ? HarnessWorld.Kind.NETHER : path.contains("end") || path.contains("chorus") ? HarnessWorld.Kind.END : HarnessWorld.Kind.OVERWORLD;
            for (int originKind = 0; originKind < 3; originKind++) {
                out.add(new ConfiguredCase(cf.key().location() + "@" + originKind, cf, index, kind, originKind));
            }
            index++;
        }
        return out;
    }

    public static Result runConfigured(HarnessWorld.Registries2 r, ConfiguredCase c, long seed, boolean vanilla, boolean cacheHeightmaps) {
        LevelHeightAccessor heights = HarnessWorld.heights(r, c.kind());
        ProtoChunk[] chunks = HarnessWorld.clone(r, terrain(r, c.kind(), seed), heights);
        Holder<Biome> biome = r.biomes.getHolderOrThrow(c.kind() == HarnessWorld.Kind.NETHER ? net.minecraft.world.level.biome.Biomes.NETHER_WASTES
            : c.kind() == HarnessWorld.Kind.END ? net.minecraft.world.level.biome.Biomes.THE_END : net.minecraft.world.level.biome.Biomes.PLAINS);
        for (ProtoChunk chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
        }
        NoiseBasedChunkGenerator generator = HarnessWorld.generator(r, c.kind());
        HarnessLevel level = new HarnessLevel(chunks, HarnessWorld.RADIUS, CENTER_X, CENTER_Z, heights, HarnessWorld.dimension(r, c.kind()), seed,
            HarnessWorld.randomState(r, c.kind(), seed), r.access, cacheHeightmaps);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(seed, CENTER_X * 16, CENTER_Z * 16);
        random.setFeatureSeed(decorationSeed, c.index(), c.originKind());
        int x = CENTER_X * 16 + 3 + c.originKind() * 4;
        int z = CENTER_Z * 16 + 9 - c.originKind() * 3;
        int y = switch (c.originKind()) {
            case 0 -> level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
            case 1 -> c.kind() == HarnessWorld.Kind.OVERWORLD ? 24 : c.kind() == HarnessWorld.Kind.NETHER ? 36 : 45;
            default -> c.kind() == HarnessWorld.Kind.OVERWORLD ? -30 : c.kind() == HarnessWorld.Kind.NETHER ? 70 : 20;
        };
        BlockPos origin = new BlockPos(x, y, z);
        boolean[] placed = new boolean[1];
        Throwable[] error = new Throwable[1];
        Runnable body = () -> {
            try {
                placed[0] = c.feature().value().place(level, generator, random, origin);
            } catch (Throwable t) {
                error[0] = t;
            }
        };
        if (vanilla) {
            FeatureHooks.runVanilla(body);
        } else {
            body.run();
        }
        return new Result(placed[0], random.getCount(), random.nextLong(), level.nextSubTickCount(), level.poiChanges(), chunks, level, error[0]);
    }

    /** Differences between two results over the write radius. Empty when identical. */
    public static List<String> diff(Result expected, Result actual) {
        List<String> out = new ArrayList<>();
        if (expected.placed() != actual.placed()) {
            out.add("placed: expected " + expected.placed() + " got " + actual.placed());
        }
        if (expected.randomCount() != actual.randomCount() || expected.randomProbe() != actual.randomProbe()) {
            out.add("random: expected " + expected.randomCount() + " calls/probe " + expected.randomProbe() + " got " + actual.randomCount() + "/" + actual.randomProbe());
        }
        if (expected.subTicks() != actual.subTicks()) {
            out.add("sub-tick count: expected " + expected.subTicks() + " got " + actual.subTicks());
        }
        if (!expected.poi().equals(actual.poi())) {
            out.add("POI changes: expected " + expected.poi() + " got " + actual.poi());
        }
        int size = 2 * HarnessWorld.RADIUS + 1;
        for (int dz = HarnessWorld.RADIUS - 1; dz <= HarnessWorld.RADIUS + 1; dz++) {
            for (int dx = HarnessWorld.RADIUS - 1; dx <= HarnessWorld.RADIUS + 1; dx++) {
                int i = dx + dz * size;
                out.addAll(FeatureCompare.compare(expected.chunks()[i], actual.chunks()[i], 6));
                if (out.size() > 20) {
                    return out;
                }
            }
        }
        return out;
    }
}
