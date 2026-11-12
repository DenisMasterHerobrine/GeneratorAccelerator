package dev.denismasterherobrine.generatoraccelerator2.memory;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block and biome containers without their own lock objects. Each {@code PalettedContainer} makes a {@code ThreadingDetector}: the
 * detector, a {@code Semaphore} and a {@code ReentrantLock} with their syncs, about 125 bytes. Generation keeps millions of
 * containers alive: 300 MB in one pack's heap. The detector only finds two threads writing one container at once, which is a bug,
 * and crashes the game then. Here one field per container records the thread inside ({@code PalettedContainerLockMixin}). A second
 * thread finds it set and crashes with the same report, the stack traces of both threads.
 *
 * <p>Also one {@code PalettedContainer.Configuration} per distinct value instead of one per container: the record holds a palette
 * factory and a bit count, and there are a few dozen distinct values.
 *
 * <p>Only when no other mod changes {@code acquire}, {@code release}, the detector's construction or uses the detector field
 * ({@link HookTargets#MEMORY_CONTAINER_LOCKS}). FerriteCore does the same, Lithium removes the lock. A mod that only adds methods, as
 * ModernFix does, leaves it on. Decided when the class loads. {@code -Dgeneratoraccelerator2.containerLocks=false} keeps vanilla's
 * detector.
 */
public final class ContainerLocks {
    private ContainerLocks() {}

    private static final boolean SWITCH = !"false".equals(System.getProperty("generatoraccelerator2.containerLocks"));

    /** Asked once, when {@code PalettedContainer} initialises. The class was inspected by then. */
    public static boolean active() {
        return SWITCH && HookTargets.allowed(HookTargets.MEMORY_CONTAINER_LOCKS);
    }

    private static final ConcurrentHashMap<Object, Object> CONFIGURATIONS = new ConcurrentHashMap<>();

    /**
     * The shared instance equal to {@code configuration}. Configurations are records. Every new container asks, so the lookup comes
     * first: {@code putIfAbsent} locks the key's bin even when the key is there, and with a few dozen keys the workers queued on it.
     */
    @SuppressWarnings("unchecked")
    public static <C> C shared(C configuration) {
        Object kept = CONFIGURATIONS.get(configuration);
        if (kept == null) {
            kept = CONFIGURATIONS.putIfAbsent(configuration, configuration);
        }
        return kept != null ? (C) kept : configuration;
    }
}
