package dev.denismasterherobrine.generatoraccelerator2.compat.biolith;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

/**
 * The parts of Biolith that {@link BiolithSearch} uses, found once by name. GA2 does not compile against Biolith. Fields
 * are read through var handles. Biolith's answer records come from factories that LambdaMetafactory binds to the record's
 * constructors. Once compiled, a factory call is a plain constructor call. Names and types match Biolith 3.0.0 to 3.0.14.
 * Anything else leaves Biolith's own search in place.
 */
final class BiolithAccess {
    private BiolithAccess() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static final String IMPL = "com.terraformersmc.biolith.impl.biome.";
    private static final String NODES = "com.terraformersmc.biolith.api.biome.BiolithFittestNodes";
    /** Biolith's placement per dimension: the coordinator's field and the placement class. */
    private static final String[][] PLACEMENTS = {
        { "OVERWORLD", "OverworldBiomePlacement" }, { "NETHER", "NetherBiomePlacement" }, { "END", "EndBiomePlacement" } };

    /** Makes Biolith's answer for a search that found one leaf. */
    interface Single {
        Object make(Climate.RTree.Leaf<?> ultimate, long ultimateDistance);
    }

    /** Makes Biolith's answer for a search that found two leaves. */
    interface Pair {
        Object make(Climate.RTree.Leaf<?> ultimate, long ultimateDistance, Climate.RTree.Leaf<?> penultimate, long penultimateDistance);
    }

    /**
     * The two thread-local last answers that Biolith adds to the climate tree, and its answer factories. {@code fixed} is true
     * when both fields are final. A tree's thread-locals can then be kept.
     */
    record Shape(VarHandle ultimate, VarHandle penultimate, boolean fixed, Single single, Pair pair) {
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> ultimateLast(Climate.RTree<?> tree) {
            return (ThreadLocal<Object>) ultimate.get(tree);
        }

        @SuppressWarnings("unchecked")
        ThreadLocal<Object> penultimateLast(Climate.RTree<?> tree) {
            return (ThreadLocal<Object>) penultimate.get(tree);
        }
    }

    private static volatile Object shape;
    private static final Object NO_SHAPE = new Object();

    /** The loaded Biolith's shape, or null when it does not fit. */
    @Nullable
    static Shape shape() {
        Object s = shape;
        if (s == null) {
            synchronized (BiolithAccess.class) {
                s = shape;
                if (s == null) {
                    s = resolve();
                    shape = s;
                }
            }
        }
        return s instanceof Shape found ? found : null;
    }

    private static Object resolve() {
        try {
            MethodHandles.Lookup tree = MethodHandles.privateLookupIn(Climate.RTree.class, MethodHandles.lookup());
            VarHandle ultimate = tree.findVarHandle(Climate.RTree.class, "previousUltimateNode", ThreadLocal.class);
            VarHandle penultimate = tree.findVarHandle(Climate.RTree.class, "previousPenultimateNode", ThreadLocal.class);
            // The var handle of a final field cannot set it
            boolean fixed = !ultimate.isAccessModeSupported(VarHandle.AccessMode.SET) && !penultimate.isAccessModeSupported(VarHandle.AccessMode.SET);
            Class<?> nodes = Class.forName(NODES, false, Climate.RTree.class.getClassLoader());
            Single single = (Single) factory(Single.class, nodes, MethodType.methodType(Object.class, Climate.RTree.Leaf.class, long.class));
            Pair pair = (Pair) factory(Pair.class, nodes, MethodType.methodType(Object.class, Climate.RTree.Leaf.class, long.class, Climate.RTree.Leaf.class, long.class));
            return new Shape(ultimate, penultimate, fixed, single, pair);
        } catch (Throwable t) {
            LOGGER.warn("Biolith climate search: unknown shape ({}) -> Biolith's own search", t.toString());
            return NO_SHAPE;
        }
    }

    /** An implementation of {@code type} whose {@code make} calls the constructor of {@code nodes} with {@code made}'s parameters. */
    private static Object factory(Class<?> type, Class<?> nodes, MethodType made) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle constructor = lookup.findConstructor(nodes, made.changeReturnType(void.class));
        CallSite site = LambdaMetafactory.metafactory(lookup, "make", MethodType.methodType(type), made, constructor, constructor.type());
        return site.getTarget().invoke();
    }

    /**
     * The biomes with sub-biome requests, and those a replacement can turn into one, in any dimension. Read from Biolith's
     * placements. Null when they cannot be read.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static Set<Object> subBiomeTargets() {
        try {
            ClassLoader loader = Climate.class.getClassLoader();
            Class<?> coordinator = Class.forName(IMPL + "BiomeCoordinator", false, loader);
            Class<?> placement = Class.forName(IMPL + "DimensionBiomePlacement", false, loader);
            Class<?> replacementSet = Class.forName(IMPL + "DimensionBiomePlacement$ReplacementRequestSet", false, loader);
            Class<?> replacement = Class.forName(IMPL + "DimensionBiomePlacement$ReplacementRequest", false, loader);
            VarHandle subs = field(placement, "subBiomeRequests", HashMap.class);
            VarHandle reps = field(placement, "replacementRequests", HashMap.class);
            VarHandle requests = field(replacementSet, "requests", List.class);
            VarHandle biome = field(replacement, "biome", ResourceKey.class);
            Set<Object> out = new HashSet<>();
            for (String[] p : PLACEMENTS) {
                Object dimension = placement(coordinator, p, placement, loader);
                Map<Object, Object> sub = (Map<Object, Object>) subs.get(dimension);
                out.addAll(sub.keySet());
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) reps.get(dimension)).entrySet()) {
                    for (Object r : (List<Object>) requests.get(e.getValue())) {
                        if (sub.containsKey(biome.get(r))) {
                            out.add(e.getKey());
                        }
                    }
                }
            }
            LOGGER.info("Biolith sub-biome requests: {} biomes {} -> ties of their first answers count by leaf", out.size(), out);
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.info("Biolith sub-biome requests: {} -> every tie of a first answer counts", e.toString());
            return null;
        }
    }

    /**
     * Biolith's placement for one dimension, a public field of the coordinator. Biolith 3.0 declares it as the dimension's
     * placement class. Other versions may declare the base class.
     */
    private static Object placement(Class<?> coordinator, String[] p, Class<?> base, ClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            return lookup.findStaticVarHandle(coordinator, p[0], Class.forName(IMPL + p[1], false, loader)).get();
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            return lookup.findStaticVarHandle(coordinator, p[0], base).get();
        }
    }

    private static VarHandle field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type);
    }
}
