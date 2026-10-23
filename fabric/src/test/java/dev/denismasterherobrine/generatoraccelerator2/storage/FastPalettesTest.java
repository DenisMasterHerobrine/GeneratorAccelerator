package dev.denismasterherobrine.generatoraccelerator2.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.denismasterherobrine.generatoraccelerator2.mixin.storage.ChunkSerializerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * {@link FastPalettes} gives the section codecs' result or nothing. Real sections of every preset decode to the codec's
 * container (compared by encoding both again, so palette order, bit width and storage count), twice so the second pass answers
 * from the kept entries. Hand-made tags with an unknown block or biome, a property value the block does not have, missing or
 * mistyped data, and data of the wrong length either give the codec's clean result too, or nothing, so the codec decides.
 */
public class FastPalettesTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 } };

    @Test
    void realSectionsMatchTheCodec() {
        Codec<PalettedContainer<BlockState>> states = ChunkSerializerAccessor.ga2$blockStateCodec();
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            Registry<Biome> registry = TerrainHarness.world(preset, 1234567L).registries.biomes;
            Codec<PalettedContainerRO<Holder<Biome>>> biomes = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
            for (int[] c : CHUNKS) {
                TerrainHarness.Site site = TerrainHarness.site(preset, 1234567L, c[0], c[1]);
                ProtoChunk chunk = site.center();
                TerrainHarness.vanillaSurface(site, chunk);
                for (int pass = 0; pass < 2; pass++) {
                    for (LevelChunkSection section : chunk.getSections()) {
                        String where = preset + " chunk " + c[0] + "," + c[1] + " pass " + pass;
                        CompoundTag s = (CompoundTag) states.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow();
                        CompoundTag b = (CompoundTag) biomes.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow();
                        PalettedContainer<BlockState> fastStates = FastPalettes.states(s);
                        PalettedContainerRO<Holder<Biome>> fastBiomes = FastPalettes.biomes(registry, b);
                        assertNotNull(fastStates, where + " states decoded");
                        assertNotNull(fastBiomes, where + " biomes decoded");
                        assertTrue(LoadPrep.sameDecoded(states, states.parse(NbtOps.INSTANCE, s).getOrThrow(), fastStates), where + " states");
                        assertTrue(LoadPrep.sameDecoded(biomes, biomes.parse(NbtOps.INSTANCE, b).getOrThrow(), fastBiomes), where + " biomes");
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 100, "sections compared: " + compared);
    }

    @Test
    void oddTagsGiveTheCodecsResultOrNothing() {
        Codec<PalettedContainer<BlockState>> states = ChunkSerializerAccessor.ga2$blockStateCodec();
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.values()[0], 1234567L).registries.biomes;
        Codec<PalettedContainerRO<Holder<Biome>>> biomes = ChunkSerializerAccessor.ga2$makeBiomeCodec(registry);
        List<CompoundTag> stateTags = new ArrayList<>();
        stateTags.add(blockStates(List.of(block("minecraft:stone", null)), null));
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:dirt", null)), null));
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:dirt", null)), new LongArrayTag(new long[256])));
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:dirt", null)), new LongArrayTag(new long[3])));
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:dirt", null)), new IntArrayTag(new int[512])));
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("ga2test:no_such_block", null)), new LongArrayTag(new long[256])));
        CompoundTag badFacing = new CompoundTag();
        badFacing.putString("facing", "up");
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:furnace", badFacing)), new LongArrayTag(new long[256])));
        CompoundTag unknownProperty = new CompoundTag();
        unknownProperty.putString("ga2test", "x");
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:oak_log", unknownProperty)), new LongArrayTag(new long[256])));
        CompoundTag axis = new CompoundTag();
        axis.putString("axis", "x");
        stateTags.add(blockStates(List.of(block("minecraft:stone", null), block("minecraft:oak_log", axis)), new LongArrayTag(new long[256])));
        CompoundTag extraKey = block("minecraft:stone", null);
        extraKey.putInt("ga2test", 1);
        stateTags.add(blockStates(List.of(extraKey), null));
        stateTags.add(blockStates(List.of(), null));
        int decoded = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (CompoundTag tag : stateTags) {
                decoded += same(states, tag, FastPalettes.states(tag), tag + " pass " + pass);
            }
        }
        List<CompoundTag> biomeTags = new ArrayList<>();
        biomeTags.add(biomes(List.of("minecraft:plains"), null));
        biomeTags.add(biomes(List.of("minecraft:plains", "minecraft:forest"), new LongArrayTag(new long[2])));
        biomeTags.add(biomes(List.of("minecraft:plains", "ga2test:no_such_biome"), new LongArrayTag(new long[2])));
        biomeTags.add(biomes(List.of("minecraft:plains", "Not A Name"), new LongArrayTag(new long[2])));
        biomeTags.add(biomes(List.of("minecraft:plains", "minecraft:forest"), null));
        for (int pass = 0; pass < 2; pass++) {
            for (CompoundTag tag : biomeTags) {
                decoded += same(biomes, tag, FastPalettes.biomes(registry, tag), tag + " pass " + pass);
            }
        }
        assertTrue(decoded >= 8, "decoded without the codec: " + decoded);
    }

    /** 1 when {@code fast} was decoded and equals the codec's clean result, 0 when it was left to the codec. Fails otherwise. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static int same(Codec codec, CompoundTag tag, Object fast, String where) {
        if (fast == null) {
            return 0;
        }
        DataResult<?> vanilla = codec.parse(NbtOps.INSTANCE, tag);
        Optional<?> value = vanilla.result();
        assertTrue(value.isPresent() && vanilla.error().isEmpty(), where + ": decoded, but the codec has an error");
        assertTrue(LoadPrep.sameDecoded(codec, value.get(), fast), where);
        return 1;
    }

    private static CompoundTag block(String name, CompoundTag properties) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        if (properties != null) {
            t.put("Properties", properties);
        }
        return t;
    }

    private static CompoundTag blockStates(List<CompoundTag> palette, Tag data) {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        list.addAll(palette);
        t.put("palette", list);
        if (data != null) {
            t.put("data", data);
        }
        return t;
    }

    private static CompoundTag biomes(List<String> palette, Tag data) {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        for (String name : palette) {
            list.add(StringTag.valueOf(name));
        }
        t.put("palette", list);
        if (data != null) {
            t.put("data", data);
        }
        return t;
    }
}
