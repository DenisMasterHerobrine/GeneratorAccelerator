package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

/**
 * Section tags encoded ahead of a save ({@link SavePrep}) are the tags the save builds, and are refused once the section
 * changed: real sections of every preset (after noise and after surface), containers of every palette kind, then writes that
 * change a value, add a value (a palette resize), or write the value already there.
 */
public class SavePrepTest {
    private static final Codec<PalettedContainer<BlockState>> STATES =
        PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    @Test
    void realSectionsMatchTheSave() {
        int sections = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            TerrainHarness.World world = TerrainHarness.world(preset, 1234567L);
            Registry<Biome> registry = world.registries.biomes;
            Codec<PalettedContainerRO<Holder<Biome>>> biomes = biomeCodec(registry);
            for (int[] c : new int[][] { { 0, 0 }, { -300, 412 } }) {
                TerrainHarness.Site site = TerrainHarness.site(preset, 1234567L, c[0], c[1]);
                ProtoChunk noise = site.center();
                ProtoChunk surface = site.center();
                TerrainHarness.vanillaSurface(site, surface);
                for (ProtoChunk chunk : new ProtoChunk[] { noise, surface }) {
                    SavePrep.prepare(chunk, registry);
                    for (LevelChunkSection section : chunk.getSections()) {
                        String where = preset + " " + c[0] + "," + c[1] + " section " + sections;
                        Tag states = SavePrep.take(section.getStates(), () -> null);
                        assertNotNull(states, where + " states prepared");
                        same(vanilla(() -> STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow(false, error -> {})), states, where + " states");
                        Tag biomeTag = SavePrep.take((PalettedContainer<?>) section.getBiomes(), () -> null);
                        assertNotNull(biomeTag, where + " biomes prepared");
                        same(vanilla(() -> biomes.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow(false, error -> {})), biomeTag, where + " biomes");
                        // taken once: the next save encodes again unless prepared again
                        assertNull(SavePrep.take(section.getStates(), () -> null), where + " taken once");
                        sections++;
                    }
                }
            }
        }
        assertTrue(sections > 300, "sections compared: " + sections);
    }

    @Test
    void writtenSectionsAreRefused() {
        TerrainHarness.World world = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        TerrainHarness.Site site = TerrainHarness.site(TerrainHarness.Preset.OVERWORLD, 1234567L, 3, 3);
        ProtoChunk chunk = site.center();
        TerrainHarness.vanillaSurface(site, chunk);
        Registry<Biome> registry = world.registries.biomes;
        LevelChunkSection[] sections = chunk.getSections();
        int written = sections.length / 2;
        SavePrep.prepare(chunk, registry);
        // one block of one section changes after the encoding
        int y = chunk.getMinBuildHeight() + 16 * written + 5;
        BlockState before = chunk.getBlockState(new BlockPos(7, y, 9));
        BlockState after = before.is(Blocks.GLOWSTONE) ? Blocks.STONE.defaultBlockState() : Blocks.GLOWSTONE.defaultBlockState();
        chunk.setBlockState(new BlockPos(7, y, 9), after, false);
        long changed = SavePrep.CHANGED.sum();
        for (int i = 0; i < sections.length; i++) {
            Tag tag = SavePrep.take(sections[i].getStates(), () -> null);
            if (i == written) {
                assertNull(tag, "section " + i + " was written to");
            } else {
                assertNotNull(tag, "section " + i + " is unchanged");
                PalettedContainer<BlockState> states = sections[i].getStates();
                same(vanilla(() -> STATES.encodeStart(NbtOps.INSTANCE, states).getOrThrow(false, error -> {})), tag, "section " + i);
            }
        }
        assertEquals(changed + 1, SavePrep.CHANGED.sum());
    }

    @Test
    void everyPaletteKind() {
        List<BlockState> all = new ArrayList<>();
        Block.BLOCK_STATE_REGISTRY.forEach(all::add);
        Random random = new Random(3);
        int checked = 0;
        // single value, linear (up to 16), hash (up to 256), global
        for (int n : new int[] { 1, 2, 9, 16, 17, 100, 256, 257, 900 }) {
            List<BlockState> values = pick(all, n, random);
            for (int write = 0; write < 4; write++) {
                PalettedContainer<BlockState> container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES);
                for (int i = 0; i < 4096; i++) {
                    container.getAndSetUnchecked(i & 15, i >> 8, (i >> 4) & 15, values.get(i < n ? i : random.nextInt(n)));
                }
                prepareStates(container);
                String where = "n=" + n + " write " + write;
                switch (write) {
                    case 0 -> {
                        // unchanged
                        Tag tag = SavePrep.take(container, () -> null);
                        assertNotNull(tag, where);
                        same(vanilla(() -> STATES.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, error -> {})), tag, where);
                    }
                    case 1 -> {
                        // the value already there: the data is the same, so is the tag
                        container.getAndSetUnchecked(5, 6, 7, container.get(5, 6, 7));
                        Tag tag = SavePrep.take(container, () -> null);
                        assertNotNull(tag, where);
                        same(vanilla(() -> STATES.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, error -> {})), tag, where);
                    }
                    case 2 -> {
                        // another value of the palette
                        BlockState other = values.get((values.indexOf(container.get(5, 6, 7)) + 1) % n);
                        BlockState single = container.get(5, 6, 7).is(Blocks.STONE) ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                        container.getAndSetUnchecked(5, 6, 7, n == 1 ? single : other);
                        assertNull(SavePrep.take(container, () -> null), where);
                    }
                    default -> {
                        // a value the palette does not hold yet (a resize at a palette's limit)
                        BlockState fresh = all.stream().filter(s -> !values.contains(s)).findFirst().orElseThrow();
                        container.getAndSetUnchecked(0, 0, 0, fresh);
                        assertNull(SavePrep.take(container, () -> null), where);
                    }
                }
                checked++;
            }
        }
        assertEquals(36, checked);
    }

    @Test
    void biomePalettes() {
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L).registries.biomes;
        Codec<PalettedContainerRO<Holder<Biome>>> codec = biomeCodec(registry);
        List<Holder<Biome>> all = new ArrayList<>();
        registry.holders().forEach(all::add);
        Random random = new Random(5);
        for (int n : new int[] { 1, 2, 4, 5, 9, 40, 64 }) {
            List<Holder<Biome>> values = pick(all, Math.min(n, all.size()), random);
            PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
            for (int i = 0; i < 64; i++) {
                container.getAndSetUnchecked(i & 3, i >> 4, (i >> 2) & 3, values.get(i < values.size() ? i : random.nextInt(values.size())));
            }
            SavePrep.prepareContainer(container, data -> SectionCodec.biomes(FastPack.pack(data, registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES)));
            Tag tag = SavePrep.take(container, () -> null);
            assertNotNull(tag, "n=" + n);
            same(vanilla(() -> codec.encodeStart(NbtOps.INSTANCE, container).getOrThrow(false, error -> {})), tag, "biomes n=" + n);
        }
    }

    @Test
    void tickLists() {
        Function<Block, String> id = b -> BuiltInRegistries.BLOCK.getKey(b).toString();
        Random random = new Random(9);
        Block[] blocks = { Blocks.WATER, Blocks.LAVA, Blocks.REDSTONE_WIRE, Blocks.SAND, Blocks.REPEATER };
        TickPriority[] priorities = TickPriority.values();
        // a full chunk's queue, prepared at one game time and saved at a later one: the delays count from the save's time
        LevelChunkTicks<Block> queue = new LevelChunkTicks<>();
        for (int i = 0; i < 300; i++) {
            queue.schedule(new ScheduledTick<>(blocks[random.nextInt(blocks.length)],
                new BlockPos(random.nextInt(16), random.nextInt(300) - 60, random.nextInt(16)), 1000 + random.nextInt(50),
                priorities[random.nextInt(priorities.length)], i));
        }
        for (long saveTime : new long[] { 990, 995, 1003 }) {
            SavePrep.prepareTicks(queue, id, 990);
            Tag tag = SavePrep.takeTicks(queue, saveTime, () -> null);
            assertNotNull(tag, "queue at " + saveTime);
            same(queue.save(saveTime, id), tag, "queue at " + saveTime);
        }
        // pending ticks (loaded from a save, not scheduled yet) come first
        List<SavedTick<Block>> saved = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            saved.add(new SavedTick<>(blocks[i % blocks.length], new BlockPos(i % 16, i, (i * 7) % 16), i % 9, priorities[i % priorities.length]));
        }
        LevelChunkTicks<Block> loaded = new LevelChunkTicks<>(saved);
        SavePrep.prepareTicks(loaded, id, 0);
        same(loaded.save(7, id), SavePrep.takeTicks(loaded, 7, () -> null), "pending ticks");
        // a generating chunk's list
        ProtoChunkTicks<Block> proto = new ProtoChunkTicks<>();
        for (int i = 0; i < 120; i++) {
            proto.schedule(new ScheduledTick<>(blocks[i % blocks.length], new BlockPos(i % 16, i - 60, (i * 3) % 16), 0,
                priorities[i % priorities.length], 0));
        }
        SavePrep.prepareTicks(proto, id, 0);
        same(proto.save(55, id), SavePrep.takeTicks(proto, 55, () -> null), "proto ticks");
        // a tick scheduled after the encoding: refused
        long changed = SavePrep.TICKS_CHANGED.sum();
        SavePrep.prepareTicks(queue, id, 990);
        queue.schedule(new ScheduledTick<>(Blocks.STONE, new BlockPos(1, 2, 3), 2000, 7));
        assertNull(SavePrep.takeTicks(queue, 990, () -> null), "queue changed");
        SavePrep.prepareTicks(proto, id, 0);
        proto.schedule(new ScheduledTick<>(Blocks.STONE, new BlockPos(1, 2, 3), 0, 8));
        assertNull(SavePrep.takeTicks(proto, 0, () -> null), "proto changed");
        assertEquals(changed + 2, SavePrep.TICKS_CHANGED.sum());
    }

    private static void prepareStates(PalettedContainer<BlockState> container) {
        SavePrep.prepareContainer(container, data -> SectionCodec.blockStates(FastPack.pack(data, Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES)));
    }

    /** Vanilla's encoding: the codec with GA2's pack and section codec off. */
    private static Tag vanilla(Supplier<Tag> encode) {
        boolean pack = FastPack.ENABLED, codec = SectionCodec.ENABLED;
        try {
            FastPack.ENABLED = false;
            SectionCodec.ENABLED = false;
            return encode.get();
        } finally {
            FastPack.ENABLED = pack;
            SectionCodec.ENABLED = codec;
        }
    }

    private static void same(Tag expected, Tag got, String where) {
        assertEquals(expected, got, where);
        assertArrayEquals(bytes(expected), bytes(got), where + " bytes");
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
