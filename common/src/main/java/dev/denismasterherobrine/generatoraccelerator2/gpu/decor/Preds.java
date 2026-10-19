package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluids;

/**
 * The block state predicates that GPU decoration ({@code gpu.decor}) decides with. Every question a GPU feature asks of a block it
 * reads is a function of that block's state alone (a rule test, a tag, "is air", a heightmap's opacity, a plant surviving on that
 * soil). So the GPU answers it from a bit per state id. The CPU checks a result against blocks written since the GPU's copy, and asks
 * the same predicate of the block's current state. Value tables do the same for small functions of a state (a leaf's distance, the
 * state with another property value).
 *
 * <p>A predicate may not know its answer for a state ({@link Unknown}, a survival rule that read more than the soil). Such a state has
 * its "unknown" bit set. A GPU feature that meets it leaves the placement to the CPU. A value table answers {@link #UNKNOWN} then.
 *
 * <p>Predicates are registered while the spec is built. Their bits over the {@link StateIds} assigned so far are extended as new ids
 * appear ({@link #extend}). The first extension runs on the calling thread. Later ones run on the decoration tables executor.
 */
public final class Preds {
    public static final int STATE_WORDS = 65536 / 32;
    public static final int TABLE = 65536;
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** Fixed predicates, registered first in this order (the heightmaps in {@code ChunkStatus.FINAL_HEIGHTMAPS}' order of types). */
    public static final int AIR = 0, HM_WORLD_SURFACE = 1, HM_OCEAN_FLOOR = 2, HM_MOTION = 3, HM_MOTION_NO_LEAVES = 4, VALID_TREE = 5, FREE = 6, VINE = 7,
        AIR_OR_LEAVES = 8, TRUNK_DIRT = 9, TREE_GROW = 10, WATER_SOURCE = 11, DIRT = 12, SNOW = 13;
    /** Fixed value tables: a leaf's distance (15: none), the state at distance 1..7, updateShape's kind, the state snowy / not snowy. */
    public static final int V_LEAF_DIST = 0, V_DIST = 1, V_UPS = 8, V_SNOWY = 9, V_NOT_SNOWY = 10;
    /** updateShape kinds: returns the state, grass's snowy rule (from above), anything else. */
    public static final int UPS_SAME = 0, UPS_SNOWY = 1, UPS_OTHER = 2;
    /** The final heightmap types by GPU index 0..3 (heightmap index 2 + i in reads). */
    public static final Heightmap.Types[] FINAL = { Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES };

    /** Thrown by a predicate that cannot answer for a state. */
    public static final class Unknown extends RuntimeException {
        public static final Unknown INSTANCE = new Unknown();

        private Unknown() {
            super(null, null, false, false);
        }
    }

    private final List<Predicate<BlockState>> tests = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Map<Object, Integer> byKey = new HashMap<>();
    private final List<ToIntFunction<BlockState>> values = new ArrayList<>();
    final StateIds ids;
    /** GPU thread: per predicate, the answer bits then the unknown bits over the state ids below {@link #tableStates}. */
    int[] bits = new int[0];
    /** GPU thread: per value table, the value of every state id below {@link #tableStates}. */
    int[] vals = new int[0];
    volatile int tableStates;

    Preds(StateIds ids) {
        this.ids = ids;
        add("air", "air", BlockState::isAir);
        for (Heightmap.Types t : FINAL) {
            add("hm:" + t, "heightmap " + t.getSerializedName(), t.isOpaque());
        }
        // TreeFeature.validTreePos, TrunkPlacer.isFree, TreeFeature.isVine / isAirOrLeaves, TrunkPlacer.isDirt
        add("validTreePos", "valid tree position", s -> s.isAir() || s.is(BlockTags.REPLACEABLE_BY_TREES));
        add("treeFree", "free for a trunk", s -> s.isAir() || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.LOGS));
        add("vine", "vine", s -> s.is(Blocks.VINE));
        add("airOrLeaves", "air or leaves", s -> s.isAir() || s.is(BlockTags.LEAVES));
        add("trunkDirt", "dirt under a trunk", s -> Feature.isDirt(s) && !s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.MYCELIUM));
        // NeoForge's onTreeGrow: false (vanilla's dirt) unless the block's class overrides it
        add("treeGrow", "plain soil for a trunk", s -> {
            if (TreeSpec.overridesTreeGrow(s.getBlock().getClass())) {
                throw Unknown.INSTANCE;
            }
            return true;
        });
        add("waterSource", "water source", s -> s.getFluidState().isSourceOfType(Fluids.WATER));
        add("dirt", "dirt", Feature::isDirt);
        add("snow", "snow", s -> s.is(BlockTags.SNOW));
        addValue(s -> {
            if (s.is(BlockTags.LOGS)) {
                return 0;
            }
            return s.hasProperty(BlockStateProperties.DISTANCE) ? s.getValue(BlockStateProperties.DISTANCE) : 15;
        });
        for (int d = 1; d <= 7; d++) {
            int dist = d;
            addValue(s -> s.hasProperty(BlockStateProperties.DISTANCE) ? ids.id(s.setValue(BlockStateProperties.DISTANCE, dist)) : -1);
        }
        addValue(s -> TreeSpec.shapeKind(s.getBlock().getClass()));
        addValue(s -> s.hasProperty(BlockStateProperties.SNOWY) ? ids.id(s.setValue(BlockStateProperties.SNOWY, true)) : -1);
        addValue(s -> s.hasProperty(BlockStateProperties.SNOWY) ? ids.id(s.setValue(BlockStateProperties.SNOWY, false)) : -1);
    }

    /** The index of the predicate registered under {@code key}, registering {@code test} first when it is new. */
    public int add(Object key, String name, Predicate<BlockState> test) {
        Integer i = byKey.get(key);
        if (i != null) {
            return i;
        }
        if (tableStates > 0) {
            throw new IllegalStateException("predicate added after the tables were built");
        }
        int n = tests.size();
        tests.add(test);
        names.add(name);
        byKey.put(key, n);
        return n;
    }

    private void addValue(ToIntFunction<BlockState> f) {
        values.add(f);
    }

    private final Map<Object, Integer> valueByKey = new HashMap<>();

    /** The index of the value table registered under {@code key}, registering {@code f} first when it is new. */
    public int addValue(Object key, ToIntFunction<BlockState> f) {
        Integer i = valueByKey.get(key);
        if (i != null) {
            return i;
        }
        if (tableStates > 0) {
            throw new IllegalStateException("value table added after the tables were built");
        }
        values.add(f);
        valueByKey.put(key, values.size() - 1);
        return values.size() - 1;
    }

    /** A block getter for asking a state about its own shapes: any read of the level makes the answer unknown. */
    static final net.minecraft.world.level.BlockGetter NO_READS = new net.minecraft.world.level.BlockGetter() {
        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) {
            throw Unknown.INSTANCE;
        }

        @Override
        public BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            throw Unknown.INSTANCE;
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            throw Unknown.INSTANCE;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    };

    /** Positions a shape question is asked at. A state whose answer differs between them depends on its position (unknown). */
    static final net.minecraft.core.BlockPos[] PROBES = { net.minecraft.core.BlockPos.ZERO, new net.minecraft.core.BlockPos(1234567, 61, -7654321),
        new net.minecraft.core.BlockPos(-31, -40, 977) };

    /**
     * A question about a state's own shape at a position. The answer must be the same at every probe position, without reading the
     * level. Otherwise it is unknown.
     */
    static boolean shapeQuestion(BlockState s, java.util.function.BiPredicate<BlockState, net.minecraft.core.BlockPos> q) {
        if (s.getBlock().hasDynamicShape()) {
            throw Unknown.INSTANCE;
        }
        boolean a = q.test(s, PROBES[0]);
        for (int i = 1; i < PROBES.length; i++) {
            if (q.test(s, PROBES[i]) != a) {
                throw Unknown.INSTANCE;
            }
        }
        return a;
    }

    public int size() {
        return tests.size();
    }

    public int valueTables() {
        return values.size();
    }

    public String name(int p) {
        return names.get(p);
    }

    /** The predicate's answer for a state (CPU side, the checks of a GPU result): 1, 0, or -1 when it cannot tell. */
    public int answer(int p, BlockState state) {
        int id = ids.id(state);
        int[] b = bits;
        if (id >= 0 && id < tableStates) {
            int base = p * 2 * STATE_WORDS + (id >> 5);
            if ((b[base + STATE_WORDS] >>> (id & 31) & 1) != 0) {
                return -1;
            }
            return b[base] >>> (id & 31) & 1;
        }
        return compute(p, state);
    }

    /** A value table's value for a state (CPU side), {@link #UNKNOWN} when it cannot tell. */
    public int value(int v, BlockState state) {
        int id = ids.id(state);
        if (id >= 0 && id < tableStates) {
            return vals[v * TABLE + id];
        }
        return computeValue(v, state);
    }

    private int compute(int p, BlockState state) {
        try {
            return tests.get(p).test(state) ? 1 : 0;
        } catch (Unknown e) {
            return -1;
        }
    }

    private int computeValue(int v, BlockState state) {
        try {
            return values.get(v).applyAsInt(state);
        } catch (Unknown e) {
            return UNKNOWN;
        }
    }

    /** Whether states were met past the tables. */
    boolean wantsExtend() {
        return Math.min(ids.size(), 65536) > tableStates;
    }

    /** Computes the bits and values of every predicate and table for every state id assigned so far. True when they grew. */
    boolean extend() {
        int n = Math.min(ids.size(), 65536);
        int from = tableStates;
        if (n <= from) {
            return false;
        }
        int[] b = bits.length == tests.size() * 2 * STATE_WORDS ? bits : java.util.Arrays.copyOf(bits, tests.size() * 2 * STATE_WORDS);
        int[] v = vals.length == values.size() * TABLE ? vals : java.util.Arrays.copyOf(vals, values.size() * TABLE);
        for (int id = from; id < n; id++) {
            BlockState st = ids.state(id);
            if (st == null) {
                n = id;
                break;
            }
            for (int p = 0; p < tests.size(); p++) {
                int a = compute(p, st);
                int base = p * 2 * STATE_WORDS + (id >> 5);
                if (a == 1) {
                    b[base] |= 1 << (id & 31);
                } else if (a < 0) {
                    b[base + STATE_WORDS] |= 1 << (id & 31);
                }
            }
            for (int t = 0; t < values.size(); t++) {
                v[t * TABLE + id] = computeValue(t, st);
            }
        }
        bits = b;
        vals = v;
        tableStates = n;
        return n > from;
    }

    /** The directions in {@code Direction.values()} order (updateLeaves' neighbours). */
    static final Direction[] DIRECTIONS = Direction.values();

    /** LeavesBlock's own class, for {@link TreeSpec#shapeKind}. */
    static final Class<?> LEAVES = LeavesBlock.class;
}
