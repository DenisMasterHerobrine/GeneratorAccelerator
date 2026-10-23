package dev.denismasterherobrine.generatoraccelerator2.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block state's id in {@code Block.BLOCK_STATE_REGISTRY}, kept on the state ({@code BlockStateFlagsMixin}) so per-state tables
 * are indexed by a field read instead of a hash lookup. The id is -1 for a state the registry does not hold.
 */
public final class StateIndex {
    private StateIndex() {}

    public static int id(BlockState state) {
        StateFlags.Holder h = (StateFlags.Holder) state;
        int v = h.ga2$registryId();
        if (v != 0) {
            return v - 1;
        }
        int id = Block.getId(state);
        h.ga2$setRegistryId(id < 0 ? 0 : id + 1);
        return id;
    }

    /**
     * Ints per state id, in pages of 256 made on first use. A table touches a few states out of a registry that can hold hundreds
     * of thousands with mods. Reads take no lock. A new page is filled before it is published. A value goes into its page in
     * place. A reader then sees the value or {@code missing}, and a value is the same whoever computes it.
     *
     * <p>Copying the page directory on every put cost 4 bytes per page of the registry. With 3.4 million states in a large pack
     * that was 52 KB per new state, about 250 KB of garbage per chunk.
     */
    public static final class Table {
        private static final java.lang.invoke.VarHandle PAGE = java.lang.invoke.MethodHandles.arrayElementVarHandle(int[][].class);
        private final int missing;
        private volatile int[][] pages = new int[0][];

        public Table(int missing) {
            this.missing = missing;
        }

        /** The value for {@code id}, or {@code missing}. */
        public int get(int id) {
            int[][] p = this.pages;
            int page = id >>> 8;
            if (page < p.length) {
                int[] q = (int[]) PAGE.getAcquire(p, page);
                if (q != null) {
                    return q[id & 255];
                }
            }
            return missing;
        }

        public synchronized void put(int id, int value) {
            int page = id >>> 8;
            int[][] p = this.pages;
            if (page >= p.length) {
                // Only the directory grows. The pages stay the same arrays.
                p = java.util.Arrays.copyOf(p, Math.max(page + 1, p.length * 2));
                this.pages = p;
            }
            int[] q = p[page];
            if (q == null) {
                q = new int[256];
                java.util.Arrays.fill(q, missing);
                q[id & 255] = value;
                PAGE.setRelease(p, page, q);
            } else {
                q[id & 255] = value;
            }
        }
    }
}
