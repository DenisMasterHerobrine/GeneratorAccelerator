package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * A surface rule source lowered to {@link Ir}, plus the tables its nodes index into. Built once per
 * rule source (programs are cached by identity of the source) and independent of the seed. Noises,
 * random factories and anchors are bound per chunk by {@link SurfaceEval}.
 *
 * <p>Lowering walks the source in the order vanilla's {@code RuleSource.apply} does: sequence
 * children in order, and a test's condition before its follow-up. The opaque sources are applied
 * per chunk to build vanilla rule objects. So they are applied in the same relative order.
 */
public final class SurfaceProgram {
    public final Ir.Rule root;
    /** Unknown rule and condition sources in apply order ({@code RuleSource} or {@code ConditionSource}). */
    public final List<Object> opaques;
    /** Other mods' rule and condition types evaluated by compat code ({@link SurfaceExtern}), by extern index. */
    public final List<SurfaceExtern.Rule> externRules;
    public final List<SurfaceExtern.Cond> externConds;
    /** Conditions the lowering assumed (a mod's config switch). Vanilla builds a chunk for which one no longer holds. */
    private final List<java.util.function.BooleanSupplier> guards;
    public final List<Predicate<Holder<Biome>>> biomeTests;
    public final List<ResourceKey<NormalNoise.NoiseParameters>> noiseKeys;
    public final List<SurfaceRules.VerticalGradientConditionSource> gradients;
    public final List<VerticalAnchor> anchors;
    public final int condCount;
    public final boolean hasOpaque;
    public final Interpreter interpreter;
    /** Generated evaluator, or the interpreter when compilation failed. Null until compiled. */
    private volatile Evaluator compiled;
    /** Deep-band plans, keyed by the chunk's build range and the resolved gradient and anchor heights. */
    final Map<String, DeepPlan> deepPlans = new java.util.concurrent.ConcurrentHashMap<>();

    public interface Evaluator {
        /** {@code SurfaceRule.tryApply} at the evaluation state's current block. */
        net.minecraft.world.level.block.state.BlockState apply(SurfaceEval s);
    }

    private SurfaceProgram(Lowering l, Ir.Rule root) {
        this.root = root;
        this.opaques = Collections.unmodifiableList(l.opaques);
        this.externRules = List.copyOf(l.externRules);
        this.externConds = List.copyOf(l.externConds);
        this.guards = List.copyOf(l.guards);
        this.biomeTests = Collections.unmodifiableList(l.biomeTests);
        this.noiseKeys = Collections.unmodifiableList(l.noiseKeys);
        this.gradients = Collections.unmodifiableList(l.gradients);
        this.anchors = Collections.unmodifiableList(l.anchors);
        this.condCount = l.nextId;
        this.hasOpaque = !l.opaques.isEmpty();
        this.interpreter = new Interpreter(this);
    }

    /** True while every assumption of the lowering still holds. */
    public boolean guardsHold() {
        for (java.util.function.BooleanSupplier g : this.guards) {
            if (!g.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    /** A copy with other rules over the same tables (condition ids and slots unchanged), {@link BiomeFold}. */
    private SurfaceProgram(SurfaceProgram parent, Ir.Rule root) {
        this.root = root;
        this.opaques = parent.opaques;
        this.externRules = parent.externRules;
        this.externConds = parent.externConds;
        this.guards = parent.guards;
        this.biomeTests = parent.biomeTests;
        this.noiseKeys = parent.noiseKeys;
        this.gradients = parent.gradients;
        this.anchors = parent.anchors;
        this.condCount = parent.condCount;
        this.hasOpaque = parent.hasOpaque;
        this.interpreter = new Interpreter(this);
    }

    /** Per biome met, the compiled rules with the biome conditions answered for that biome. Rebuilt when tags are bound again. */
    private volatile Map<Holder<Biome>, Evaluator> byBiome = new java.util.IdentityHashMap<>();
    private final Map<java.util.BitSet, Evaluator> byAnswers = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int byBiomeEpoch = dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch.current();
    public static final java.util.concurrent.atomic.LongAdder BIOME_PROGRAMS = new java.util.concurrent.atomic.LongAdder();
    /** Picks of a biome's program by the surface builder (counted when the block's biome changes). */
    public static final java.util.concurrent.atomic.LongAdder BIOME_PICKS = new java.util.concurrent.atomic.LongAdder();

    /**
     * The compiled rules for blocks of {@code biome}: every biome condition answered as {@code biomeTests} answer it for that
     * biome ({@link BiomeFold}). Biomes with the same answers share one program.
     */
    public Evaluator forBiome(Holder<Biome> biome) {
        BIOME_PICKS.increment();
        int epoch = dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch.current();
        Evaluator e = epoch == byBiomeEpoch ? byBiome.get(biome) : null;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            if (epoch != byBiomeEpoch) {
                // tag-based biome conditions may answer differently now
                byBiome = new java.util.IdentityHashMap<>();
                byAnswers.clear();
                byBiomeEpoch = epoch;
            }
            e = byBiome.get(biome);
            if (e != null) {
                return e;
            }
            java.util.BitSet answers = new java.util.BitSet(biomeTests.size());
            for (int slot = 0; slot < biomeTests.size(); slot++) {
                if (biomeTests.get(slot).test(biome)) {
                    answers.set(slot);
                }
            }
            e = byAnswers.get(answers);
            if (e == null) {
                e = SurfaceCompiler.compile(new SurfaceProgram(this, BiomeFold.fold(this.root, answers)));
                byAnswers.put(answers, e);
                BIOME_PROGRAMS.increment();
            }
            Map<Holder<Biome>, Evaluator> next = new java.util.IdentityHashMap<>(byBiome);
            next.put(biome, e);
            byBiome = next;
            return e;
        }
    }

    /** The compiled evaluator, generated on first use (the interpreter if generation fails). */
    public Evaluator compiled() {
        Evaluator e = compiled;
        if (e == null) {
            synchronized (this) {
                e = compiled;
                if (e == null) {
                    e = SurfaceCompiler.compile(this);
                    compiled = e;
                }
            }
        }
        return e;
    }

    public static SurfaceProgram lower(SurfaceRules.RuleSource source) {
        Lowering l = new Lowering();
        Ir.Rule root = l.rule(source);
        return new SurfaceProgram(l, root);
    }

    /** The deep-band plan for blocks in {@code [minY, maxY)} with the gradients and anchors resolved for the chunk. */
    public DeepPlan deepPlan(int minY, int maxY, int[] gradLo, int[] gradHi, int[] anchorY) {
        // A dimension's chunks nearly all resolve to the same plan. The last one is compared first, without building the key.
        LastPlan last = this.lastPlan;
        if (last != null && last.minY == minY && last.maxY == maxY && java.util.Arrays.equals(last.gradLo, gradLo) && java.util.Arrays.equals(last.gradHi, gradHi)
            && java.util.Arrays.equals(last.anchorY, anchorY)) {
            return last.plan;
        }
        String key = minY + "," + maxY + java.util.Arrays.toString(gradLo) + java.util.Arrays.toString(gradHi) + java.util.Arrays.toString(anchorY);
        DeepPlan plan = deepPlans.computeIfAbsent(key, k -> DeepPlan.build(this, minY, maxY, gradLo, gradHi, anchorY));
        this.lastPlan = new LastPlan(minY, maxY, gradLo.clone(), gradHi.clone(), anchorY.clone(), plan);
        return plan;
    }

    /** The plan {@link #deepPlan} returned last, with copies of its inputs. Replaced whole, so a reader sees one consistent plan. */
    private record LastPlan(int minY, int maxY, int[] gradLo, int[] gradHi, int[] anchorY, DeepPlan plan) {}

    private volatile LastPlan lastPlan;

    private static final class Lowering implements SurfaceExtern.Context {
        final List<Object> opaques = new ArrayList<>();
        final List<SurfaceExtern.Rule> externRules = new ArrayList<>();
        final List<SurfaceExtern.Cond> externConds = new ArrayList<>();
        final List<java.util.function.BooleanSupplier> guards = new ArrayList<>();
        final List<Predicate<Holder<Biome>>> biomeTests = new ArrayList<>();
        final Map<Object, Integer> biomeSlots = new HashMap<>();
        final List<ResourceKey<NormalNoise.NoiseParameters>> noiseKeys = new ArrayList<>();
        final Map<Object, Integer> noiseSlots = new HashMap<>();
        final List<SurfaceRules.VerticalGradientConditionSource> gradients = new ArrayList<>();
        final Map<Object, Integer> gradientSlots = new HashMap<>();
        final List<VerticalAnchor> anchors = new ArrayList<>();
        final Map<Object, Integer> anchorSlots = new HashMap<>();
        final Map<Object, Integer> condIds = new HashMap<>();
        int nextId;

        @Override
        public Ir.Rule rule(SurfaceRules.RuleSource source) {
            if (source instanceof SurfaceRules.SequenceRuleSource seq) {
                if (seq.sequence().size() == 1) {
                    return rule(seq.sequence().get(0));
                }
                List<Ir.Rule> rules = new ArrayList<>(seq.sequence().size());
                for (SurfaceRules.RuleSource child : seq.sequence()) {
                    rules.add(rule(child));
                }
                return new Ir.Seq(List.copyOf(rules));
            }
            if (source instanceof SurfaceRules.TestRuleSource test) {
                Ir.Cond cond = cond(test.ifTrue());
                Ir.Rule then = rule(test.thenRun());
                return new Ir.Test(cond, then);
            }
            if (source instanceof SurfaceRules.BlockRuleSource block) {
                return new Ir.State(block.resultState());
            }
            if (source instanceof SurfaceRules.Bandlands) {
                return new Ir.Bands();
            }
            NamespacedRules.Parts namespaced = NamespacedRules.of(source);
            if (namespaced != null) {
                return namespaced(namespaced);
            }
            List<SurfaceRules.RuleSource> sequence = ModdedRules.merged(source);
            if (sequence == null) {
                sequence = ModdedRules.references(source);
            }
            if (sequence != null) {
                // a sequence put together by a library, with vanilla's sequence semantics. One rule is itself. No rules give nothing
                if (sequence.size() == 1) {
                    return rule(sequence.get(0));
                }
                List<Ir.Rule> rules = new ArrayList<>(sequence.size());
                for (SurfaceRules.RuleSource child : sequence) {
                    rules.add(rule(child));
                }
                return new Ir.Seq(List.copyOf(rules));
            }
            ModdedRules.Config config = ModdedRules.config(source);
            if (config != null) {
                guards.add(config.unchanged());
                return rule(config.selected());
            }
            for (SurfaceExtern.RuleLowering lowering : SurfaceExtern.RULES) {
                Ir.Rule lowered = lowering.lower(source, this);
                if (lowered != null) {
                    return lowered;
                }
            }
            opaques.add(source);
            return new Ir.OpaqueRule(opaques.size() - 1);
        }

        @Override
        public Ir.Rule extern(SurfaceExtern.Rule rule) {
            externRules.add(rule);
            return new Ir.ExternRule(externRules.size() - 1, rule.positional());
        }

        @Override
        public Ir.Cond extern(SurfaceExtern.Cond cond) {
            externConds.add(cond);
            // its own id: compat code may keep state per use, as vanilla's per-application objects do
            return new Ir.ExternCond(nextId++, externConds.size() - 1, cond.scope(), cond.positional());
        }

        @Override
        public void guard(java.util.function.BooleanSupplier holds) {
            guards.add(holds);
        }

        /** TerraBlender's per-namespace rules as a sequence of biome-namespace tests ending in the base rule ({@link NamespacedRules}). */
        private Ir.Rule namespaced(NamespacedRules.Parts parts) {
            List<Ir.Rule> rules = new ArrayList<>(parts.sources().size() + 1);
            for (Map.Entry<String, SurfaceRules.RuleSource> e : parts.sources().entrySet()) {
                String namespace = e.getKey();
                String key = NamespacedRules.CLASS_NAME + ":" + namespace;
                Predicate<ResourceKey<Biome>> inNamespace = k -> k.location().getNamespace().equals(namespace);
                int slot = slot(biomeSlots, key, () -> biomeTests.add(h -> h.is(inNamespace)), biomeTests);
                Ir.Cond cond = new Ir.Biome(id(key), slot);
                rules.add(new Ir.Test(cond, rule(e.getValue())));
            }
            rules.add(rule(parts.base()));
            return new Ir.Seq(List.copyOf(rules));
        }

        @Override
        public Ir.Cond cond(SurfaceRules.ConditionSource source) {
            if (source instanceof SurfaceRules.BiomeConditionSource biome) {
                Predicate<ResourceKey<Biome>> test = biome.biomeNameTest;
                int slot = slot(biomeSlots, biome, () -> biomeTests.add(h -> h.is(test)), biomeTests);
                return new Ir.Biome(id(source), slot);
            }
            if (source instanceof SurfaceRules.NoiseThresholdConditionSource noise) {
                int slot = slot(noiseSlots, noise.noise(), () -> noiseKeys.add(noise.noise()), noiseKeys);
                return new Ir.Noise(id(source), slot, noise.minThreshold(), noise.maxThreshold());
            }
            if (source instanceof SurfaceRules.VerticalGradientConditionSource gradient) {
                int slot = slot(gradientSlots, gradient, () -> gradients.add(gradient), gradients);
                return new Ir.Gradient(id(source), slot);
            }
            if (source instanceof SurfaceRules.YConditionSource y) {
                int anchor = slot(anchorSlots, y.anchor(), () -> anchors.add(y.anchor()), anchors);
                return new Ir.YCheck(id(source), anchor, y.surfaceDepthMultiplier(), y.addStoneDepth());
            }
            if (source instanceof SurfaceRules.WaterConditionSource w) {
                return new Ir.Water(id(source), w.offset(), w.surfaceDepthMultiplier(), w.addStoneDepth());
            }
            if (source instanceof SurfaceRules.StoneDepthCheck d) {
                return new Ir.StoneDepth(id(source), d.offset(), d.addSurfaceDepth(), d.secondaryDepthRange(), d.surfaceType() == CaveSurface.CEILING);
            }
            if (source instanceof SurfaceRules.Steep) {
                return new Ir.Steep(id(source));
            }
            if (source instanceof SurfaceRules.Hole) {
                return new Ir.Hole(id(source));
            }
            if (source instanceof SurfaceRules.AbovePreliminarySurface) {
                return new Ir.AboveSurface(id(source));
            }
            if (source instanceof SurfaceRules.Temperature) {
                return new Ir.Temperature(id(source));
            }
            if (source instanceof SurfaceRules.NotConditionSource not) {
                return new Ir.Not(id(source), cond(not.target()));
            }
            HolderSet<Biome> biomes = ModdedRules.biomes(source);
            if (biomes != null) {
                int slot = slot(biomeSlots, source, () -> biomeTests.add(biomes::contains), biomeTests);
                return new Ir.Biome(id(source), slot);
            }
            net.minecraft.tags.TagKey<Biome> tag = ModdedRules.biomeTag(source);
            if (tag != null) {
                int slot = slot(biomeSlots, source, () -> biomeTests.add(h -> h.is(tag)), biomeTests);
                return new Ir.Biome(id(source), slot);
            }
            int[] slope = ModdedRules.slope(source);
            if (slope != null) {
                // every application is its own lazy object (computed at its first test in a column), so every use gets its own id
                return new Ir.Slope(nextId++, slope[0], slope[1]);
            }
            List<SurfaceRules.ConditionSource> all = ModdedRules.allOf(source);
            if (all != null) {
                return new Ir.All(nextId++, parts(all));
            }
            List<SurfaceRules.ConditionSource> any = ModdedRules.anyOf(source);
            if (any != null) {
                return new Ir.Any(nextId++, parts(any));
            }
            for (SurfaceExtern.CondLowering lowering : SurfaceExtern.CONDS) {
                Ir.Cond lowered = lowering.lower(source, this);
                if (lowered != null) {
                    return lowered;
                }
            }
            opaques.add(source);
            return new Ir.OpaqueCond(nextId++, opaques.size() - 1);
        }

        /** Conditions lowered in the order they are applied. */
        private List<Ir.Cond> parts(List<SurfaceRules.ConditionSource> sources) {
            List<Ir.Cond> parts = new ArrayList<>(sources.size());
            for (SurfaceRules.ConditionSource s : sources) {
                parts.add(cond(s));
            }
            return List.copyOf(parts);
        }

        /** Equal sources of a pure condition share an id (and hence a cached value). */
        private int id(Object source) {
            Integer id = condIds.get(source);
            if (id == null) {
                id = nextId++;
                condIds.put(source, id);
            }
            return id;
        }

        private static <T> int slot(Map<Object, Integer> slots, Object key, Runnable add, List<T> list) {
            Integer slot = slots.get(key);
            if (slot == null) {
                add.run();
                slot = list.size() - 1;
                slots.put(key, slot);
            }
            return slot;
        }
    }
}
