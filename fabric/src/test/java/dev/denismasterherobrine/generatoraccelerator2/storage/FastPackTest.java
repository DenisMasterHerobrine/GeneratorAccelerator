package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.generatoraccelerator2.testutil.RegionChunks;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

/**
 * Saving with the one-pass pack gives byte-identical section tags. Checked on real sections of every preset (after
 * noise and after surface) and on synthetic block state and biome containers of every palette kind (single, linear,
 * hash, global), including palettes that still hold values no longer present.
 */
public class FastPackTest {
    private static final Codec<PalettedContainer<BlockState>> STATES =
        PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 } };

    @Test
    void realSections() {
        long before = FastPack.PACKED.sum();
        int sections = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            TerrainHarness.World world = TerrainHarness.world(preset, 1234567L);
            Codec<PalettedContainerRO<Holder<Biome>>> biomes = biomeCodec(world.registries.biomes);
            for (int[] c : CHUNKS) {
                TerrainHarness.Site site = TerrainHarness.site(preset, 1234567L, c[0], c[1]);
                ProtoChunk noise = site.center();
                ProtoChunk surface = site.center();
                TerrainHarness.vanillaSurface(site, surface);
                for (ProtoChunk chunk : new ProtoChunk[] { noise, surface }) {
                    for (LevelChunkSection section : chunk.getSections()) {
                        String where = preset + " chunk " + c[0] + "," + c[1] + " section " + sections;
                        same(() -> STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow(false, error -> {}), where + " states");
                        same(() -> biomes.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow(false, error -> {}), where + " biomes");
                        sections++;
                    }
                }
            }
        }
        assertTrue(sections > 500, "sections compared: " + sections);
        // the harness clones chunks through the codec too, so at least every compared container went through the fast pack
        assertTrue(FastPack.PACKED.sum() - before >= 2L * sections, "fast packs: " + (FastPack.PACKED.sum() - before));
    }

    @Test
    void syntheticBlockStates() {
        List<BlockState> all = new ArrayList<>();
        Block.BLOCK_STATE_REGISTRY.forEach(all::add);
        Random random = new Random(7);
        int[] distinct = { 1, 2, 3, 15, 16, 17, 33, 255, 256, 257, 400, 1000, 4096 };
        int compared = 0;
        for (int n : distinct) {
            for (int pattern = 0; pattern < 4; pattern++) {
                List<BlockState> values = pick(all, n, random);
                PalettedContainer<BlockState> container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES);
                fill(container, values, pattern, random, 16);
                if (pattern == 3) {
                    // overwrite most of it: the palette keeps values the storage no longer holds
                    for (int i = 0; i < 4096 - 7; i++) {
                        container.getAndSetUnchecked(i & 15, i >> 8, (i >> 4) & 15, values.get(0));
                    }
                }
                same(() -> STATES.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, error -> {}), "states n=" + n + " pattern " + pattern);
                same(() -> STATES.encodeStart(NbtOps.INSTANCE, container.copy()).getOrThrow(false, error -> {}), "states copy n=" + n + " pattern " + pattern);
                compared++;
            }
        }
        assertEquals(distinct.length * 4, compared);
    }

    @Test
    void syntheticBiomes() {
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L).registries.biomes;
        Codec<PalettedContainerRO<Holder<Biome>>> codec = biomeCodec(registry);
        List<Holder<Biome>> all = new ArrayList<>();
        registry.holders().forEach(all::add);
        Random random = new Random(11);
        for (int n : new int[] { 1, 2, 3, 4, 5, 8, 9, 17, 40, 64 }) {
            for (int pattern = 0; pattern < 4; pattern++) {
                List<Holder<Biome>> values = pick(all, Math.min(n, all.size()), random);
                PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS),
                    PalettedContainer.Strategy.SECTION_BIOMES);
                fill(container, values, pattern, random, 4);
                if (pattern == 3) {
                    for (int i = 0; i < 64 - 3; i++) {
                        container.getAndSetUnchecked(i & 3, i >> 4, (i >> 2) & 3, values.get(0));
                    }
                }
                same(() -> codec.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, error -> {}), "biomes n=" + n + " pattern " + pattern);
            }
        }
    }

    /** {@link SectionCodec} builds the same block state and biome tags as the codecs, for real and synthetic sections. */
    @Test
    void sectionCodecMatchesCodecs() throws IOException {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Codec<PalettedContainerRO<Holder<Biome>>> biomes = biomeCodec(world.registries.biomes);
        List<PalettedContainer<BlockState>> states = new ArrayList<>();
        List<PalettedContainerRO<Holder<Biome>>> biomeList = new ArrayList<>();
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            TerrainHarness.Site site = TerrainHarness.site(preset, 1234567L, 5, -9);
            ProtoChunk chunk = site.center();
            TerrainHarness.vanillaSurface(site, chunk);
            for (LevelChunkSection section : chunk.getSections()) {
                states.add(section.getStates());
                if (preset == TerrainHarness.Preset.OVERWORLD) {
                    biomeList.add(section.getBiomes());
                }
            }
        }
        Path region = RegionChunks.defaultRegion();
        if (region != null) {
            for (CompoundTag tag : RegionChunks.readTags(region, 64)) {
                for (Tag t : tag.getList("sections", Tag.TAG_COMPOUND)) {
                    CompoundTag section = (CompoundTag) t;
                    if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                        states.add(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow(false, error -> {}));
                    }
                    if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                        biomeList.add(biomes.parse(NbtOps.INSTANCE, section.getCompound("biomes")).getOrThrow(false, error -> {}));
                    }
                }
            }
        }
        for (int i = 0; i < states.size(); i++) {
            Tag vanilla = STATES.encodeStart(NbtOps.INSTANCE, states.get(i)).getOrThrow(false, error -> {});
            Tag fast = SectionCodec.blockStates(states.get(i));
            assertEquals(vanilla, fast, "block states " + i);
            assertArrayEquals(bytes(vanilla), bytes(fast), "block states bytes " + i);
        }
        for (int i = 0; i < biomeList.size(); i++) {
            Tag vanilla = biomes.encodeStart(NbtOps.INSTANCE, biomeList.get(i)).getOrThrow(false, error -> {});
            Tag fast = SectionCodec.biomes(biomeList.get(i), world.registries.biomes);
            assertEquals(vanilla, fast, "biomes " + i);
            assertArrayEquals(bytes(vanilla), bytes(fast), "biomes bytes " + i);
        }
        assertTrue(states.size() > 100, "sections compared: " + states.size());
    }

    /** Pattern 0: random entries; 1: long runs; 2: one value per y layer then random noise; 3: random, later overwritten. */
    private static <T> void fill(PalettedContainer<T> container, List<T> values, int pattern, Random random, int side) {
        int size = side * side * side;
        for (int i = 0; i < size; i++) {
            int x = i % side;
            int z = (i / side) % side;
            int y = i / (side * side);
            T value = switch (pattern) {
                case 1 -> values.get(Math.min(values.size() - 1, i * values.size() / size));
                case 2 -> random.nextInt(8) == 0 ? values.get(random.nextInt(values.size())) : values.get(y % values.size());
                default -> values.get(random.nextInt(values.size()));
            };
            container.getAndSetUnchecked(x, y, z, value);
        }
        if (values.size() > 1 && pattern != 1) {
            // every value at least once
            for (int k = 0; k < values.size() && k < size; k++) {
                container.getAndSetUnchecked(k % side, k / (side * side), (k / side) % side, values.get(k));
            }
        }
    }

    private static <T> List<T> pick(List<T> all, int n, Random random) {
        List<T> copy = new ArrayList<>(all);
        Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, n));
    }

    private static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(Registry<Biome> biomes) {
        return PalettedContainer.codecRO(biomes.asHolderIdMap(), biomes.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES,
            biomes.getHolderOrThrow(Biomes.PLAINS));
    }

    private static void same(Supplier<Tag> encode, String where) {
        Tag vanilla;
        try {
            FastPack.ENABLED = false;
            vanilla = encode.get();
        } finally {
            FastPack.ENABLED = true;
        }
        Tag fast = encode.get();
        assertEquals(vanilla, fast, where);
        assertArrayEquals(bytes(vanilla), bytes(fast), where + " bytes");
    }

    private static byte[] bytes(Tag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeUnnamedTag(tag, new DataOutputStream(out));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
