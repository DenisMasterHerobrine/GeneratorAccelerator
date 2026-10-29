package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * {@code NoiseChunk.preliminarySurfaceLevel} shared between chunks. Aquifer statuses sample it at
 * 13 columns spread over 5 x 3 chunks around each aquifer cell. Every chunk keeps its own map, so
 * many neighbouring chunks scan each column. A scan is usually a {@code find_top_surface}: one density evaluation per
 * cell height, from its upper bound down to the surface.
 *
 * <p>Values are shared only between chunks of the same RandomState and vertical layout, without
 * old-chunk blending. They are shared only when the surface level function cannot
 * depend on the chunk (see {@code StateDependence.chunkIndependent}). Each slot of a direct-mapped
 * table holds the quart column and its level in one long, written atomically. A racing reader thus
 * sees either the whole entry or a different column. A collision only costs a recomputation.
 */
public final class SurfaceLevelCache {
    public static final int MISS = Integer.MIN_VALUE;

    private static final int BITS = 17;
    private static final int COORD_LIMIT = 1 << 23;
    private static final int NO_SURFACE = 0xFFFF;
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(long[].class);

    /** Shared-table lookups. They happen only on a chunk-local miss, so counting is cheap. */
    public static final LongAdder HITS = new LongAdder();
    public static final LongAdder MISSES = new LongAdder();

    /** Test hook. Null follows the config. Otherwise it forces sharing on or off for chunks created afterwards. */
    public static volatile Boolean override;

    private static final ConcurrentMap<RandomState, PerState> STATES = new MapMaker().weakKeys().concurrencyLevel(4).makeMap();

    private final long[] slots = new long[1 << BITS];

    private SurfaceLevelCache() {}

    private static final class PerState {
        final boolean shareable;
        final ConcurrentHashMap<Long, SurfaceLevelCache> byLayout = new ConcurrentHashMap<>();

        PerState(boolean shareable) {
            this.shareable = shareable;
        }
    }

    public static void register(RandomState state, boolean shareable) {
        STATES.put(state, new PerState(shareable));
    }

    /** The cache a new NoiseChunk should use, or null when its levels must stay private to it. */
    public static SurfaceLevelCache forChunk(RandomState state, NoiseSettings settings, Blender blender) {
        if (blender != Blender.empty() || !DfcHooks.enabled()) {
            return null;
        }
        Boolean forced = override;
        if (forced != null ? !forced : !DfcConfig.sharedSurfaceCache()) {
            return null;
        }
        if (!MixinTargets.surfaceLevelAllowed()) {
            return null;
        }
        PerState per = STATES.get(state);
        if (per == null || !per.shareable) {
            return null;
        }
        long layout = ((long) settings.minY() << 40) ^ ((long) settings.height() << 16) ^ settings.getCellHeight();
        return per.byLayout.computeIfAbsent(layout, k -> new SurfaceLevelCache());
    }

    /** @param column {@code ColumnPos.asLong} of a quart-aligned block column, as NoiseChunk passes it */
    public int get(long column) {
        int x = ColumnPos.getX(column);
        int z = ColumnPos.getZ(column);
        if (!inRange(x, z)) {
            return MISS;
        }
        long key = key(x, z);
        long entry = (long) SLOTS.getOpaque(this.slots, slot(key));
        if (entry != 0L && (entry & 0xFFFF_FFFF_FFFFL) == key) {
            HITS.increment();
            int stored = (int) (entry >>> 48);
            return stored == NO_SURFACE ? Integer.MAX_VALUE : stored - 0x8000;
        }
        MISSES.increment();
        return MISS;
    }

    public void put(long column, int level) {
        int x = ColumnPos.getX(column);
        int z = ColumnPos.getZ(column);
        int stored;
        if (level == Integer.MAX_VALUE) {
            stored = NO_SURFACE;
        } else if (level >= -0x8000 && level < 0x7FFF) {
            stored = level + 0x8000;
        } else {
            return;
        }
        if (!inRange(x, z)) {
            return;
        }
        long key = key(x, z);
        SLOTS.setOpaque(this.slots, slot(key), key | (long) stored << 48);
    }

    private static boolean inRange(int x, int z) {
        // NoiseChunk only asks for quart-aligned columns. Anything else is left alone.
        return (x & 3) == 0 && (z & 3) == 0 && x > -COORD_LIMIT * 4 && x < COORD_LIMIT * 4 && z > -COORD_LIMIT * 4 && z < COORD_LIMIT * 4;
    }

    /** Quart x and z, 24 bits each. An all-zero slot reads as empty, which at worst costs one recomputation. */
    private static long key(int x, int z) {
        return ((long) (x >> 2) & 0xFF_FFFFL) | (((long) (z >> 2) & 0xFF_FFFFL) << 24);
    }

    private static int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - BITS));
    }
}
