package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkBlocks;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkAccessAccessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;

/**
 * {@code NoiseBasedChunkGenerator.applyCarvers} with vanilla's carvers replayed from
 * {@link CarverPaths} and their block work done by {@link Carving}.
 *
 * <p>The loop is vanilla's. It visits the 17x17 source chunks x-major. Each source's carver biome is
 * cached on the source chunk, as vanilla caches it. The carvers run in list order with their index.
 * Supported carvers replay the cached path of their start, skipping starts whose reach misses this
 * chunk. Any other carver gets vanilla's seeded random and runs its own {@code carve}.
 */
public final class CarverRunner {
    private CarverRunner() {}

    /**
     * Another mod's modifier of the {@code surfaceRule()} value in {@code applyCarvers} (Citadel's merged rules). It is applied to
     * the rule the carving context is built from. Null when none is installed.
     */
    public static volatile java.util.function.BiFunction<NoiseBasedChunkGenerator, net.minecraft.world.level.levelgen.SurfaceRules.RuleSource,
        net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> surfaceRuleModifier;

    /** The surface rule vanilla's {@code applyCarvers} builds its carving context from, other mods' modifier included. */
    static net.minecraft.world.level.levelgen.SurfaceRules.RuleSource surfaceRule(NoiseBasedChunkGenerator generator) {
        net.minecraft.world.level.levelgen.SurfaceRules.RuleSource rule = generator.generatorSettings().value().surfaceRule();
        java.util.function.BiFunction<NoiseBasedChunkGenerator, net.minecraft.world.level.levelgen.SurfaceRules.RuleSource,
            net.minecraft.world.level.levelgen.SurfaceRules.RuleSource> modifier = surfaceRuleModifier;
        return modifier != null ? modifier.apply(generator, rule) : rule;
    }

    /** Source chunk lookup ({@code WorldGenRegion.getChunk(x, z)}). */
    public interface ChunkLookup {
        ChunkAccess get(int x, int z);
    }

    private static final ThreadLocal<Boolean> DISABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<ConfiguredWorldCarver<?>, Integer> KINDS = new ConcurrentHashMap<>();

    /** Forgets the carvers of a world that stopped (they belong to its registries). */
    public static void clearKinds() {
        KINDS.clear();
    }

    public static final LongAdder CHUNKS_FAST = new LongAdder();

    /** Top materials (below carved grass) answered from the chunk's rules built once, and by vanilla's call ({@code Carving.topMaterial}). */
    public static long topReused() {
        return Carving.TOP_REUSED.sum();
    }

    public static long topVanilla() {
        return Carving.TOP_VANILLA.sum();
    }
    public static final LongAdder CHUNKS_VANILLA = new LongAdder();
    public static final LongAdder CARVES_REPLAYED = new LongAdder();
    public static final LongAdder CARVES_FOREIGN = new LongAdder();
    /** {@code -Dgeneratoraccelerator2.verify.carvers=N}: every N-th chunk GA2 carves is checked against vanilla on a copy. */
    public static final dev.denismasterherobrine.generatoraccelerator2.chunkio.StepVerify VERIFY = new dev.denismasterherobrine.generatoraccelerator2.chunkio.StepVerify("carvers");

    /** Threads inside {@link #runVanilla}. While there are none, the override is not looked up (see {@code DfcHooks}). */
    private static final java.util.concurrent.atomic.AtomicInteger OVERRIDES = new java.util.concurrent.atomic.AtomicInteger();
    private static final HookTargets.Hook ENTRY = HookTargets.hook(HookTargets.CARVER_ENTRY);

    public static boolean enabled() {
        return (OVERRIDES.get() == 0 || !DISABLED.get()) && CarverConfig.enabled() && ENTRY.allowed();
    }

    /** Runs {@code r} with the carver hook disabled on this thread (vanilla carves). */
    public static void runVanilla(Runnable r) {
        OVERRIDES.incrementAndGet();
        boolean prev = DISABLED.get();
        DISABLED.set(Boolean.TRUE);
        try {
            r.run();
        } finally {
            DISABLED.set(prev);
            OVERRIDES.decrementAndGet();
        }
    }

    /** @return false when vanilla must carve this chunk */
    public static boolean apply(NoiseBasedChunkGenerator generator, ChunkLookup region, RegistryAccess registries, long seed, RandomState randomState,
                                BiomeManager levelBiomes, ChunkAccess chunk, GenerationStep.Carving step) {
        // a chunk the GPU filled carves with the GPU's aquifer cells and no NoiseChunk. The carving context's top material reads the
        // GPU's surface levels.
        dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain terrain = dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain.of(chunk);
        if (!enabled() || chunk.getClass() != ProtoChunk.class || ((ChunkAccessAccessor) chunk).ga2$noiseChunk() == null && terrain == null) {
            CHUNKS_VANILLA.increment();
            return false;
        }
        ChunkBlocks blocks = ChunkBlocks.of(chunk);
        if (!blocks.fast()) {
            CHUNKS_VANILLA.increment();
            return false;
        }
        BiomeSource biomeSource = generator.getBiomeSource();
        BiomeManager biomes = levelBiomes.withDifferentSource((x, y, z) -> biomeSource.getNoiseBiome(x, y, z, randomState.sampler()));
        WorldgenRandom random = null;
        ChunkPos pos = chunk.getPos();
        NoiseChunk noiseChunk = terrain != null ? null : ((ChunkAccessAccessor) chunk).ga2$noiseChunk();
        Aquifer aquifer = terrain != null ? terrain.aquifer() : noiseChunk.aquifer();
        CarvingContext context = new CarvingContext(generator, registries, chunk.getHeightAccessorForGeneration(), noiseChunk, randomState,
            surfaceRule(generator));
        CarvingMask mask = ((ProtoChunk) chunk).getOrCreateCarvingMask(step);
        Carving carving = new Carving(context, chunk, blocks, biomes::getBiome, aquifer, mask);
        // the start check's random, reseeded for every source chunk and carver
        FastLegacyRandom start = new FastLegacyRandom(0L);
        blocks.lock();
        try {
            for (int j = -8; j <= 8; j++) {
                for (int k = -8; k <= 8; k++) {
                    int sx = pos.x + j;
                    int sz = pos.z + k;
                    ChunkAccess sourceChunk = region.get(sx, sz);
                    BiomeGenerationSettings settings = sourceChunk.carverBiome(() -> generator.getBiomeGenerationSettings(
                        biomeSource.getNoiseBiome(QuartPos.fromBlock(SectionPos.sectionToBlockCoord(sx)), 0, QuartPos.fromBlock(SectionPos.sectionToBlockCoord(sz)),
                            randomState.sampler())));
                    // a holder set is read by index. Its iterator cost one object per chunk around each carved chunk.
                    Iterable<Holder<ConfiguredWorldCarver<?>>> carvers = settings.getCarvers(step);
                    HolderSet<ConfiguredWorldCarver<?>> set = carvers instanceof HolderSet<ConfiguredWorldCarver<?>> s ? s : null;
                    java.util.Iterator<Holder<ConfiguredWorldCarver<?>>> it = set == null ? carvers.iterator() : null;
                    int n = set == null ? 0 : set.size();
                    for (int l = 0; set != null ? l < n : it.hasNext(); l++) {
                        Holder<ConfiguredWorldCarver<?>> holder = set != null ? set.get(l) : it.next();
                        ConfiguredWorldCarver<?> carver = holder.value();
                        // read first. computeIfAbsent locks the key's bin even when the key is present, unless the key heads the
                        // bin. This runs for every carver of the 289 chunks around each carved chunk.
                        Integer known = KINDS.get(carver);
                        int kind = known != null ? known : KINDS.computeIfAbsent(carver, CarverPaths::kind);
                        if (kind != CarverPaths.UNSUPPORTED) {
                            CarverPath path = CarverPaths.path(seed, sx, sz, l, carver, kind, context, start);
                            if (!path.misses(carving.midX(), carving.midZ())) {
                                carving.carver((CarverConfiguration) carver.config(), kind == CarverPaths.NETHER);
                                carving.replay(path);
                                CARVES_REPLAYED.increment();
                            }
                        } else {
                            if (random == null) {
                                random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
                            }
                            random.setLargeFeatureSeed(seed + (long) l, sx, sz);
                            if (carver.isStartChunk(random)) {
                                blocks.unlock();
                                try {
                                    carver.carve(context, chunk, biomes::getBiome, random, aquifer, new ChunkPos(sx, sz), mask);
                                } finally {
                                    blocks.lock();
                                }
                                CARVES_FOREIGN.increment();
                            }
                        }
                    }
                }
            }
        } finally {
            blocks.unlock();
        }
        CHUNKS_FAST.increment();
        return true;
    }
}
