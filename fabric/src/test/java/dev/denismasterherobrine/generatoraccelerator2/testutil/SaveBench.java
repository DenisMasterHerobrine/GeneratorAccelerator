package dev.denismasterherobrine.generatoraccelerator2.testutil;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.generatoraccelerator2.storage.FastPack;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Cost of encoding sections for saving (most of {@code ChunkSerializer.write}), per chunk, with each switchable change
 * on and off. Sections come from full chunks of a dev-run world ({@code -Dga2.bench.region=<.mca file>}, default: a
 * region of the last streamed bench world), decoded with vanilla's codecs.
 * {@code gradlew test --tests "*SaveBench*" -Dga2.bench=true}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "true")
public class SaveBench {
    private static final Codec<PalettedContainer<BlockState>> STATES =
        PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    @Test
    void encodeSections() throws IOException {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Codec<PalettedContainerRO<Holder<Biome>>> biomes = PalettedContainer.codecRO(world.registries.biomes.asHolderIdMap(),
            world.registries.biomes.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, world.registries.biomes.getHolderOrThrow(Biomes.PLAINS));
        Path region = Path.of(System.getProperty("ga2.bench.region", "../run/bench_bench-onstream512/region/r.-88.87.mca"));
        if (!Files.exists(region)) {
            region = Path.of(System.getProperty("ga2.bench.region", "run/bench_bench-onstream512/region/r.-88.87.mca"));
        }
        List<CompoundTag> chunks = readRegion(region, 256);
        List<PalettedContainer<BlockState>> states = new ArrayList<>();
        List<PalettedContainerRO<Holder<Biome>>> biomeList = new ArrayList<>();
        for (CompoundTag chunk : chunks) {
            for (Tag t : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                CompoundTag section = (CompoundTag) t;
                if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                    states.add(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow());
                }
                if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                    biomeList.add(biomes.parse(NbtOps.INSTANCE, section.getCompound("biomes")).getOrThrow());
                }
            }
        }
        System.out.println(String.format(Locale.ROOT, "save bench: %s, %d full chunks, %d sections", region, chunks.size(), states.size()));
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        for (int round = 0; round < rounds; round++) {
            for (boolean fast : new boolean[] { false, true }) {
                FastPack.ENABLED = fast;
                long t0 = System.nanoTime();
                int sink = 0;
                for (PalettedContainer<BlockState> c : states) {
                    sink += STATES.encodeStart(NbtOps.INSTANCE, c).getOrThrow().getId();
                }
                long t1 = System.nanoTime();
                for (PalettedContainerRO<Holder<Biome>> c : biomeList) {
                    sink += biomes.encodeStart(NbtOps.INSTANCE, c).getOrThrow().getId();
                }
                long t2 = System.nanoTime();
                if (round >= rounds / 2) {
                    System.out.println(String.format(Locale.ROOT, "save bench: fastPack=%-5s states %.1f us/chunk, biomes %.1f us/chunk (sink %d)", fast,
                        (t1 - t0) / 1e3 / chunks.size(), (t2 - t1) / 1e3 / chunks.size(), sink));
                }
            }
        }
        FastPack.ENABLED = true;
    }

    /** The IO thread's part: NBT write + deflate per chunk, vanilla's stream chain against {@code FastRegionWrite.encode}. */
    @Test
    void encodeRegionChunks() throws IOException {
        Path region = Path.of(System.getProperty("ga2.bench.region", "run/bench_bench-onstream512/region/r.-88.87.mca"));
        List<CompoundTag> chunks = readRegion(region, 400);
        int rounds = Integer.getInteger("ga2.bench.rounds", 12);
        for (int round = 0; round < rounds; round++) {
            long t0 = System.nanoTime();
            long bytes = 0;
            for (CompoundTag tag : chunks) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8096);
                out.write(new byte[5]);
                try (java.io.DataOutputStream data = new java.io.DataOutputStream(net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_DEFLATE.wrap(out))) {
                    NbtIo.write(tag, data);
                }
                bytes += out.size();
            }
            long t1 = System.nanoTime();
            for (CompoundTag tag : chunks) {
                bytes += dev.denismasterherobrine.generatoraccelerator2.storage.FastRegionWrite.encode(tag, 2).limit();
            }
            long t2 = System.nanoTime();
            if (round >= rounds / 2) {
                System.out.println(String.format(Locale.ROOT, "region write bench: vanilla %.1f us/chunk, fast %.1f us/chunk (%d bytes)", (t1 - t0) / 1e3 / chunks.size(),
                    (t2 - t1) / 1e3 / chunks.size(), bytes));
            }
        }
    }

    /** Full chunks of an .mca file (zlib chunks only), at most {@code limit}. */
    static List<CompoundTag> readRegion(Path file, int limit) throws IOException {
        List<CompoundTag> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int i = 0; i < 1024 && out.size() < limit; i++) {
                int entry = ((header[i * 4] & 255) << 24) | ((header[i * 4 + 1] & 255) << 16) | ((header[i * 4 + 2] & 255) << 8) | (header[i * 4 + 3] & 255);
                int sector = entry >>> 8;
                if (sector == 0) {
                    continue;
                }
                raf.seek(sector * 4096L);
                int length = raf.readInt();
                int type = raf.readByte();
                if (type != 2 || length <= 1) {
                    continue;
                }
                byte[] data = new byte[length - 1];
                raf.readFully(data);
                CompoundTag tag = NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))), NbtAccounter.unlimitedHeap());
                if ("minecraft:full".equals(tag.getString("Status"))) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static ListTag unused() {
        return new ListTag();
    }
}
