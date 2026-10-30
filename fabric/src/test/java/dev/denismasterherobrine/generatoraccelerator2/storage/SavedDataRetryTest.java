package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ConcurrentModificationException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;

/** {@link SavedDataRetry} through vanilla's {@code SavedData.save(File)}. */
public class SavedDataRetryTest {
    /** Throws as a map walked during another thread's addition does, a given number of times. */
    private static final class Racing extends SavedData {
        int failures;
        int calls;

        Racing(int failures) {
            this.failures = failures;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            this.calls++;
            tag.putInt("partial", this.calls);
            if (this.failures-- > 0) {
                throw new ConcurrentModificationException();
            }
            tag.putString("whole", "yes");
            return tag;
        }
    }

    @Test
    void aRacingWriteIsWrittenAgainWithAFreshTag() throws IOException {
        File file = Files.createTempFile("ga2-saved", ".dat").toFile();
        try {
            Racing data = new Racing(3);
            data.setDirty();
            data.save(file);
            assertEquals(4, data.calls);
            CompoundTag written = NbtIo.readCompressed(file).getCompound("data");
            assertEquals("yes", written.getString("whole"));
            assertEquals(4, written.getInt("partial"), "only the last attempt's keys");
        } finally {
            file.delete();
        }
    }

    @Test
    void aWriteThatKeepsFailingStillThrows() throws IOException {
        File file = Files.createTempFile("ga2-saved", ".dat").toFile();
        try {
            Racing data = new Racing(100);
            data.setDirty();
            assertThrows(ConcurrentModificationException.class, () -> data.save(file));
            assertEquals(SavedDataRetry.ATTEMPTS, data.calls);
        } finally {
            file.delete();
        }
    }
}
