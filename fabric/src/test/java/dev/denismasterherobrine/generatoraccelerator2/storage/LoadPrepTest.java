package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.ChunkSerializerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.junit.jupiter.api.Test;

/**
 * What {@link LoadPrep} prepares on the worker pool equals what {@code ChunkSerializer.read} makes from the same tags:
 * decoded palettes (compared by re-encoding with the same codec, so palette order and bit width count), the block counts of
 * the sections built from them, and the block and fluid tick lists of full and proto chunks (ticks outside the chunk and
 * unknown ids included). A tag changed in place after preparing, or a tick list taken for another chunk, gets nothing.
 */
public class LoadPrepTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 } };
    private static final Function<String, Optional<Block>> BLOCKS = id -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(id));
    private static final Function<String, Optional<Fluid>> FLUIDS = id -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(id));

    @Test
    void sectionsMatchVanilla() {
        Codec<PalettedContainer<BlockState>> states = ChunkSerializerAccessor.ga2$blockStateCodec();
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            Registry<Biome> registry = TerrainHarness.world(preset, 1234567L).registries.biomes;
            Codec<PalettedContainerRO<Holder<Biome>>> biomes = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
            for (int[] c : CHUNKS) {
                TerrainHarness.Site site = TerrainHarness.site(preset, 1234567L, c[0], c[1]);
                ProtoChunk chunk = site.center();
                TerrainHarness.vanillaSurface(site, chunk);
                for (boolean checked : new boolean[] { false, true }) {
                    CompoundTag tag = chunkTag(chunk, states, biomes, true);
                    LoadPrep.prepare(chunk, registry, chunk.getPos(), tag, checked);
                    LoadPrep.beginForTest(tag);
                    LoadPrep.Bundle bundle = LoadPrep.current();
                    assertNotNull(bundle);
                    ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
                    for (int i = 0; i < sections.size(); i++) {
                        CompoundTag section = sections.getCompound(i);
                        String where = preset + " chunk " + c[0] + "," + c[1] + " section " + i + (checked ? " checked" : "");
                        PalettedContainer<BlockState> vanillaStates = states.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow(false, error -> {});
                        PalettedContainerRO<Holder<Biome>> vanillaBiomes = biomes.parse(NbtOps.INSTANCE, section.getCompound("biomes")).getOrThrow(false, error -> {});
                        Object preparedStates = LoadPrep.takeDecoded(bundle, section.getCompound("block_states"));
                        Object preparedBiomes = LoadPrep.takeDecoded(bundle, section.getCompound("biomes"));
                        assertNotNull(preparedStates, where + " states prepared");
                        assertNotNull(preparedBiomes, where + " biomes prepared");
                        assertTrue(LoadPrep.sameDecoded(states, vanillaStates, preparedStates), where + " states");
                        assertTrue(LoadPrep.sameDecoded(biomes, vanillaBiomes, preparedBiomes), where + " biomes");
                        @SuppressWarnings("unchecked")
                        LevelChunkSection prepared = LoadPrep.takeSection(bundle, (PalettedContainer<BlockState>) preparedStates,
                            (PalettedContainerRO<Holder<Biome>>) preparedBiomes);
                        assertNotNull(prepared, where + " section prepared");
                        assertTrue(LoadPrep.sameSection(new LevelChunkSection(vanillaStates, vanillaBiomes), prepared), where + " block counts");
                        // taken once
                        assertNull(LoadPrep.takeDecoded(bundle, section.getCompound("block_states")), where + " taken twice");
                        compared++;
                    }
                    LoadPrep.end();
                }
            }
        }
        assertTrue(compared > 100, "sections compared: " + compared);
    }

    @Test
    void changedTagsAreNotTaken() {
        Codec<PalettedContainer<BlockState>> states = ChunkSerializerAccessor.ga2$blockStateCodec();
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L).registries.biomes;
        Codec<PalettedContainerRO<Holder<Biome>>> biomes = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
        TerrainHarness.Site site = TerrainHarness.site(TerrainHarness.Preset.OVERWORLD, 1234567L, 0, 0);
        ProtoChunk chunk = site.center();
        // checked: another mod changes read, so palettes are taken only while their tags equal the copies
        CompoundTag tag = chunkTag(chunk, states, biomes, true);
        LoadPrep.prepare(chunk, registry, chunk.getPos(), tag, true);
        LoadPrep.beginForTest(tag);
        LoadPrep.Bundle bundle = LoadPrep.current();
        long changed = LoadPrep.CHANGED.sum();
        int edited = 0;
        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag blockStates = sections.getCompound(i).getCompound("block_states");
            if (blockStates.get("data") instanceof LongArrayTag data && data.getAsLongArray().length > 0) {
                // in place, as a mod's callback in read could
                data.getAsLongArray()[0] ^= 1L;
                assertNull(LoadPrep.takeDecoded(bundle, blockStates), "section " + i + " changed in place");
                edited++;
            }
        }
        assertTrue(edited > 0, "sections edited");
        assertEquals(changed + edited, LoadPrep.CHANGED.sum());
        // and no tick lists are prepared then
        assertNull(LoadPrep.takeTicks(bundle, tag.getList("block_ticks", Tag.TAG_COMPOUND), chunk.getPos(), LevelChunkTicks.class));
        LoadPrep.end();
        // a tick list taken for another chunk, or as the other kind, gets nothing
        tag = chunkTag(chunk, states, biomes, true);
        LoadPrep.prepare(chunk, registry, chunk.getPos(), tag, false);
        LoadPrep.beginForTest(tag);
        assertNull(LoadPrep.takeTicks(LoadPrep.current(), tag.getList("block_ticks", Tag.TAG_COMPOUND), new ChunkPos(5, 5), LevelChunkTicks.class));
        assertNull(LoadPrep.takeTicks(LoadPrep.current(), tag.getList("fluid_ticks", Tag.TAG_COMPOUND), chunk.getPos(), ProtoChunkTicks.class));
        LoadPrep.end();
    }

    @Test
    void ticksMatchVanilla() {
        Codec<PalettedContainer<BlockState>> states = ChunkSerializerAccessor.ga2$blockStateCodec();
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L).registries.biomes;
        Codec<PalettedContainerRO<Holder<Biome>>> biomes = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
        TerrainHarness.Site site = TerrainHarness.site(TerrainHarness.Preset.OVERWORLD, 1234567L, 3, -2);
        ProtoChunk chunk = site.center();
        for (boolean full : new boolean[] { true, false }) {
            CompoundTag tag = chunkTag(chunk, states, biomes, full);
            LoadPrep.prepare(chunk, registry, chunk.getPos(), tag, false);
            LoadPrep.beginForTest(tag);
            LoadPrep.Bundle bundle = LoadPrep.current();
            ListTag blockTicks = tag.getList("block_ticks", Tag.TAG_COMPOUND);
            ListTag fluidTicks = tag.getList("fluid_ticks", Tag.TAG_COMPOUND);
            if (full) {
                @SuppressWarnings("unchecked")
                LevelChunkTicks<Block> b = (LevelChunkTicks<Block>) LoadPrep.takeTicks(bundle, blockTicks, chunk.getPos(), LevelChunkTicks.class);
                @SuppressWarnings("unchecked")
                LevelChunkTicks<Fluid> f = (LevelChunkTicks<Fluid>) LoadPrep.takeTicks(bundle, fluidTicks, chunk.getPos(), LevelChunkTicks.class);
                assertNotNull(b);
                assertNotNull(f);
                assertEquals(LevelChunkTicks.load(blockTicks, BLOCKS, chunk.getPos()).save(0L, blk -> BuiltInRegistries.BLOCK.getKey(blk).toString()),
                    b.save(0L, blk -> BuiltInRegistries.BLOCK.getKey(blk).toString()));
                assertEquals(LevelChunkTicks.load(fluidTicks, FLUIDS, chunk.getPos()).save(0L, fl -> BuiltInRegistries.FLUID.getKey(fl).toString()),
                    f.save(0L, fl -> BuiltInRegistries.FLUID.getKey(fl).toString()));
            } else {
                @SuppressWarnings("unchecked")
                ProtoChunkTicks<Block> b = (ProtoChunkTicks<Block>) LoadPrep.takeTicks(bundle, blockTicks, chunk.getPos(), ProtoChunkTicks.class);
                @SuppressWarnings("unchecked")
                ProtoChunkTicks<Fluid> f = (ProtoChunkTicks<Fluid>) LoadPrep.takeTicks(bundle, fluidTicks, chunk.getPos(), ProtoChunkTicks.class);
                assertNotNull(b);
                assertNotNull(f);
                assertEquals(ProtoChunkTicks.load(blockTicks, BLOCKS, chunk.getPos()).save(0L, blk -> BuiltInRegistries.BLOCK.getKey(blk).toString()),
                    b.save(0L, blk -> BuiltInRegistries.BLOCK.getKey(blk).toString()));
                assertEquals(ProtoChunkTicks.load(fluidTicks, FLUIDS, chunk.getPos()).save(0L, fl -> BuiltInRegistries.FLUID.getKey(fl).toString()),
                    f.save(0L, fl -> BuiltInRegistries.FLUID.getKey(fl).toString()));
            }
            LoadPrep.end();
        }
    }

    /** A chunk tag as far as LoadPrep reads it: status, sections through the codecs, and tick lists with odd entries. */
    private static CompoundTag chunkTag(ProtoChunk chunk, Codec<PalettedContainer<BlockState>> states,
                                        Codec<PalettedContainerRO<Holder<Biome>>> biomes, boolean full) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", full ? "minecraft:full" : "minecraft:noise");
        ListTag sections = new ListTag();
        LevelChunkSection[] all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) chunk.getSectionYFromSectionIndex(i));
            section.put("block_states", states.encodeStart(NbtOps.INSTANCE, all[i].getStates()).getOrThrow(false, error -> {}));
            section.put("biomes", biomes.encodeStart(NbtOps.INSTANCE, all[i].getBiomes()).getOrThrow(false, error -> {}));
            sections.add(section);
        }
        tag.put("sections", sections);
        Random random = new Random(chunk.getPos().toLong());
        tag.put("block_ticks", ticks(chunk.getPos(), random, new String[] { "minecraft:stone", "minecraft:water", "minecraft:redstone_wire",
            "minecraft:no_such_block", "not a location" }));
        tag.put("fluid_ticks", ticks(chunk.getPos(), random, new String[] { "minecraft:water", "minecraft:flowing_lava", "minecraft:nothing" }));
        return tag;
    }

    private static ListTag ticks(ChunkPos pos, Random random, String[] ids) {
        ListTag list = new ListTag();
        for (int i = 0; i < 80; i++) {
            CompoundTag t = new CompoundTag();
            t.putString("i", ids[random.nextInt(ids.length)]);
            // mostly inside the chunk, some in the neighbour (dropped by the loader)
            int dx = random.nextInt(10) == 0 ? 16 + random.nextInt(16) : random.nextInt(16);
            t.putInt("x", pos.getMinBlockX() + dx);
            t.putInt("y", random.nextInt(300) - 60);
            t.putInt("z", pos.getMinBlockZ() + random.nextInt(16));
            t.putInt("t", random.nextInt(40) - 5);
            t.putInt("p", random.nextInt(7) - 3);
            list.add(t);
        }
        return list;
    }
}
