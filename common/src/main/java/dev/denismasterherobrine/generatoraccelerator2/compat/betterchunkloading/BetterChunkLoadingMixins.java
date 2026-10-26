package dev.denismasterherobrine.generatoraccelerator2.compat.betterchunkloading;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Better Chunk Loading mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class BetterChunkLoadingMixins {
    private BetterChunkLoadingMixins() {}

    public static final String MOD_ID = "betterchunkloading";
    /**
     * On the {@code getChunkFutureMainThread} call in {@code ServerChunkCache.getChunk}: a chunk still to load is raised in the
     * loading order while a thread waits for it. A chunk GA2 answers off the server thread already has the status, so the call
     * would hand back its finished future either way.
     */
    public static final String CHUNK_CACHE = "com.betterchunkloading.mixin.ServerChunkCacheMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_CACHE, Set.of("prioWaiting", "lambda$prioWaiting$0", "lambda$prioWaiting$1"),
            "Better Chunk Loading still raises chunks that a thread waits for. Chunks that are already there are handed out at once."));

    /** Nothing to set up. */
    public static void register() {
        CompatAdapters.register(HookTargets.CHUNK_PRESENT_OFF_THREAD, CHUNK_CACHE, () -> true);
    }
}
