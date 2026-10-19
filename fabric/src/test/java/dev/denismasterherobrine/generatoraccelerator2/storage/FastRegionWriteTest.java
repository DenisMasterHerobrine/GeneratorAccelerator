package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.RegionFileAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;

/**
 * Region file writes through {@link FastRegionWrite} put the same bytes on disk as vanilla's stream chain: real chunk
 * tags of a dev-run world (when one is around) and synthetic tags of every type, with non-ASCII, NUL and over-long
 * strings. Compared as the encoded buffer and as whole region files (header timestamps aside).
 */
public class FastRegionWriteTest {
    @Test
    void sameBytesAsVanilla() throws IOException {
        List<CompoundTag> tags = new ArrayList<>(synthetic());
        Path region = RegionChunks.defaultRegion();
        if (region != null) {
            tags.addAll(RegionChunks.readTags(region, 300));
        }
        for (int i = 0; i < tags.size(); i++) {
            assertArrayEquals(vanilla(tags.get(i)), fast(tags.get(i)), "tag " + i);
        }
        assertTrue(tags.size() >= 20, "tags compared: " + tags.size());
    }

    @Test
    void sameRegionFile(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        List<CompoundTag> tags = new ArrayList<>(synthetic());
        Path region = RegionChunks.defaultRegion();
        if (region != null) {
            tags.addAll(RegionChunks.readTags(region, 200));
        }
        RegionStorageInfo info = new RegionStorageInfo("test", ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld")), "chunk");
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        try (RegionFile va = new RegionFile(info, a.resolve("r.0.0.mca"), a, false);
             RegionFile fa = new RegionFile(info, b.resolve("r.0.0.mca"), b, false)) {
            for (int i = 0; i < tags.size(); i++) {
                ChunkPos pos = new ChunkPos(i % 32, (i / 32) % 32);
                try (DataOutputStream out = va.getChunkDataOutputStream(pos)) {
                    NbtIo.write(tags.get(i), out);
                }
                ((RegionFileAccessor) fa).ga2$write(pos, FastRegionWrite.encode(tags.get(i), RegionFileVersion.VERSION_DEFLATE.getId()));
            }
        }
        byte[] x = Files.readAllBytes(a.resolve("r.0.0.mca"));
        byte[] y = Files.readAllBytes(b.resolve("r.0.0.mca"));
        // timestamps (second 4 KiB) are the wall clock of each write
        Arrays.fill(x, 4096, 8192, (byte) 0);
        Arrays.fill(y, 4096, 8192, (byte) 0);
        assertArrayEquals(x, y, "region file");
    }

    /** Compressed on the worker pool: the result is taken only for the tag submitted last for the chunk, and is the same bytes. */
    @Test
    void precompressedBytes() throws IOException {
        List<CompoundTag> tags = synthetic();
        Precompress pre = new Precompress();
        ChunkPos pos = new ChunkPos(3, -7);
        pre.submit(pos, tags.get(0));
        pre.submit(pos, tags.get(1));
        org.junit.jupiter.api.Assertions.assertNull(pre.take(pos, tags.get(0)), "a replaced tag is compressed by the writer");
        ByteBuffer taken = null;
        for (int tries = 0; tries < 200 && taken == null; tries++) {
            pre.submit(pos, tags.get(1));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taken = pre.take(pos, tags.get(1));
        }
        org.junit.jupiter.api.Assertions.assertNotNull(taken, "a finished result is taken");
        assertArrayEquals(vanilla(tags.get(1)), Arrays.copyOfRange(taken.array(), 0, taken.limit()), "precompressed bytes");
        org.junit.jupiter.api.Assertions.assertNull(pre.take(pos, tags.get(1)), "taken once");
    }

    /** What {@code RegionFile.ChunkBuffer} holds when closed for this tag. */
    private static byte[] vanilla(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8096);
        bytes.write(new byte[] { 0, 0, 0, 0, (byte) RegionFileVersion.VERSION_DEFLATE.getId() });
        try (DataOutputStream out = new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(bytes))) {
            NbtIo.write(tag, out);
        }
        byte[] result = bytes.toByteArray();
        ByteBuffer.wrap(result).putInt(0, result.length - 5 + 1);
        return result;
    }

    private static byte[] fast(CompoundTag tag) throws IOException {
        ByteBuffer buffer = FastRegionWrite.encode(tag, RegionFileVersion.VERSION_DEFLATE.getId());
        return Arrays.copyOfRange(buffer.array(), 0, buffer.limit());
    }

    private static List<CompoundTag> synthetic() {
        List<CompoundTag> out = new ArrayList<>();
        Random random = new Random(5);
        for (int n = 0; n < 20; n++) {
            CompoundTag tag = new CompoundTag();
            tag.putByte("b", (byte) random.nextInt());
            tag.putShort("s", (short) random.nextInt());
            tag.putInt("i", random.nextInt());
            tag.putLong("l", random.nextLong());
            tag.putFloat("f", n == 1 ? Float.NaN : n == 2 ? -0.0F : random.nextFloat());
            tag.putDouble("d", n == 1 ? Double.NaN : n == 2 ? Double.NEGATIVE_INFINITY : random.nextGaussian());
            tag.putBoolean("z", random.nextBoolean());
            tag.putString("ascii", "minecraft:stone_" + n);
            tag.putString("unicode", "é中\u0000￿😀 " + n);
            if (n == 3) {
                tag.putString("long", "x".repeat(70000)); // over 65535 bytes: NbtIo writes its fallback
            }
            tag.put("bytes", new ByteArrayTag(randomBytes(random, random.nextInt(5000))));
            tag.put("ints", new IntArrayTag(random.ints(random.nextInt(2000)).toArray()));
            tag.put("longs", new LongArrayTag(random.longs(random.nextInt(2000)).toArray()));
            ListTag list = new ListTag();
            for (int k = 0; k < random.nextInt(300); k++) {
                CompoundTag e = new CompoundTag();
                e.putString("Name", "minecraft:block_" + random.nextInt(50));
                e.put("v", DoubleTag.valueOf(random.nextDouble()));
                e.put("w", FloatTag.valueOf(random.nextFloat()));
                list.add(e);
            }
            tag.put("list", list);
            ListTag strings = new ListTag();
            for (int k = 0; k < 40; k++) {
                strings.add(StringTag.valueOf(Integer.toString(random.nextInt(), 36)));
            }
            tag.put("strings", strings);
            out.add(tag);
        }
        return out;
    }

    private static byte[] randomBytes(Random random, int n) {
        byte[] b = new byte[n];
        // compressible: runs
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (random.nextInt(8) == 0 ? random.nextInt() : (i > 0 ? b[i - 1] : 0));
        }
        return b;
    }

    @SuppressWarnings("unused")
    private static Level unused() {
        return null;
    }
}
