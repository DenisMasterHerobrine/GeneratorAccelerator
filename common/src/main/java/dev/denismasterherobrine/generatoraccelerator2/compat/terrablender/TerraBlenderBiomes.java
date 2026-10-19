package dev.denismasterherobrine.generatoraccelerator2.compat.terrablender;

import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * TerraBlender's biome lookup, for the GPU's biomes. TerraBlender's mixin replaces {@code MultiNoiseBiomeSource.getNoiseBiome}
 * with {@code findValuePositional} on the parameter list. That method picks a region per quart column ({@code getUniqueness}, a
 * layered noise of x and z). It then searches the region's own climate tree with vanilla's distance. A placeholder leaf
 * ({@code Region.DEFERRED_PLACEHOLDER}) sends the quart to region 0's tree instead. The GPU searches the same trees. The CPU
 * computes the regions with TerraBlender's own noise.
 *
 * <p>TerraBlender makes a fresh uniqueness area for every chunk (a clone of the list). The area only caches values of x and z, so
 * every area gives the same values. GA2 keeps one clone with its own area. The area locks itself, so any thread may read it.
 *
 * <p>GA2 does not compile against TerraBlender. The methods are found by name on TerraBlender's interface.
 */
public final class TerraBlenderBiomes {
    private TerraBlenderBiomes() {}

    private static final String LIST = "terrablender.worldgen.IExtendedParameterList";
    private static final String REGION = "terrablender.api.Region";

    private static volatile Api api;
    private static volatile String apiProblem;

    /** TerraBlender's methods, found once. */
    private record Api(Class<?> list, MethodHandle initialized, MethodHandle treeCount, MethodHandle tree, MethodHandle copy, MethodHandle recreate,
                       MethodHandle uniqueness, MethodHandle find, ResourceKey<Biome> placeholder) {}

    /** Whether TerraBlender's lookup has the shape GA2 reproduces. The {@code gpu.biomes} adapter for TerraBlender's mixins. */
    public static boolean available() {
        return api() != null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Api api() {
        Api a = api;
        if (a != null || apiProblem != null) {
            return a;
        }
        synchronized (TerraBlenderBiomes.class) {
            if (api != null || apiProblem != null) {
                return api;
            }
            try {
                Class<?> list = Class.forName(LIST, false, TerraBlenderBiomes.class.getClassLoader());
                MethodHandles.Lookup l = MethodHandles.publicLookup();
                Method find = list.getMethod("findValuePositional", Climate.TargetPoint.class, int.class, int.class, int.class);
                Object key = Class.forName(REGION, false, TerraBlenderBiomes.class.getClassLoader()).getField("DEFERRED_PLACEHOLDER").get(null);
                api = new Api(list,
                    l.unreflect(list.getMethod("isInitialized")),
                    l.unreflect(list.getMethod("getTreeCount")),
                    l.unreflect(list.getMethod("getTree", int.class)),
                    l.unreflect(list.getMethod("clone")),
                    l.unreflect(list.getMethod("recreateUniqueness")),
                    l.unreflect(list.getMethod("getUniqueness", int.class, int.class, int.class)),
                    l.unreflect(find),
                    (ResourceKey<Biome>) key);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                apiProblem = e.toString();
            }
            return api;
        }
    }

    /**
     * TerraBlender's regions of {@code list}. Returns null when TerraBlender does not decide this list's biomes: TerraBlender is
     * absent, or the list was never set up for regions (a dimension without a region type). Vanilla's search then decides them.
     *
     * @throws IllegalStateException when TerraBlender decides the list's biomes in a way GA2 does not know
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Regions of(Climate.ParameterList<Holder<Biome>> list) {
        if (!Compat.isLoaded(TerraBlenderMixins.MOD_ID)) {
            return null;
        }
        Api a = api();
        if (a == null) {
            throw new IllegalStateException("TerraBlender's parameter list: unknown shape (" + apiProblem + ")");
        }
        if (!a.list().isInstance(list)) {
            throw new IllegalStateException("TerraBlender's parameter list: not extended");
        }
        try {
            if (!(boolean) a.initialized().invoke(list)) {
                return null;
            }
            int n = (int) a.treeCount().invoke(list);
            Climate.RTree<Holder<Biome>>[] trees = new Climate.RTree[n];
            for (int i = 0; i < n; i++) {
                trees[i] = (Climate.RTree<Holder<Biome>>) a.tree().invoke(list, i);
            }
            if (n == 0 || trees[0] == null) {
                throw new IllegalStateException("TerraBlender's parameter list: no tree for region 0");
            }
            Object own = a.copy().invoke(list);
            a.recreate().invoke(own);
            return new Regions(a, trees, own);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("TerraBlender's parameter list: " + t, t);
        }
    }

    /** One parameter list's regions: a climate tree per region index, and GA2's own copy of the list for the region noise. */
    public static final class Regions implements dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeRegions {
        private final Api api;
        /** The tree of each region index. Null for a region that added no biomes (TerraBlender never fills it). */
        private final Climate.RTree<Holder<Biome>>[] trees;
        private final Object own;

        private Regions(Api api, Climate.RTree<Holder<Biome>>[] trees, Object own) {
            this.api = api;
            this.trees = trees;
            this.own = own;
        }

        @Override
        public Climate.RTree<Holder<Biome>>[] trees() {
            return trees;
        }

        /** The region index of quart column {@code qx, qz}, as TerraBlender's {@code getUniqueness} gives it. */
        @Override
        public int region(int qx, int qz) {
            try {
                return (int) api.uniqueness().invoke(own, qx, 0, qz);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The biome TerraBlender's lookup gives at quart {@code x, y, z} for {@code target}. Updates the trees' last results as it does. */
        @Override
        @SuppressWarnings("unchecked")
        public Holder<Biome> find(Climate.TargetPoint target, int x, int y, int z) {
            try {
                return (Holder<Biome>) api.find().invoke(own, target, x, y, z);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Whether {@code biome} is TerraBlender's placeholder, which sends the quart to region 0's tree. */
        @Override
        public boolean deferred(Holder<Biome> biome) {
            return biome.is(api.placeholder());
        }

        private static RuntimeException rethrow(Throwable t) {
            if (t instanceof RuntimeException r) {
                return r;
            }
            if (t instanceof Error e) {
                throw e;
            }
            return new IllegalStateException(t);
        }
    }
}
