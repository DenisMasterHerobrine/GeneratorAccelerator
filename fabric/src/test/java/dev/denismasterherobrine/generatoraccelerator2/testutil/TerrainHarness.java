package dev.denismasterherobrine.generatoraccelerator2.testutil;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Real terrain for surface and carver tests: the vanilla noise generator with the real biome source
 * of a world preset fills proto chunks exactly as the NOISE step does (biomes, then the noise fill),
 * without structures. Runs of the surface and carver code start from clones of the filled chunk, each
 * with its own fresh NoiseChunk, so a vanilla run and an GA2 run see identical input.
 */
public final class TerrainHarness {
    private TerrainHarness() {}

    public enum Preset {
        OVERWORLD(NoiseGeneratorSettings.OVERWORLD, BuiltinDimensionTypes.OVERWORLD),
        LARGE_BIOMES(NoiseGeneratorSettings.LARGE_BIOMES, BuiltinDimensionTypes.OVERWORLD),
        AMPLIFIED(NoiseGeneratorSettings.AMPLIFIED, BuiltinDimensionTypes.OVERWORLD),
        NETHER(NoiseGeneratorSettings.NETHER, BuiltinDimensionTypes.NETHER),
        END(NoiseGeneratorSettings.END, BuiltinDimensionTypes.END);

        final ResourceKey<NoiseGeneratorSettings> settings;
        final ResourceKey<DimensionType> dimension;

        Preset(ResourceKey<NoiseGeneratorSettings> settings, ResourceKey<DimensionType> dimension) {
            this.settings = settings;
            this.dimension = dimension;
        }
    }

    /** One generator + random state + biome source, like a ServerLevel's chunk source. */
    public static final class World {
        public final Preset preset;
        public final long seed;
        public final HarnessWorld.Registries2 registries;
        public final NoiseBasedChunkGenerator generator;
        public final NoiseGeneratorSettings settings;
        public final RandomState randomState;
        public final BiomeSource biomeSource;
        public final LevelHeightAccessor heights;
        public final Aquifer.FluidPicker fluidPicker;
        /** The level's biome zoom seed ({@code BiomeManager.obfuscateSeed(seed)}). */
        public final long zoomSeed;

        public World(Preset preset, long seed) {
            this.preset = preset;
            this.seed = seed;
            this.registries = HarnessWorld.registries();
            var biomeLookup = registries.biomes.asLookup();
            this.biomeSource = switch (preset) {
                case NETHER -> MultiNoiseBiomeSource.createFromPreset(Holder.direct(
                    new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.NETHER, biomeLookup)));
                case END -> TheEndBiomeSource.create(biomeLookup);
                default -> MultiNoiseBiomeSource.createFromPreset(Holder.direct(
                    new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, biomeLookup)));
            };
            Holder<NoiseGeneratorSettings> settingsHolder = registries.lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset.settings);
            this.settings = settingsHolder.value();
            this.generator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
            this.randomState = RandomState.create(settings, registries.lookup.lookupOrThrow(Registries.NOISE), seed);
            DimensionType dim = registries.lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(preset.dimension).value();
            this.heights = LevelHeightAccessor.create(dim.minY(), dim.height());
            this.fluidPicker = FillHarness.vanillaPicker(settings);
            this.zoomSeed = BiomeManager.obfuscateSeed(seed);
            biomeSource.possibleBiomes(); // resolve lazily built biome lists up front
        }

        /** A world on other registries (a datapack's noise settings and biome parameters, loaded by the game's own registry loader). */
        public World(HarnessWorld.Registries2 registries, Holder<NoiseGeneratorSettings> settingsHolder, BiomeSource biomeSource,
                     net.minecraft.core.HolderGetter<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> noises, DimensionType dim, long seed) {
            this.preset = Preset.OVERWORLD;
            this.seed = seed;
            this.registries = registries;
            this.biomeSource = biomeSource;
            this.settings = settingsHolder.value();
            this.generator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
            this.randomState = RandomState.create(settings, noises, seed);
            this.heights = LevelHeightAccessor.create(dim.minY(), dim.height());
            this.fluidPicker = FillHarness.vanillaPicker(settings);
            this.zoomSeed = BiomeManager.obfuscateSeed(seed);
            biomeSource.possibleBiomes();
        }

        public ProtoChunk emptyChunk(int cx, int cz) {
            return new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, heights, registries.biomes, null);
        }

        /** The NoiseChunk the NOISE step would create for this chunk without structures nearby. */
        public NoiseChunk noiseChunk(ChunkAccess chunk) {
            return chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState,
                new Beardifier(new ObjectArrayList<Beardifier.Rigid>().iterator(), new ObjectArrayList<JigsawJunction>().iterator()),
                settings, fluidPicker, Blender.empty()));
        }

        /** BIOMES step: biomes from the real biome source. */
        public ProtoChunk biomes(int cx, int cz) {
            ProtoChunk chunk = emptyChunk(cx, cz);
            noiseChunk(chunk);
            generator.createBiomes(randomState, Blender.empty(), null, chunk).join();
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            return chunk;
        }

        /** BIOMES and NOISE steps. */
        public ProtoChunk noise(int cx, int cz) {
            ProtoChunk chunk = biomes(cx, cz);
            generator.fillFromNoise(Blender.empty(), randomState, null, chunk).join();
            chunk.setPersistedStatus(ChunkStatus.NOISE);
            return chunk;
        }

        public WorldGenerationContext generationContext() {
            return new WorldGenerationContext(generator, heights);
        }

        /** Holder of the biome the biome source picks at a block position (quart sampling at the block's quart). */
        public Holder<Biome> biomeAt(int x, int y, int z) {
            return biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler());
        }
    }

    private static final Map<String, World> WORLDS = new HashMap<>();

    public static synchronized World world(Preset preset, long seed) {
        return WORLDS.computeIfAbsent(preset + ":" + seed, k -> new World(preset, seed));
    }

    /**
     * A chunk after NOISE plus its 3x3 neighbourhood at BIOMES: everything the SURFACE step reads.
     * {@link #center()} returns a fresh clone with a fresh NoiseChunk for every run.
     */
    public static final class Site {
        public final World world;
        public final int cx;
        public final int cz;
        private final ProtoChunk filled;
        private final ProtoChunk[] neighbours = new ProtoChunk[9];

        Site(World world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.filled = world.noise(cx, cz);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    neighbours[(dx + 1) + (dz + 1) * 3] = dx == 0 && dz == 0 ? filled : world.biomes(cx + dx, cz + dz);
                }
            }
        }

        /** A fresh copy of the filled chunk (blocks, heightmaps, status) with a fresh NoiseChunk. */
        public ProtoChunk center() {
            ProtoChunk copy = HarnessWorld.clone(world.registries, new ProtoChunk[] { filled }, world.heights)[0];
            copyBiomes(filled, copy);
            world.noiseChunk(copy);
            return copy;
        }

        /** The region's noise-biome view used by the surface step's BiomeManager (LevelReader.getNoiseBiome over loaded chunks). */
        public BiomeManager.NoiseBiomeSource regionBiomes(ChunkAccess center) {
            return (x, y, z) -> {
                int sx = QuartPos.toSection(x) - cx + 1;
                int sz = QuartPos.toSection(z) - cz + 1;
                if (sx < 0 || sx > 2 || sz < 0 || sz > 2) {
                    throw new IllegalStateException("biome outside the surface region: quart " + x + "," + z);
                }
                ChunkAccess chunk = sx == 1 && sz == 1 ? center : neighbours[sx + sz * 3];
                return chunk.getNoiseBiome(x, y, z);
            };
        }

        public BiomeManager surfaceBiomeManager(ChunkAccess center) {
            return new BiomeManager(regionBiomes(center), world.zoomSeed);
        }
    }

    private static void copyBiomes(ProtoChunk from, ProtoChunk to) {
        for (int s = 0; s < from.getSections().length; s++) {
            var src = from.getSection(s).getBiomes();
            var dst = to.getSection(s);
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        ((net.minecraft.world.level.chunk.PalettedContainer<Holder<Biome>>) dst.getBiomes()).getAndSetUnchecked(x, y, z, src.get(x, y, z));
                    }
                }
            }
        }
    }

    public static Site site(Preset preset, long seed, int cx, int cz) {
        return new Site(world(preset, seed), cx, cz);
    }

    /** Vanilla's surface step body: {@code NoiseBasedChunkGenerator.buildSurface(ChunkAccess, ...)} on a site copy. */
    public static void vanillaSurface(Site site, ProtoChunk chunk) {
        World w = site.world;
        w.generator.buildSurface(chunk, w.generationContext(), w.randomState, null, site.surfaceBiomeManager(chunk), w.registries.biomes, Blender.empty());
    }

    /** Lookup of the source chunks a carver run reads ({@code WorldGenRegion.getChunk} within radius 8). */
    public interface ChunkLookup {
        ChunkAccess get(int cx, int cz);
    }

    /** A 17x17 neighbourhood of bare proto chunks (STRUCTURE_STARTS stand-ins: carvers only cache their carver biome on them). */
    public static ChunkLookup carverNeighbourhood(World w, ProtoChunk center) {
        return carverNeighbourhood(w, center, new HashMap<>());
    }

    /**
     * Like {@link #carverNeighbourhood(World, ProtoChunk)} with the stand-in chunks taken from (and kept
     * in) {@code chunks}, so runs over neighbouring chunks share them, and their cached carver biomes,
     * as in a level.
     */
    public static ChunkLookup carverNeighbourhood(World w, ProtoChunk center, Map<Long, ProtoChunk> chunks) {
        ChunkPos c = center.getPos();
        return (x, z) -> {
            if (x == c.x && z == c.z) {
                return center;
            }
            if (Math.abs(x - c.x) > 8 || Math.abs(z - c.z) > 8) {
                throw new IllegalStateException("carver read outside radius 8");
            }
            return chunks.computeIfAbsent(ChunkPos.asLong(x, z), k -> w.emptyChunk(x, z));
        };
    }

    /**
     * Transcription of {@code NoiseBasedChunkGenerator.applyCarvers} with a chunk lookup in place of the
     * WorldGenRegion. The carvers themselves are the vanilla code. The biome manager is the level's
     * ({@code BiomeManager} with the zoom seed) re-sourced to the biome source, as in vanilla.
     */
    public static void vanillaCarvers(World w, ProtoChunk chunk, ChunkLookup region) {
        BiomeManager biomeManager = new BiomeManager((x, y, z) -> w.biomeSource.getNoiseBiome(x, y, z, w.randomState.sampler()), w.zoomSeed);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        ChunkPos pos = chunk.getPos();
        NoiseChunk noiseChunk = w.noiseChunk(chunk);
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext context = new CarvingContext(w.generator, w.registries.access, chunk.getHeightAccessorForGeneration(), noiseChunk, w.randomState,
            w.settings.surfaceRule());
        var mask = chunk.getOrCreateCarvingMask(GenerationStep.Carving.AIR);
        for (int j = -8; j <= 8; j++) {
            for (int k = -8; k <= 8; k++) {
                ChunkPos source = new ChunkPos(pos.x + j, pos.z + k);
                ChunkAccess sourceChunk = region.get(source.x, source.z);
                BiomeGenerationSettings biomeSettings = sourceChunk.carverBiome(() -> w.generator.getBiomeGenerationSettings(
                    w.biomeSource.getNoiseBiome(QuartPos.fromBlock(source.getMinBlockX()), 0, QuartPos.fromBlock(source.getMinBlockZ()), w.randomState.sampler())));
                int l = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : biomeSettings.getCarvers(GenerationStep.Carving.AIR)) {
                    ConfiguredWorldCarver<?> carver = holder.value();
                    random.setLargeFeatureSeed(w.seed + (long) l, source.x, source.z);
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, chunk, biomeManager::getBiome, random, aquifer, source, mask);
                    }
                    l++;
                }
            }
        }
    }
}
