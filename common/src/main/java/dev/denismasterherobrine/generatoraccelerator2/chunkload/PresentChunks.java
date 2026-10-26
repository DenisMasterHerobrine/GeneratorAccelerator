package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.ChunkMapUpdatingInvoker;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Chunks asked of the server level by other threads. Vanilla's {@code ServerChunkCache.getChunk} hands such a call to the server
 * thread and waits for it, even when the chunk has long had the status. Mods' worldgen code does this from the worker threads:
 * Lithostitched's {@code in_structure} block predicate asks every chunk its search box touches for its structure references.
 * In a 575-mod pack the workers waited 30 s for such answers in one 33 s square.
 *
 * <p>When the chunk's holder in the visible map has the status, and the holder's level still allows it, the chunk is answered at
 * once. That is the chunk the server thread would answer: its future for the status is complete, and complete futures stay so.
 * The server thread would also add a ticket that keeps the chunk loaded for a tick. A chunk that does not have the status yet
 * goes to the server thread as before. Off when another mod changes {@code getChunk} ({@code chunkLoad.presentOffThread}) or
 * with {@code -Dgeneratoraccelerator2.presentChunks=false}.
 */
public final class PresentChunks {
    private PresentChunks() {}

    private static final boolean SWITCH = !"false".equals(System.getProperty("generatoraccelerator2.presentChunks"));
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.CHUNK_PRESENT_OFF_THREAD);
    /** Chunks answered at once off the server thread. */
    public static final LongAdder ANSWERED = new LongAdder();
    /** Other mods' handlers at the head of {@code getChunk}, done before a chunk is answered here (compat), or null. */
    public static volatile java.util.function.Consumer<ServerChunkCache> beforeAnswer;

    /** The chunk at {@code x, z} when it has {@code status} already, else null (the server thread answers then). */
    @Nullable
    public static ChunkAccess present(ServerChunkCache cache, int x, int z, ChunkStatus status) {
        if (!SWITCH || !HOOK.allowed()) {
            return null;
        }
        ChunkHolder holder = ((ChunkMapUpdatingInvoker) cache.chunkMap).ga2$visibleChunk(ChunkPos.asLong(x, z));
        ChunkAccess chunk = holder == null ? null : holder.getChunkIfPresent(status);
        if (chunk != null) {
            java.util.function.Consumer<ServerChunkCache> before = beforeAnswer;
            if (before != null) {
                before.accept(cache);
            }
            ANSWERED.increment();
        }
        return chunk;
    }
}
