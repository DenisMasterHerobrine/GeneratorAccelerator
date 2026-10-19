package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A dimension's block states as the GPU's 16-bit ids, for the chunk blocks the GPU keeps (from the surface on). The surface
 * program's states come first, in the program's order. These are the ids its packed blocks use. Every state that carvers and
 * decoration write follows, in the order first written. Ids never change. The GPU's per-state tables are built from
 * {@link #snapshot()}.
 */
public final class StateIds {
    private final ConcurrentHashMap<BlockState, Integer> ids = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BlockState> states = new CopyOnWriteArrayList<>();

    public StateIds(List<BlockState> initial) {
        for (BlockState s : initial) {
            if (ids.putIfAbsent(s, states.size()) == null) {
                states.add(s);
            } else {
                // the same state twice in the surface's list keeps its first id. The list still advances, so ids match the surface's.
                states.add(s);
            }
        }
        // what a read outside the build height answers. Its id is known before any chunk is decorated (voidAir).
        id(net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState());
    }

    /** The id void air gets in the ids made from {@code initial} (the decoration kernel's constant). */
    public static int voidAir(List<BlockState> initial) {
        int i = initial.indexOf(net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState());
        return i >= 0 ? i : initial.size();
    }

    /** The id of a state, assigned on first use. Returns -1 past 65535 states. The chunk then leaves the GPU's keeping. */
    public int id(BlockState s) {
        Ga2StateId.Cache c = ((Ga2StateId) s).ga2$stateId();
        if (c != null && c.owner() == this) {
            return c.id();
        }
        Integer i = ids.get(s);
        if (i != null) {
            ((Ga2StateId) s).ga2$setStateId(new Ga2StateId.Cache(this, i));
            return i;
        }
        synchronized (this) {
            i = ids.get(s);
            if (i == null) {
                if (states.size() >= 0xFFFF) {
                    return -1;
                }
                i = states.size();
                states.add(s);
                ids.put(s, i);
            }
            return i;
        }
    }

    public BlockState state(int id) {
        return states.get(id);
    }

    public int size() {
        return states.size();
    }

    /** The states by id as now (ids beyond belong to states written later). */
    public BlockState[] snapshot() {
        return states.toArray(new BlockState[0]);
    }
}
