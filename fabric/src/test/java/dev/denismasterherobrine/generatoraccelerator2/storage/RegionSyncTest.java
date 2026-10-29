package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.RegionFileAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A region file opened for batched syncing ({@link RegionSync}) is forced once its batch is full or its time is up, is clean
 * after vanilla's own flush and close, and holds the same bytes as a file synced on every write.
 */
public class RegionSyncTest {
    @Test
    void batchedFileIsForcedByBatchSizeAndTime(@TempDir Path dir) throws IOException {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1L); // boots the game (registries, mixins)
        // the timer thread must not force the file while this test counts its writes
        System.setProperty("generatoraccelerator2.syncDelayMs", "600000");
        try {
            run(dir);
        } finally {
            System.clearProperty("generatoraccelerator2.syncDelayMs");
        }
    }

    private static void run(Path dir) throws IOException {
        assertFalse(RegionSync.openUnsynced(false, null), "a storage that does not sync stays as it is");
        assertFalse(RegionSync.consumeClaim(), "no claim without a sync request");
        assertTrue(RegionSync.enabled(), "batched syncing off: " + RegionSync.stats());
        RegionStorageInfo info = new RegionStorageInfo("test", ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld")), "chunk");
        Path batched = dir.resolve("batched");
        Path synced = dir.resolve("synced");
        Files.createDirectories(batched);
        Files.createDirectories(synced);
        List<CompoundTag> tags = FastRegionWriteTest.synthetic();
        long delay = 10_000_000L;
        RegionSync.Batched b;
        try (RegionFile file = new RegionFile(info, batched.resolve("r.0.0.mca"), batched, RegionSync.openUnsynced(true, null));
             RegionFile reference = new RegionFile(info, synced.resolve("r.0.0.mca"), synced, true)) {
            b = (RegionSync.Batched) (Object) file;
            assertTrue(b.ga2$batched(), "the file took the claim");
            assertFalse(((RegionSync.Batched) (Object) reference).ga2$batched(), "a synced file is not batched");
            int written = 0;
            for (int i = 0; i < 3; i++) {
                write(file, reference, tags, written++);
            }
            assertEquals(3, b.ga2$dirtyWrites());
            long since = b.ga2$dirtySince();
            assertTrue(since != 0L, "dirty since the first write");
            RegionSync.forceDue(since + 1, delay);
            assertEquals(3, b.ga2$dirtyWrites(), "not due yet");
            RegionSync.forceDue(since + delay, delay);
            assertEquals(0, b.ga2$dirtyWrites(), "forced once its time was up");
            assertEquals(0L, b.ga2$dirtySince());
            for (int i = 0; i < RegionSync.MAX_BATCH - 1; i++) {
                write(file, reference, tags, written++);
            }
            assertEquals(RegionSync.MAX_BATCH - 1, b.ga2$dirtyWrites());
            write(file, reference, tags, written++);
            assertEquals(0, b.ga2$dirtyWrites(), "forced when the batch was full");
            write(file, reference, tags, written++);
            assertEquals(1, b.ga2$dirtyWrites());
            file.flush();
            assertEquals(0, b.ga2$dirtyWrites(), "vanilla's flush leaves the file clean");
            assertEquals(0L, b.ga2$dirtySince());
            write(file, reference, tags, written++);
            assertEquals(1, b.ga2$dirtyWrites());
        }
        assertTrue(b.ga2$closed());
        assertEquals(0, b.ga2$dirtyWrites(), "closing leaves the file clean");
        byte[] x = Files.readAllBytes(batched.resolve("r.0.0.mca"));
        byte[] y = Files.readAllBytes(synced.resolve("r.0.0.mca"));
        // timestamps (second 4 KiB) are the wall clock of each write
        Arrays.fill(x, 4096, 8192, (byte) 0);
        Arrays.fill(y, 4096, 8192, (byte) 0);
        assertArrayEquals(y, x, "region file bytes");
        System.out.println("RegionSyncTest: " + RegionSync.stats());
    }

    private static void write(RegionFile file, RegionFile reference, List<CompoundTag> tags, int n) throws IOException {
        ChunkPos pos = new ChunkPos(n % 32, (n / 32) % 32);
        CompoundTag tag = tags.get(n % tags.size());
        ((RegionFileAccessor) file).ga2$write(pos, FastRegionWrite.encode(tag, RegionFileVersion.VERSION_DEFLATE.getId()));
        ((RegionFileAccessor) reference).ga2$write(pos, FastRegionWrite.encode(tag, RegionFileVersion.VERSION_DEFLATE.getId()));
    }
}
