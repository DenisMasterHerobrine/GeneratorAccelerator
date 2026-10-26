package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Decides, once per {@code NoiseBasedAquifer}, whether the exact fast path runs for it. The work
 * itself is in {@code NoiseBasedAquiferMixin}. This class is independent of the density function
 * compiler. It therefore also works when the compiler's NoiseChunk hooks are off for compatibility.
 */
public final class AquiferFastPath {
    private AquiferFastPath() {}

    /** The instance runs vanilla code. */
    public static final int OFF = 0;
    /** Optimised nearest-cell search only. Computes exactly the aquifer statuses vanilla computes. */
    public static final int SEARCH = 1;
    /** Search plus per-neighbourhood classification, which may compute a few statuses ahead of vanilla. */
    public static final int CLASSIFY = 2;
    /**
     * Search, plus classification ahead. A neighbourhood is classified ahead once every status it still lacks would read
     * only preliminary surface levels the chunk already holds. Only {@code initial_density_without_jaggedness} reads
     * order-sensitive state.
     */
    public static final int COLUMNS = 3;

    /** Returned by the hook when the original method must run. */
    public static final Object FALLBACK = new Object();

    /** Router fields a status computation evaluates. The last field is evaluated through NoiseChunk.preliminarySurfaceLevel. */
    public static final String[] STATUS_FIELDS = {
        "erosion", "depth", "fluid_level_floodedness", "fluid_level_spread", "lava", "initial_density_without_jaggedness"
    };
    /** The status fields a status computation evaluates itself, without the surface levels it reads through the chunk's memo. */
    public static final String[] POINT_FIELDS = { "erosion", "depth", "fluid_level_floodedness", "fluid_level_spread", "lava" };

    /**
     * {@code -Dgeneratoraccelerator2.aquifer.airBoxes=false} turns off whole-cell air from known statuses in neighbourhoods
     * that are not classified. Tests toggle it.
     */
    public static boolean AIR_BOXES = !"false".equals(System.getProperty("generatoraccelerator2.aquifer.airBoxes"));

    /** {@code -Dgeneratoraccelerator2.aquifer.columns=false} keeps such routers in plain search mode. */
    private static final boolean COLUMNS_ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.aquifer.columns"));

    /** Per-path counters for tests and benchmarks. Compiled out unless the property is set at startup. */
    public static final boolean STATS = Boolean.getBoolean("generatoraccelerator2.aquifer.stats");
    public static final LongAdder SOLID = new LongAdder();
    public static final LongAdder LAVA = new LongAdder();
    public static final LongAdder UNIFORM = new LongAdder();
    public static final LongAdder ABOVE_FLUIDS = new LongAdder();
    public static final LongAdder SEARCHED = new LongAdder();
    public static final LongAdder FALLBACKS = new LongAdder();
    public static final LongAdder LAZY_FLAGS = new LongAdder();
    /** Neighbourhoods classified in search mode once their searches had computed every status. */
    public static final LongAdder LATE_CLASSIFIED = new LongAdder();
    /** Neighbourhoods classified ahead in {@link #COLUMNS} mode. Always counted, with one increment per neighbourhood. */
    public static final LongAdder AHEAD_CLASSIFIED = new LongAdder();
    /**
     * Aquifers whose cells were checked against the floodedness caches of {@link #cellCachesFor}, always counted. The second
     * counter holds the aquifers that kept vanilla's order after the check.
     */
    public static final LongAdder CELLS_CHECKED = new LongAdder();
    public static final LongAdder CELLS_CONFLICT = new LongAdder();
    /** Parts of whole air cells in unclassified neighbourhoods, answered from the statuses of their nearest cells. */
    public static final LongAdder BOX_AIR = new LongAdder();
    /** Blocks of unclassified neighbourhoods answered the same way, per 4x4x4 box. */
    public static final LongAdder BOX_BLOCKS = new LongAdder();

    /** Test hook. Null follows the config. Otherwise it forces the path on or off for aquifers created afterwards. */
    public static volatile Boolean override;

    /**
     * Mode per {@code RandomState.aquiferRandom()}, which every aquifer of that state receives. The mode is:
     * {@link #CLASSIFY} when the status fields read no order-sensitive state, so evaluating them for a neighbourhood ahead
     * of vanilla cannot change any value. {@link #COLUMNS} when only the surface levels the fields read through the chunk's
     * memo are order-sensitive. {@link #SEARCH} otherwise.
     */
    private static final ConcurrentMap<PositionalRandomFactory, Integer> MODES = new MapMaker().weakKeys().concurrencyLevel(4).makeMap();
    /**
     * Entries for random states whose mode holds only once each aquifer has checked its cells. Each entry holds the vanilla
     * functions of the cache_2d markers below {@code fluid_level_floodedness} that read y
     * ({@code StateDependence.cellCentreCaches}). An aquifer may have two cells in one block column where such a function
     * differs between the two cell heights. That aquifer keeps vanilla's order ({@link #SEARCH}).
     */
    private static final ConcurrentMap<PositionalRandomFactory, DensityFunction[]> CELL_CACHES =
        new MapMaker().weakKeys().concurrencyLevel(4).makeMap();

    public static void registerRandomState(PositionalRandomFactory aquiferRandom, boolean classifySafe) {
        registerMode(aquiferRandom, classifySafe ? CLASSIFY : SEARCH);
    }

    public static void registerMode(PositionalRandomFactory aquiferRandom, int mode) {
        registerMode(aquiferRandom, mode, null);
    }

    /**
     * {@link #registerMode(PositionalRandomFactory, int)}, where the mode holds for an aquifer only after its cells are checked
     * against {@code cellCaches}. Null means no check.
     */
    public static void registerMode(PositionalRandomFactory aquiferRandom, int mode, DensityFunction[] cellCaches) {
        MODES.put(aquiferRandom, mode);
        if (cellCaches != null && cellCaches.length > 0) {
            CELL_CACHES.put(aquiferRandom, cellCaches);
        } else {
            CELL_CACHES.remove(aquiferRandom);
        }
    }

    /** The functions an aquifer's cells must agree on for its mode to hold, or null ({@link #CELL_CACHES}). */
    public static DensityFunction[] cellCachesFor(PositionalRandomFactory random) {
        return CELL_CACHES.get(random);
    }

    public static int modeFor(Object aquifer, PositionalRandomFactory random) {
        if (aquifer.getClass() != Aquifer.NoiseBasedAquifer.class || !DfcHooks.enabled()) {
            return OFF;
        }
        if (MixinTargets.aquiferLiquidRegions() && !LiquidRegions.usable()) {
            return OFF; // Better Caves' liquid regions cannot be read, so the step they add cannot be applied here
        }
        Boolean forced = override;
        if (forced != null ? !forced : !DfcConfig.aquiferFastPath()) {
            return OFF;
        }
        if (!MixinTargets.aquiferAllowed()) {
            return OFF;
        }
        if (MixinTargets.aquiferSearchOnly()) {
            // Another mod's change reads state that depends on the order of status computation (YUNG's Cave Biomes).
            return SEARCH;
        }
        Integer mode = MODES.get(random);
        if (mode == null) {
            return SEARCH;
        }
        // The memo the classification reads must be vanilla's own map, filled only by vanilla's surface level methods.
        if (mode == COLUMNS && (!COLUMNS_ENABLED || !MixinTargets.surfaceLevelAllowed())) {
            return SEARCH;
        }
        return mode;
    }

    public static void resetStats() {
        SOLID.reset();
        LAVA.reset();
        UNIFORM.reset();
        ABOVE_FLUIDS.reset();
        SEARCHED.reset();
        FALLBACKS.reset();
        LAZY_FLAGS.reset();
        LATE_CLASSIFIED.reset();
        AHEAD_CLASSIFIED.reset();
        CELLS_CHECKED.reset();
        CELLS_CONFLICT.reset();
        BOX_AIR.reset();
        BOX_BLOCKS.reset();
    }

    public static String statsLine() {
        return "solid " + SOLID.sum() + ", lava " + LAVA.sum() + ", uniform " + UNIFORM.sum() + ", above fluids " + ABOVE_FLUIDS.sum()
            + ", searched " + SEARCHED.sum() + ", fallback " + FALLBACKS.sum() + ", lazy flags resolved " + LAZY_FLAGS.sum()
            + ", late classified " + LATE_CLASSIFIED.sum() + ", classified ahead " + AHEAD_CLASSIFIED.sum()
            + ", aquifers checked for column caches " + CELLS_CHECKED.sum() + " (" + CELLS_CONFLICT.sum() + " kept vanilla's order)" + ", air cell parts from known statuses " + BOX_AIR.sum() + ", blocks from known statuses " + BOX_BLOCKS.sum();
    }
}
