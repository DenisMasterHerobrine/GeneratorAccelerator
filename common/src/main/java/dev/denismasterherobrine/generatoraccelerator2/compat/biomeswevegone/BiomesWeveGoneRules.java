package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceSystemAccessor;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceExtern;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;

/**
 * Oh The Biomes We've Gone's surface rule types, compiled. GA2 has no dependency on the mod. It recognises the types by
 * class name and shape.
 *
 * <ul>
 * <li>{@code BetweenRepeatingNoiseRange}: applies the vanilla rule that it builds in its constructor, a sequence of noise
 * threshold tests. It therefore lowers to that rule.</li>
 * <li>{@code BandsRuleSource(bandStates, bandSizeProvider, bandsCountProvider, frequency, noiseScale)}: the mod's band
 * lookup on the surface system ({@code BandsContext.getBandsState}). The lookup uses a band table drawn once from the
 * surface system's noise random at the origin. The table is indexed by the block's y, shifted by the secondary surface
 * noise at the column. GA2 evaluates it here with the same arithmetic. GA2 builds the table the same way and keeps it in a
 * concurrent map. The mod's own map is not safe to fill from several threads, and GA2's parallel surface step does
 * that.</li>
 * <li>{@code WeightedRuleSource(ruleSources)}: picks one of its rules per column. The pick is drawn from the surface
 * system's noise random at the column's position in the chunk. GA2 lowers it to one test per rule on that draw.</li>
 * </ul>
 */
public final class BiomesWeveGoneRules {
    private BiomesWeveGoneRules() {}

    static final String BETWEEN = "net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BetweenRepeatingNoiseRange";
    static final String BANDS = "net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsRuleSource";
    static final String WEIGHTED = "net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource";
    static final String BANDS_CONTEXT = "net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsContext";

    public static void register() {
        SurfaceExtern.register(BiomesWeveGoneRules::lower);
    }

    static Ir.Rule lower(SurfaceRules.RuleSource source, SurfaceExtern.Context ctx) {
        String name = source.getClass().getName();
        if (name.equals(BETWEEN)) {
            Object rule = Reflect.field(source, BETWEEN, "rule");
            return rule instanceof SurfaceRules.RuleSource r ? ctx.rule(r) : null;
        }
        if (name.equals(BANDS)) {
            Object[] c = Reflect.components(source, BANDS, "bandStates", "bandSizeProvider", "bandsCountProvider", "frequency", "noiseScale");
            Class<?> bandsContext = Reflect.type(BANDS_CONTEXT);
            if (c == null || bandsContext == null || !(c[0] instanceof WeightedList<?> states) || !(c[1] instanceof IntProvider size)
                || !(c[2] instanceof IntProvider count) || !(c[3] instanceof Float frequency) || !(c[4] instanceof Integer scale)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            WeightedList<BlockState> bandStates = (WeightedList<BlockState>) states;
            return ctx.extern(new Bands(source, bandsContext, bandStates, size, count, frequency, scale));
        }
        if (name.equals(WEIGHTED)) {
            Object[] c = Reflect.components(source, WEIGHTED, "ruleSources");
            if (c == null || !(c[0] instanceof WeightedList<?> list)) {
                return null;
            }
            return weighted(source, list, ctx);
        }
        return null;
    }

    /** Column slots of {@link SurfaceEval#columnValue}, one per bands rule. */
    private static final AtomicInteger SLOTS = new AtomicInteger();

    /** Band tables per surface system and rule. The mod also keeps one table per surface system and rule. */
    private static final ConcurrentMap<SurfaceSystem, ConcurrentMap<Object, BlockState[]>> TABLES = new MapMaker().weakKeys().makeMap();

    private record Bands(Object source, Class<?> bandsContext, WeightedList<BlockState> bandStates, IntProvider bandSizeProvider,
                         IntProvider bandsCountProvider, float frequency, int noiseScale, int slot) implements SurfaceExtern.Rule, SurfaceExtern.Gpu {
        Bands(Object source, Class<?> bandsContext, WeightedList<BlockState> bandStates, IntProvider size, IntProvider count, float frequency,
              int noiseScale) {
            this(source, bandsContext, bandStates, size, count, frequency, noiseScale, SLOTS.getAndIncrement());
        }

        @Override
        public BlockState apply(SurfaceEval s) {
            SurfaceSystem system = s.system();
            if (!bandsContext.isInstance(system)) {
                return Blocks.STONE.defaultBlockState();
            }
            BlockState[] states = TABLES.computeIfAbsent(system, k -> new MapMaker().weakKeys().makeMap()).computeIfAbsent(source, k -> table(system));
            double scaledNoise = s.columnValue(slot);
            if (Double.isNaN(scaledNoise)) {
                scaledNoise = ((SurfaceSystemAccessor) system).ga2$surfaceSecondaryNoise().getValue((double) ((float) s.blockX * frequency), 0.0,
                    (double) ((float) s.blockZ * frequency)) * (double) noiseScale;
                s.setColumnValue(slot, scaledNoise);
            }
            int stateIndex = Math.floorMod((long) s.blockY + Math.round(scaledNoise), states.length - 1);
            return states[stateIndex];
        }

        /**
         * The same lookup in OpenCL. It samples the secondary surface noise at the block's x and z, scaled in float. The result
         * is multiplied by the noise scale, rounded as {@code Math.round} rounds, and added to y. That sum is taken modulo the
         * table's length minus one, so the last band is never picked, as in the mod. The table holds the modulus, then the state
         * ids. It is the world's own table, drawn from the world's noise random.
         */
        @Override
        public String gpu(SurfaceExtern.GpuSetup setup) {
            SurfaceSystem system = setup.system();
            if (!bandsContext.isInstance(system)) {
                return "return " + setup.state(Blocks.STONE.defaultBlockState()) + ";";
            }
            BlockState[] states = TABLES.computeIfAbsent(system, k -> new MapMaker().weakKeys().makeMap()).computeIfAbsent(source, k -> table(system));
            if (states.length < 2) {
                // floorMod by zero. The mod throws here.
                return null;
            }
            int[] data = new int[states.length];
            data[0] = states.length - 1;
            for (int i = 0; i < states.length - 1; i++) {
                data[1 + i] = setup.state(states[i]);
            }
            int slot = setup.table(data);
            String f = Float.toHexString(frequency) + "f";
            return "{ double bn = nn(s->c, SN_SECONDARY, (double) ((float) s->x * " + f + "), 0.0, (double) ((float) s->z * " + f + ")) * " + noiseScale
                + ".0; int bt = SF_EXT(" + slot + "); long bm = (long) s->sp[bt]; long br = ((long) s->y + sf_round(bn)) % bm; if (br < 0) br += bm;"
                + " return s->sp[bt + 1 + (int) br]; }";
        }

        private BlockState[] table(SurfaceSystem system) {
            List<BlockState> states = new ArrayList<>();
            RandomSource random = ((SurfaceSystemAccessor) system).ga2$noiseRandom().at(BlockPos.ZERO);
            int bandsCount = bandsCountProvider.sample(random);
            for (int bandIdx = 0; bandIdx < bandsCount; bandIdx++) {
                int bandSize = bandSizeProvider.sample(random);
                BlockState state = bandStates.getRandom(random).orElseThrow();
                for (int size = 0; size < bandSize; size++) {
                    states.add(state);
                }
            }
            return states.toArray(new BlockState[0]);
        }

        @Override
        public boolean positional() {
            return true;
        }
    }

    /**
     * One test per distinct rule, in list order, on the column's draw, followed by that rule. Vanilla applies the drawn
     * rule per column.
     */
    private static Ir.Rule weighted(SurfaceRules.RuleSource source, WeightedList<?> list, SurfaceExtern.Context ctx) {
        List<SurfaceRules.RuleSource> rules = new ArrayList<>();
        for (Weighted<?> entry : list.unwrap()) {
            if (!(entry.value() instanceof SurfaceRules.RuleSource r)) {
                return null;
            }
            if (!rules.contains(r)) {
                rules.add(r);
            }
        }
        if (rules.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        WeightedList<SurfaceRules.RuleSource> weights = (WeightedList<SurfaceRules.RuleSource>) list;
        List<Ir.Rule> tests = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            tests.add(new Ir.Test(ctx.extern(new Drawn(source, weights, rules, i)), ctx.rule(rules.get(i))));
        }
        return tests.size() == 1 ? tests.get(0) : new Ir.Seq(List.copyOf(tests));
    }

    /** Draw tables per surface system. Each entry is the index, into the distinct rules, of the rule drawn for a chunk column. */
    private static final ConcurrentMap<SurfaceSystem, ConcurrentMap<Object, byte[]>> DRAWS = new MapMaker().weakKeys().makeMap();

    private record Drawn(Object source, WeightedList<SurfaceRules.RuleSource> weights, List<SurfaceRules.RuleSource> rules, int index)
        implements SurfaceExtern.Cond, SurfaceExtern.Gpu {
        /** The same test in OpenCL. The int table holds the world's draw table: the rule drawn at each position in a chunk. */
        @Override
        public String gpu(SurfaceExtern.GpuSetup setup) {
            SurfaceSystem system = setup.system();
            byte[] draws = DRAWS.computeIfAbsent(system, k -> new MapMaker().weakKeys().makeMap()).computeIfAbsent(source, k -> draws(system));
            int[] data = new int[256];
            for (int i = 0; i < 256; i++) {
                data[i] = draws[i];
            }
            return "s->sp[SF_EXT(" + setup.table(data) + ") + ((s->x & 15) * 16 + (s->z & 15))] == " + index;
        }

        @Override
        public boolean test(SurfaceEval s) {
            SurfaceSystem system = s.system();
            byte[] draws = DRAWS.computeIfAbsent(system, k -> new MapMaker().weakKeys().makeMap()).computeIfAbsent(source, k -> draws(system));
            return draws[(s.blockX & 15) * 16 + (s.blockZ & 15)] == index;
        }

        private byte[] draws(SurfaceSystem system) {
            PositionalRandomFactory random = ((SurfaceSystemAccessor) system).ga2$noiseRandom();
            byte[] out = new byte[256];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    out[x * 16 + z] = (byte) rules.indexOf(weights.getRandom(random.at(x, 0, z)).get());
                }
            }
            return out;
        }

        @Override
        public Ir.Scope scope() {
            return Ir.Scope.COLUMN;
        }

        @Override
        public boolean positional() {
            return true;
        }
    }
}
