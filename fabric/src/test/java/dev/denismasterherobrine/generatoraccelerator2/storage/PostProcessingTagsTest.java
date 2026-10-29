package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.junit.jupiter.api.Test;

/** {@link PostProcessingTags} gives vanilla's tags and bytes for lists of every shape. */
public class PostProcessingTagsTest {
    @Test
    void sameTagsAsVanilla() throws IOException {
        Random random = new Random(23);
        for (int round = 0; round < 200; round++) {
            ShortList[] lists = new ShortList[24];
            for (int i = 0; i < lists.length; i++) {
                int kind = random.nextInt(4);
                if (kind == 0) {
                    continue;
                }
                ShortArrayList list = new ShortArrayList();
                int n = kind == 1 ? 0 : random.nextInt(kind == 2 ? 8 : 700);
                for (int k = 0; k < n; k++) {
                    list.add((short) random.nextInt(4096));
                }
                lists[i] = list;
            }
            ListTag vanilla;
            try {
                PostProcessingTags.ENABLED = false;
                vanilla = ChunkSerializer.packOffsets(lists);
            } finally {
                PostProcessingTags.ENABLED = true;
            }
            ListTag fast = ChunkSerializer.packOffsets(lists);
            assertEquals(vanilla, fast, "round " + round);
            assertArrayEquals(bytes(vanilla), bytes(fast), "round " + round + " bytes");
        }
    }

    private static byte[] bytes(ListTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tag.write(new DataOutputStream(out));
        return out.toByteArray();
    }
}
