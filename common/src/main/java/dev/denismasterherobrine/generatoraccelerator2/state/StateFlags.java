package dev.denismasterherobrine.generatoraccelerator2.state;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a section write ({@code LevelChunkSection.setBlockState}) counts about a block state: whether the state is non-empty
 * (NeoForge's {@code isEmpty}), randomly ticking, and holds a fluid. Vanilla queries each flag through the state, its block and
 * its fluid state on every write. The flags are fixed per state, so each state computes them once
 * ({@code BlockStateFlagsMixin}).
 */
public final class StateFlags {
    private StateFlags() {}

    public static final int KNOWN = 1, NON_EMPTY = 2, TICKING = 4, FLUID = 8;

    /** Implemented by every block state. */
    public interface Holder {
        byte ga2$flags();

        void ga2$setFlags(byte flags);

        /** {@link StateIndex}: the registry id + 1, 0 not known yet. */
        int ga2$registryId();

        void ga2$setRegistryId(int id);
    }

    public static int of(BlockState state) {
        Holder h = (Holder) state;
        int f = h.ga2$flags();
        if (f != 0) {
            return f;
        }
        f = KNOWN;
        if (!PlatformHooks.isEmpty(state)) {
            f |= NON_EMPTY;
        }
        if (state.isRandomlyTicking()) {
            f |= TICKING;
        }
        if (!state.getFluidState().isEmpty()) {
            f |= FLUID;
        }
        h.ga2$setFlags((byte) f);
        return f;
    }
}
