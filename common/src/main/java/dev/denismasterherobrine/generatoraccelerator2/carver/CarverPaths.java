package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.memory.IdleTrims;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.BiasedToBottomHeight;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight;

/**
 * Builds and caches {@link CarverPath}s. A path holds the start decision and everything the start
 * carves. It is keyed by world seed, source chunk, carver index, configured carver and generation
 * height range.
 *
 * <p>Every random draw of a vanilla cave, nether cave or canyon carver depends only on the world
 * seed, the source chunk, the carver's index in the source biome's list and the carver's
 * configuration. Vanilla seeds the shared random with {@code setLargeFeatureSeed} before each
 * carver, and every tunnel draws from its own random. The target chunk only decides which ellipsoids
 * are carved and where a tunnel stops ({@code canReach}). Those tests draw nothing. So the simulation
 * below is the vanilla carver code with the ellipsoids recorded instead of carved. It runs once per
 * start and is replayed for each of the up to 289 chunks the start can reach. Only exact vanilla
 * carver and configuration classes are simulated, and only with vanilla value providers that never
 * draw Gaussians, so no random state survives the reseeding. Everything else carves through the
 * vanilla code.
 */
public final class CarverPaths {
    private CarverPaths() {}

    public static final LongAdder SIMULATED = new LongAdder();
    public static final LongAdder HITS = new LongAdder();

    private static final Set<Class<?>> FLOATS = Set.of(ConstantFloat.class, UniformFloat.class, TrapezoidFloat.class);
    private static final Set<Class<?>> HEIGHTS = Set.of(ConstantHeight.class, UniformHeight.class, BiasedToBottomHeight.class, VeryBiasedToBottomHeight.class,
        TrapezoidHeight.class);

    /** Kinds of simulated carvers. */
    static final int CAVE = 0;
    static final int NETHER = 1;
    static final int CANYON = 2;
    static final int UNSUPPORTED = -1;

    /** Which simulation fits a configured carver, or {@link #UNSUPPORTED}. */
    static int kind(ConfiguredWorldCarver<?> carver) {
        if (!HookTargets.allowed(HookTargets.CARVER_REPLAY)) {
            // another mod changes the vanilla carvers: they keep running their (changed) code
            return UNSUPPORTED;
        }
        Class<?> worldCarver = carver.worldCarver().getClass();
        Object config = carver.config();
        if (worldCarver == CaveWorldCarver.class || worldCarver == NetherWorldCarver.class) {
            if (config.getClass() != CaveCarverConfiguration.class) {
                return UNSUPPORTED;
            }
            CaveCarverConfiguration c = (CaveCarverConfiguration) config;
            if (c.debugSettings.isDebugMode() || !HEIGHTS.contains(c.y.getClass()) || !floats(c.yScale, c.horizontalRadiusMultiplier, c.verticalRadiusMultiplier, c.floorLevel)) {
                return UNSUPPORTED;
            }
            return worldCarver == CaveWorldCarver.class ? CAVE : NETHER;
        }
        if (worldCarver == CanyonWorldCarver.class) {
            if (config.getClass() != CanyonCarverConfiguration.class) {
                return UNSUPPORTED;
            }
            CanyonCarverConfiguration c = (CanyonCarverConfiguration) config;
            if (c.debugSettings.isDebugMode() || !HEIGHTS.contains(c.y.getClass()) || !floats(c.yScale, c.verticalRotation, c.shape.distanceFactor, c.shape.thickness,
                c.shape.horizontalRadiusFactor)) {
                return UNSUPPORTED;
            }
            return CANYON;
        }
        return UNSUPPORTED;
    }

    private static boolean floats(FloatProvider... providers) {
        for (FloatProvider p : providers) {
            if (!FLOATS.contains(p.getClass())) {
                return false;
            }
        }
        return true;
    }

    // ---- cache ----

    private record Key(long seed, int sx, int sz, int index, ConfiguredWorldCarver<?> carver, int minGenY, int genDepth) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.seed == seed && k.sx == sx && k.sz == sz && k.index == index && k.carver == carver && k.minGenY == minGenY
                && k.genDepth == genDepth;
        }

        @Override
        public int hashCode() {
            long h = seed * 31 + sx;
            h = h * 1_000_003 + sz;
            h = h * 31 + index;
            h = h * 31 + System.identityHashCode(carver);
            h = h * 31 + minGenY;
            h = h * 31 + genDepth;
            return Long.hashCode(h);
        }
    }

    /** Two generations. Lookups promote from the old one. When the current one is full, it becomes the old one. */
    private static volatile ConcurrentHashMap<Key, CarverPath> current = new ConcurrentHashMap<>();
    private static volatile ConcurrentHashMap<Key, CarverPath> previous = new ConcurrentHashMap<>();

    static int capacity() {
        return CarverConfig.pathCacheEntries();
    }

    /**
     * The path of carver {@code index} of source chunk {@code (sx, sz)}: {@link CarverPath#NONE} when the
     * start check fails, otherwise everything the start carves.
     */
    static CarverPath path(long seed, int sx, int sz, int index, ConfiguredWorldCarver<?> carver, int kind, WorldGenerationContext context, FastLegacyRandom start) {
        // most sources start nothing. Their check is a few random draws, cheaper than a lookup,
        // and is kept out of the cache. {@code start} is the caller's, reseeded here in full.
        start.setLargeFeatureSeed(seed + (long) index, sx, sz);
        if (!carver.isStartChunk(start)) {
            HITS.increment();
            return CarverPath.NONE;
        }
        Key key = new Key(seed, sx, sz, index, carver, context.getMinGenY(), context.getGenDepth());
        ConcurrentHashMap<Key, CarverPath> cur = current;
        CarverPath path = cur.get(key);
        if (path != null) {
            HITS.increment();
            return path;
        }
        path = previous.get(key);
        if (path == null) {
            path = simulate(seed, sx, sz, index, carver, kind, context);
            SIMULATED.increment();
        } else {
            HITS.increment();
        }
        // A nearly full heap keeps an eighth of the usual generation.
        int shift = MemoryPressure.high() ? 3 : 0;
        if (cur.put(key, path) == null && (cur.size() > capacity() >> shift || weight.addAndGet(weight(path)) > MAX_STEPS >> shift)) {
            synchronized (CarverPaths.class) {
                if (current == cur) {
                    previous = cur;
                    current = new ConcurrentHashMap<>();
                    weight.set(0);
                }
            }
        }
        return path;
    }

    public static void clear() {
        synchronized (CarverPaths.class) {
            current = new ConcurrentHashMap<>();
            previous = new ConcurrentHashMap<>();
            weight.set(0);
        }
    }

    /** When the heap fills, the old generation goes. The current one shrinks as it is used. */
    private static final IdleTrims.Trimmable TRIM = (now, pressure) -> {
        if (pressure) {
            previous = new ConcurrentHashMap<>();
        }
    };

    static {
        IdleTrims.register(TRIM);
    }

    /**
     * Recorded ellipsoids one generation may hold, about 44 bytes each. That is a few thousand starts, the working set of
     * the chunks being carved, whatever the carvers' start probabilities.
     */
    static final long MAX_STEPS = 1L << 18;
    private static final AtomicLong weight = new AtomicLong();

    private static long weight(CarverPath path) {
        long w = 1;
        for (CarverPath.Op op : path.ops) {
            w += weight(op);
        }
        return w;
    }

    private static long weight(CarverPath.Op op) {
        if (op instanceof CarverPath.Tunnel t) {
            return 1 + t.n + (t.branchA != null ? weight(t.branchA) : 0) + (t.branchB != null ? weight(t.branchB) : 0);
        }
        return 1;
    }

    public static int size() {
        return current.size() + previous.size();
    }

    // ---- simulation (vanilla code with ellipsoids recorded) ----

    static CarverPath simulate(long seed, int sx, int sz, int index, ConfiguredWorldCarver<?> carver, int kind, WorldGenerationContext context) {
        FastLegacyRandom random = new FastLegacyRandom(0L);
        random.setLargeFeatureSeed(seed + (long) index, sx, sz);
        if (!carver.isStartChunk(random)) {
            return CarverPath.NONE;
        }
        List<CarverPath.Op> ops = new ArrayList<>();
        ChunkPos source = new ChunkPos(sx, sz);
        if (kind == CANYON) {
            canyon(context, (CanyonCarverConfiguration) carver.config(), random, source, ops);
        } else {
            cave(context, (CaveCarverConfiguration) carver.config(), random, source, kind == NETHER, ops);
        }
        return CarverPath.of(ops);
    }

    private static int caveBound(boolean nether) {
        return nether ? 10 : 15;
    }

    private static float thickness(RandomSource random, boolean nether) {
        if (nether) {
            return (random.nextFloat() * 2.0F + random.nextFloat()) * 2.0F;
        }
        float f = random.nextFloat() * 2.0F + random.nextFloat();
        if (random.nextInt(10) == 0) {
            f *= random.nextFloat() * random.nextFloat() * 3.0F + 1.0F;
        }
        return f;
    }

    /** {@code CaveWorldCarver.carve} (and {@code NetherWorldCarver}'s overrides of bound, thickness and y scale). */
    private static void cave(WorldGenerationContext ctx, CaveCarverConfiguration config, FastLegacyRandom random, ChunkPos chunkPos, boolean nether,
                             List<CarverPath.Op> ops) {
        int i = SectionPos.sectionToBlockCoord(4 * 2 - 1);
        int j = random.nextInt(random.nextInt(random.nextInt(caveBound(nether)) + 1) + 1);
        for (int k = 0; k < j; k++) {
            double d0 = (double) chunkPos.getBlockX(random.nextInt(16));
            double d1 = (double) config.y.sample(random, ctx);
            double d2 = (double) chunkPos.getBlockZ(random.nextInt(16));
            double d3 = (double) config.horizontalRadiusMultiplier.sample(random);
            double d4 = (double) config.verticalRadiusMultiplier.sample(random);
            double d5 = (double) config.floorLevel.sample(random);
            CarverPath.Skip skip = new CarverPath.Skip(d5);
            int l = 1;
            if (random.nextInt(4) == 0) {
                double d6 = (double) config.yScale.sample(random);
                float f1 = 1.0F + random.nextFloat() * 6.0F;
                // createRoom
                double r0 = 1.5 + (double) (Mth.sin((float) (Math.PI / 2)) * f1);
                double r1 = r0 * d6;
                ops.add(new CarverPath.Room(d0 + 1.0, d1, d2, r0, r1, skip));
                l += random.nextInt(4);
            }
            for (int k1 = 0; k1 < l; k1++) {
                float f = random.nextFloat() * (float) (Math.PI * 2);
                float f3 = (random.nextFloat() - 0.5F) / 4.0F;
                float f2 = thickness(random, nether);
                int i1 = i - random.nextInt(i / 4);
                ops.add(tunnel(random.nextLong(), d0, d1, d2, d3, d4, f2, f, f3, 0, i1, nether ? 5.0 : 1.0, skip));
            }
        }
    }

    /** {@code CaveWorldCarver.createTunnel}. */
    private static CarverPath.Tunnel tunnel(long seed, double x, double y, double z, double hScale, double vScale, float thickness, float yaw, float pitch,
                                            int start, int end, double yScale, CarverPath.Skip skip) {
        CarverPath.Tunnel tunnel = new CarverPath.Tunnel(end, thickness, skip);
        FastLegacyRandom randomsource = new FastLegacyRandom(seed);
        int i = randomsource.nextInt(end / 2) + end / 4;
        boolean flag = randomsource.nextInt(6) == 0;
        float f = 0.0F;
        float f1 = 0.0F;
        for (int j = start; j < end; j++) {
            double d0 = 1.5 + (double) (Mth.sin((float) Math.PI * (float) j / (float) end) * thickness);
            double d1 = d0 * yScale;
            float f2 = Mth.cos(pitch);
            x += (double) (Mth.cos(yaw) * f2);
            y += (double) Mth.sin(pitch);
            z += (double) (Mth.sin(yaw) * f2);
            pitch *= flag ? 0.92F : 0.7F;
            pitch += f1 * 0.1F;
            yaw += f * 0.1F;
            f1 *= 0.9F;
            f *= 0.75F;
            f1 += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 2.0F;
            f += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 4.0F;
            if (j == i && thickness > 1.0F) {
                long seedA = randomsource.nextLong();
                CarverPath.Tunnel a = tunnel(seedA, x, y, z, hScale, vScale, randomsource.nextFloat() * 0.5F + 0.5F, yaw - (float) (Math.PI / 2), pitch / 3.0F, j,
                    end, 1.0, skip);
                long seedB = randomsource.nextLong();
                CarverPath.Tunnel b = tunnel(seedB, x, y, z, hScale, vScale, randomsource.nextFloat() * 0.5F + 0.5F, yaw + (float) (Math.PI / 2), pitch / 3.0F, j,
                    end, 1.0, skip);
                tunnel.branch(a, b);
                tunnel.freeze();
                return tunnel;
            }
            if (randomsource.nextInt(4) != 0) {
                tunnel.step(j, x, y, z, d0 * hScale, d1 * vScale);
            }
        }
        tunnel.freeze();
        return tunnel;
    }

    /** {@code CanyonWorldCarver.carve} and {@code doCarve}. */
    private static void canyon(WorldGenerationContext ctx, CanyonCarverConfiguration config, FastLegacyRandom random, ChunkPos chunkPos, List<CarverPath.Op> ops) {
        int i = (4 * 2 - 1) * 16;
        double d0 = (double) chunkPos.getBlockX(random.nextInt(16));
        int j = config.y.sample(random, ctx);
        double d1 = (double) chunkPos.getBlockZ(random.nextInt(16));
        float f = random.nextFloat() * (float) (Math.PI * 2);
        float f1 = config.verticalRotation.sample(random);
        double d2 = (double) config.yScale.sample(random);
        float f2 = config.shape.thickness.sample(random);
        int k = (int) ((float) i * config.shape.distanceFactor.sample(random));
        long seed = random.nextLong();
        // doCarve
        double x = d0;
        double y = (double) j;
        double z = d1;
        float thickness = f2;
        float yaw = f;
        float pitch = f1;
        int end = k;
        double yScale = d2;
        FastLegacyRandom randomsource = new FastLegacyRandom(seed);
        float[] afloat = widthFactors(ctx, config, randomsource);
        CarverPath.Tunnel tunnel = new CarverPath.Tunnel(end, thickness, new CarverPath.Skip(afloat));
        float g = 0.0F;
        float g1 = 0.0F;
        for (int s = 0; s < end; s++) {
            double e0 = 1.5 + (double) (Mth.sin((float) s * (float) Math.PI / (float) end) * thickness);
            double e1 = e0 * yScale;
            e0 *= (double) config.shape.horizontalRadiusFactor.sample(randomsource);
            e1 = updateVerticalRadius(config, randomsource, e1, (float) end, (float) s);
            float h2 = Mth.cos(pitch);
            float h3 = Mth.sin(pitch);
            x += (double) (Mth.cos(yaw) * h2);
            y += (double) h3;
            z += (double) (Mth.sin(yaw) * h2);
            pitch *= 0.7F;
            pitch += g1 * 0.05F;
            yaw += g * 0.05F;
            g1 *= 0.8F;
            g *= 0.5F;
            g1 += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 2.0F;
            g += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 4.0F;
            if (randomsource.nextInt(4) != 0) {
                tunnel.step(s, x, y, z, e0, e1);
            }
        }
        tunnel.freeze();
        ops.add(tunnel);
    }

    private static float[] widthFactors(WorldGenerationContext ctx, CanyonCarverConfiguration config, FastLegacyRandom random) {
        int i = ctx.getGenDepth();
        float[] afloat = new float[i];
        float f = 1.0F;
        for (int j = 0; j < i; j++) {
            if (j == 0 || random.nextInt(config.shape.widthSmoothness) == 0) {
                f = 1.0F + random.nextFloat() * random.nextFloat();
            }
            afloat[j] = f * f;
        }
        return afloat;
    }

    private static double updateVerticalRadius(CanyonCarverConfiguration config, FastLegacyRandom random, double radius, float end, float step) {
        float f = 1.0F - Mth.abs(0.5F - step / end) * 2.0F;
        float f1 = config.shape.verticalRadiusDefaultFactor + config.shape.verticalRadiusCenterFactor * f;
        return (double) f1 * radius * (double) Mth.randomBetween(random, 0.75F, 1.0F);
    }
}
