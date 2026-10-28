package dev.denismasterherobrine.generatoraccelerator2.testutil;

import com.mojang.serialization.Codec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * Full chunks of a dev-run world as bare proto chunks (block states only, all biomes plains), for benchmarks and tests that
 * want real generated content (features, structures) rather than test terrain. The default region is one of the last
 * streamed bench world ({@code -Dga2.bench.region=<.mca>} picks another).
 */
public final class RegionChunks {
    private RegionChunks() {}

    private static final Codec<PalettedContainer<BlockState>> STATES =
        PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    @Nullable
    public static Path defaultRegion() {
        String p = System.getProperty("ga2.bench.region", "E:/My Mods/GeneratorAccelerator/run/bench_bench-onstream512/region/r.-88.87.mca");
        Path path = Path.of(p);
        return Files.exists(path) ? path : null;
    }

    /** Raw chunk tags of an .mca file (zlib chunks only), at most {@code limit}, any status. */
    public static List<CompoundTag> readTags(Path file, int limit) throws IOException {
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
                out.add(NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))), NbtAccounter.UNLIMITED));
            }
        }
        return out;
    }

    /** The region's chunk origin (chunk coordinates of its corner), from the file name {@code r.<x>.<z>.mca}. */
    public static ChunkPos origin(Path region) {
        String[] parts = region.getFileName().toString().split("\\.");
        return new ChunkPos(Integer.parseInt(parts[1]) * 32, Integer.parseInt(parts[2]) * 32);
    }

    /** A side x side square of the region's full chunks starting at region-relative chunk (rx, rz), row by row. Null if any is missing. */
    @Nullable
    public static ProtoChunk[] load(Path region, int rx, int rz, int side, TerrainHarness.World world) throws IOException {
        ProtoChunk[] out = new ProtoChunk[side * side];
        ChunkPos origin = origin(region);
        Holder<Biome> plains = world.registries.biomes.getHolderOrThrow(Biomes.PLAINS);
        try (RandomAccessFile raf = new RandomAccessFile(region.toFile(), "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    int i = (rx + dx) + (rz + dz) * 32;
                    int entry = ((header[i * 4] & 255) << 24) | ((header[i * 4 + 1] & 255) << 16) | ((header[i * 4 + 2] & 255) << 8) | (header[i * 4 + 3] & 255);
                    int sector = entry >>> 8;
                    if (sector == 0) {
                        return null;
                    }
                    raf.seek(sector * 4096L);
                    int length = raf.readInt();
                    int type = raf.readByte();
                    if (type != 2) {
                        return null;
                    }
                    byte[] data = new byte[length - 1];
                    raf.readFully(data);
                    CompoundTag tag = NbtIo.read(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))), NbtAccounter.UNLIMITED);
                    if (!"minecraft:full".equals(tag.getString("Status"))) {
                        return null;
                    }
                    LevelChunkSection[] sections = new LevelChunkSection[world.heights.getSectionsCount()];
                    for (Tag t : tag.getList("sections", Tag.TAG_COMPOUND)) {
                        CompoundTag s = (CompoundTag) t;
                        int index = s.getByte("Y") - world.heights.getMinSection();
                        if (index >= 0 && index < sections.length && s.contains("block_states", Tag.TAG_COMPOUND)) {
                            PalettedContainer<BlockState> states = STATES.parse(NbtOps.INSTANCE, s.getCompound("block_states")).getOrThrow(false, error -> {});
                            sections[index] = new LevelChunkSection(states,
                                new PalettedContainer<>(world.registries.biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
                        }
                    }
                    for (int k = 0; k < sections.length; k++) {
                        if (sections[k] == null) {
                            sections[k] = new LevelChunkSection(world.registries.biomes);
                        }
                    }
                    ChunkPos pos = new ChunkPos(origin.x + rx + dx, origin.z + rz + dz);
                    ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), world.heights,
                        world.registries.biomes, null);
                    chunk.setStatus(ChunkStatus.FEATURES);
                    out[dx + dz * side] = chunk;
                }
            }
        }
        return out;
    }
}
