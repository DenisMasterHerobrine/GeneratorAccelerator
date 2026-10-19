package dev.denismasterherobrine.generatoraccelerator2.gpu.surface;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BiomeTemperatureAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.PerlinSimplexNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.SimplexNoiseAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceSystemAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.XoroshiroFactoryAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceBuilder;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceExtern;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The SURFACE step on the GPU for one dimension. It holds GA2's lowered surface rules ({@code surface.compile.Ir}) as OpenCL C, plus
 * the tables the rules read: block states, biome predicates and temperatures, noises, random factory seeds, anchors and clay bands. It
 * also holds the column walk of {@code SurfaceSystem.buildSurface}, as GA2's surface builder runs it.
 *
 * <p>Every condition transcribes the matching {@code SurfaceEval} condition (vanilla's {@code compute()} bodies). The conditions are
 * pure functions of the block, its column and the chunk's heightmap. Only a rule placing air could change the heightmap, and such a
 * chunk is left to the CPU. So each column is walked on its own. Chunks whose columns need {@code SurfaceSystem}'s eroded badlands or
 * frozen ocean extensions are left to the CPU.
 */
public final class SurfaceSpec {
    /** Why a dimension's surface stays on the CPU. */
    public static final class Unsupported extends RuntimeException {
        Unsupported(String why) {
            super(why);
        }
    }

    /** Ints of the window of quart biomes a chunk's surface reads (6 x 6 quart columns, every quart layer). */
    public final int windowInts;
    public final int quartsY;
    /** The surface's block states by id. The noise step's states come first ({@code baseCount}), then the rules' states. */
    public final List<BlockState> states = new ArrayList<>();
    private final Map<BlockState, Integer> stateIds = new IdentityHashMap<>();
    public final int baseCount;
    /** Surface noises, appended to the program's noise tables at {@link #specBase}. {@link #specBase} is set before {@link #source}. */
    public final List<NoiseSpec> noiseSpecs = new ArrayList<>();
    private final Map<NormalNoise, Integer> noiseIds = new IdentityHashMap<>();
    public int specBase = -1;
    public final List<Holder<Biome>> biomes;
    /** Ints of the int table ({@link #table}). */
    public final int[] table;

    private final SurfaceProgram program;
    /** The tag epoch the biome predicates were answered in ({@code TagEpoch}). After a reload, they may answer differently. */
    private final int tagEpoch = dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch.current();
    private final int[] slotSpec;
    private final int surfaceNoise, secondaryNoise, bandsNoise;
    private final long noiseRandomLo, noiseRandomHi;
    private final int[] gradLo, gradHi;
    private final long[] gradSeedLo, gradSeedHi;
    private final int[] anchorY;
    private final long zoomSeed;
    private final int predWords;
    private final int defaultId;
    private final int minY, height;
    // offsets in the int table
    private final int tPred, tInfo, tBands, tSimplex, tSeed, tSnow, tExt;
    /** Compat externs ({@link SurfaceExtern.Gpu}). Their int table data is stored by slot, and their OpenCL by the program's extern index. */
    private final List<int[]> extData = new ArrayList<>();
    private final String[] extRuleCode, extCondCode;
    /** An extern's code evaluates a noise of its own ({@code SF_SPEC}). */
    private boolean externNoise;
    /** Tectonic's snow start offset ({@code compat.tectonic}), or 0. The temperature reads the height lowered by this offset. */
    private final int snowOffset;
    private final int nbands;
    // The three temperature noises. Per level: the perm table's offset in the int table (-1: no level). Also their factors.
    private final int[][] simplexLevels = new int[3][];
    private final double[] simplexInput = new double[3], simplexValue = new double[3];

    /**
     * @param baseStates the noise step's states by id (the GPU's ids)
     * @param biomes the biomes by the GPU's biome index (every biome the source returns)
     */
    public SurfaceSpec(NoiseBasedChunkGenerator generator, NoiseGeneratorSettings settings, RandomState randomState, List<BlockState> baseStates,
                       List<Holder<Biome>> biomes, long worldSeed, int programMinY, int programHeight, LevelHeightAccessor level) {
        if (settings.useLegacyRandomSource()) {
            throw new Unsupported("a legacy random source");
        }
        SurfaceRules.RuleSource rule = settings.surfaceRule();
        this.program = SurfaceBuilder.program(rule);
        {
            // The types by name, for the status line, which shows the mods whose rules keep the surface on the CPU. Compat code's
            // externs run here when they have a GPU form.
            java.util.Set<String> types = new java.util.TreeSet<>();
            for (Object o : program.opaques) {
                types.add(o.getClass().getName());
            }
            for (Object o : program.externRules) {
                if (!(o instanceof SurfaceExtern.Gpu)) {
                    types.add(o.getClass().getName());
                }
            }
            for (Object o : program.externConds) {
                if (!(o instanceof SurfaceExtern.Gpu)) {
                    types.add(o.getClass().getName());
                }
            }
            if (!types.isEmpty()) {
                throw new Unsupported("surface rules with other mods' rule types " + types);
            }
        }
        SurfaceSystem system = randomState.surfaceSystem();
        if (system.getClass() != SurfaceSystem.class) {
            throw new Unsupported("surface system " + system.getClass().getName());
        }
        SurfaceSystemAccessor sys = (SurfaceSystemAccessor) system;
        this.minY = programMinY;
        this.height = programHeight;
        this.quartsY = height / 4;
        this.windowInts = 36 * quartsY;
        for (BlockState s : baseStates) {
            stateIds.putIfAbsent(s, states.size());
            states.add(s);
        }
        this.baseCount = states.size();
        BlockState def = sys.ga2$defaultBlock();
        Integer d = stateIds.get(def);
        if (d == null) {
            throw new Unsupported("a default block the noise step does not place");
        }
        this.defaultId = d;
        // Noises: the program's slots, the surface depth, the secondary noise and the clay bands' offset.
        this.slotSpec = new int[program.noiseKeys.size()];
        for (int i = 0; i < slotSpec.length; i++) {
            slotSpec[i] = noise(randomState.getOrCreateNoise(program.noiseKeys.get(i)));
        }
        this.surfaceNoise = noise(sys.ga2$surfaceNoise());
        this.secondaryNoise = noise(sys.ga2$surfaceSecondaryNoise());
        this.bandsNoise = noise(sys.ga2$clayBandsOffsetNoise());
        long[] nr = xoroshiro(sys.ga2$noiseRandom(), "the surface's noise random");
        this.noiseRandomLo = nr[0];
        this.noiseRandomHi = nr[1];
        // Anchors resolve as the surface step resolves them, against the level (generation context of the generator and the chunk).
        WorldGenerationContext ctx = new WorldGenerationContext(generator, level != null ? level : LevelHeightAccessor.create(minY, height));
        int g = program.gradients.size();
        this.gradLo = new int[g];
        this.gradHi = new int[g];
        this.gradSeedLo = new long[g];
        this.gradSeedHi = new long[g];
        for (int i = 0; i < g; i++) {
            SurfaceRules.VerticalGradientConditionSource src = program.gradients.get(i);
            gradLo[i] = src.trueAtAndBelow().resolveY(ctx);
            gradHi[i] = src.falseAtAndAbove().resolveY(ctx);
            long[] seeds = xoroshiro(randomState.getOrCreateRandomFactory(src.randomName()), "gradient " + src.randomName());
            gradSeedLo[i] = seeds[0];
            gradSeedHi[i] = seeds[1];
        }
        this.anchorY = new int[program.anchors.size()];
        for (int i = 0; i < anchorY.length; i++) {
            anchorY[i] = program.anchors.get(i).resolveY(ctx);
        }
        this.zoomSeed = BiomeManager.obfuscateSeed(worldSeed);
        // States the rules place. A chunk where a rule places air goes to the CPU, since air can change the heightmap that the steep
        // condition reads live.
        collectStates(program.root);
        // Compat externs: their code, the states they place and their data.
        SurfaceExtern.GpuSetup setup = new SurfaceExtern.GpuSetup() {
            @Override
            public SurfaceSystem system() {
                return system;
            }

            @Override
            public int state(BlockState s) {
                return SurfaceSpec.this.state(s);
            }

            @Override
            public int table(int[] data) {
                extData.add(data.clone());
                return extData.size() - 1;
            }

            @Override
            public RandomState randomState() {
                return randomState;
            }

            @Override
            public String noise(NormalNoise n, String x, String y, String z) {
                // The spec's index among the program's noises is known only when the source is written (SF_SPEC).
                externNoise = true;
                return "nn(s->c, SF_SPEC(" + SurfaceSpec.this.noise(n) + "), " + x + ", " + y + ", " + z + ")";
            }
        };
        this.extRuleCode = new String[program.externRules.size()];
        for (int i = 0; i < extRuleCode.length; i++) {
            extRuleCode[i] = externCode(program.externRules.get(i), setup);
        }
        this.extCondCode = new String[program.externConds.size()];
        for (int i = 0; i < extCondCode.length; i++) {
            extCondCode[i] = externCode(program.externConds.get(i), setup);
        }
        BlockState[] bands = sys.ga2$clayBands();
        this.nbands = bands.length;
        int[] bandIds = new int[nbands];
        boolean usesBands = usesBands(program.root);
        for (int i = 0; i < nbands; i++) {
            bandIds[i] = usesBands ? state(bands[i]) : 0;
        }
        // The int table: biome predicates (words per biome), biome info (flags, temperature bits), clay bands and simplex perms. Then
        // the world's seeds, as two ints each: the zoom seed, the noise random's seeds, and each gradient's seeds. Keeping seeds in the
        // table means the program's source does not depend on the seed, so one build serves every world of a pack.
        this.biomes = biomes;
        int nb = biomes.size();
        this.predWords = Math.max(1, (program.biomeTests.size() + 31) / 32);
        List<int[]> perms = new ArrayList<>();
        PerlinSimplexNoise[] temps = { BiomeTemperatureAccessor.ga2$temperatureNoise(), BiomeTemperatureAccessor.ga2$frozenTemperatureNoise(), Biome.BIOME_INFO_NOISE };
        int simplexInts = 0;
        for (int t = 0; t < 3; t++) {
            PerlinSimplexNoiseAccessor a = (PerlinSimplexNoiseAccessor) temps[t];
            SimplexNoise[] levels = a.ga2$noiseLevels();
            simplexLevels[t] = new int[levels.length];
            simplexInput[t] = a.ga2$highestFreqInputFactor();
            simplexValue[t] = a.ga2$highestFreqValueFactor();
            for (int l = 0; l < levels.length; l++) {
                if (levels[l] == null) {
                    simplexLevels[t][l] = -1;
                } else {
                    simplexLevels[t][l] = simplexInts;
                    perms.add(((SimplexNoiseAccessor) levels[l]).ga2$p());
                    simplexInts += 256;
                }
            }
        }
        this.tPred = 0;
        this.tInfo = tPred + nb * predWords;
        this.tBands = tInfo + nb * 2;
        this.tSimplex = tBands + nbands;
        this.tSeed = tSimplex + simplexInts;
        this.tSnow = tSeed + 6 + 4 * gradSeedLo.length;
        // After the snow offset: one start per extern data slot, then the data. The data length may depend on the seed. The starts'
        // positions do not.
        this.tExt = tSnow + 1;
        int extLen = extData.size();
        for (int[] ed : extData) {
            extLen += ed.length;
        }
        this.table = new int[tExt + extLen];
        for (int k = 0, at = tExt + extData.size(); k < extData.size(); k++) {
            int[] ed = extData.get(k);
            table[tExt + k] = at;
            System.arraycopy(ed, 0, table, at, ed.length);
            at += ed.length;
        }
        this.snowOffset = snowOffset();
        table[tSnow] = snowOffset;
        putLong(table, tSeed, zoomSeed);
        putLong(table, tSeed + 2, noiseRandomLo);
        putLong(table, tSeed + 4, noiseRandomHi);
        for (int i = 0; i < gradSeedLo.length; i++) {
            putLong(table, tSeed + 6 + 4 * i, gradSeedLo[i]);
            putLong(table, tSeed + 8 + 4 * i, gradSeedHi[i]);
        }
        for (int b = 0; b < nb; b++) {
            Holder<Biome> h = biomes.get(b);
            for (int slot = 0; slot < program.biomeTests.size(); slot++) {
                if (program.biomeTests.get(slot).test(h)) {
                    table[tPred + b * predWords + (slot >> 5)] |= 1 << (slot & 31);
                }
            }
            int flags = 0;
            if (h.is(Biomes.ERODED_BADLANDS)) {
                flags |= 1;
            }
            if (h.is(Biomes.FROZEN_OCEAN) || h.is(Biomes.DEEP_FROZEN_OCEAN)) {
                flags |= 2;
            }
            Biome.ClimateSettings climate = PlatformHooks.climateSettings(h.value());
            if (climate.temperatureModifier() == Biome.TemperatureModifier.FROZEN) {
                flags |= 4;
            } else if (climate.temperatureModifier() != Biome.TemperatureModifier.NONE) {
                throw new Unsupported("temperature modifier " + climate.temperatureModifier());
            }
            table[tInfo + b * 2] = flags;
            table[tInfo + b * 2 + 1] = Float.floatToRawIntBits(climate.temperature());
        }
        System.arraycopy(bandIds, 0, table, tBands, nbands);
        int o = tSimplex;
        for (int[] p : perms) {
            System.arraycopy(p, 0, table, o, 256);
            o += 256;
        }
        if (states.size() > 0xFFFF) {
            throw new Unsupported("more than 65535 block states");
        }
        // Check that every rule and condition has OpenCL. Otherwise the surface stays on the CPU before the dimension's program is made.
        rule(new StringBuilder(), program.root, "");
    }

    private int noise(NormalNoise n) {
        Integer i = noiseIds.get(n);
        if (i == null) {
            i = noiseSpecs.size();
            noiseSpecs.add(NoiseSpec.of(n));
            noiseIds.put(n, i);
        }
        return i;
    }

    private static long[] xoroshiro(PositionalRandomFactory f, String what) {
        if (f.getClass() != XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class) {
            throw new Unsupported(what + " is a " + Names.mojangSimpleName(f.getClass()));
        }
        XoroshiroFactoryAccessor a = (XoroshiroFactoryAccessor) f;
        return new long[] { a.ga2$seedLo(), a.ga2$seedHi() };
    }

    private int state(BlockState s) {
        Integer i = stateIds.get(s);
        if (i == null) {
            i = states.size();
            states.add(s);
            stateIds.put(s, i);
        }
        return i;
    }

    private void collectStates(Ir.Rule r) {
        switch (r) {
            case Ir.Seq s -> s.rules().forEach(this::collectStates);
            case Ir.Test t -> collectStates(t.then());
            case Ir.State st -> state(st.state());
            case Ir.Bands b -> {
            }
            case Ir.ExternRule e -> {
                // The extern's states are collected with its code (externCode).
            }
            default -> throw new Unsupported("rule " + r.getClass().getSimpleName());
        }
    }

    /** A compat extern's OpenCL ({@link SurfaceExtern.Gpu}), or Unsupported when it has none for this dimension. */
    private static String externCode(Object extern, SurfaceExtern.GpuSetup setup) {
        String code = extern instanceof SurfaceExtern.Gpu g ? g.gpu(setup) : null;
        if (code == null) {
            throw new Unsupported("surface rules with other mods' rule types [" + Names.mojangName(extern.getClass()) + "] (no GPU form for this dimension)");
        }
        return code;
    }

    private static boolean usesBands(Ir.Rule r) {
        return switch (r) {
            case Ir.Seq s -> s.rules().stream().anyMatch(SurfaceSpec::usesBands);
            case Ir.Test t -> usesBands(t.then());
            case Ir.Bands b -> true;
            default -> false;
        };
    }

    /** Whether the tables still answer as the rules would. The tags must be the same and the lowering's assumptions must still hold. */
    public boolean current() {
        return tagEpoch == dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch.current() && program.guardsHold() && snowOffset == snowOffset();
    }

    /** The height offset of the temperature's snow line. Tectonic's offset when its biome mixin is the change GA2 handles, else 0. */
    private static int snowOffset() {
        return dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicSnow.applies()
            ? dev.denismasterherobrine.generatoraccelerator2.compat.tectonic.TectonicSnow.offset().orElse(Integer.MIN_VALUE) : 0;
    }

    /** The id of a state, or -1 when the surface never has it. */
    public int idOf(BlockState s) {
        Integer i = stateIds.get(s);
        return i == null ? -1 : i;
    }

    /** Kind per state id for the column walk: 0 air, 1 a fluid (not air), 2 solid. */
    private int kind(BlockState s) {
        return s.isAir() ? 0 : !s.getFluidState().isEmpty() ? 1 : 2;
    }

    // ---- OpenCL ------------------------------------------------------------------------------------------------------------------

    private static String d(double v) {
        if (Double.isNaN(v)) {
            return "NAN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "INFINITY" : "(-INFINITY)";
        }
        return "(" + Double.toHexString(v) + ")";
    }

    private static void putLong(int[] t, int at, long v) {
        t[at] = (int) v;
        t[at + 1] = (int) (v >>> 32);
    }

    /** A long the table holds as two ints, low first ({@link #putLong}). */
    static final String SEED64 = "long sf_seed64(__global const int* t, int i) { return (long) ((ulong) (uint) t[i] | ((ulong) (uint) t[i + 1] << 32)); }\n";

    private String cond(Ir.Cond c) {
        return switch (c) {
            case Ir.Biome b -> "sf_pred(s, " + b.slot() + ")";
            case Ir.Noise n -> "sf_noise_in(s, " + n.slot() + ", " + (specBase + slotSpec[n.slot()]) + ", " + d(n.min()) + ", " + d(n.max()) + ")";
            case Ir.Gradient g -> "sf_grad(s, " + gradLo[g.slot()] + ", " + gradHi[g.slot()] + ", " + g.slot() + ")";
            case Ir.YCheck y -> "(s->y + " + (y.addStone() ? "s->sda" : "0") + " >= " + anchorY[y.anchor()] + " + s->sd * " + y.mult() + ")";
            case Ir.Water w -> "(s->wh == INT_MIN || s->y + " + (w.addStone() ? "s->sda" : "0") + " >= s->wh + " + w.offset() + " + s->sd * " + w.mult() + ")";
            case Ir.StoneDepth sd -> "sf_stone(s, " + sd.offset() + ", " + (sd.addSurface() ? 1 : 0) + ", " + sd.secondaryRange() + ", " + (sd.ceiling() ? 1 : 0) + ")";
            case Ir.Steep st -> "sf_steep(s)";
            case Ir.Slope sl -> "sf_slope(s, " + sl.min() + ", " + sl.max() + ")";
            case Ir.Hole h -> "(s->sd <= 0)";
            case Ir.AboveSurface a -> "(s->y >= s->minSurf)";
            case Ir.Temperature t -> "sf_cold(s)";
            case Ir.Not n -> "(!" + cond(n.target()) + ")";
            case Ir.All a -> a.conds().isEmpty() ? "1" : "(" + String.join(" && ", a.conds().stream().map(this::cond).toList()) + ")";
            case Ir.Any a -> a.conds().isEmpty() ? "0" : "(" + String.join(" || ", a.conds().stream().map(this::cond).toList()) + ")";
            case Ir.Const k -> k.value() ? "1" : "0";
            case Ir.ExternCond e -> "(" + extCondCode[e.index()] + ")";
            default -> throw new Unsupported("condition " + c.getClass().getSimpleName());
        };
    }

    private void rule(StringBuilder b, Ir.Rule r, String ind) {
        switch (r) {
            case Ir.Seq s -> {
                for (Ir.Rule c : s.rules()) {
                    rule(b, c, ind);
                }
            }
            case Ir.Test t -> {
                b.append(ind).append("if (").append(cond(t.cond())).append(") {\n");
                rule(b, t.then(), ind + "  ");
                b.append(ind).append("}\n");
            }
            case Ir.State st -> b.append(ind).append("return ").append(stateIds.get(st.state())).append(";\n");
            case Ir.Bands bd -> b.append(ind).append("return sf_band(s);\n");
            case Ir.ExternRule e -> b.append(ind).append(extRuleCode[e.index()]).append('\n');
            default -> throw new Unsupported("rule " + r.getClass().getSimpleName());
        }
    }

    private String simplexFn(int t, String name) {
        StringBuilder b = new StringBuilder("double ").append(name).append("(__global const int* sp, double x, double y) {\n  double d0 = 0.0;\n");
        double d1 = simplexInput[t], d2 = simplexValue[t];
        // The factors step as vanilla steps them (halving and doubling are exact). Each level gets its own constants.
        for (int l = 0; l < simplexLevels[t].length; l++) {
            if (simplexLevels[t][l] >= 0) {
                b.append("  d0 += sx_value(sp + ").append(tSimplex + simplexLevels[t][l]).append(", x * ").append(d(d1)).append(" + 0.0, y * ").append(d(d1))
                    .append(" + 0.0) * ").append(d(d2)).append(";\n");
            }
            d1 /= 2.0;
            d2 *= 2.0;
        }
        return b.append("  return d0;\n}\n").toString();
    }

    /** BiomeManager's zoom hash (LinearCongruentialGenerator.next and getFiddle). */
    private static final String LCG = """
        long sf_lcg(long a, long b) { ulong u = (ulong) a; u = u * (u * 6364136223846793005UL + 1442695040888963407UL); return (long) (u + (ulong) b); }
        double sf_fiddle(long h) { double d0 = (double) ((h >> 24) & 1023L) / 1024.0; return (d0 - 0.5) * 0.9; }
        """;

    /** SimplexNoise.getValue(x, y) and the three temperature noises over the int table. Emitted after SX_GRAD and the table offsets. */
    private String climate() {
        double sqrt3 = Math.sqrt(3.0);
        double f2 = 0.5 * (sqrt3 - 1.0);
        double g2 = (3.0 - sqrt3) / 6.0;
        return """
            /* SimplexNoise.getValue(x, y) */
            double sx_corner(int g, double x, double y) {
              __constant const int* G = SX_GRAD + g * 3;
              double d1 = 0.5 - x * x - y * y - 0.0 * 0.0;
              if (d1 < 0.0) return 0.0;
              d1 *= d1;
              return d1 * d1 * ((double) G[0] * x + (double) G[1] * y + (double) G[2] * 0.0);
            }
            double sx_value(__global const int* p, double x, double y) {
              double d0 = (x + y) * SX_F2;
              int i = jfloor(x + d0), j = jfloor(y + d0);
              double d1 = (double) (i + j) * SX_G2;
              double d2 = (double) i - d1, d3 = (double) j - d1;
              double d4 = x - d2, d5 = y - d3;
              int k, l;
              if (d4 > d5) { k = 1; l = 0; } else { k = 0; l = 1; }
              double d6 = d4 - (double) k + SX_G2, d7 = d5 - (double) l + SX_G2;
              double d8 = d4 - 1.0 + 2.0 * SX_G2, d9 = d5 - 1.0 + 2.0 * SX_G2;
              int i1 = i & 0xFF, j1 = j & 0xFF;
              int k1 = p[(i1 + p[j1 & 0xFF]) & 0xFF] % 12;
              int l1 = p[(i1 + k + p[(j1 + l) & 0xFF]) & 0xFF] % 12;
              int i2 = p[(i1 + 1 + p[(j1 + 1) & 0xFF]) & 0xFF] % 12;
              double d10 = sx_corner(k1, d4, d5), d11 = sx_corner(l1, d6, d7), d12 = sx_corner(i2, d8, d9);
              return 70.0 * (d10 + d11 + d12);
            }
            """.replace("SX_F2", d(f2)).replace("SX_G2", d(g2))
            + simplexFn(0, "sx_temperature") + simplexFn(1, "sx_frozen") + simplexFn(2, "sx_info");
    }

    /**
     * The parts of the surface source that GPU decoration's own program needs, emitted after {@code ClEmitter.decorPrelude}. These
     * are the window and zoom defines, the climate table's offset, the zoom hash and the temperature noises.
     */
    public String decorHelpers() {
        StringBuilder b = new StringBuilder();
        b.append("// ---- surface helpers for decoration (gpu.surface.SurfaceSpec) ----\n");
        b.append("#define SF_MINY ").append(minY).append("\n#define SF_H ").append(height).append("\n#define SF_QY ").append(quartsY).append("\n");
        b.append("#define SF_TINFO ").append(tInfo).append("\n#define SF_TSEED ").append(tSeed).append('\n').append(SEED64);
        b.append("__constant int SX_GRAD[48] = {1,1,0, -1,1,0, 1,-1,0, -1,-1,0, 1,0,1, -1,0,1, 1,0,-1, -1,0,-1, 0,1,1, 0,-1,1, 0,1,-1, 0,-1,-1, 1,1,0, 0,-1,1, -1,1,0, 0,-1,-1};\n");
        b.append(LCG).append(climate());
        return b.toString();
    }

    /**
     * The surface source, appended to the dimension's program after the noise tables, {@code Ctx} and the Xoroshiro helpers. It holds
     * the rules, the helpers they read, and the kernels {@code k_sunpack}, {@code k_sbuild}, {@code k_spack} and {@code k_sheights}.
     */
    public String source(String commonArgs, String ctxInit, String commonInit, int metaInts) {
        if (specBase < 0) {
            throw new IllegalStateException("specBase not set");
        }
        int nslot = Math.max(1, slotSpec.length);
        int surfaceSlot = -1;
        for (int i = 0; i < slotSpec.length; i++) {
            if (slotSpec[i] == surfaceNoise) {
                surfaceSlot = i;
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("// ---- surface (gpu.surface.SurfaceSpec) ----\n");
        b.append("#define SF_MINY ").append(minY).append("\n#define SF_H ").append(height).append("\n#define SF_QY ").append(quartsY).append("\n");
        b.append("#define SF_NSEC ").append(height / 16).append("\n#define SF_META ").append(metaInts).append("\n");
        b.append("#define SF_WIN ").append(windowInts).append("\n#define SF_DEFAULT ").append(defaultId).append("\n#define SF_NSTATES ").append(states.size()).append("\n");
        b.append("#define SF_TPRED ").append(tPred).append("\n#define SF_PW ").append(predWords).append("\n#define SF_TINFO ").append(tInfo)
            .append("\n#define SF_TBANDS ").append(tBands).append("\n#define SF_NBANDS ").append(nbands).append("\n");
        // The zoom seed is the column walk's own copy of the table's zoom seed. sf_zoom reads it through s.
        b.append("#define SF_TSEED ").append(tSeed).append("\n#define SF_ZSEED (s->zs)\n#define SF_TSNOW ").append(tSnow).append('\n').append(SEED64);
        if (!extData.isEmpty()) {
            // The start of a compat extern's data slot in the int table (SurfaceExtern.Gpu). Without externs, the source is as before.
            b.append("#define SF_TEXT ").append(tExt).append("\n#define SF_EXT(k) (s->sp[SF_TEXT + (k)])\n");
        }
        if (externNoise) {
            // A surface noise's index among the program's noise specs. The surface's specs come after the router's.
            b.append("#define SF_SPEC(i) (").append(specBase).append(" + (i))\n");
            b.append("int sf_between(double v, double mn, double mx) { return v >= mn && v <= mx; }\n");
        }
        b.append("#define SN_SECONDARY ").append(specBase + secondaryNoise).append("\n#define SN_BANDS ").append(specBase + bandsNoise).append("\n");
        b.append("__constant int SX_GRAD[48] = {1,1,0, -1,1,0, 1,-1,0, -1,-1,0, 1,0,1, -1,0,1, 1,0,-1, -1,0,-1, 0,1,1, 0,-1,1, 0,1,-1, 0,-1,-1, 1,1,0, 0,-1,1, -1,1,0, 0,-1,-1};\n");
        b.append("__constant uchar SF_KIND[").append(states.size()).append("] = {");
        for (int i = 0; i < states.size(); i++) {
            b.append(i == 0 ? "" : ",").append(kind(states.get(i)));
        }
        b.append("};\n");
        // Heightmap predicates per state: bit 0 WORLD_SURFACE_WG, bit 1 OCEAN_FLOOR_WG, bit 2 a fluid.
        b.append("__constant uchar SF_HM[").append(states.size()).append("] = {");
        for (int i = 0; i < states.size(); i++) {
            BlockState s = states.get(i);
            int m = (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(s) ? 1 : 0) | (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(s) ? 2 : 0)
                | (s.getFluidState().isEmpty() ? 0 : 4);
            b.append(i == 0 ? "" : ",").append(m);
        }
        b.append("};\n");
        b.append("typedef struct {\n  Ctx* c; __global const int* sp; __global const ushort* win; __global const int* tops; __global const ushort* col;\n"
            + "  int x, y, z, lx, lz, wqx0, wqz0;\n  int sd, minSurf, sda, wh, bio, runBottom, steep, secDone;\n  long zs;\n  double sec;\n  double nv[")
            .append(nslot).append("]; uchar nd[").append(nslot).append("];\n} SC;\n");
        b.append("""
            int jd2i(double d) { if (d != d) return 0; if (d >= 2147483647.0) return 2147483647; if (d <= -2147483648.0) return (int) 0x80000000; return (int) d; }
            int jd2i_floor(double d) { int i = jd2i(d); return d < (double) i ? i - 1 : i; }
            int sf_iadd(int a, int b) { return (int) ((uint) a + (uint) b); }
            long sf_round(double a) {
              long bits = as_long(a);
              long be = (bits & 0x7FF0000000000000L) >> 52;
              long shift = (52 - 1 + 1023) - be;
              if ((shift & -64L) == 0) {
                long r = (bits & 0x000FFFFFFFFFFFFFL) | 0x0010000000000000L;
                if (bits < 0) r = -r;
                return ((r >> shift) + 1) >> 1;
              }
              return (long) a;
            }
            """).append(LCG).append("""
            int sf_win(SC* s, int qx, int qy, int qz) {
              int lo = SF_MINY >> 2, hi = lo + SF_QY - 1;
              int y = qy < lo ? lo : qy > hi ? hi : qy;
              return s->win[((y - lo) * 6 + (qz - s->wqz0)) * 6 + (qx - s->wqx0)];
            }
            /* BiomeManager.getBiome over the chunk's window of stored quart biomes; a call (it runs at most once per block), since
               inlined into every biome test of the rules it made Terralith's program build a third longer, as sf_cold does */
            NI int sf_zoom(SC* s, int x, int y, int z) {
              int i = x - 2, j = y - 2, k = z - 2;
              int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
              double d0 = (double) (i & 3) / 4.0, d1 = (double) (j & 3) / 4.0, d2 = (double) (k & 3) / 4.0;
              int k1 = 0; double d3 = INFINITY;
              for (int l1 = 0; l1 < 8; l1++) {
                int fx = (l1 & 4) == 0, fy = (l1 & 2) == 0, fz = (l1 & 1) == 0;
                int i2 = fx ? l : l + 1, j2 = fy ? i1 : i1 + 1, k2 = fz ? j1 : j1 + 1;
                double d4 = fx ? d0 : d0 - 1.0, d5 = fy ? d1 : d1 - 1.0, d6 = fz ? d2 : d2 - 1.0;
                long h = sf_lcg(SF_ZSEED, (long) i2); h = sf_lcg(h, (long) j2); h = sf_lcg(h, (long) k2);
                h = sf_lcg(h, (long) i2); h = sf_lcg(h, (long) j2); h = sf_lcg(h, (long) k2);
                double f0 = sf_fiddle(h); h = sf_lcg(h, SF_ZSEED); double f1 = sf_fiddle(h); h = sf_lcg(h, SF_ZSEED); double f2 = sf_fiddle(h);
                double a = d6 + f2, bb = d5 + f1, cc = d4 + f0;
                double d7 = a * a + bb * bb + cc * cc;
                if (d3 > d7) { k1 = l1; d3 = d7; }
              }
              return sf_win(s, (k1 & 4) == 0 ? l : l + 1, (k1 & 2) == 0 ? i1 : i1 + 1, (k1 & 1) == 0 ? j1 : j1 + 1);
            }
            int sf_bio(SC* s) { if (s->bio < 0) s->bio = sf_zoom(s, s->x, s->y, s->z); return s->bio; }
            int sf_pred(SC* s, int slot) { return (s->sp[SF_TPRED + sf_bio(s) * SF_PW + (slot >> 5)] >> (slot & 31)) & 1; }
            double sf_noise(SC* s, int slot, int spec) {
              if (!s->nd[slot]) { s->nv[slot] = nn(s->c, spec, (double) s->x, 0.0, (double) s->z); s->nd[slot] = 1; }
              return s->nv[slot];
            }
            int sf_noise_in(SC* s, int slot, int spec, double mn, double mx) { double v = sf_noise(s, slot, spec); return v >= mn && v <= mx; }
            int sf_grad(SC* s, int lo, int hi, int gi) {
              int y = s->y;
              if (y <= lo) return 1;
              if (y >= hi) return 0;
              double d = jmap((double) y, (double) lo, (double) hi, 1.0, 0.0);
              Xo r = xo_at((ulong) sf_seed64(s->sp, SF_TSEED + 6 + 4 * gi), (ulong) sf_seed64(s->sp, SF_TSEED + 8 + 4 * gi), s->x, y, s->z);
              return (double) xo_nextFloat(&r) < d;
            }
            double sf_secondary(SC* s) {
              if (!s->secDone) { s->sec = nn(s->c, SN_SECONDARY, (double) s->x, 0.0, (double) s->z); s->secDone = 1; }
              return s->sec;
            }
            int sf_below(SC* s) {
              if (s->runBottom >= s->y) {
                int k2 = -32512;
                for (int j3 = s->y - 1; j3 >= SF_MINY - 1; j3--) {
                  int kd = j3 >= SF_MINY ? SF_KIND[s->col[j3 - SF_MINY]] : 0;
                  if (kd != 2) { k2 = j3 + 1; break; }
                }
                s->runBottom = k2;
              }
              return s->y - s->runBottom + 1;
            }
            int sf_stone(SC* s, int offset, int addSurface, int range, int ceiling) {
              int i = ceiling ? sf_below(s) : s->sda;
              int j = addSurface ? s->sd : 0;
              int k = range == 0 ? 0 : jd2i(jmap(sf_secondary(s), -1.0, 1.0, 0.0, (double) range));
              return i <= 1 + offset + j + k;
            }
            int sf_steep(SC* s) {
              if (s->steep == 0) {
                int i = s->lx, j = s->lz;
                int k = max(j - 1, 0), l = min(j + 1, 15);
                int i1 = s->tops[i * 16 + k] - 1, j1 = s->tops[i * 16 + l] - 1;
                int r;
                if (j1 >= i1 + 4) r = 1;
                else {
                  int k1 = max(i - 1, 0), l1 = min(i + 1, 15);
                  int i2 = s->tops[k1 * 16 + j] - 1, j2 = s->tops[l1 * 16 + j] - 1;
                  r = i2 >= j2 + 4;
                }
                s->steep = r ? 2 : 1;
              }
              return s->steep == 2;
            }
            /* Lithostitched's slope condition: the spread of the four neighbouring columns' heights (clamped to the chunk) in [mn, mx] */
            int sf_slope(SC* s, int mn, int mx) {
              int x = s->lx, z = s->lz;
              int no = s->tops[x * 16 + max(z - 1, 0)] - 1, so = s->tops[x * 16 + min(z + 1, 15)] - 1;
              int we = s->tops[max(x - 1, 0) * 16 + z] - 1, ea = s->tops[min(x + 1, 15) * 16 + z] - 1;
              int spread = max(max(no, so), max(ea, we)) - min(min(no, so), min(ea, we));
              return spread >= mn && spread <= mx;
            }
            int sf_band(SC* s) {
              int i = (int) sf_round(nn(s->c, SN_BANDS, (double) s->x, 0.0, (double) s->z) * 4.0);
              return s->sp[SF_TBANDS + (s->y + i + SF_NBANDS) % SF_NBANDS];
            }
            """).append(climate());
        b.append("""
            /* Biome.getTemperature (FROZEN: TemperatureModifier.FROZEN) and coldEnoughToSnow */
            NI int sf_cold(SC* s) {
              int bi = sf_bio(s);
              __global const int* info = s->sp + SF_TINFO + bi * 2;
              float f = as_float(info[1]);
              if (info[0] & 4) {
                double d0 = sx_frozen(s->sp, (double) s->x * 0.05, (double) s->z * 0.05) * 7.0;
                double d1 = sx_info(s->sp, (double) s->x * 0.2, (double) s->z * 0.2);
                double d2 = d0 + d1;
                if (d2 < 0.3) { double d3 = sx_info(s->sp, (double) s->x * 0.09, (double) s->z * 0.09); if (d3 < 0.8) f = 0.2f; }
              }
              float t;
              int y = s->y - s->sp[SF_TSNOW]; /* Tectonic's snow line reads the height that much lower (0 without it) */
              if (y > 80) {
                float f1 = (float) (sx_temperature(s->sp, (double) ((float) s->x / 8.0f), (double) ((float) s->z / 8.0f)) * 8.0);
                t = f - (f1 + (float) y - 80.0f) * 0.05f / 40.0f;
              } else t = f;
              return !(t >= 0.15f);
            }
            """);
        b.append("int sf_apply(SC* s) {\n");
        rule(b, program.root, "  ");
        b.append("  return -1;\n}\n");
        // ---- kernels ----
        // Unpacks the noise step's packed sections into one id per block: blk[li][x][z][y]. A copy is kept for the pack's write order.
        b.append("__kernel void k_sunpack(__global const long* storage, __global const int* meta, int n, __global ushort* blk, __global ushort* blk0) {\n");
        b.append("  int g = get_global_id(0); if (g >= n * 256 * SF_H) return;\n");
        b.append("  int li = g / (256 * SF_H); int r = g % (256 * SF_H); int col = r / SF_H; int yy = r % SF_H; int lx = col >> 4, lz = col & 15;\n");
        b.append("  int sec = yy >> 4, ly = yy & 15;\n  __global const int* m = meta + (li * SF_NSEC + sec) * SF_META;\n");
        b.append("  int id = 0;\n  if ((m[0] & 0xff) != 0) {\n    int idx = (ly << 8) | (lz << 4) | lx;\n");
        b.append("    long w = storage[((long) li * SF_NSEC + sec) * 256 + (idx >> 4)];\n    int pi = (int) ((w >> ((idx & 15) * 4)) & 15L);\n");
        b.append("    id = pi == 0 ? 0 : m[pi];\n  }\n  blk[g] = (ushort) id; blk0[g] = (ushort) id;\n}\n");
        // the column walk
        b.append("__kernel void k_sbuild(").append(commonArgs).append(", __global ushort* blk, __global const int* tops, __global const ushort* win, __global const int* cpar, "
            + "__global const int* sp, __global int* sflag, int n) {\n");
        b.append("  int g = get_global_id(0); if (g >= n * 256) return;\n").append(ctxInit).append(commonInit);
        b.append("  int li = g >> 8, col = g & 255, lx = col >> 4, lz = col & 15;\n  __global const int* cp = cpar + li * 8;\n");
        b.append("  SC ss; SC* s = &ss; s->c = c; s->sp = sp; s->zs = sf_seed64(sp, SF_TSEED); s->tops = tops + li * 256; s->win = win + (long) li * SF_WIN;\n");
        b.append("  s->wqx0 = cp[0] * 4 - 1; s->wqz0 = cp[1] * 4 - 1; s->lx = lx; s->lz = lz;\n");
        b.append("  __global ushort* bc = blk + (long) li * 256 * SF_H + col * SF_H; s->col = bc;\n");
        b.append("  int x = cp[0] * 16 + lx, z = cp[1] * 16 + lz; s->x = x; s->z = z;\n");
        b.append("  int k1 = s->tops[col];\n");
        b.append("  int hb = sf_zoom(s, x, k1, z);\n  if (sp[SF_TINFO + hb * 2] & 3) { atomic_or(sflag + li, 1); return; }\n");
        b.append("  for (int i = 0; i < ").append(nslot).append("; i++) s->nd[i] = 0;\n  s->secDone = 0; s->steep = 0;\n");
        b.append("  double sn = nn(c, ").append(specBase + surfaceNoise).append(", (double) x, 0.0, (double) z);\n");
        if (surfaceSlot >= 0) {
            b.append("  s->nv[").append(surfaceSlot).append("] = sn; s->nd[").append(surfaceSlot).append("] = 1;\n");
        }
        b.append("  Xo nr = xo_at((ulong) sf_seed64(sp, SF_TSEED + 2), (ulong) sf_seed64(sp, SF_TSEED + 4), x, 0, z);\n");
        b.append("  double ndv = (double) (xo_next(&nr) >> 11) * (double) 1.110223E-16f;\n");
        b.append("  s->sd = jd2i(sn * 2.75 + 3.0 + ndv * 0.25);\n");
        b.append("  double ddx = (double) ((float) (x & 15) / 16.0f), ddz = (double) ((float) (z & 15) / 16.0f);\n");
        b.append("  double la = (double) cp[2] + ddx * ((double) cp[3] - (double) cp[2]);\n  double lb = (double) cp[4] + ddx * ((double) cp[5] - (double) cp[4]);\n");
        b.append("  s->minSurf = sf_iadd(sf_iadd(jd2i_floor(la + ddz * (lb - la)), s->sd), -8);\n");
        b.append("  int i2 = 0, j2 = INT_MIN; s->runBottom = INT_MAX;\n");
        b.append("  for (int y = k1; y >= SF_MINY; y--) {\n");
        b.append("    int id = y < SF_MINY + SF_H ? bc[y - SF_MINY] : 0;\n    int kd = SF_KIND[id];\n");
        b.append("    if (kd == 0) { i2 = 0; j2 = INT_MIN; }\n    else if (kd == 1) { if (j2 == INT_MIN) j2 = y + 1; }\n");
        b.append("    else {\n      i2++;\n      s->sda = i2; s->wh = j2; s->y = y; s->bio = -1;\n");
        b.append("      if (id == SF_DEFAULT) {\n        int res = sf_apply(s);\n        if (res >= 0) {\n          bc[y - SF_MINY] = (ushort) res;\n");
        // Air may lower the heightmap that other columns' steep conditions read during vanilla's build (its frozen ocean holes). The
        // CPU's build is used then.
        b.append("          if (SF_KIND[res] == 0) atomic_or(sflag + li, 2);\n        }\n      }\n    }\n  }\n}\n");
        // Per section: vanilla's palette through the writes in vanilla's order (columns x then z, y down), plus the section's storage,
        // histogram and marks. Each section gets one work-group of 256. The section's blocks before and after the surface are read once
        // into local memory (16 consecutive y of a column per 16 work-items). The palette is the section's own palette, followed by the
        // states the writes bring, in the order of their first write. Each work-item walks one column, whose writes are consecutive in
        // that order. It records the first write of each new state in a small local table. If the new states fit the palette (no
        // resize), that is the answer. Otherwise one work-item replays the palette through every write, resizing as vanilla does. This
        // is rare: it needs more than 16 states in a section. The marks, the packing and the histogram are split across work-items.
        b.append("""
            /* finds or adds state s in the section's table of states (64 slots, open addressing), keeping the smallest write position */
            void sp_note(__local int* key, __local int* first, int s, int pos, __local int* over) {
              int h = (int) (((uint) s * 0x9E3779B1u) >> 26);
              for (int p = 0; p < 64; p++) {
                int slot = (h + p) & 63;
                int k = key[slot];
                if (k == -1) { k = atomic_cmpxchg(&key[slot], -1, s); if (k == -1) k = s; }
                if (k == s) { atomic_min(&first[slot], pos); return; }
              }
              *over = 1;
            }
            __kernel __attribute__((reqd_work_group_size(256, 1, 1)))
            void k_spack(__global const ushort* blk0, __global const ushort* blk, __global const int* meta, int n, __global long* sstore,
                         __global int* smeta, __global long* smarks) {
              int g = get_group_id(0); int lid = get_local_id(0);
              int li = g / SF_NSEC, sec = g % SF_NSEC;
              __global const ushort* a = blk0 + (long) li * 256 * SF_H + sec * 16;
              __global const ushort* f = blk + (long) li * 256 * SF_H + sec * 16;
              __global const int* m = meta + (li * SF_NSEC + sec) * SF_META;
              __global int* om = smeta + (long) g * SPM;
              __global long* os = sstore + (long) g * 512;
              __global long* ok = smarks + (long) g * 64;
              /* by col * 16 + ly, col = x * 16 + z */
              __local ushort la[4096]; __local ushort lf[4096];
              __local int pal[256]; __local int hist[256];
              /* the states of the section's palette (first -1) and those the writes bring (first: their first write's position) */
              __local int hkey[64]; __local int hfirst[64]; __local int hrank[64];
              __local ushort mk16[256];
              __local int changed, lsize, lbits, nnew, over;
              if (lid == 0) { changed = 0; nnew = 0; over = 0; }
              if (lid < 64) { hkey[lid] = -1; hfirst[lid] = 0x7fffffff; }
              barrier(CLK_LOCAL_MEM_FENCE);
              int ch = 0;
              for (int e = lid; e < 4096; e += 256) {
                int col = e >> 4, ly = e & 15;
                ushort av = a[col * SF_H + ly], fv = f[col * SF_H + ly];
                la[e] = av; lf[e] = fv;
                ch |= av != fv;
              }
              if (ch) atomic_or(&changed, 1);
              barrier(CLK_LOCAL_MEM_FENCE);
              if (!changed) {
                if (lid < 64) ok[lid] = 0;
                if (lid == 0) om[0] = 0;
                return;
              }
              /* the fluid marks of the changed blocks, by y << 8 | z << 4 | x: the 16 x of one row (y, z) per work-item */
              {
                int z = lid & 15, y = lid >> 4; uint mk = 0;
                for (int x = 0; x < 16; x++) {
                  int e = (x * 16 + z) * 16 + y; int fi = lf[e];
                  if (la[e] != fi && (SF_HM[fi] & 4)) mk |= 1u << x;
                }
                mk16[lid] = (ushort) mk;
              }
              int psize = m[0] & 0xff;
              if (lid < psize) { int s = lid == 0 ? 0 : m[lid]; pal[lid] = s; sp_note(hkey, hfirst, s, -1, &over); }
              barrier(CLK_LOCAL_MEM_FENCE);
              if (lid < 64) {
                ok[lid] = (long) ((ulong) mk16[lid * 4] | (ulong) mk16[lid * 4 + 1] << 16 | (ulong) mk16[lid * 4 + 2] << 32 | (ulong) mk16[lid * 4 + 3] << 48);
              }
              /* the column's writes, in their order (y down); a write of the state just noted needs no note */
              {
                int last = -1;
                for (int ly = 15; ly >= 0; ly--) {
                  int ai = la[lid * 16 + ly], fi = lf[lid * 16 + ly];
                  if (ai != fi && fi != last) { sp_note(hkey, hfirst, fi, lid * 16 + 15 - ly, &over); last = fi; }
                }
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              if (lid < 64) {
                int p = hfirst[lid];
                if (p >= 0 && p < 4096) {
                  int rank = 0;
                  for (int j = 0; j < 64; j++) { int q = hfirst[j]; rank += q >= 0 && q < p; }
                  hrank[lid] = rank;
                  atomic_inc(&nnew);
                }
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              if (!over && psize + nnew <= 16) {
                /* no resize: the section's palette, then the new states by first write */
                if (lid < 64) { int p = hfirst[lid]; if (p >= 0 && p < 4096) pal[psize + hrank[lid]] = hkey[lid]; }
                if (lid == 0) { lsize = psize + nnew; lbits = 4; }
              } else if (lid == 0) {
                int size = psize, bits = 4, fail = 0;
                pal[0] = 0;
                for (int i = 1; i < psize; i++) pal[i] = m[i];
                int pos = 0;
                for (int col = 0; col < 256 && !fail; col++) {
                  for (int ly = 15; ly >= 0; ly--, pos++) {
                    int ai = la[col * 16 + ly], fi = lf[col * 16 + ly];
                    if (ai == fi) continue;
                    int found = 0;
                    for (int i = 0; i < size; i++) if (pal[i] == fi) { found = 1; break; }
                    if (found) continue;
                    if (size >= (1 << bits)) {
                      /* PalettedContainer.onResize: the storage's states in index order, then the new one */
                      if (bits >= 8) { fail = 1; break; }
                      bits++;
                      int ns = 0;
                      for (int idx = 0; idx < 4096; idx++) {
                        int c2 = (idx & 15) * 16 + ((idx >> 4) & 15), iy = idx >> 8;
                        int p2 = c2 * 16 + (15 - iy);
                        int st = p2 < pos ? lf[c2 * 16 + iy] : la[c2 * 16 + iy];
                        int have = 0;
                        for (int i = 0; i < ns; i++) if (pal[i] == st) { have = 1; break; }
                        if (!have) pal[ns++] = st;
                      }
                      size = ns;
                    }
                    pal[size++] = fi;
                  }
                }
                lsize = fail ? -1 : size; lbits = bits;
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              int size = lsize, bits = lbits;
              if (size < 0) { if (lid == 0) om[0] = -1; return; }
              for (int i = lid; i < size; i += 256) hist[i] = 0;
              barrier(CLK_LOCAL_MEM_FENCE);
              /* storage: values per long without spanning, as SimpleBitStorage; the counts added once per run of one state */
              int vpl = 64 / bits;
              int words = (4096 + vpl - 1) / vpl;
              for (int w = lid; w < words; w += 256) {
                ulong word = 0;
                int lastSt = -1, pi = 0, run = 0;
                for (int j = 0; j < vpl; j++) {
                  int idx = w * vpl + j; if (idx >= 4096) break;
                  int st = lf[((idx & 15) * 16 + ((idx >> 4) & 15)) * 16 + (idx >> 8)];
                  if (st != lastSt) {
                    if (run) atomic_add(&hist[pi], run);
                    pi = 0;
                    for (int i = 0; i < size; i++) if (pal[i] == st) { pi = i; break; }
                    lastSt = st; run = 0;
                  }
                  run++;
                  word |= ((ulong) pi) << (j * bits);
                }
                if (run) atomic_add(&hist[pi], run);
                os[w] = (long) word;
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              if (lid == 0) { om[0] = size; om[1] = bits; }
              for (int i = lid; i < size; i += 256) { om[2 + i] = pal[i]; om[2 + 256 + i] = hist[i]; }
            }
            /* the two worldgen heightmaps from the built columns: first available above the highest block each predicate holds for */
            __kernel void k_sheights(__global const ushort* blk, int n, __global int* stops) {
              int g = get_global_id(0); if (g >= n * 256) return;
              __global const ushort* c = blk + (long) g * SF_H;
              int ws = SF_MINY, of = SF_MINY, wd = 0, od = 0;
              for (int yy = SF_H - 1; yy >= 0 && !(wd && od); yy--) {
                int hm = SF_HM[c[yy]];
                if (!wd && (hm & 1)) { ws = SF_MINY + yy + 1; wd = 1; }
                if (!od && (hm & 2)) { of = SF_MINY + yy + 1; od = 1; }
              }
              stops[g * 2] = ws; stops[g * 2 + 1] = of;
            }
            """.replace("SPM", Integer.toString(SECTION_META)));
        return b.toString();
    }

    /** Ints per section in {@code k_spack}'s meta output: [0] palette size (0 unchanged, -1 needs the CPU), [1] bits, palette, counts. */
    public static final int SECTION_META = 2 + 256 + 256;
}
