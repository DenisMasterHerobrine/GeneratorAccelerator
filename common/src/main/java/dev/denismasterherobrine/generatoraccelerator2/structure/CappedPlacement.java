package dev.denismasterherobrine.generatoraccelerator2.structure;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.CappedProcessorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.ProcessorRuleAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.RuleProcessorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlackstoneReplaceProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockAgeProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LavaSubmergedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.NopProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProtectedBlockProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * The template clip ({@link TemplatePlacement}) for processor lists with vanilla's {@code CappedProcessor}: archaeology in trail
 * ruins, ancient city overhauls and ruins of other mods.
 *
 * <p>A capped processor changes a sampled number of blocks once every block is processed. It shuffles the indices of the whole
 * processed list with a random from the world seed and the placement's position, and runs its delegate on them in that order
 * until enough blocks changed. Which blocks of a chunk change therefore depends on the whole template: how many blocks survived
 * the processors, and which of them the delegate changed before the cap was reached.
 *
 * <p>So only that is worked out for the blocks outside the box. Whether a block survives is asked from the processors that can
 * drop blocks (rot, protected blocks, ignored blocks, jigsaw replacement), called with a stand-in holding the block's position
 * and template state. The capped pass is vanilla's, transcribed. A block it visits is processed in full only when the delegate
 * could change it. A rule delegate cannot when none of its rules' input tests matches a state the list can turn the template
 * state into. The blocks inside the box are processed in full, in vanilla's order, and come out as vanilla's.
 *
 * <p>Taken are lists of vanilla's own processors without gravity, where ignored blocks and jigsaw replacement come before any
 * processor that changes states. Other mods' processors are taken when they hand every block back unchanged
 * ({@link TemplatePlacement.Kind#unchanged}), as Lithostitched's empty hook lists on ocean ruins and the like do. Another
 * mod's change to {@code CappedProcessor.finalizeProcessing} keeps vanilla's path ({@link HookTargets#STRUCTURE_TEMPLATE_CAPPED}).
 */
final class CappedPlacement {
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_TEMPLATE_CAPPED);

    /** Processors that never drop a block. */
    private static final Set<Class<?>> KEEPS = Set.of(RuleProcessor.class, NopProcessor.class, LavaSubmergedBlockProcessor.class,
        BlackstoneReplaceProcessor.class, BlockAgeProcessor.class);
    /** Processors that drop a block by its template state and position alone. */
    private static final Set<Class<?>> DROPS = Set.of(BlockRotProcessor.class, ProtectedBlockProcessor.class);
    /** Processors that drop a block by its current state, taken only before any state changes. */
    private static final Set<Class<?>> DROPS_BY_STATE = Set.of(BlockIgnoreProcessor.class, JigsawReplacementProcessor.class);
    private static final Set<Class<?>> CHANGES_STATE = Set.of(RuleProcessor.class, LavaSubmergedBlockProcessor.class, BlackstoneReplaceProcessor.class,
        BlockAgeProcessor.class, JigsawReplacementProcessor.class);
    /** Vanilla's input tests that can match a state for some draw exactly when they match it with a random that draws 0. */
    private static final Set<Class<?>> STATE_TESTS = Set.of(AlwaysTrueTest.class, BlockMatchTest.class, BlockStateMatchTest.class, TagMatchTest.class,
        RandomBlockMatchTest.class, RandomBlockStateMatchTest.class);

    private final ServerLevelAccessor level;
    private final BlockPos offset;
    private final BlockPos pivot;
    private final StructurePlaceSettings settings;
    private final List<StructureTemplate.StructureBlockInfo> blocks;
    @Nullable
    private final StructureTemplate template;
    private final List<StructureProcessor> processors;
    /** Per surviving block: its template index, its position, and its processed block once known. */
    private final int[] at;
    private final BlockPos[] pos;
    private final StructureTemplate.StructureBlockInfo[] values;
    private int count;
    private int processedCount;
    /** States the list can turn a template state into, or null for any. */
    private final Map<BlockState, Set<BlockState>> reach = new IdentityHashMap<>();

    private CappedPlacement(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                            List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template) {
        this.level = level;
        this.offset = offset;
        this.pivot = pivot;
        this.settings = settings;
        this.blocks = blocks;
        this.template = template;
        this.processors = settings.getProcessors();
        this.at = new int[blocks.size()];
        this.pos = new BlockPos[blocks.size()];
        this.values = new StructureTemplate.StructureBlockInfo[blocks.size()];
    }

    /** The clipped result, or null when the call runs as vanilla's. The caller has checked every processor's kind. */
    @Nullable
    static List<StructureTemplate.StructureBlockInfo> clipped(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                              List<StructureTemplate.StructureBlockInfo> blocks, @Nullable StructureTemplate template,
                                                              BoundingBox box) {
        if (!HOOK.allowed()) {
            return TemplatePlacement.standDown("CappedProcessor changed by another mod");
        }
        List<StructureProcessor> processors = settings.getProcessors();
        int[] drops = new int[processors.size()];
        int dropCount = 0;
        boolean changed = false;
        boolean jigsaw = false;
        for (int n = 0; n < processors.size(); n++) {
            Class<?> type = processors.get(n).getClass();
            if (DROPS_BY_STATE.contains(type)) {
                if (changed) {
                    return TemplatePlacement.standDown("CappedProcessor with " + type.getSimpleName() + " after a state change");
                }
                drops[dropCount++] = n;
                jigsaw |= type == JigsawReplacementProcessor.class;
            } else if (DROPS.contains(type)) {
                drops[dropCount++] = n;
            } else if (type != CappedProcessor.class && !KEEPS.contains(type) && !unchanged(processors.get(n), level)) {
                return TemplatePlacement.standDown("CappedProcessor with " + type.getName());
            }
            changed |= CHANGES_STATE.contains(type);
        }
        CappedPlacement run = new CappedPlacement(level, offset, pivot, settings, blocks, template);
        run.survivors(drops, dropCount, jigsaw);
        List<StructureTemplate.StructureBlockInfo> placed = run.cap() ? run.inside(box) : null;
        if (placed == null) {
            // a block the drop test kept came out dropped
            return TemplatePlacement.standDown("CappedProcessor drop test missed");
        }
        TemplatePlacement.CLIPPED.increment();
        TemplatePlacement.CAPPED.increment();
        TemplatePlacement.SKIPPED.add(blocks.size() - run.processedCount);
        TemplatePlacement.PROCESSED.add(run.processedCount);
        return placed;
    }

    /** True for another mod's processor that hands every block back as it gets it. */
    private static boolean unchanged(StructureProcessor processor, ServerLevelAccessor level) {
        TemplatePlacement.Kind kind = TemplatePlacement.kind(processor, level.registryAccess());
        return kind != null && kind.unchanged();
    }

    /** The blocks that survive the list, in template order. */
    private void survivors(int[] drops, int dropCount, boolean jigsaw) {
        Mirror mirror = this.settings.getMirror();
        Rotation rotation = this.settings.getRotation();
        BlockPos rotationPivot = this.settings.getRotationPivot();
        int n = 0;
        for (int b = 0; b < this.blocks.size(); b++) {
            StructureTemplate.StructureBlockInfo info = this.blocks.get(b);
            BlockPos p = BlockPos.of(TemplatePlacement.place(info.pos(), mirror, rotation, rotationPivot, this.offset));
            if (dropCount > 0) {
                // jigsaw replacement reads the final state from the block's NBT
                CompoundTag nbt = jigsaw && info.nbt() != null && info.state().is(Blocks.JIGSAW) ? info.nbt().copy() : null;
                StructureTemplate.StructureBlockInfo stand = new StructureTemplate.StructureBlockInfo(p, info.state(), nbt);
                boolean kept = true;
                for (int d = 0; kept && d < dropCount; d++) {
                    kept = PlatformHooks.processTemplateBlock(this.processors.get(drops[d]), this.level, this.offset, this.pivot, info, stand, this.settings,
                        this.template) != null;
                }
                if (!kept) {
                    continue;
                }
            }
            this.at[n] = b;
            this.pos[n] = p;
            n++;
        }
        this.count = n;
    }

    /** Surviving block {@code i} through the whole list, as vanilla's per-block loop processes it. Null when the list drops it. */
    @Nullable
    private StructureTemplate.StructureBlockInfo process(int i) {
        StructureTemplate.StructureBlockInfo info = this.blocks.get(this.at[i]);
        StructureTemplate.StructureBlockInfo current = new StructureTemplate.StructureBlockInfo(this.pos[i], info.state(),
            info.nbt() != null ? info.nbt().copy() : null);
        for (int n = 0, size = this.processors.size(); current != null && n < size; n++) {
            current = PlatformHooks.processTemplateBlock(this.processors.get(n), this.level, this.offset, this.pivot, info, current, this.settings, this.template);
        }
        this.processedCount++;
        return current;
    }

    /** The capped processors' finalize passes in list order. False when a block came out dropped. */
    private boolean cap() {
        boolean replacedAny = false;
        for (StructureProcessor processor : this.processors) {
            if (processor.getClass() != CappedProcessor.class) {
                continue;
            }
            CappedProcessorAccessor capped = (CappedProcessorAccessor) processor;
            IntProvider limit = capped.ga2$limit();
            if (limit.maxInclusive() == 0 || this.count == 0) {
                continue;
            }
            StructureProcessor delegate = capped.ga2$delegate();
            List<ProcessorRule> rules = delegate.getClass() == RuleProcessor.class ? ((RuleProcessorAccessor) delegate).ga2$rules() : null;
            Map<BlockState, Boolean> mayChange = new IdentityHashMap<>();
            if (rules != null && !replacedAny && !this.anyMayChange(rules, mayChange)) {
                // every visit would hand the block back
                continue;
            }
            // CappedProcessor.finalizeProcessing
            RandomSource random = RandomSource.createThreadLocalInstance(this.level.getLevel().getSeed()).forkPositional().at(this.offset);
            int maxToReplace = Math.min(limit.sample(random), this.count);
            if (maxToReplace < 1) {
                continue;
            }
            IntArrayList indices = Util.toShuffledList(IntStream.range(0, this.count), random);
            int replaced = 0;
            for (int k = 0; k < this.count && replaced < maxToReplace; k++) {
                int i = indices.getInt(k);
                StructureTemplate.StructureBlockInfo original = this.blocks.get(this.at[i]);
                StructureTemplate.StructureBlockInfo processed = this.values[i];
                if (processed == null) {
                    if (rules != null && !this.mayChange(original.state(), rules, mayChange)) {
                        continue;
                    }
                    processed = this.process(i);
                    if (processed == null) {
                        return false;
                    }
                    this.values[i] = processed;
                }
                StructureTemplate.StructureBlockInfo maybeAltered = delegate.processBlock(this.level, this.offset, this.pivot, original, processed, this.settings);
                if (maybeAltered != null && !processed.equals(maybeAltered)) {
                    replaced++;
                    this.values[i] = maybeAltered;
                    replacedAny = true;
                }
            }
        }
        return true;
    }

    /** The processed blocks inside the box, in list order, or null when a block came out dropped. */
    @Nullable
    private List<StructureTemplate.StructureBlockInfo> inside(BoundingBox box) {
        List<StructureTemplate.StructureBlockInfo> placed = new ArrayList<>();
        for (int i = 0; i < this.count; i++) {
            StructureTemplate.StructureBlockInfo info = this.values[i];
            if (info == null) {
                if (!box.isInside(this.pos[i])) {
                    continue;
                }
                info = this.process(i);
                if (info == null) {
                    return null;
                }
            } else if (!box.isInside(info.pos())) {
                continue;
            }
            placed.add(info);
        }
        return placed;
    }

    private boolean anyMayChange(List<ProcessorRule> rules, Map<BlockState, Boolean> mayChange) {
        Set<BlockState> seen = new ReferenceOpenHashSet<>();
        for (int i = 0; i < this.count; i++) {
            BlockState state = this.blocks.get(this.at[i]).state();
            if (seen.add(state) && this.mayChange(state, rules, mayChange)) {
                return true;
            }
        }
        return false;
    }

    /** False when no rule of the delegate can match any state the list can turn {@code original} into. */
    private boolean mayChange(BlockState original, List<ProcessorRule> rules, Map<BlockState, Boolean> memo) {
        Boolean known = memo.get(original);
        if (known == null) {
            Set<BlockState> states = this.reach(original);
            known = states == null || anyMatches(rules, states);
            memo.put(original, known);
        }
        return known;
    }

    @Nullable
    private Set<BlockState> reach(BlockState original) {
        if (this.reach.containsKey(original)) {
            return this.reach.get(original);
        }
        Set<BlockState> states = new ReferenceOpenHashSet<>();
        states.add(original);
        for (StructureProcessor processor : this.processors) {
            Class<?> type = processor.getClass();
            if (type == RuleProcessor.class) {
                List<BlockState> outputs = new ArrayList<>();
                for (ProcessorRule rule : ((RuleProcessorAccessor) processor).ga2$rules()) {
                    if (matches(rule, states)) {
                        outputs.add(rule.getOutputState());
                    }
                }
                states.addAll(outputs);
            } else if (type == JigsawReplacementProcessor.class) {
                if (states.stream().anyMatch(s -> s.is(Blocks.JIGSAW))) {
                    states = null;
                    break;
                }
            } else if (CHANGES_STATE.contains(type)) {
                states = null;
                break;
            }
        }
        this.reach.put(original, states);
        return states;
    }

    private static boolean anyMatches(List<ProcessorRule> rules, Set<BlockState> states) {
        for (ProcessorRule rule : rules) {
            if (matches(rule, states)) {
                return true;
            }
        }
        return false;
    }

    /** True when the rule's input test can match one of the states. */
    private static boolean matches(ProcessorRule rule, Set<BlockState> states) {
        RuleTest input = ((ProcessorRuleAccessor) rule).ga2$inputPredicate();
        if (!STATE_TESTS.contains(input.getClass())) {
            return true;
        }
        for (BlockState state : states) {
            if (input.test(state, ZeroRandom.INSTANCE)) {
                return true;
            }
        }
        return false;
    }

    /** Draws 0 every time: a random input test then matches exactly the states it can match at all. */
    private static final class ZeroRandom implements RandomSource {
        static final ZeroRandom INSTANCE = new ZeroRandom();

        @Override
        public RandomSource fork() {
            return this;
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            throw new UnsupportedOperationException("forkPositional");
        }

        @Override
        public void setSeed(long seed) {
        }

        @Override
        public int nextInt() {
            return 0;
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public boolean nextBoolean() {
            return false;
        }

        @Override
        public float nextFloat() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0;
        }

        @Override
        public double nextGaussian() {
            return 0;
        }
    }
}
