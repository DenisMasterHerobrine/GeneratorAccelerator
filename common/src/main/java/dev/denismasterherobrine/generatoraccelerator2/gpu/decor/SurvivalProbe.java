package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dynamic analysis of {@code BlockState.canSurvive} (vanilla's, NeoForge's soil hooks and any mod's). The real method runs against a
 * level that has only the block below the plant and answers nothing else. A plant whose rule reads only that block survives as a
 * function of the soil's state. The GPU then answers it from a predicate table ({@link Preds}). A rule that reads a few other
 * fixed blocks around the plant is learned as a table over classes of those blocks' states ({@link Multi}). A rule that asks
 * anything else (light, the dimension), or does not fit a table, keeps its placements on the CPU. The CPU checks the answers again
 * wherever a block changed ({@link DecorJob}). The first uses of each GPU feature are compared whole ({@code DecorJob}'s probation).
 */
public final class SurvivalProbe {
    private SurvivalProbe() {}

    /** Thrown inside the probe's level when the rule reads something other than the soil. */
    private static final class Escape extends RuntimeException {
        static final Escape INSTANCE = new Escape();

        private Escape() {
            super(null, null, false, false);
        }
    }

    private static final BlockPos AT = new BlockPos(8, 64, 8);
    private static final BlockPos BELOW = AT.below();

    /** Per plant state: whether its rule reads the soil alone (decided on a few soils), cached. */
    private static final ConcurrentHashMap<BlockState, Boolean> SOIL_ONLY = new ConcurrentHashMap<>();

    /**
     * Whether {@code plant.canSurvive(level, pos)} reads nothing but the block below {@code pos} (and the build height), tried on a
     * few soils (one with a fluid, one without). A rule that reads nothing at all also counts (its answer is then the same for every
     * soil).
     */
    public static boolean soilOnly(BlockState plant, int minY, int height) {
        return SOIL_ONLY.computeIfAbsent(plant, p -> {
            for (BlockState soil : new BlockState[] { Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.MOSS_BLOCK.defaultBlockState() }) {
                try {
                    p.canSurvive(level(soil, minY, height), AT);
                } catch (Escape e) {
                    return false;
                } catch (RuntimeException e) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * Whether {@code plant} survives on {@code soil}, for a plant whose rule reads the soil alone ({@link #soilOnly}). Throws Unknown
     * otherwise.
     */
    public static boolean survives(BlockState plant, BlockState soil, int minY, int height) {
        try {
            return plant.canSurvive(level(soil, minY, height), AT);
        } catch (Escape e) {
            throw Preds.Unknown.INSTANCE;
        }
    }

    // ---- rules that read a few blocks around the plant

    /** Maximum blocks a rule may read, and maximum class combinations of its table. */
    private static final int MAX_POSITIONS = 6, MAX_COMBINATIONS = 4096, CONTEXTS = 64;

    /**
     * A survival rule that reads a few fixed blocks around the plant (sugar cane's water beside its soil, cactus' free sides, tall
     * seagrass' own water). Per block read, the states fall into classes that behave alike. The answer is a table over the classes.
     * A state's class is the rule's answers over fixed sample surroundings ({@link #classOf}). A state whose answers match no class
     * seen while the rule was learned has no class. It is unknown, and the CPU decides.
     */
    public static final class Multi {
        /** The blocks read: where the probe has them, and relative to the plant. */
        private final BlockPos[] at;
        public final BlockPos[] offsets;
        /** Per block read: the class index for each class's answer signature. */
        private final java.util.List<java.util.Map<Long, Integer>> classes = new java.util.ArrayList<>();
        public final int[] counts;
        /** The answer per class combination (class of block 0 + count 0 * (class of block 1 + ...)). */
        public final java.util.BitSet table = new java.util.BitSet();
        private final BlockState plant;
        private BlockState[][] contexts;
        private final int minY, height;

        private Multi(BlockState plant, BlockPos[] at, BlockState[][] contexts, int minY, int height) {
            this.plant = plant;
            this.at = at;
            this.offsets = new BlockPos[at.length];
            for (int i = 0; i < at.length; i++) {
                offsets[i] = at[i].subtract(AT);
            }
            this.contexts = contexts;
            this.counts = new int[at.length];
            this.minY = minY;
            this.height = height;
        }

        /** The class of {@code s} read at block {@code i}. Throws Unknown when the state's answers match no class. */
        public int classOf(int i, BlockState s) {
            Integer c = classes.get(i).get(signature(i, s));
            if (c == null) {
                throw Preds.Unknown.INSTANCE;
            }
            return c;
        }

        private long signature(int i, BlockState s) {
            long sig = 0;
            BlockState[] a = new BlockState[at.length];
            for (int m = 0; m < contexts.length; m++) {
                System.arraycopy(contexts[m], 0, a, 0, a.length);
                a[i] = s;
                if (eval(a)) {
                    sig |= 1L << m;
                }
            }
            return sig;
        }

        /** Evaluates the rule with these states at its blocks. Throws Unknown when the rule reads another block. */
        boolean eval(BlockState[] a) {
            try {
                return plant.canSurvive(assigned(at, a, null, minY, height), AT);
            } catch (Escape e) {
                throw Preds.Unknown.INSTANCE;
            } catch (RuntimeException e) {
                throw Preds.Unknown.INSTANCE;
            }
        }
    }

    /** States the rules are learned on: air, fluids, soils, stone, ice, snow, plants (the plant's own states added). */
    private static java.util.List<BlockState> samples(BlockState plant) {
        java.util.List<BlockState> l = new java.util.ArrayList<>();
        net.minecraft.world.level.block.Block[] blocks = { Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.FARMLAND, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL,
            Blocks.CLAY, Blocks.ICE, Blocks.PACKED_ICE, Blocks.FROSTED_ICE, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.POWDER_SNOW, Blocks.OAK_LOG,
            Blocks.OAK_LEAVES, Blocks.GLASS, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.NETHERRACK, Blocks.END_STONE, Blocks.BEDROCK, Blocks.CACTUS,
            Blocks.SUGAR_CANE, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.LILY_PAD, Blocks.OAK_SLAB, Blocks.OAK_FENCE,
            Blocks.SHORT_GRASS, Blocks.DIRT_PATH };
        for (net.minecraft.world.level.block.Block b : blocks) {
            l.add(b.defaultBlockState());
        }
        BlockState water = Blocks.WATER.defaultBlockState(), lava = Blocks.LAVA.defaultBlockState();
        for (int lvl : new int[] { 0, 1, 7, 8 }) {
            l.add(water.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, lvl));
            l.add(lava.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, lvl));
        }
        l.add(Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true));
        l.add(Blocks.TALL_SEAGRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        for (BlockState s : plant.getBlock().getStateDefinition().getPossibleStates()) {
            if (l.size() < 120) {
                l.add(s);
            }
        }
        return l;
    }

    private static final ConcurrentHashMap<BlockState, java.util.Optional<Multi>> MULTI = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockState, String> WHY = new ConcurrentHashMap<>();

    private static Multi fail(BlockState plant, String why) {
        WHY.put(plant, why);
        return null;
    }

    /** Why the plant's rule could not be learned, or null. */
    public static String why(BlockState plant) {
        return WHY.get(plant);
    }

    /** The plant's rule over a few blocks around it, learned and checked. Null when it reads anything else or does not fit a table. */
    public static Multi multi(BlockState plant, int minY, int height) {
        return MULTI.computeIfAbsent(plant, p -> java.util.Optional.ofNullable(learn(p, minY, height))).orElse(null);
    }

    private static Multi learn(BlockState plant, int minY, int height) {
        java.util.List<BlockState> samples = samples(plant);
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        java.util.Random rnd = new java.util.Random(0x5EEDL);
        // the blocks read: with every block air, then each sample at each block found, then random surroundings
        if (!reads(plant, found, new BlockPos[0], new BlockState[0], minY, height)) {
            return fail(plant, "asks the level for more than blocks");
        }
        for (int round = 0; round < 4; round++) {
            int before = found.size();
            for (int i = 0; i < found.size() && found.size() <= MAX_POSITIONS; i++) {
                for (BlockState s : samples) {
                    if (!reads(plant, found, new BlockPos[] { found.get(i) }, new BlockState[] { s }, minY, height)) {
                        return fail(plant, "asks the level for more than blocks with " + s + " at " + found.get(i).subtract(AT).toShortString());
                    }
                }
            }
            for (int t = 0; t < 300 && found.size() <= MAX_POSITIONS; t++) {
                BlockPos[] at = found.toArray(new BlockPos[0]);
                BlockState[] a = new BlockState[at.length];
                for (int i = 0; i < a.length; i++) {
                    a[i] = samples.get(rnd.nextInt(samples.size()));
                }
                if (!reads(plant, found, at, a, minY, height)) {
                    return fail(plant, "asks the level for more than blocks in some surroundings");
                }
            }
            if (found.size() > MAX_POSITIONS) {
                return fail(plant, "reads more than " + MAX_POSITIONS + " blocks");
            }
            if (found.size() == before) {
                break;
            }
        }
        if (found.isEmpty()) {
            return fail(plant, "reads no block");
        }
        int n = found.size(), ns = samples.size();
        Multi m = new Multi(plant, found.toArray(new BlockPos[0]), new BlockState[0][], minY, height);
        BlockState[][] reps = new BlockState[n][];
        int[][] sampleClass = new int[n][ns];
        try {
            // surroundings the plant survives in. In each, per block, the first change that kills the plant is where that block decides.
            // Random surroundings alone rarely show this for a rule that needs every side right, like cactus.
            java.util.List<int[]> witnesses = new java.util.ArrayList<>();
            for (int t = 0; t < 8000 && witnesses.size() < 4; t++) {
                int[] w = randomPick(rnd, n, ns);
                if (m.eval(states(samples, w))) {
                    witnesses.add(w);
                }
            }
            java.util.List<int[]> ctx = new java.util.ArrayList<>(witnesses);
            // per block, surroundings where that block decides (probe states there answer differently). These are found by random walks
            // from the surviving surroundings. A rule that survives on any of four sides needs the surroundings where only that side can
            // still help.
            int[] probes = new int[Math.min(16, ns)];
            for (int q = 0; q < probes.length; q++) {
                probes[q] = q * ns / probes.length;
            }
            for (int i = 0; i < n && !witnesses.isEmpty(); i++) {
                int got = 0;
                for (int walk = 0; walk < 400 && got < 6 && ctx.size() < CONTEXTS - 4; walk++) {
                    int[] b = witnesses.get(walk % witnesses.size()).clone();
                    for (int st = 1 + rnd.nextInt(n); st > 0; st--) {
                        b[rnd.nextInt(n)] = rnd.nextInt(ns);
                    }
                    if (pivotal(m, samples, b, i, probes)) {
                        ctx.add(b);
                        got++;
                    }
                }
            }
            while (ctx.size() < CONTEXTS) {
                ctx.add(randomPick(rnd, n, ns));
            }
            BlockState[][] contexts = new BlockState[ctx.size()][];
            for (int c = 0; c < contexts.length; c++) {
                contexts[c] = states(samples, ctx.get(c));
            }
            m.contexts = contexts;
            for (int i = 0; i < n; i++) {
                java.util.Map<Long, Integer> map = new java.util.HashMap<>();
                java.util.List<BlockState> rep = new java.util.ArrayList<>();
                for (int j = 0; j < ns; j++) {
                    BlockState st = samples.get(j);
                    long sig = m.signature(i, st);
                    if (!map.containsKey(sig)) {
                        map.put(sig, map.size());
                        rep.add(st);
                    }
                    sampleClass[i][j] = map.get(sig);
                }
                m.classes.add(map);
                m.counts[i] = map.size();
                reps[i] = rep.toArray(new BlockState[0]);
            }
            long combos = 1;
            for (int c : m.counts) {
                combos *= c;
            }
            if (combos > MAX_COMBINATIONS) {
                return fail(plant, combos + " class combinations over " + n + " blocks " + java.util.Arrays.toString(m.counts));
            }
            BlockState[] a = new BlockState[n];
            for (int idx = 0; idx < combos; idx++) {
                for (int i = 0, r = idx; i < n; r /= m.counts[i], i++) {
                    a[i] = reps[i][r % m.counts[i]];
                }
                if (m.eval(a)) {
                    m.table.set(idx);
                }
            }
            // checked against the rule itself: every single and some double changes around the surviving surroundings, then random ones
            java.util.List<int[]> tests = new java.util.ArrayList<>();
            for (int[] w : witnesses) {
                for (int j = 0; j < n; j++) {
                    for (int q = 0; q < ns; q++) {
                        int[] b = w.clone();
                        b[j] = q;
                        tests.add(b);
                    }
                }
                for (int t = 0; t < 500; t++) {
                    int[] b = w.clone();
                    b[rnd.nextInt(n)] = rnd.nextInt(ns);
                    b[rnd.nextInt(n)] = rnd.nextInt(ns);
                    tests.add(b);
                }
            }
            for (int t = 0; t < 3000; t++) {
                tests.add(randomPick(rnd, n, ns));
            }
            for (int[] b : tests) {
                int idx = 0, stride = 1;
                for (int i = 0; i < n; i++) {
                    idx += sampleClass[i][b[i]] * stride;
                    stride *= m.counts[i];
                }
                if (m.eval(states(samples, b)) != m.table.get(idx)) {
                    StringBuilder w = new StringBuilder("its table disagrees with it at");
                    for (int i = 0; i < n; i++) {
                        w.append(' ').append(m.offsets[i].toShortString()).append('=').append(samples.get(b[i]));
                    }
                    return fail(plant, w.toString());
                }
            }
        } catch (Preds.Unknown e) {
            return fail(plant, "reads another block while learned");
        }
        return m;
    }

    /** Whether the rule answers both ways over the probe states at block {@code i}, the rest as {@code a}. */
    private static boolean pivotal(Multi m, java.util.List<BlockState> samples, int[] a, int i, int[] probes) {
        int[] b = a.clone();
        boolean yes = false, no = false;
        for (int q : probes) {
            b[i] = q;
            if (m.eval(states(samples, b))) {
                yes = true;
            } else {
                no = true;
            }
            if (yes && no) {
                return true;
            }
        }
        return false;
    }

    private static int[] randomPick(java.util.Random rnd, int n, int ns) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextInt(ns);
        }
        return a;
    }

    private static BlockState[] states(java.util.List<BlockState> samples, int[] pick) {
        BlockState[] a = new BlockState[pick.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = samples.get(pick[i]);
        }
        return a;
    }

    /**
     * Runs the rule with {@code a} at {@code at} (air elsewhere) and adds any other block it reads to {@code found}. Returns false when
     * the rule asks the level anything else.
     */
    private static boolean reads(BlockState plant, java.util.List<BlockPos> found, BlockPos[] at, BlockState[] a, int minY, int height) {
        try {
            plant.canSurvive(assigned(at, a, found, minY, height), AT);
            return true;
        } catch (Escape e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A level with {@code a} at {@code at} and air elsewhere. Records the blocks read into {@code found} when given. */
    private static LevelReader assigned(BlockPos[] at, BlockState[] a, java.util.List<BlockPos> found, int minY, int height) {
        InvocationHandler h = (proxy, method, args) -> {
            // dispatched by Mojang name. The proxy is called with the running game's method names (Names).
            String name = Names.mojangMethod(method.getName());
            int n = args == null ? 0 : args.length;
            if (n == 1 && args[0] instanceof BlockPos pos && (name.equals("getBlockState") || name.equals("getFluidState"))) {
                BlockState s = Blocks.AIR.defaultBlockState();
                boolean known = false;
                for (int i = 0; i < at.length; i++) {
                    if (at[i].equals(pos)) {
                        s = a[i];
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    if (found == null) {
                        throw Escape.INSTANCE;
                    }
                    BlockPos imm = pos.immutable();
                    if (!found.contains(imm)) {
                        found.add(imm);
                    }
                }
                return name.equals("getBlockState") ? s : s.getFluidState();
            }
            return answerFixed(proxy, method, args, minY, height);
        };
        return (LevelReader) Proxy.newProxyInstance(SurvivalProbe.class.getClassLoader(), new Class<?>[] { LevelReader.class }, h);
    }

    private static Object answerFixed(Object proxy, Method method, Object[] args, int minY, int height) throws Throwable {
        // dispatched by Mojang name. The proxy is called with the running game's method names (Names).
        String name = Names.mojangMethod(method.getName());
        int n = args == null ? 0 : args.length;
        switch (name) {
            case "getMinBuildHeight":
                if (n == 0) {
                    return minY;
                }
                break;
            case "getHeight":
                if (n == 0) {
                    return height;
                }
                break;
            case "getMaxBuildHeight":
                if (n == 0) {
                    return minY + height;
                }
                break;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "GA2 survival probe";
            default:
                break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw Escape.INSTANCE;
    }

    private static LevelReader level(BlockState soil, int minY, int height) {
        InvocationHandler h = (proxy, method, args) -> answer(proxy, method, args, soil, minY, height);
        return (LevelReader) Proxy.newProxyInstance(SurvivalProbe.class.getClassLoader(), new Class<?>[] { LevelReader.class }, h);
    }

    private static Object answer(Object proxy, Method method, Object[] args, BlockState soil, int minY, int height) throws Throwable {
        // dispatched by Mojang name. The proxy is called with the running game's method names (Names).
        String name = Names.mojangMethod(method.getName());
        int n = args == null ? 0 : args.length;
        if (n == 1 && args[0] instanceof BlockPos pos && (name.equals("getBlockState") || name.equals("getFluidState"))) {
            if (!pos.equals(BELOW)) {
                throw Escape.INSTANCE;
            }
            return name.equals("getBlockState") ? soil : soil.getFluidState();
        }
        switch (name) {
            case "getMinBuildHeight":
                if (n == 0) {
                    return minY;
                }
                break;
            case "getHeight":
                if (n == 0) {
                    return height;
                }
                break;
            case "getMaxBuildHeight":
                if (n == 0) {
                    return minY + height;
                }
                break;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "GA2 survival probe";
            default:
                break;
        }
        if (method.isDefault()) {
            // the default method's own body. That body ends in the methods above.
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw Escape.INSTANCE;
    }
}
