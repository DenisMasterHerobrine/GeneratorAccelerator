package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceSystemAccessor;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;

/**
 * One {@code SurfaceSystem} per worker thread for the surfaces built with other mods' code that keeps state in it.
 *
 * <p>A dimension has one surface system, made by its random state. Vanilla builds one surface at a time, so other mods' mixins on
 * {@code buildSurface} may keep a chunk's state in the system's fields. Environmental does: it resets its pine barrens stone raises
 * at the head, fills them column by column and reads them at the tail. GA2 builds several surfaces at once, and two chunks would then
 * share that state. While other mods change {@code buildSurface} in ways GA2 does not know ({@link HookTargets#SURFACE_BUILD} stands
 * down), or while GA2's builder runs such known code (Environmental's, {@code compat.environmental}), each worker thread builds on
 * its own copy instead. The copy is made with the constructor's arguments, so vanilla's state is
 * the same (the noises are the random state's own instances, the clay bands and the random factory are made from the same seed), and
 * every mod's constructor code gives the copy its own fields. Whatever a mod keeps per chunk stays with one thread.
 *
 * <p>{@code -Dgeneratoraccelerator2.surfaceCopies=false} turns the copies off. Such surfaces are then built one at a time, on the
 * worldgen thread.
 */
public final class SurfaceCopies {
    private SurfaceCopies() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.surfaceCopies"));
    private static final HookTargets.Hook BUILD = HookTargets.hook(HookTargets.SURFACE_BUILD);
    /** Copies made, one per thread and dimension. */
    public static final LongAdder MADE = new LongAdder();

    /** The copies of each dimension's system, by thread. Weak keys: a system goes with its random state. */
    private static final Map<SurfaceSystem, ThreadLocal<SurfaceSystem>> COPIES = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * True when other mods change {@code buildSurface} in ways GA2 does not know, so the surfaces vanilla builds need their own system.
     * Also true when known code that GA2's builder runs keeps a build's values in the system ({@code ColumnHooks.Hook.keepsSystemState}).
     */
    public static boolean needed() {
        return !BUILD.unchanged() && !BUILD.allowed() || ColumnHooks.keepsSystemState();
    }

    /** The system this thread builds {@code randomState}'s surfaces with: its own copy while {@link #needed}, else {@code shared}. */
    public static SurfaceSystem forThread(SurfaceSystem shared, RandomState randomState) {
        if (!ENABLED || !needed()) {
            return shared;
        }
        ThreadLocal<SurfaceSystem> local = COPIES.computeIfAbsent(shared, s -> new ThreadLocal<>());
        SurfaceSystem copy = local.get();
        if (copy == null) {
            copy = copy(shared, randomState);
            local.set(copy);
        }
        return copy;
    }

    /** A system made as the random state made {@code shared}. */
    static SurfaceSystem copy(SurfaceSystem shared, RandomState randomState) {
        SurfaceSystemAccessor a = (SurfaceSystemAccessor) shared;
        MADE.increment();
        return new SurfaceSystem(randomState, a.ga2$defaultBlock(), a.ga2$seaLevel(), a.ga2$noiseRandom());
    }
}
