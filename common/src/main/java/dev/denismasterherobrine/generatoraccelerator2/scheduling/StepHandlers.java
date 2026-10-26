package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

/**
 * Other mods' code that vanilla's SURFACE step runs before its generator call (their mixins on
 * {@code ChunkStatusTasks.generateSurface}). When GA2 takes the step over ({@link ParallelSteps#surfaceStep}), GA2 runs
 * this code on the worker right before that call. Vanilla runs the step on its single worldgen thread, so such code runs
 * one chunk at a time there. GA2's scheduler thread would do the same. A mod that builds terrain there holds every chunk
 * behind it. OTBWG's crag gardens, for example, looked up 256 biomes per chunk. The code for each mixin lives in
 * {@code compat/<modid>}. It registers here once it was reviewed for running on several threads at once: it writes only
 * the step's chunk and reads what is final when the step may start, or the compat module makes the rest safe.
 * {@link HookTargets} knows which handled mixins changed the method and in which order their code runs
 * ({@code compat.KnownMixins}).
 */
public final class StepHandlers {
    private StepHandlers() {}

    /** What vanilla's step holds at the generator call: its arguments and the locals it made. */
    public record Frame(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, ServerLevel level,
                        WorldGenRegion region) {}

    /** One handler method of another mod's mixin. */
    public interface Handler {
        /** The handler's name as written in the mixin (the merged method's name ends with it). */
        String handler();

        /** True for code vanilla's step runs right after the generator call (a coremod's, which leaves no position to sort by). */
        default boolean afterCall() {
            return false;
        }

        void run(Frame f) throws Throwable;
    }

    /** Builds a mixin's handlers by looking up its merged methods. Throws when the methods do not fit. */
    @FunctionalInterface
    public interface Factory {
        List<Handler> create() throws ReflectiveOperationException;
    }

    private static final Map<String, Factory> FACTORIES = new ConcurrentHashMap<>();
    private static final Map<String, List<Handler>> CREATED = new ConcurrentHashMap<>();
    private static final Handler[] NONE = new Handler[0];
    private static volatile Handler[] surface;

    /** Registers the code for one handled mixin of {@code generateSurface} ({@code compat/<modid>}). */
    public static void register(String mixin, Factory factory) {
        FACTORIES.put(mixin, factory);
        CompatAdapters.register(HookTargets.SURFACE_STEP_BODY, mixin, () -> create(mixin) != null);
    }

    private static List<Handler> create(String mixin) {
        List<Handler> handlers = CREATED.get(mixin);
        if (handlers != null) {
            return handlers;
        }
        Factory factory = FACTORIES.get(mixin);
        if (factory == null) {
            return null;
        }
        try {
            handlers = List.copyOf(factory.create());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ga2Log.get("Scheduling").warn("step adapter for {} incompatible: {}", mixin, e.toString());
            return null;
        }
        CREATED.put(mixin, handlers);
        return handlers;
    }

    /**
     * The handled code of the SURFACE step in execution order, or null when there is none or it cannot run. The step then
     * keeps vanilla's body. Only meaningful once {@link HookTargets#allowed} said yes for {@link HookTargets#SURFACE_STEP_BODY}.
     */
    public static Handler[] surface() {
        Handler[] s = surface;
        if (s == null) {
            s = resolve();
            surface = s;
        }
        return s.length == 0 ? null : s;
    }

    private static Handler[] resolve() {
        List<HookTargets.Handled> handled = HookTargets.handled(HookTargets.SURFACE_STEP_BODY);
        if (handled.isEmpty()) {
            return NONE;
        }
        List<Handler> ordered = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (String mixin : HookTargets.handledMixins(HookTargets.SURFACE_STEP_BODY)) {
            List<Handler> handlers = create(mixin);
            if (handlers == null) {
                return NONE;
            }
            for (Handler h : handlers) {
                int position = Integer.MAX_VALUE;
                for (HookTargets.Handled c : handled) {
                    if (c.mixin().equals(mixin) && handlerName(c.method()).endsWith("$" + h.handler())) {
                        position = Math.min(position, c.at());
                    }
                }
                if (position == Integer.MAX_VALUE) {
                    // the mixin does not call this handler from the step (another version of it): nothing to run
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
        Handler[] out = new Handler[order.length];
        for (int i = 0; i < order.length; i++) {
            out[i] = ordered.get(order[i]);
        }
        return out;
    }

    /** Runs, in order, the handlers placed before ({@code after} false) or right after the generator call. A failure propagates as the step's own would. */
    static void run(Handler[] handlers, Frame frame, boolean after) {
        try {
            for (Handler h : handlers) {
                if (h.afterCall() == after) {
                    h.run(frame);
                }
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static String handlerName(String nameAndDesc) {
        String name = nameAndDesc.substring(0, nameAndDesc.indexOf('('));
        return name.replaceFirst("\\$mixinextras\\$bridge\\$\\d+$", "");
    }

    /**
     * The static handler method another mod's mixin merged into {@code ChunkStatusTasks}. Its merged name ends with
     * {@code "$" + name}, and it takes exactly {@code params}. That excludes MixinExtras' bridge for sugar, which takes references.
     */
    public static MethodHandle staticHandler(String name, Class<?>... params) throws ReflectiveOperationException {
        MethodHandle h = Reflect.mergedMethod(ChunkStatusTasks.class, "$" + name, true, void.class, params);
        if (h == null) {
            throw new NoSuchMethodException("ChunkStatusTasks.*$" + name + Arrays.toString(params));
        }
        return h;
    }
}
