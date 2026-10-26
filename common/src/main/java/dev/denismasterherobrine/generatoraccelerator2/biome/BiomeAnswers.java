package dev.denismasterherobrine.generatoraccelerator2.biome;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * The biome source's answers for quart points outside a generation region's chunks, kept per level
 * ({@code WorldGenRegion.getUncachedNoiseBiome}, {@code WorldGenRegionBiomesMixin}). A region answers such a point with
 * {@code ServerLevel.getUncachedNoiseBiome}: the biome source's lookup with the level's climate sampler, a climate sample and
 * a tree search, with every biome mod's code on top. Zeta's multi-chunk features (Quark's underground styles and big stone
 * clusters) ask the biome at the sources of every chunk within their radius, five or six chunks around the decorated one. So
 * each source's biome was looked up again for every chunk around it, 5% of the workers' time in a 575-mod pack. Here each
 * point's answer is looked up once and kept.
 *
 * <p>That gives vanilla's answer only for a source that answers from the position alone. Kept for the levels whose source is
 * vanilla's multi-noise source, or a wrapper that compat code knows ({@link #knownSource}, Lithostitched's injector source). Other
 * mods' changes to that source's lookup keep vanilla's lookup, unless compat code reviewed them ({@code biomes.regionAnswers}).
 * Mods set their sources up while the server starts, so nothing is kept before the server's first tick or before compat code
 * says its mod is ready ({@link #requireReady}). Vanilla's tree search starts from the thread's last answer, which decides
 * between two leaves at exactly the same distance. The answer at such a point depends on what the thread looked up before. So an
 * answer is kept only when every climate search of its lookup had a single nearest leaf ({@link SearchTies}). The top of the
 * overworld has such points (a frozen ocean exactly as near as a cold ocean).
 *
 * <p>{@code -Dgeneratoraccelerator2.verify.biomeAnswers=N}: every N-th kept answer is looked up again and compared.
 */
public final class BiomeAnswers {
    /** Implemented by every server level ({@code ServerLevelBiomeAnswersMixin}): its answers, decided once. */
    public interface Kept {
        @Nullable
        BiomeAnswers ga2$biomeAnswers();

        void ga2$setBiomeAnswers(BiomeAnswers answers);
    }

    /** Raised by {@link #forget}. A level's answers made before are dropped and decided again. */
    private static volatile int epoch;

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.BIOME_ANSWERS);
    private static final VerifyCounter VERIFY = new VerifyCounter("biomeAnswers");

    /** Lookups outside a region's chunks, and those looked up again (not kept yet, or outside the packed range). */
    public static final LongAdder ASKED = new LongAdder();
    public static final LongAdder LOOKED_UP = new LongAdder();
    /** Answers not kept: a climate search in the lookup had another leaf as near as its answer, or GA2 did not see it. */
    public static final LongAdder UNSURE = new LongAdder();

    private static final List<Predicate<BiomeSource>> WRAPPERS = new CopyOnWriteArrayList<>();
    private static final List<BooleanSupplier> READY = new CopyOnWriteArrayList<>();
    private static volatile boolean ready;

    /** Slots of the table. A slot holds one answer. A later answer for another point in the same slot takes its place. */
    private static final int SLOTS = 1 << 16;
    private static final int MASK = SLOTS - 1;

    /** One kept answer. Immutable, so a thread that reads it from the table sees its fields. */
    private record Answer(long key, Holder<Biome> biome) {}

    /** The lookup whose answers are kept: the level's {@code getUncachedNoiseBiome}. */
    @FunctionalInterface
    interface Lookup {
        Holder<Biome> at(int x, int y, int z);
    }

    private final Lookup lookup;
    private final Answer[] table;
    private final int madeIn;

    /** Answers of {@code lookup}, or none kept when it is null. */
    BiomeAnswers(@Nullable Lookup lookup) {
        this.lookup = lookup;
        this.table = lookup == null ? null : new Answer[SLOTS];
        this.madeIn = epoch;
    }

    /** Drops every level's answers (a datapack reload: mods' biome lookups may take requests from data). */
    public static void forget() {
        epoch++;
    }

    /** A source that compat code knows to answer from the position alone, as its base source does (a vanilla multi-noise source). */
    public static void knownSource(Predicate<BiomeSource> source) {
        WRAPPERS.add(source);
    }

    /** A check that compat code's mod has set its biome lookup up. Nothing is kept until every such check holds. */
    public static void requireReady(BooleanSupplier check) {
        READY.add(check);
    }

    private static boolean ready(ServerLevel level) {
        if (ready) {
            return true;
        }
        if (level.getServer().getTickCount() <= 0) {
            return false;
        }
        for (BooleanSupplier check : READY) {
            if (!check.getAsBoolean()) {
                return false;
            }
        }
        ready = true;
        return true;
    }

    /** The answers kept for {@code level}, or null when its answers are not kept (yet). */
    @Nullable
    public static BiomeAnswers of(ServerLevel level) {
        Kept holder = (Kept) level;
        BiomeAnswers answers = holder.ga2$biomeAnswers();
        if (answers == null || answers.madeIn != epoch) {
            if (!HOOK.allowed() || !ready(level)) {
                return null;
            }
            answers = new BiomeAnswers(known(level.getChunkSource().getGenerator().getBiomeSource()) ? level::getUncachedNoiseBiome : null);
            holder.ga2$setBiomeAnswers(answers);
        }
        return answers.table == null ? null : answers;
    }

    private static boolean known(BiomeSource source) {
        if (source.getClass() == MultiNoiseBiomeSource.class) {
            return true;
        }
        for (Predicate<BiomeSource> wrapper : WRAPPERS) {
            if (wrapper.test(source)) {
                return true;
            }
        }
        return false;
    }

    /** {@code level.getUncachedNoiseBiome(x, y, z)}, looked up once per point. */
    public Holder<Biome> get(int x, int y, int z) {
        ASKED.increment();
        // quart coordinates: x and z within 2^23 (33 million blocks), y within 2^15
        if (x != (x << 8 >> 8) || z != (z << 8 >> 8) || y != (short) y) {
            LOOKED_UP.increment();
            return lookup.at(x, y, z);
        }
        long key = (long) x << 40 | ((long) z & 0xFFFFFFL) << 16 | (y & 0xFFFFL);
        long h = key * 0x9E3779B97F4A7C15L;
        int slot = (int) (h ^ h >>> 32) & MASK;
        Answer a = table[slot];
        if (a != null && a.key == key) {
            if (VERIFY.sample()) {
                Holder<Biome> again = lookup.at(x, y, z);
                VERIFY.result(again == a.biome, "quart " + x + " " + y + " " + z, again, a.biome);
            }
            return a.biome;
        }
        LOOKED_UP.increment();
        SearchTies.Watch watch = SearchTies.start();
        Holder<Biome> biome;
        boolean sure;
        try {
            biome = lookup.at(x, y, z);
        } finally {
            sure = watch == null || SearchTies.end(watch);
        }
        // a nested lookup is kept only by the outer one, which watches its searches
        if (sure && watch != null) {
            table[slot] = new Answer(key, biome);
        } else {
            UNSURE.increment();
        }
        return biome;
    }
}
