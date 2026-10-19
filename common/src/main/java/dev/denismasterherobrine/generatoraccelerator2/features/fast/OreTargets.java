package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch;
import java.util.Map;
import com.google.common.collect.MapMaker;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * For an ore configuration whose rule tests are pure functions of the block state (tag, block and
 * state matches, always-true), the set of matching targets per block state id, filled lazily. Any
 * other rule test (the random ones, or a modded class) gets no table and the fast path tests every
 * target exactly as vanilla does.
 *
 * <p>Lookups go by the state's registry id first, in a paged {@link dev.denismasterherobrine.generatoraccelerator2.state.StateIndex.Table}.
 * Pages of 256 ids are made on first use, so the table stays small even when the registry holds hundreds of thousands
 * of states with mods. A state the registry lacks falls back to an identity table. That table only holds the states an
 * ore actually met (a few dozen). Reads are lock-free in both tables. A new state is added to a copy that replaces the
 * identity table.
 */
public final class OreTargets {
    /** Weak identity keys, lock-free reads: every ore placement looks its configuration up here. */
    private static final Map<OreConfiguration, OreTargets> CACHE = new MapMaker().weakKeys().makeMap();
    private static final RandomSource NO_RANDOM = RandomSource.create(0L);
    private static final int UNKNOWN = -1;

    private final OreConfiguration.TargetBlockState[] targets;
    private volatile Table table = new Table(16);
    private final dev.denismasterherobrine.generatoraccelerator2.state.StateIndex.Table byId = new dev.denismasterherobrine.generatoraccelerator2.state.StateIndex.Table(UNKNOWN);
    private final int epoch;

    private OreTargets(OreConfiguration config) {
        this.targets = config.targetStates.toArray(new OreConfiguration.TargetBlockState[0]);
        this.epoch = TagEpoch.current();
    }

    /** Open addressing by identity. Never changed once published. */
    private static final class Table {
        final BlockState[] keys;
        final int[] masks;
        final int size;

        Table(int capacity) {
            this.keys = new BlockState[capacity];
            this.masks = new int[capacity];
            this.size = 0;
        }

        private Table(BlockState[] keys, int[] masks, int size) {
            this.keys = keys;
            this.masks = masks;
            this.size = size;
        }

        static int slot(BlockState state, int capacity) {
            int h = System.identityHashCode(state);
            return (h ^ (h >>> 16)) & (capacity - 1);
        }

        int get(BlockState state) {
            int n = this.keys.length;
            for (int i = slot(state, n); ; i = (i + 1) & (n - 1)) {
                BlockState k = this.keys[i];
                if (k == state) {
                    return this.masks[i];
                }
                if (k == null) {
                    return UNKNOWN;
                }
            }
        }

        /** A copy with {@code state} added (grown when half full). */
        Table with(BlockState state, int mask) {
            int n = this.size + 1 > this.keys.length / 2 ? this.keys.length * 2 : this.keys.length;
            BlockState[] k = new BlockState[n];
            int[] m = new int[n];
            for (int i = 0; i < this.keys.length; i++) {
                if (this.keys[i] != null) {
                    put(k, m, this.keys[i], this.masks[i]);
                }
            }
            put(k, m, state, mask);
            return new Table(k, m, this.size + 1);
        }

        private static void put(BlockState[] k, int[] m, BlockState state, int mask) {
            int n = k.length;
            int i = slot(state, n);
            while (k[i] != null && k[i] != state) {
                i = (i + 1) & (n - 1);
            }
            k[i] = state;
            m[i] = mask;
        }
    }

    /**
     * Per thread, the answers of {@link #of} for the last configurations asked, by identity. Each entry is a configuration's slot,
     * then its answer (a table or {@link #NONE}). The shared map counts every read with an atomic that all threads of its segment
     * write. An ore placement asks once, so every worker thread hit the same counter.
     */
    private static final ThreadLocal<Object[]> RECENT = ThreadLocal.withInitial(() -> new Object[16]);
    private static final Object NONE = new Object();

    /** The table for {@code config}, or {@code null} when its rule tests are not pure. */
    public static OreTargets of(OreConfiguration config) {
        Object[] recent = RECENT.get();
        int slot = (System.identityHashCode(config) & 7) * 2;
        if (recent[slot] == config) {
            Object v = recent[slot + 1];
            if (v == NONE) {
                return null;
            }
            OreTargets t = (OreTargets) v;
            if (t.epoch == TagEpoch.current()) {
                return t;
            }
        }
        OreTargets t = lookup(config);
        recent[slot] = config;
        recent[slot + 1] = t == null ? NONE : t;
        return t;
    }

    private static OreTargets lookup(OreConfiguration config) {
        OreTargets t = CACHE.get(config);
        if (t != null && t.epoch == TagEpoch.current()) {
            return t;
        }
        if (config.targetStates.size() > 30 || config.targetStates.size() == 0) {
            return null;
        }
        for (OreConfiguration.TargetBlockState target : config.targetStates) {
            Class<? extends RuleTest> cls = target.target.getClass();
            if (cls != TagMatchTest.class && cls != BlockMatchTest.class && cls != BlockStateMatchTest.class && cls != AlwaysTrueTest.class) {
                return null;
            }
        }
        t = new OreTargets(config);
        CACHE.put(config, t);
        return t;
    }

    /** Bit {@code i} set when target {@code i} matches {@code state}. Bits are in target order. */
    public int mask(BlockState state) {
        // by the state's registry id (a field read and two array reads). A state the registry lacks goes by identity.
        int id = dev.denismasterherobrine.generatoraccelerator2.state.StateIndex.id(state);
        if (id >= 0) {
            int v = this.byId.get(id);
            if (v != UNKNOWN) {
                return v;
            }
            v = compute(state);
            this.byId.put(id, v);
            return v;
        }
        int m = this.table.get(state);
        if (m != UNKNOWN) {
            return m;
        }
        m = compute(state);
        synchronized (this) {
            Table t = this.table;
            if (t.get(state) == UNKNOWN && t.size < MAX_STATES) {
                this.table = t.with(state, m);
            }
        }
        return m;
    }

    /** States kept per configuration. Past this, the rest are tested each time (a configuration meeting thousands is odd). */
    private static final int MAX_STATES = 4096;

    private int compute(BlockState state) {
        int m = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i].target.test(state, NO_RANDOM)) {
                m |= 1 << i;
            }
        }
        return m;
    }

    public OreConfiguration.TargetBlockState target(int index) {
        return targets[index];
    }
}
