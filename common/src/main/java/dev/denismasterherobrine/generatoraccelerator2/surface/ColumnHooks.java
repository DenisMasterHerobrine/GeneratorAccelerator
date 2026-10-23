package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Other mods' code that vanilla's {@code SurfaceSystem.buildSurface} runs for every column through their mixins on that
 * method. {@link SurfaceBuilder} runs this code at the same places and in the same order. The code for each mixin lives in
 * {@code compat/<modid>} and registers here. {@link HookTargets} knows which handled mixins changed the method and in
 * which order their code runs there ({@code compat.KnownMixins}).
 *
 * <p>The places, in vanilla's column loop:
 * <ul>
 *     <li>{@link Place#START}: once, at the head of {@code buildSurface}, before the loop.</li>
 *     <li>{@link Place#TOP}: after the column's biome lookup, at the eroded badlands test ({@code holder.is(ERODED_BADLANDS)}).
 *     Code injected before the test runs first, then at most one wrapper of the test itself.</li>
 *     <li>{@link Place#BEFORE_RULES}: after the eroded badlands extension, before the heightmap read that starts the rule walk.</li>
 *     <li>{@link Place#FROZEN}: after the walk, at the frozen ocean test ({@code holder.is(FROZEN_OCEAN)}). Code before the
 *     test runs first, then at most one wrapper of the test.</li>
 *     <li>{@link Place#END}: once, at the return of {@code buildSurface}, after the loop.</li>
 * </ul>
 * Handlers that share values through MixinExtras' {@code @Share} get references that live for one build ({@link Frame#shared}).
 */
public final class ColumnHooks {
    private ColumnHooks() {}

    public enum Place { START, TOP, BEFORE_RULES, FROZEN, END }

    /** What vanilla's column loop holds where the hooks run: the method's arguments and the column's locals. One per build. */
    public static final class Frame {
        public SurfaceSystem system;
        public RandomState randomState;
        public BiomeManager biomeManager;
        public Registry<Biome> biomes;
        public boolean legacy;
        public WorldGenerationContext context;
        public ChunkAccess chunk;
        public NoiseChunk noiseChunk;
        public SurfaceRules.RuleSource rule;
        /** The callback info a handler of an injection gets (vanilla's method cannot be cancelled there). */
        public final CallbackInfo ci = new CallbackInfo("buildSurface", false);
        public BlockColumn column;
        public Holder<Biome> biome;
        /** Column position: section-relative and absolute. */
        public int localX;
        public int localZ;
        public int x;
        public int z;
        /** One above the column's top block before the hooks ({@code getHeight(WORLD_SURFACE_WG) + 1}). */
        public int top;
        /** Vanilla's rule context with this column's position ({@code updateXZ}). Set when a hook asks for it. */
        public SurfaceRules.Context ruleContext;
        /** The position of the column's biome lookup, vanilla's second mutable position ({@code (x, legacy ? 0 : top, z)}). */
        public net.minecraft.core.BlockPos.MutableBlockPos biomePos;
        /** Values a mixin's handlers share for one build ({@code @Share}), by a key of the hook's own. */
        public final Map<Object, Object> shared = new java.util.IdentityHashMap<>();
    }

    /** A {@code @Share}d reference for one build. */
    public static final class Ref<T> implements com.llamalad7.mixinextras.sugar.ref.LocalRef<T> {
        private T value;

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T value) {
            this.value = value;
        }
    }

    /** A {@code @Share}d int for one build. */
    public static final class IntRef implements com.llamalad7.mixinextras.sugar.ref.LocalIntRef {
        private int value;

        @Override
        public int get() {
            return value;
        }

        @Override
        public void set(int value) {
            this.value = value;
        }
    }

    /** The biome test a wrapper wraps ({@code Holder.is(ResourceKey)}). */
    @FunctionalInterface
    public interface Test {
        boolean is(Holder<Biome> biome, ResourceKey<Biome> key);
    }

    /** One handler method of another mod's mixin, run at its place. */
    public interface Hook {
        /** The handler's name as written in the mixin (the merged method's name ends with it). */
        String handler();

        Place place();

        /** True for a wrapper of the biome test at its place ({@link #wrap}). Otherwise {@link #run} is called. */
        default boolean wrapper() {
            return false;
        }

        default void run(Frame f) throws Throwable {}

        default boolean wrap(Frame f, ResourceKey<Biome> key, Test original) throws Throwable {
            return original.is(f.biome, key);
        }

        /**
         * True when the code reads and writes only this column, through the frame's block column and the chunk's
         * heightmaps. GA2's builder then keeps its sections locked and may postpone deep writes. Code that may touch the
         * chunk directly (another mod's API calling out to further mods) runs with the sections unlocked.
         */
        default boolean columnLocal() {
            return false;
        }

        /** True when the code reads vanilla's rule context ({@link Frame#ruleContext}). */
        default boolean needsRuleContext() {
            return false;
        }

        /**
         * True unless the code is known never to read the NoiseChunk ({@link Frame#noiseChunk}). A chunk the GPU filled has none
         * ({@code gpu.GpuTerrain}). It is made only while every hook of the column loop leaves it alone.
         */
        default boolean readsNoiseChunk() {
            return true;
        }

        /**
         * True for code that only records what it is handed (at {@link Place#START}, {@link Place#TOP} or {@link Place#END}) and changes
         * no block. Where the GPU builds the surface, it runs on the CPU before the GPU's blocks are written ({@link #records}),
         * with the column's biome looked up as vanilla's loop looks it up.
         */
        default boolean record() {
            return false;
        }
    }

    /** Whether {@code mixin}'s code is set up (its handlers found). */
    public static boolean ready(String mixin) {
        return create(mixin) != null;
    }

    /**
     * Runs the record hooks ({@link Hook#record}) for a chunk whose surface another builder makes (the GPU's), before that builder
     * writes: the start hooks, then per column the top hooks with the column's biome at one above its top
     * ({@code biomeManager.getBiome}, vanilla's lookup), then the end hooks. Does nothing without record hooks.
     */
    public static void records(SurfaceSystem system, RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean legacy,
                               WorldGenerationContext context, ChunkAccess chunk, SurfaceRules.RuleSource rule) {
        Resolved r = resolved();
        if (r == null || !r.valid() || !r.hasRecords()) {
            return;
        }
        Frame f = new Frame();
        f.system = system;
        f.randomState = randomState;
        f.biomeManager = biomeManager;
        f.biomes = biomes;
        f.legacy = legacy;
        f.context = context;
        f.chunk = chunk;
        f.rule = rule;
        f.biomePos = new net.minecraft.core.BlockPos.MutableBlockPos();
        net.minecraft.world.level.levelgen.Heightmap surface = chunk.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG);
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        try {
            Resolved.runRecords(r.start, f);
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    f.localX = k;
                    f.localZ = l;
                    f.x = minX + k;
                    f.z = minZ + l;
                    f.top = surface.getFirstAvailable(k, l);
                    f.biome = biomeManager.getBiome(f.biomePos.set(f.x, legacy ? 0 : f.top, f.z));
                    Resolved.runRecords(r.top, f);
                }
            }
            Resolved.runRecords(r.end, f);
        } catch (Throwable t) {
            throw Resolved.rethrow(t);
        }
    }

    /** True when no hook of the column loop reads the NoiseChunk (or there are no hooks), and the hooks can all run. */
    public static boolean noiseChunkFree() {
        Resolved r = resolved();
        return r == null || r.valid() && !r.readsNoiseChunk();
    }

    /** Builds a mixin's hooks by looking up its merged handler methods. Throws when the methods do not fit. */
    @FunctionalInterface
    public interface Factory {
        List<Hook> create() throws ReflectiveOperationException;
    }

    private static final Map<String, Factory> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, List<Hook>> CREATED = new ConcurrentHashMap<>();
    private static volatile Resolved resolved;

    /** Registers the code for one handled mixin of {@code buildSurface} ({@code compat/<modid>}). */
    public static void register(String mixin, Factory factory) {
        FACTORIES.put(mixin, factory);
        CompatAdapters.register(HookTargets.SURFACE_BUILD, mixin, () -> create(mixin) != null);
    }

    private static List<Hook> create(String mixin) {
        List<Hook> hooks = CREATED.get(mixin);
        if (hooks != null) {
            return hooks;
        }
        Factory factory = FACTORIES.get(mixin);
        if (factory == null) {
            return null;
        }
        try {
            hooks = List.copyOf(factory.create());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ga2Log.get("Surface").warn("surface adapter for {} incompatible: {}", mixin, e.toString());
            return null;
        }
        CREATED.put(mixin, hooks);
        return hooks;
    }

    /**
     * The hooks of every handled mixin, by place and in execution order, or null when there are none. When the hooks cannot
     * all run (two wrappers of one test, or code that is not set up), the result is not {@link Resolved#valid()}. GA2's
     * builder then leaves the surface to vanilla.
     */
    public static Resolved resolved() {
        Resolved r = resolved;
        if (r == null) {
            try {
                r = resolve();
            } catch (IllegalStateException e) {
                Ga2Log.get("Surface").warn(
                    "foreign surface code incompatible with GA2 surface builder ({}) -> vanilla surfaces", e.getMessage());
                r = Resolved.INVALID;
            }
            resolved = r;
        }
        return r == Resolved.NONE ? null : r;
    }

    private static Resolved resolve() {
        List<HookTargets.Handled> handled = HookTargets.handled(HookTargets.SURFACE_BUILD);
        if (handled.isEmpty()) {
            return Resolved.NONE;
        }
        List<Hook> start = new ArrayList<>();
        List<Hook> end = new ArrayList<>();
        List<Hook> top = new ArrayList<>();
        List<Hook> rules = new ArrayList<>();
        List<Hook> frozen = new ArrayList<>();
        Hook topWrapper = null;
        Hook frozenWrapper = null;
        boolean local = true;
        boolean context = false;
        // handled changes are in execution order. Each hook takes the position of its handler's (first) call
        List<Hook> ordered = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (String mixin : HookTargets.handledMixins(HookTargets.SURFACE_BUILD)) {
            List<Hook> hooks = create(mixin);
            if (hooks == null) {
                throw new IllegalStateException("the code for " + mixin + " is not set up");
            }
            for (Hook h : hooks) {
                int position = Integer.MAX_VALUE;
                for (HookTargets.Handled c : handled) {
                    if (c.mixin().equals(mixin) && handlerName(c.method()).endsWith("$" + h.handler())) {
                        position = Math.min(position, c.at());
                    }
                }
                if (position == Integer.MAX_VALUE) {
                    // the mixin does not call this handler from buildSurface (an older or newer version of it): nothing to run
                    continue;
                }
                ordered.add(h);
                at.add(position);
            }
        }
        Integer[] order = new Integer[ordered.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingInt(at::get));
        for (int i : order) {
            Hook h = ordered.get(i);
            local &= h.columnLocal();
            context |= h.needsRuleContext();
            switch (h.place()) {
                case START, END -> {
                    if (h.wrapper()) {
                        throw new IllegalStateException("a wrapper at the head or return of the build");
                    }
                    (h.place() == Place.START ? start : end).add(h);
                }
                case TOP -> {
                    if (h.wrapper()) {
                        if (topWrapper != null) {
                            throw new IllegalStateException("two wrappers of the eroded badlands test");
                        }
                        topWrapper = h;
                    } else {
                        top.add(h);
                    }
                }
                case BEFORE_RULES -> {
                    if (h.wrapper()) {
                        throw new IllegalStateException("a wrapper before the rule walk");
                    }
                    rules.add(h);
                }
                case FROZEN -> {
                    if (h.wrapper()) {
                        if (frozenWrapper != null) {
                            throw new IllegalStateException("two wrappers of the frozen ocean test");
                        }
                        frozenWrapper = h;
                    } else {
                        frozen.add(h);
                    }
                }
            }
        }
        return new Resolved(start.toArray(new Hook[0]), top.toArray(new Hook[0]), topWrapper, rules.toArray(new Hook[0]), frozen.toArray(new Hook[0]), frozenWrapper,
            end.toArray(new Hook[0]), local, context, true);
    }

    private static String handlerName(String nameAndDesc) {
        String name = nameAndDesc.substring(0, nameAndDesc.indexOf('('));
        return name.replaceFirst("\\$mixinextras\\$bridge\\$\\d+$", "");
    }

    /** Every hook by place, in execution order. */
    public record Resolved(Hook[] start, Hook[] top, Hook topWrapper, Hook[] rules, Hook[] frozen, Hook frozenWrapper, Hook[] end, boolean columnLocal,
                           boolean needsRuleContext, boolean valid) {
        static final Resolved NONE = new Resolved(new Hook[0], new Hook[0], null, new Hook[0], new Hook[0], null, new Hook[0], true, false, true);
        static final Resolved INVALID = new Resolved(new Hook[0], new Hook[0], null, new Hook[0], new Hook[0], null, new Hook[0], true, false, false);

        private static final Test PLAIN = Holder::is;

        /** Whether some hook only records ({@link Hook#record}). */
        boolean hasRecords() {
            for (Hook[] hooks : new Hook[][] { start, top, end }) {
                for (Hook h : hooks) {
                    if (h.record()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void runRecords(Hook[] hooks, Frame f) throws Throwable {
            for (Hook h : hooks) {
                if (h.record()) {
                    h.run(f);
                }
            }
        }

        /** Whether some hook may read the NoiseChunk ({@link Hook#readsNoiseChunk}). */
        boolean readsNoiseChunk() {
            for (Hook[] hooks : new Hook[][] { start, top, rules, frozen, end, { topWrapper, frozenWrapper } }) {
                for (Hook h : hooks) {
                    if (h != null && h.readsNoiseChunk()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The hooks at the top of the column. Returns the eroded badlands test's answer. */
        public boolean top(Frame f, ResourceKey<Biome> erodedBadlands) {
            try {
                for (Hook h : top) {
                    h.run(f);
                }
                return topWrapper != null ? topWrapper.wrap(f, erodedBadlands, PLAIN) : f.biome.is(erodedBadlands);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The hooks at the head of the build, before the column loop. */
        public void start(Frame f) {
            run(start, f);
        }

        /** The hooks at the return of the build, after the column loop. */
        public void end(Frame f) {
            run(end, f);
        }

        private static void run(Hook[] hooks, Frame f) {
            try {
                for (Hook h : hooks) {
                    h.run(f);
                }
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public void beforeRules(Frame f) {
            try {
                for (Hook h : rules) {
                    h.run(f);
                }
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** The hooks after the rule walk. Returns the frozen ocean test's answer. */
        public boolean frozen(Frame f, ResourceKey<Biome> frozenOcean) {
            try {
                for (Hook h : frozen) {
                    h.run(f);
                }
                return frozenWrapper != null ? frozenWrapper.wrap(f, frozenOcean, PLAIN) : f.biome.is(frozenOcean);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        static RuntimeException rethrow(Throwable t) {
            if (t instanceof RuntimeException r) {
                throw r;
            }
            if (t instanceof Error e) {
                throw e;
            }
            return new RuntimeException(t);
        }
    }

    /** {@code buildSurface}'s parameters, which every injection handler takes first (then its callback info). */
    public static final Class<?>[] BUILD_ARGS = {RandomState.class, BiomeManager.class, Registry.class, boolean.class, WorldGenerationContext.class,
        ChunkAccess.class, NoiseChunk.class, SurfaceRules.RuleSource.class};

    /**
     * The handler method another mod's mixin merged into {@code SurfaceSystem}. Its merged name ends with {@code "$" + name},
     * and it takes exactly {@code params}. That excludes MixinExtras' bridge for sugar, which takes references.
     */
    public static MethodHandle handler(String name, Class<?> returnType, Class<?>... params) throws ReflectiveOperationException {
        for (Method m : SurfaceSystem.class.getDeclaredMethods()) {
            if (m.getName().endsWith("$" + name) && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == returnType
                && Arrays.equals(m.getParameterTypes(), params)) {
                m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m);
            }
        }
        throw new NoSuchMethodException("SurfaceSystem.*$" + name + Arrays.toString(params));
    }

    /** {@link #BUILD_ARGS}, the callback info, then {@code locals}. */
    public static Class<?>[] injectionParams(Class<?>... locals) {
        Class<?>[] p = Arrays.copyOf(BUILD_ARGS, BUILD_ARGS.length + 1 + locals.length);
        p[BUILD_ARGS.length] = CallbackInfo.class;
        System.arraycopy(locals, 0, p, BUILD_ARGS.length + 1, locals.length);
        return p;
    }
}
