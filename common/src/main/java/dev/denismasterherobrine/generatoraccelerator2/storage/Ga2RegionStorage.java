package dev.denismasterherobrine.generatoraccelerator2.storage;

import java.io.IOException;
import net.minecraft.world.level.ChunkPos;

/** What {@code RegionFileStorageMixin} adds to a region storage. */
public interface Ga2RegionStorage {
    /** The chunk compression results of the storage. */
    Precompress ga2$precompress();

    /**
     * {@code RegionFileStorage.read} up to the parse. Returns an {@code Optional<CompoundTag>} when the answer is known:
     * no chunk there, or a chunk in an external file, which is parsed here. Otherwise returns a
     * {@link ParallelRead.Unparsed} holding the chunk's bytes.
     */
    Object ga2$readRaw(ChunkPos pos) throws IOException;
}
