package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether GA2 may write a state into a section's storage without {@code LevelChunkSection.setBlockState}: the noise fill's packed
 * sections ({@link SectionWriter}), the GPU's sections and the surface builder's whole-section rewrites and unchanged blocks
 * ({@link ChunkBlocks}).
 *
 * <p>Another mod's change to {@code setBlockState} keeps every such write off ({@link HookTargets#NOISE_SECTION_WRITES}). A known
 * change that acts only on the writes of some states registers what it acts on ({@link #register}). The direct writes then stay on,
 * and a section that would hold one of those states goes through {@code setBlockState}, where the change runs.
 */
public final class SectionWrites {
    private SectionWrites() {}

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.NOISE_SECTION_WRITES);
    @SuppressWarnings("unchecked")
    private static volatile Predicate<BlockState>[] acting = new Predicate[0];

    /**
     * Registers a known change to {@code setBlockState} that acts on the writes of the states {@code acts} accepts. Returns what
     * takes it out again (for tests).
     */
    public static synchronized Runnable register(Predicate<BlockState> acts) {
        Predicate<BlockState>[] next = Arrays.copyOf(acting, acting.length + 1);
        next[next.length - 1] = acts;
        acting = next;
        return () -> unregister(acts);
    }

    @SuppressWarnings("unchecked")
    private static synchronized void unregister(Predicate<BlockState> acts) {
        acting = Arrays.stream(acting).filter(p -> p != acts).toArray(Predicate[]::new);
    }

    /** Whether writes may skip {@code setBlockState} at all. */
    public static boolean allowed() {
        return HOOK.allowed();
    }

    /** Whether no known change acts on writes of {@code state}. Meaningful only while {@link #allowed}. */
    public static boolean direct(BlockState state) {
        for (Predicate<BlockState> p : acting) {
            if (p.test(state)) {
                return false;
            }
        }
        return true;
    }

    /** Whether some known change acts on the writes of some states. */
    public static boolean filtered() {
        return acting.length != 0;
    }

    /** {@link #allowed} and {@link #direct}: whether a write of {@code state} may skip {@code setBlockState}. */
    public static boolean skips(BlockState state) {
        return HOOK.allowed() && direct(state);
    }
}
