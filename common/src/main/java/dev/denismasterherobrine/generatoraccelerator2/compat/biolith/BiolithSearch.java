package dev.denismasterherobrine.generatoraccelerator2.compat.biolith;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeConfig;
import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateLeaf;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.biome.SearchTies;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;

/**
 * Biolith's climate search ({@code MixinSearchTree.biolith$searchTreeGet}) through GA2's flat copy of the tree
 * ({@link FlatClimateTree#searchFittest}). Biolith's biome placement runs this search for every noise biome. The search
 * returns the nearest leaf and the nearest leaf of another biome. GA2 keeps Biolith's traversal, seeding and tie rules,
 * and Biolith's result object. GA2 reads and writes Biolith's per-thread last answers the same way Biolith does. Biolith
 * walks the tree's node objects through an iterator per subtree and compares biomes by their ids. The flat copy walks
 * arrays and compares numbers.
 *
 * <p>Only applies to the metric of Biolith's own callers, vanilla's {@code Node::distance}. The metric's lambda class must
 * come from a host class in which every {@code DistanceMetric} lambda is that method. GA2 checks this from the host's
 * bytecode once per class. Anything else runs Biolith's own search: another metric, a tree whose leaves are not all
 * registered biomes, or a Biolith version with another shape. Biolith's fields and records are reached through
 * {@link BiolithAccess}.
 */
public final class BiolithSearch {
    private BiolithSearch() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static final String NODE = "net/minecraft/world/level/biome/Climate$RTree$Node";
    private static final String METRIC = "Lnet/minecraft/world/level/biome/Climate$DistanceMetric;";

    public static final LongAdder FLAT = new LongAdder();
    public static final LongAdder BIOLITH = new LongAdder();

    /** Kept on a flat tree whose leaves are not all registered biomes ({@link #keys}). */
    private static final Object NO_KEYS = new Object();

    private static final ClassValue<Boolean> VANILLA_METRIC = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaMetricClass(type);
        }
    };


    /** This thread's last answer and what it was made from ({@link #search}). */
    private static final class LastAnswer {
        Object ultimate, penultimate, shape, answer;
        long du, dp;
        int generation;
    }

    private static final ThreadLocal<LastAnswer> LAST = ThreadLocal.withInitial(LastAnswer::new);
    /** Bumped when a server stops. A thread's last answer holds that server's biomes, so it is not used or kept after that. */
    private static volatile int generation;

    public static void forgetWorlds() {
        generation++;
        leafBiomes = null;
    }

    /** Called after a datapack reload: Biolith's requests from data may have changed. */
    public static void forgetRequests() {
        leafBiomes = null;
    }

    /**
     * The biomes whose first answer's leaf Biolith's sub-biome criteria may read (its parameter ranges): those with sub-biome
     * requests, and those a replacement can turn into one, in any dimension. Read from Biolith's placements when first needed,
     * once the server runs ({@code biome.BiomeAnswers}). {@link #ANY_BIOME} when they cannot be read.
     */
    private static volatile Set<Object> leafBiomes;
    private static final Set<Object> ANY_BIOME = new HashSet<>();
    /** The End's biomes, for which Biolith's vanilla compat builds a leaf from the first answer's ranges. */
    private static final Set<Object> END_BIOMES = Set.of(Biomes.SMALL_END_ISLANDS,
        Biomes.END_BARRENS, Biomes.END_MIDLANDS, Biomes.END_HIGHLANDS);

    /** Whether Biolith may read the leaf of a first answer {@code leaf}, beyond its biome and distance. */
    static boolean firstLeafRead(Climate.RTree.Leaf<?> leaf) {
        Set<Object> biomes = leafBiomes;
        if (biomes == null) {
            Set<Object> read = BiolithAccess.subBiomeTargets();
            leafBiomes = biomes = read != null ? read : ANY_BIOME;
        }
        if (biomes == ANY_BIOME || !(leaf.value instanceof Holder<?> holder)) {
            return true;
        }
        Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
        return key.isEmpty() || biomes.contains(key.get()) || END_BIOMES.contains(key.get());
    }

    /** Biolith's answer for {@code target}, or null to run Biolith's own search. */
    public static Object search(Climate.RTree<?> tree, Climate.TargetPoint target, Climate.DistanceMetric<?> metric) {
        Object answer = flatSearch(tree, target, metric);
        if (answer == null) {
            // Biolith's own search: GA2 does not see whether its answers hold for any last answers
            SearchTies.unsure(SearchTies.UNSEEN);
        }
        return answer;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object flatSearch(Climate.RTree<?> tree, Climate.TargetPoint target, Climate.DistanceMetric<?> metric) {
        if (!BiomeConfig.flatClimateSearch() || metric == null || !VANILLA_METRIC.get(metric.getClass())) {
            return null;
        }
        BiolithAccess.Shape s = BiolithAccess.shape();
        if (s == null) {
            return null;
        }
        FlatClimateTree flat = ((Ga2ClimateTree) (Object) tree).ga2$flat();
        if (!flat.hasSubtrees()) {
            return null;
        }
        int[] keys = keys(flat);
        if (keys == null) {
            return null;
        }
        ThreadLocal[] lasts = lasts(flat, tree, s);
        ThreadLocal ultimateLast = lasts[0];
        ThreadLocal penultimateLast = lasts[1];
        Climate.RTree.Leaf<?> ultimateBefore = (Climate.RTree.Leaf<?>) ultimateLast.get();
        Climate.RTree.Leaf<?> penultimateBefore = (Climate.RTree.Leaf<?>) penultimateLast.get();
        int u = id(flat, ultimateBefore);
        int p = id(flat, penultimateBefore);
        if (u == -2 || p == -2) {
            return null;
        }
        int uId, pId;
        long uDist, pDist;
        // the GPU's two answers, in a chunk whose biome step it handed them to. They are this walk's answers when the last answers
        // are of two biomes (see FlatClimateTree.searchFittest)
        Climate.RTree.Leaf<?>[] handed = u >= 0 && p >= 0 && keys[u] == keys[p] ? null
            : BiomeHand.fittest(tree, target);
        int hu = handed == null ? -2 : id(flat, handed[0]);
        int hp = handed == null ? -2 : id(flat, handed[1]);
        boolean byGpu = hu >= 0 && hp != -2;
        if (byGpu) {
            uId = hu;
            pId = hp;
            uDist = flat.distanceTo(hu, target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness());
            pDist = hp < 0 ? Long.MAX_VALUE
                : flat.distanceTo(hp, target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness());
        } else if (FlatClimateTree.COLUMNS) {
            FlatClimateTree.Fittest r = flat.fittestColumns(target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(),
                target.weirdness(), keys, u, p);
            uId = r.ids[0];
            pId = r.ids[1];
            uDist = r.distance[0];
            pDist = r.distance[1];
        } else {
            int[] ids = new int[2];
            long[] distance = new long[2];
            flat.searchFittest(target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness(),
                keys, u, p, ids, distance);
            uId = ids[0];
            pId = ids[1];
            uDist = distance[0];
            pDist = distance[1];
        }
        // The GPU's answers are the only leaves at their distances. Others are checked while a lookup watches (biome.SearchTies).
        // Biolith's sub-biome criteria read the first answer's leaf (its parameter ranges) and the second's biome and distance.
        // Without such criteria for the first answer's biome, a tie with a leaf of the same biome gives the same answer
        if (!byGpu && SearchTies.watching()) {
            long t0 = target.temperature(), t1 = target.humidity(), t2 = target.continentalness(), t3 = target.erosion(), t4 = target.depth(), t5 = target.weirdness();
            if (!flat.alone(t0, t1, t2, t3, t4, t5, uId, uId >= 0 && !firstLeafRead(flat.leaf(uId)))) {
                SearchTies.unsure(SearchTies.BIOLITH_FIRST);
            } else if (!flat.secondAlone(t0, t1, t2, t3, t4, t5, pId, keys, keys[uId < 0 ? keys.length - 1 : uId])) {
                SearchTies.unsure(SearchTies.BIOLITH_SECOND);
            }
        }
        Climate.RTree.Leaf<?> ultimate = uId < 0 ? null : flat.leaf(uId);
        Climate.RTree.Leaf<?> penultimate = pId < 0 ? null : flat.leaf(pId);
        // Unchanged answers are common across a column's quarts. Thread-local writes cost more than the reads above.
        if (ultimate != ultimateBefore) {
            ultimateLast.set(ultimate);
        }
        if (penultimate != penultimateBefore) {
            penultimateLast.set(penultimate);
        }
        FLAT.increment();
        // Biolith's answer is an immutable record. The previous one on this thread is returned again when it holds the same leaves
        // at the same distances. That is common along a column, where the depth term often stays the same.
        LastAnswer last = LAST.get();
        long du = uDist, dp = penultimate == null ? 0 : pDist;
        int g = generation;
        if (last.generation != g) {
            last.answer = last.ultimate = last.penultimate = null;
            last.generation = g;
        }
        if (last.answer != null && last.ultimate == ultimate && last.penultimate == penultimate && last.du == du && last.dp == dp && last.shape == s) {
            return last.answer;
        }
        Object answer = penultimate == null ? s.single().make(ultimate, du) : s.pair().make(ultimate, du, penultimate, dp);
        last.ultimate = ultimate;
        last.penultimate = penultimate;
        last.du = du;
        last.dp = dp;
        last.shape = s;
        last.answer = answer;
        return answer;
    }

    /** {@code -Dgeneratoraccelerator2.verify.biomes=N}: every N-th search also runs Biolith's and compares, as for TerraBlender. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.biomes", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();

    public static boolean sampleVerify() {
        return VERIFY_EVERY > 0 && SEEN.incrementAndGet() % VERIFY_EVERY == 0;
    }

    /** The current thread's last two answers from the Biolith search on {@code tree}. Null when they cannot be read. */
    public static Object[] lastAnswers(Climate.RTree<?> tree) {
        BiolithAccess.Shape s = BiolithAccess.shape();
        if (s == null) {
            return null;
        }
        return new Object[] { s.ultimateLast(tree).get(), s.penultimateLast(tree).get() };
    }

    public static void restoreLastAnswers(Climate.RTree<?> tree, Object[] answers) {
        BiolithAccess.Shape s = BiolithAccess.shape();
        if (s == null || answers == null) {
            return;
        }
        s.ultimateLast(tree).set(answers[0]);
        s.penultimateLast(tree).set(answers[1]);
    }

    /** Compares GA2's result and last answers with Biolith's. Records compare leaves by identity and distances by value. */
    public static void compare(Object ga2, Object[] ga2Last, Object biolith, Object[] biolithLast, Climate.TargetPoint target) {
        VERIFIED.incrementAndGet();
        boolean same = Objects.equals(ga2, biolith) && ga2Last != null && biolithLast != null
            && ga2Last[0] == biolithLast[0] && ga2Last[1] == biolithLast[1];
        if (!same && DIFFERING.incrementAndGet() <= 10) {
            Ga2Log.get("Verify").warn("Biolith search mismatch at {}: GA2 {} (last {}), Biolith {} (last {})", target, ga2,
                Arrays.toString(ga2Last), biolith, Arrays.toString(biolithLast));
        }
    }

    /** The leaf's id in {@code flat}, -1 for none, -2 for a leaf of another tree. */
    private static int id(FlatClimateTree flat, Climate.RTree.Leaf<?> leaf) {
        if (leaf == null) {
            return -1;
        }
        int id = ((Ga2ClimateLeaf) (Object) leaf).ga2$index();
        return id >= 0 && id < flat.nodes() && flat.leaf(id) == leaf ? id : -2;
    }

    /**
     * Each leaf's biome as a number. Equal ids get equal numbers ({@code ResourceLocation.equals}, as Biolith compares
     * them). Index {@code [nodes]} holds the number of Biolith's placeholder for no leaf ({@code biolith:null}). Returns
     * null when a leaf is not a registered biome. Biolith's own search then runs and fails as it would without GA2.
     */
    private static int[] keys(FlatClimateTree flat) {
        Object data = flat.compatData;
        if (data instanceof int[] keys) {
            return keys;
        }
        if (data == NO_KEYS) {
            return null;
        }
        int nodes = flat.nodes();
        int[] keys = new int[nodes + 1];
        Map<ResourceLocation, Integer> numbers = new HashMap<>();
        numbers.put(ResourceLocation.fromNamespaceAndPath("biolith", "null"), 0);
        keys[nodes] = 0;
        for (int id = 0; id < nodes; id++) {
            Climate.RTree.Leaf<?> leaf = flat.leaf(id);
            if (leaf == null) {
                continue;
            }
            if (!(leaf.value instanceof Holder<?> holder)) {
                flat.compatData = NO_KEYS;
                return null;
            }
            Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
            if (key.isEmpty()) {
                flat.compatData = NO_KEYS;
                return null;
            }
            keys[id] = numbers.computeIfAbsent(key.get().location(), k -> numbers.size());
        }
        flat.compatData = keys;
        return keys;
    }

    /**
     * The tree's two thread-local last answers (Biolith's final fields on the tree), read once per tree and kept on its flat copy.
     * Reading them on every search was 0.8% of the workers' time in a 575-mod pack.
     */
    @SuppressWarnings("rawtypes")
    private static ThreadLocal[] lasts(FlatClimateTree flat, Climate.RTree<?> tree, BiolithAccess.Shape s) {
        if (flat.compatFields instanceof ThreadLocal[] kept) {
            return kept;
        }
        ThreadLocal[] read = { s.ultimateLast(tree), s.penultimateLast(tree) };
        if (s.fixed()) {
            flat.compatFields = read;
        }
        return read;
    }

    /**
     * True for a lambda class whose host class uses only vanilla's {@code Node::distance} for every {@code DistanceMetric}
     * lambda. GA2's flat copy computes that metric.
     */
    private static boolean vanillaMetricClass(Class<?> type) {
        String name = type.getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda <= 0 || !Climate.DistanceMetric.class.isAssignableFrom(type)) {
            return false;
        }
        String host = name.substring(0, lambda).replace('.', '/');
        ClassLoader loader = type.getClassLoader() != null ? type.getClassLoader() : ClassLoader.getSystemClassLoader();
        try (InputStream in = loader.getResourceAsStream(host + ".class")) {
            if (in == null) {
                return false;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int metrics = 0;
            for (MethodNode m : node.methods) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode indy && Names.mojangDesc(Type.getReturnType(indy.desc).getDescriptor()).equals(METRIC)) {
                        if (!indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || indy.bsmArgs.length < 2
                            || !(indy.bsmArgs[1] instanceof Handle h) || h.getTag() != Opcodes.H_INVOKEVIRTUAL || !Names.mojangClass(h.getOwner()).equals(NODE)
                            || !Names.mojangMethod(h.getName()).equals("distance") || !h.getDesc().equals("([J)J")) {
                            LOGGER.info("{}: non-vanilla climate distance metric -> Biolith's own search", host);
                            return false;
                        }
                        metrics++;
                    }
                }
            }
            return metrics > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
