package dev.denismasterherobrine.generatoraccelerator2.structure;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Remembered answers of {@code NoiseBasedChunkGenerator.getBaseHeight}, the height of a column before structures.
 * Structure starts ask for it: the chunk's middle for every jigsaw structure tried there, the corners or junctions for
 * others. Vanilla computes it by building a NoiseChunk of one cell column, with an empty blender, no structure pieces and
 * a fresh aquifer of its own. It walks that column down block by block until the heightmap's predicate holds. Nothing
 * outside that NoiseChunk enters the computation. For one generator and random state, the answer therefore depends only
 * on the column, the heightmap and the height range. A remembered answer is the one vanilla would compute again.
 *
 * <p>Applies only to vanilla's generator class, and only when no other mod changes {@code getBaseHeight} or
 * {@code iterateNoiseColumn} ({@code HookTargets} key {@code structures.heightProbe}). The random state's density functions
 * must also all be compiled. A function GA2 does not lower is another mod's code, which could read state of its own. A
 * table per generator, random state and height range keeps the last answer per slot (direct-mapped).
 * {@code -Dgeneratoraccelerator2.heightProbes=false} turns it off.
 */
public final class HeightProbes {
    private HeightProbes() {}

    /** Not final so the test can compare with and without. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.heightProbes"));
    public static final LongAdder ASKED = new LongAdder();
    public static final LongAdder REMEMBERED = new LongAdder();

    private static final int SLOTS = 8192;

    private record Entry(int x, int z, int type, int height) {}

    private static final class Table {
        final int minY;
        final int height;
        final AtomicReferenceArray<Entry> entries = new AtomicReferenceArray<>(SLOTS);

        Table(int minY, int height) {
            this.minY = minY;
            this.height = height;
        }
    }

    /** Per generator and random state: the table of the last height range asked. In practice there is one per dimension. */
    private static final ConcurrentMap<NoiseBasedChunkGenerator, ConcurrentMap<RandomState, Table>> TABLES =
        new MapMaker().weakKeys().concurrencyLevel(8).makeMap();

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_HEIGHT_PROBE);

    @Nullable
    private static Table table(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor level, boolean create) {
        if (!ENABLED || generator.getClass() != NoiseBasedChunkGenerator.class || !HOOK.allowed()) {
            return null;
        }
        ConcurrentMap<RandomState, Table> byState = TABLES.get(generator);
        if (byState == null) {
            if (!create) {
                return null;
            }
            byState = TABLES.computeIfAbsent(generator, g -> new MapMaker().weakKeys().concurrencyLevel(8).makeMap());
        }
        Table t = byState.get(random);
        if (t == null || t.minY != level.getMinY() || t.height != level.getHeight()) {
            if (!create || !compiledWhole(random)) {
                return null;
            }
            t = new Table(level.getMinY(), level.getHeight());
            byState.put(random, t);
        }
        return t;
    }

    private static boolean compiledWhole(RandomState random) {
        ProgramSet set = ((Ga2RandomState) (Object) random).ga2$programs();
        return set != null && !set.report().hasFallbacks();
    }

    private static int slot(int x, int z, int type) {
        int h = x * 0x9E3779B1 ^ z * 0x85EBCA77 ^ type * 0xC2B2AE3D;
        return (h ^ h >>> 15) & (SLOTS - 1);
    }

    /** The remembered answer, or {@link Integer#MIN_VALUE}. */
    public static int lookup(NoiseBasedChunkGenerator generator, int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        ASKED.increment();
        Table t = table(generator, random, level, false);
        if (t == null) {
            return Integer.MIN_VALUE;
        }
        Entry e = t.entries.get(slot(x, z, type.ordinal()));
        if (e != null && e.x == x && e.z == z && e.type == type.ordinal()) {
            REMEMBERED.increment();
            return e.height;
        }
        return Integer.MIN_VALUE;
    }

    public static void remember(NoiseBasedChunkGenerator generator, int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random, int height) {
        Table t = table(generator, random, level, true);
        if (t != null) {
            t.entries.set(slot(x, z, type.ordinal()), new Entry(x, z, type.ordinal(), height));
        }
    }
}
