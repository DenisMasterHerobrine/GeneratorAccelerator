package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.ChunkMapUpdatingInvoker;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.junit.jupiter.api.Test;

/** {@link PresentChunks}: the hook into {@code ServerChunkCache.getChunk} and the visible holder lookup apply. */
class PresentChunksTest {
    @Test
    void mixinsApply() {
        assertTrue(ChunkMapUpdatingInvoker.class.isAssignableFrom(ChunkMap.class),
            "the chunk map hands out visible holders");
        assertTrue(Arrays.stream(ServerChunkCache.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("presentOffThread")),
            "getChunk answers present chunks off the server thread");
    }
}
