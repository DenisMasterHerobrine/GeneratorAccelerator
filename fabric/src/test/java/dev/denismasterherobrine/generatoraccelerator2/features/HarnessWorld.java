package dev.denismasterherobrine.generatoraccelerator2.features;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * Builds harness levels: registries, a real chunk generator, and a deterministic synthetic terrain
 * (surface from noise, stone and deepslate, cave-air tunnels, water with sand and gravel beds, lava
 * below the deep level, bedrock, some all-air sections, snow in cold chunks) on a grid of proto
 * chunks at CARVERS with the worldgen heightmaps primed and the final heightmaps present in half of
 * the chunks. Terrain kinds cover the three vanilla dimensions.
 */
public final class HarnessWorld {
    private HarnessWorld() {}

    public enum Kind { OVERWORLD, NETHER, END }

    public static final int RADIUS = 3;
    /** FEATURES: STRUCTURE_STARTS at radius 8, CARVERS at radius 1 (the dependency list vanilla's region uses). */
    public static final ChunkDependencies FEATURE_DEPENDENCIES;

    static {
        List<ChunkStatus> deps = new ArrayList<>();
        deps.add(ChunkStatus.CARVERS);
        deps.add(ChunkStatus.CARVERS);
        for (int i = 2; i <= 8; i++) {
            deps.add(ChunkStatus.STRUCTURE_STARTS);
        }
        FEATURE_DEPENDENCIES = new ChunkDependencies(ImmutableList.copyOf(deps));
    }

    public static final class Registries2 {
        public final HolderLookup.Provider lookup;
        public final Registry<Biome> biomes;
        public final RegistryAccess access;

        /** Registries loaded elsewhere (e.g. from a datapack by the game's registry loader). */
        public Registries2(HolderLookup.Provider lookup, Registry<Biome> biomes, RegistryAccess access) {
            this.lookup = lookup;
            this.biomes = biomes;
            this.access = access;
        }

        Registries2() {
            dev.denismasterherobrine.generatoraccelerator2.testutil.VanillaTags.bind();
            this.lookup = VanillaRegistries.createLookup();
            MappedRegistry<Biome> reg = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
            lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(ref -> reg.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
            reg.freeze();
            this.biomes = reg;
            this.access = new RegistryAccess.ImmutableRegistryAccess(List.of(reg));
        }
    }

    private static Registries2 registries;

    public static synchronized Registries2 registries() {
        if (registries == null) {
            registries = new Registries2();
        }
        return registries;
    }

    public static NoiseBasedChunkGenerator generator(Registries2 r, Kind kind) {
        ResourceKey<NoiseGeneratorSettings> key = switch (kind) {
            case OVERWORLD -> NoiseGeneratorSettings.OVERWORLD;
            case NETHER -> NoiseGeneratorSettings.NETHER;
            case END -> NoiseGeneratorSettings.END;
        };
        Holder<NoiseGeneratorSettings> settings = r.lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key);
        Holder<Biome> plains = r.biomes.getHolderOrThrow(Biomes.PLAINS);
        return new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), settings);
    }

    public static DimensionType dimension(Registries2 r, Kind kind) {
        ResourceKey<DimensionType> key = switch (kind) {
            case OVERWORLD -> BuiltinDimensionTypes.OVERWORLD;
            case NETHER -> BuiltinDimensionTypes.NETHER;
            case END -> BuiltinDimensionTypes.END;
        };
        return r.lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key).value();
    }

    public static RandomState randomState(Registries2 r, Kind kind, long seed) {
        ResourceKey<NoiseGeneratorSettings> key = switch (kind) {
            case OVERWORLD -> NoiseGeneratorSettings.OVERWORLD;
            case NETHER -> NoiseGeneratorSettings.NETHER;
            case END -> NoiseGeneratorSettings.END;
        };
        return RandomState.create(r.lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value(), r.lookup.lookupOrThrow(Registries.NOISE), seed);
    }

    /** The grid's chunk index for {@code (cx, cz)} around the centre, as {@link HarnessLevel} expects. */
    public static int index(int cx, int cz, int centerX, int centerZ) {
        int size = 2 * RADIUS + 1;
        return (cx - centerX + RADIUS) + (cz - centerZ + RADIUS) * size;
    }

    /**
     * Builds the terrain once per (kind, seed, centre). Callers clone it with {@link #clone} for each
     * run so both runs start from identical chunks. {@code biomeAt} picks the biome of a chunk.
     */
    public static ProtoChunk[] terrain(Registries2 r, Kind kind, long seed, int centerX, int centerZ, IntFunction<Holder<Biome>> biomeAt) {
        DimensionType dim = dimension(r, kind);
        LevelHeightAccessor heights = LevelHeightAccessor.create(dim.minY(), dim.height());
        int size = 2 * RADIUS + 1;
        ProtoChunk[] chunks = new ProtoChunk[size * size];
        ImprovedNoise noise = new ImprovedNoise(new LegacyRandomSource(seed ^ 0x5DEECE66DL));
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int cx = centerX - RADIUS + dx;
                int cz = centerZ - RADIUS + dz;
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, heights, r.biomes, null);
                fill(chunk, kind, noise, seed, biomeAt.apply(dx + dz * size));
                Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                if (((cx + cz) & 1) == 0) {
                    Heightmap.primeHeightmaps(chunk, ChunkStatus.FINAL_HEIGHTMAPS);
                }
                chunks[dx + dz * size] = chunk;
            }
        }
        return chunks;
    }

    private static void fill(ProtoChunk chunk, Kind kind, ImprovedNoise noise, long seed, Holder<Biome> biome) {
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        boolean cold = (biome.unwrapKey().map(k -> k.location().getPath()).orElse("")).contains("snow")
            || biome.unwrapKey().map(k -> k.location().getPath()).orElse("").contains("frozen");
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                double h = noise.noise(x * 0.021, 0.0, z * 0.021) * 28.0 + noise.noise(x * 0.09, 7.0, z * 0.09) * 5.0;
                boolean mountains = (seed & 1L) != 0L; // odd seeds: tall terrain so high ore bands and mountain features run
                int surface = switch (kind) {
                    case OVERWORLD -> mountains ? 120 + (int) (h * 3.0) : 70 + (int) h;
                    case NETHER -> 40 + (int) (h * 0.7);
                    case END -> 50 + (int) (h * 0.5);
                };
                for (int y = minY; y < maxY; y++) {
                    BlockState state = null;
                    switch (kind) {
                        case OVERWORLD -> {
                            if (y == minY) {
                                state = Blocks.BEDROCK.defaultBlockState();
                            } else if (y <= surface) {
                                boolean cave = y < surface - 4 && noise.noise(x * 0.075, y * 0.075, z * 0.075) > 0.42;
                                if (cave) {
                                    state = y < -54 ? Blocks.LAVA.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState();
                                } else if (y == surface) {
                                    state = surface <= 62 ? (noise.noise(x * 0.3, 1.0, z * 0.3) > 0.1 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState()
                                        : Blocks.GRASS_BLOCK.defaultBlockState();
                                } else if (y > surface - 4) {
                                    state = Blocks.DIRT.defaultBlockState();
                                } else if (y < 0) {
                                    state = (y < -8 || noise.noise(x * 0.2, y * 0.2, z * 0.2) > 0.0) ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                                } else {
                                    state = Blocks.STONE.defaultBlockState();
                                }
                            } else if (y <= 62) {
                                state = Blocks.WATER.defaultBlockState();
                            } else if (cold && y == surface + 1) {
                                state = Blocks.SNOW.defaultBlockState();
                            }
                        }
                        case NETHER -> {
                            boolean ceiling = y >= maxY - 6 + (int) (noise.noise(x * 0.1, 3.0, z * 0.1) * 4.0);
                            if (y == minY || y == maxY - 1) {
                                state = Blocks.BEDROCK.defaultBlockState();
                            } else if (ceiling || y <= surface) {
                                boolean cave = y < surface - 3 && y > 34 && noise.noise(x * 0.06, y * 0.06, z * 0.06) > 0.35;
                                if (cave) {
                                    state = Blocks.CAVE_AIR.defaultBlockState();
                                } else {
                                    double m = noise.noise(x * 0.05, y * 0.05, z * 0.05);
                                    double n = noise.noise(x * 0.03, 11.0, z * 0.03);
                                    if (y == surface && !ceiling && n > 0.15) {
                                        state = n > 0.4 ? Blocks.WARPED_NYLIUM.defaultBlockState() : Blocks.CRIMSON_NYLIUM.defaultBlockState();
                                    } else {
                                        state = m > 0.3 ? Blocks.SOUL_SAND.defaultBlockState() : m < -0.35 ? Blocks.BASALT.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
                                    }
                                }
                            } else if (y <= 31) {
                                state = Blocks.LAVA.defaultBlockState();
                            }
                        }
                        case END -> {
                            if (y <= surface && y > surface - 30 + (int) (noise.noise(x * 0.05, 2.0, z * 0.05) * 20.0)) {
                                state = Blocks.END_STONE.defaultBlockState();
                            }
                        }
                    }
                    if (state != null) {
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                        section.setBlockState(lx, y & 15, lz, state, false);
                    }
                }
            }
        }
        chunk.fillBiomesFromNoise((x, y, z, sampler) -> biome, Climate.empty());
    }

    /** A deep copy of the terrain grid: new sections, new heightmap data, fresh tick lists. */
    public static ProtoChunk[] clone(Registries2 r, ProtoChunk[] source, LevelHeightAccessor heights) {
        ProtoChunk[] out = new ProtoChunk[source.length];
        for (int i = 0; i < source.length; i++) {
            ProtoChunk src = source[i];
            LevelChunkSection[] sections = new LevelChunkSection[src.getSections().length];
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection sec = src.getSection(s);
                sections[s] = new LevelChunkSection(cloneStates(sec.getStates()), sec.getBiomes().recreate());
            }
            ProtoChunk copy = new ProtoChunk(src.getPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), heights, r.biomes, null);
            for (Map.Entry<Heightmap.Types, Heightmap> e : src.getHeightmaps()) {
                copy.setHeightmap(e.getKey(), e.getValue().getRawData().clone());
            }
            copy.setPersistedStatus(src.getPersistedStatus());
            out[i] = copy;
        }
        return out;
    }

    private static final Codec<PalettedContainer<BlockState>> STATES_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
        PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    /** {@code PalettedContainer.copy()} shares the resize handler with its source, so clone through the codec like the chunk serializer does. */
    public static PalettedContainer<BlockState> cloneStates(PalettedContainer<BlockState> states) {
        Tag tag = STATES_CODEC.encodeStart(NbtOps.INSTANCE, states).getOrThrow();
        return STATES_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    public static LevelHeightAccessor heights(Registries2 r, Kind kind) {
        DimensionType dim = dimension(r, kind);
        return LevelHeightAccessor.create(dim.minY(), dim.height());
    }
}
