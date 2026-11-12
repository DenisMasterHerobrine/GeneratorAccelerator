package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ChunkHolder;

/**
 * The holders whose ticket level makes their chunk block ticking, per chunk map. {@code ServerChunkCache.tickChunks} walks every
 * holder of the map each tick to find the ticking chunks. During a pregeneration the map holds tens of thousands of holders and
 * a handful tick: the walk was 1-9% of the server thread in testers' profiles. The tick walks this set instead and asks each
 * holder for its ticking chunk as vanilla does.
 *
 * <p>The order differs from the map's. Vanilla shuffles the list before it ticks the chunks, and the shuffle draws one random
 * number per entry whatever the order. The map's own order follows the moment each holder was made, which differs between
 * runs anyway. Kept in step by {@code ChunkHolder.updateFutures}, on the server thread. Off with
 * {@code -Dgeneratoraccelerator2.tickingHolders=false}, and when another mod changes {@code tickChunks} or {@code updateFutures}.
 */
public final class TickingHolders {
    private TickingHolders() {}

    private static final boolean SWITCH = !"false".equals(System.getProperty("generatoraccelerator2.tickingHolders"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.CHUNK_TICKING_HOLDERS);

    /** The chunk map that keeps the set. */
    public interface Owner {
        ReferenceLinkedOpenHashSet<ChunkHolder> ga2$tickingHolders();
    }

    public static boolean enabled() {
        return SWITCH && HOOK.allowed();
    }

    /** After {@code updateFutures}: whether the holder's ticket level now makes its chunk block ticking. */
    public static void update(Owner map, ChunkHolder holder, boolean ticking) {
        ReferenceLinkedOpenHashSet<ChunkHolder> set = map.ga2$tickingHolders();
        if (ticking) {
            set.add(holder);
        } else {
            set.remove(holder);
        }
    }
}
