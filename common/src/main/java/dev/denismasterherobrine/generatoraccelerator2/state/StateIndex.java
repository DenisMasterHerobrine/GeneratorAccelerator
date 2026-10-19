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
     * of thousands with mods. Reads take no lock. A page is published whole.
     */
    public static final class Table {
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
                int[] q = p[page];
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
                p = java.util.Arrays.copyOf(p, Math.max(page + 1, p.length * 2));
            } else {
                p = p.clone();
            }
            int[] q = p[page];
            q = q == null ? new int[256] : q.clone();
            if (p[page] == null) {
                java.util.Arrays.fill(q, missing);
            }
            q[id & 255] = value;
            p[page] = q;
            this.pages = p;
        }
    }
}
