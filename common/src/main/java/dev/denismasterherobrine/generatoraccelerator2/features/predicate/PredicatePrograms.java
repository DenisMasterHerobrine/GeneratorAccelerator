package dev.denismasterherobrine.generatoraccelerator2.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesConfig;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionReads;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import net.minecraft.world.level.material.Fluid;

/**
 * Block predicate trees ({@code all_of}, {@code any_of}, {@code not} over block tests) evaluated as one small program per
 * predicate object instead of through the vanilla objects: no iterator per combinator, nested {@code all_of} / {@code any_of}
 * flattened, no {@code BlockPos} per offset test (a generation region's reads take a reused position), a test of a fixed
 * block list as an identity check on the block, and a block read at the same offset twice in one evaluation done once.
 *
 * <p>Exactness: the program asks the same questions in the same order and stops where vanilla's short-circuits stop. When
 * nothing else changes {@code getBlockState}, a block read is the region's own read ({@code RegionReads}), which
 * {@code WorldGenRegion.getBlockState} answers with. Otherwise it is {@code getBlockState} at {@code pos.offset(offset)} as
 * vanilla calls it. A read is remembered only within one evaluation. It is forgotten once a part the program does not run
 * itself has run: any predicate it does not know, a vanilla class another mod changes, {@code would_survive},
 * {@code has_sturdy_face}, {@code inside_world_bounds}, {@code unobstructed}. Those parts are asked with the caller's
 * position, as vanilla asks them. A fixed block list ({@code HolderSet.Direct}) is checked by block identity only when
 * {@code BlockStateBase.is} and {@code HolderSet.Direct.contains} are vanilla's. Tags and named sets are asked as vanilla
 * asks them, because they can change with a datapack reload. A predicate that is not a vanilla combinator or block test is
 * asked directly.
 *
 * <p>{@code -Dgeneratoraccelerator2.predicatePrograms=false} turns it off. {@code -Dgeneratoraccelerator2.verify.predicates=N}
 * asks every N-th predicate the vanilla way too and compares.
 */
public final class PredicatePrograms {
    private PredicatePrograms() {}

    /** Not final so the tests can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.predicatePrograms"));
    public static final VerifyCounter VERIFY = new VerifyCounter("predicates");
    public static final LongAdder COMPILED = new LongAdder();
    public static final LongAdder OPAQUE_ROOTS = new LongAdder();

    /** Stored in a predicate whose program would only ask the predicate itself. */
    private static final Object NONE = new Object();

    /** The vanilla classes the compiler knows, by name (most are package-private). */
    private static final String PKG = "net.minecraft.world.level.levelgen.blockpredicates.";

    /** A {@code block_predicate_filter} that keeps its predicate's program ({@code BlockPredicateFilterPredicateMixin}). */
    public interface FilterProgram {
        boolean ga2$test(WorldGenLevel level, BlockPos pos);
    }

    /** The parts of the vanilla predicate classes the compiler reads ({@code BlockPredicateAccessors}). */
    public interface Combining {
        List<BlockPredicate> ga2$predicates();
    }

    public interface Negating {
        BlockPredicate ga2$predicate();
    }

    public interface StateTesting {
        Vec3i ga2$offset();
    }

    public interface MatchingBlocks {
        HolderSet<Block> ga2$blocks();
    }

    public interface MatchingFluids {
        HolderSet<Fluid> ga2$fluids();
    }

    public interface MatchingTag {
        TagKey<Block> ga2$tag();
    }

    public static boolean enabled() {
        return ENABLED && FeaturesConfig.enabled() && FeaturesConfig.predicatePrograms();
    }

    /**
     * The program for {@code predicate}: a {@link Program}, or a marker meaning "ask the predicate". The caller (the filter
     * or scan that asks the predicate) keeps it in a field of its own. The config is read here, once per caller, not on every
     * call. Callers keep the program, rather than the predicate object keeping it, so the hot path has no type check against
     * an interface. On Java 21, a check against an interface many classes are asked about costs more than a small
     * predicate's whole test.
     */
    public static Object programFor(BlockPredicate predicate) {
        if (!enabled()) {
            return NONE;
        }
        return COMPILE_AFTER <= 0 ? compile(predicate) : new Lazy();
    }

    /**
     * How often a predicate is asked before its program is made ({@code -Dgeneratoraccelerator2.predicatePrograms.after}, 0 makes
     * it at the first call). Each program is a generated class, which the JIT then compiles once it is hot. A modded pack has
     * thousands of placed features, most asked a few times a minute; their classes cost compiler time (a whole core for the first
     * minute on a four-core machine) for a gain of a few nanoseconds per test. Until then the predicate is asked itself.
     */
    public static int COMPILE_AFTER = Integer.getInteger("generatoraccelerator2.predicatePrograms.after", 1024); // not final for the tests

    /** True once {@code program} (from {@link #programFor}) runs its own code: made at once, or a lazy one asked often enough. */
    public static boolean made(Object program) {
        return program instanceof Program || (program instanceof Lazy lazy && lazy.program != null);
    }

    /** A program not made yet: the caller keeps this and the predicate answers itself until it has been asked {@link #COMPILE_AFTER} times. */
    static final class Lazy {
        int calls;
        volatile Object program;
    }

    /** The answer of {@code predicate.test(level, pos)}, through {@code program} (from {@link #programFor} for this predicate). */
    public static boolean test(Object program, BlockPredicate predicate, WorldGenLevel level, BlockPos pos) {
        if (!(program instanceof Program compiled)) {
            if (program instanceof Lazy lazy) {
                Object made = lazy.program;
                if (made == null) {
                    // racy count: a second thread may compile too, and both get the same program
                    if (++lazy.calls < COMPILE_AFTER) {
                        return predicate.test(level, pos);
                    }
                    synchronized (lazy) {
                        made = lazy.program;
                        if (made == null) {
                            made = compile(predicate);
                            lazy.program = made;
                        }
                    }
                }
                return test(made, predicate, level, pos);
            }
            return predicate.test(level, pos);
        }
        boolean answer = compiled.run(level, pos);
        if (VERIFY.sample()) {
            boolean vanilla = predicate.test(level, pos);
            VERIFY.result(vanilla == answer, pos, vanilla, answer);
            return vanilla;
        }
        return answer;
    }

    // ---- compiling

    /** Unchanged by other mods: the class's own {@code test} (and for block tests, {@code StateTestingPredicate.test}). */
    private static boolean own(Class<?> cls) {
        return Names.mojangBinaryClass(cls.getName()).startsWith(PKG) && FeatureTargets.allowed(predicateKey(cls.getName()));
    }

    /** {@code blockPredicate.<simple name>}, from a class or internal name (by its Mojang name, {@link Names}). */
    public static String predicateKey(String className) {
        String name = Names.mojangClass(className.replace('.', '/')).replace('/', '.');
        return "blockPredicate." + name.substring(name.lastIndexOf('.') + 1);
    }

    static Object compile(BlockPredicate root) {
        Compiler c = new Compiler();
        Node node = c.node(root);
        if (node instanceof Opaque) {
            OPAQUE_ROOTS.increment();
            return NONE;
        }
        COMPILED.increment();
        return new Program(node, c.assignSlots());
    }

    private static final class Compiler {
        /** Leaves reading a block, by offset: offsets read by two or more get a memo slot. */
        private final Map<Long, List<Leaf>> byOffset = new HashMap<>();

        Node node(BlockPredicate p) {
            Class<?> cls = p.getClass();
            String name = Names.mojangBinaryClass(cls.getName());
            if (!own(cls)) {
                return new Opaque(p);
            }
            switch (name.substring(PKG.length())) {
                case "AllOfPredicate", "AnyOfPredicate" -> {
                    boolean all = name.endsWith("AllOfPredicate");
                    List<Node> kids = new ArrayList<>();
                    flatten(p, cls, kids);
                    Node[] array = kids.toArray(new Node[0]);
                    return all ? new All(array) : new Any(array);
                }
                case "NotPredicate" -> {
                    return new Not(node(((Negating) p).ga2$predicate()));
                }
                case "TrueBlockPredicate" -> {
                    return True.INSTANCE;
                }
                default -> {
                }
            }
            if (p instanceof net.minecraft.world.level.levelgen.blockpredicates.HasSturdyFacePredicate h) {
                return new Sturdy(h.offset.getX(), h.offset.getY(), h.offset.getZ(), h.direction);
            }
            if (p instanceof StateTestingPredicate && own(StateTestingPredicate.class)) {
                Vec3i offset = ((StateTesting) p).ga2$offset();
                Leaf leaf = leaf(p, name.substring(PKG.length()), offset);
                if (leaf != null) {
                    long key = BlockPos.asLong(offset.getX(), offset.getY(), offset.getZ());
                    byOffset.computeIfAbsent(key, k -> new ArrayList<>()).add(leaf);
                    return leaf;
                }
            }
            return new Opaque(p);
        }

        /** The children of a combinator, with nested combinators of the same kind (also unchanged) spliced in, in order. */
        private void flatten(BlockPredicate p, Class<?> kind, List<Node> out) {
            for (BlockPredicate child : ((Combining) p).ga2$predicates()) {
                if (child.getClass() == kind && own(kind)) {
                    flatten(child, kind, out);
                } else {
                    out.add(node(child));
                }
            }
        }

        @Nullable
        private Leaf leaf(BlockPredicate p, String simpleName, Vec3i o) {
            int dx = o.getX(), dy = o.getY(), dz = o.getZ();
            return switch (simpleName) {
                case "MatchingBlocksPredicate" -> blocks(((MatchingBlocks) p).ga2$blocks(), dx, dy, dz);
                case "MatchingBlockTagPredicate" -> new InTag(dx, dy, dz, ((MatchingTag) p).ga2$tag());
                case "MatchingFluidsPredicate" -> new InFluids(dx, dy, dz, ((MatchingFluids) p).ga2$fluids());
                case "SolidPredicate" -> new Solid(dx, dy, dz);
                case "ReplaceablePredicate" -> new Replaceable(dx, dy, dz);
                default -> null;
            };
        }

        private Leaf blocks(HolderSet<Block> set, int dx, int dy, int dz) {
            if (set.getClass() == HolderSet.Direct.class && FeatureTargets.allowed(STATE_IS)) {
                // is(set) == set.contains(block.builtInRegistryHolder()), by identity of the holder: the blocks whose own holder is listed
                List<Block> blocks = new ArrayList<>();
                try {
                    for (Holder<Block> h : set) {
                        if (h instanceof Holder.Reference<Block> ref && ref.isBound() && ref.value().builtInRegistryHolder() == ref) {
                            if (!blocks.contains(ref.value())) {
                                blocks.add(ref.value());
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    return new InSet(dx, dy, dz, set);
                }
                if (blocks.size() == 1) {
                    return new OneBlock(dx, dy, dz, blocks.get(0));
                }
                return blocks.size() <= 8 ? new FewBlocks(dx, dy, dz, blocks.toArray(new Block[0])) : new ManyBlocks(dx, dy, dz, new ReferenceOpenHashSet<>(blocks));
            }
            return new InSet(dx, dy, dz, set);
        }

        int assignSlots() {
            int slots = 0;
            for (List<Leaf> leaves : byOffset.values()) {
                if (leaves.size() > 1) {
                    for (Leaf leaf : leaves) {
                        leaf.slot = slots;
                    }
                    slots++;
                }
            }
            return slots;
        }
    }

    /** Key of {@code BlockStateBase.is(HolderSet)} and {@code HolderSet.Direct.contains}. */
    public static final String STATE_IS = "blockPredicate.stateIs";

    // ---- running

    /** A program as one generated class ({@link PredicateCodegen}). */
    public interface Compiled {
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads);
    }

    /** Programs compiled, generated as classes, and generations that failed (with the last failure), for stats and tests. */
    public static String codegenStats() {
        Throwable t = PredicateCodegen.lastFailure;
        return "programs " + COMPILED.sum() + ", as classes " + PredicateCodegen.GENERATED.sum() + ", generation failed " + PredicateCodegen.FAILED.sum()
            + (t != null ? " (last: " + t + ")" : "");
    }

    /** {@code -Dgeneratoraccelerator2.predicatePrograms.codegen=false}: programs run as trees of node objects. */
    static final boolean CODEGEN = !"false".equals(System.getProperty("generatoraccelerator2.predicatePrograms.codegen"));

    static final class Program {
        final Node root;
        final int slots;
        @Nullable final Compiled code;

        Program(Node root, int slots) {
            this.root = root;
            this.slots = slots;
            this.code = CODEGEN ? PredicateCodegen.generate(root, slots) : null;
        }

        boolean run(WorldGenLevel level, BlockPos pos) {
            RegionReads reads = level instanceof Ga2Region region && REGION_READS_UNCHANGED.allowed() ? region.ga2$reads() : null;
            Compiled c = this.code;
            if (c != null) {
                return c.test(level, pos, reads);
            }
            return this.root.test(level, pos, reads, this.slots > 0 ? new Memo(this.slots) : null);
        }
    }

    /** {@code StateTestingPredicate.test}'s read, {@code level.getBlockState(origin.offset(dx, dy, dz))}. Called by generated programs. */
    public static BlockState read(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, int dx, int dy, int dz) {
        BlockState state = reads != null ? reads.readInSection(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz) : null;
        return state != null ? state : level.getBlockState(origin.offset(dx, dy, dz));
    }

    /**
     * True when nothing else changes {@code WorldGenRegion.getBlockState}: the region's reads are then what the call answers,
     * other mods' code included (there is none).
     */
    private static final FeatureTargets.Hook REGION_READS_UNCHANGED = FeatureTargets.hook(FeatureTargets.REGION_READS_UNCHANGED);

    /** The block reads of one evaluation at offsets read more than once, until code the program does not run itself runs. */
    static final class Memo {
        final BlockState[] states;

        Memo(int slots) {
            this.states = new BlockState[slots];
        }

        void forget() {
            java.util.Arrays.fill(this.states, null);
        }
    }

    abstract static class Node {
        abstract boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo);
    }

    static final class All extends Node {
        final Node[] kids;

        All(Node[] kids) {
            this.kids = kids;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            for (Node n : this.kids) {
                if (!n.test(level, origin, reads, memo)) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class Any extends Node {
        final Node[] kids;

        Any(Node[] kids) {
            this.kids = kids;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            for (Node n : this.kids) {
                if (n.test(level, origin, reads, memo)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Not extends Node {
        final Node kid;

        Not(Node kid) {
            this.kid = kid;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return !this.kid.test(level, origin, reads, memo);
        }
    }

    static final class True extends Node {
        static final True INSTANCE = new True();

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return true;
        }
    }

    /** A predicate the program does not run itself: asked with the caller's level and position. */
    static final class Opaque extends Node {
        final BlockPredicate predicate;

        Opaque(BlockPredicate predicate) {
            this.predicate = predicate;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            if (memo != null) {
                memo.forget();
            }
            boolean answer = this.predicate.test(level, origin);
            if (memo != null) {
                memo.forget();
            }
            return answer;
        }
    }

    /** {@code has_sturdy_face}: the state at the offset and its face, read through the thread's position instead of a new one. */
    static final class Sturdy extends Node {
        final int dx, dy, dz;
        final net.minecraft.core.Direction direction;

        Sturdy(int dx, int dy, int dz, net.minecraft.core.Direction direction) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.direction = direction;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return sturdy(level, origin, this.dx, this.dy, this.dz, this.direction);
        }
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> STURDY_AT = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * {@code HasSturdyFacePredicate.test}: {@code level.getBlockState(p).isFaceSturdy(level, p, direction)} at {@code origin} plus the
     * offset. The face test keeps no position, so the thread's mutable one stands in for vanilla's new one.
     */
    public static boolean sturdy(WorldGenLevel level, BlockPos origin, int dx, int dy, int dz, net.minecraft.core.Direction direction) {
        BlockPos.MutableBlockPos at = STURDY_AT.get().set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
        return level.getBlockState(at).isFaceSturdy(level, at, direction);
    }

    abstract static class Leaf extends Node {
        final int dx, dy, dz;
        int slot = -1;

        Leaf(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        /** {@code StateTestingPredicate.test}'s read: {@code level.getBlockState(pos.offset(offset))}. */
        final BlockState state(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            int slot = this.slot;
            if (slot >= 0) {
                BlockState known = memo.states[slot];
                if (known != null) {
                    return known;
                }
            }
            BlockState state = reads != null ? reads.readInSection(origin.getX() + this.dx, origin.getY() + this.dy, origin.getZ() + this.dz) : null;
            if (state == null) {
                state = level.getBlockState(origin.offset(this.dx, this.dy, this.dz));
            }
            if (slot >= 0) {
                memo.states[slot] = state;
            }
            return state;
        }
    }

    static final class OneBlock extends Leaf {
        final Block block;

        OneBlock(int dx, int dy, int dz, Block block) {
            super(dx, dy, dz);
            this.block = block;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).getBlock() == this.block;
        }
    }

    static final class FewBlocks extends Leaf {
        final Block[] blocks;

        FewBlocks(int dx, int dy, int dz, Block[] blocks) {
            super(dx, dy, dz);
            this.blocks = blocks;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            Block b = state(level, origin, reads, memo).getBlock();
            for (Block x : this.blocks) {
                if (x == b) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class ManyBlocks extends Leaf {
        final ReferenceOpenHashSet<Block> blocks;

        ManyBlocks(int dx, int dy, int dz, ReferenceOpenHashSet<Block> blocks) {
            super(dx, dy, dz);
            this.blocks = blocks;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return this.blocks.contains(state(level, origin, reads, memo).getBlock());
        }
    }

    static final class InSet extends Leaf {
        final HolderSet<Block> set;

        InSet(int dx, int dy, int dz, HolderSet<Block> set) {
            super(dx, dy, dz);
            this.set = set;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).is(this.set);
        }
    }

    static final class InTag extends Leaf {
        final TagKey<Block> tag;

        InTag(int dx, int dy, int dz, TagKey<Block> tag) {
            super(dx, dy, dz);
            this.tag = tag;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).is(this.tag);
        }
    }

    static final class InFluids extends Leaf {
        final HolderSet<Fluid> fluids;

        InFluids(int dx, int dy, int dz, HolderSet<Fluid> fluids) {
            super(dx, dy, dz);
            this.fluids = fluids;
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).getFluidState().is(this.fluids);
        }
    }

    static final class Solid extends Leaf {
        Solid(int dx, int dy, int dz) {
            super(dx, dy, dz);
        }

        @SuppressWarnings("deprecation")
        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).isSolid();
        }
    }

    static final class Replaceable extends Leaf {
        Replaceable(int dx, int dy, int dz) {
            super(dx, dy, dz);
        }

        @Override
        boolean test(WorldGenLevel level, BlockPos origin, @Nullable RegionReads reads, @Nullable Memo memo) {
            return state(level, origin, reads, memo).canBeReplaced();
        }
    }
}
