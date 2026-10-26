package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.state.PaletteCopy;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

/**
 * A container resize with one palette lookup per distinct old id ({@code PaletteCopyMixin}) leaves the same palette (values
 * in the same order) and the same storage as vanilla's copy. The same writes are replayed on two containers, one per copy
 * method. The writes go through every palette kind and every resize between them (single value, linear widths, hash
 * widths, global).
 */
public class PaletteCopyTest {
    @Test
    void blockStateResizes() {
        List<BlockState> all = new ArrayList<>();
        Block.BLOCK_STATE_REGISTRY.forEach(all::add);
        Random random = new Random(21);
        int resizesChecked = 0;
        for (int run = 0; run < 12; run++) {
            Collections.shuffle(all, random);
            int distinct = new int[] { 2, 5, 16, 17, 40, 256, 257, 600 }[run % 8];
            List<BlockState> values = new ArrayList<>(all.subList(0, distinct));
            long seed = random.nextLong();
            PalettedContainer<BlockState> fast = states();
            PalettedContainer<BlockState> vanilla = states();
            resizesChecked += replay(fast, vanilla, values, seed, 16);
        }
        assertTrue(resizesChecked > 25, "resizes compared: " + resizesChecked);
    }

    @Test
    void biomeResizes() {
        Registry<Biome> registry = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L).registries.biomes;
        List<Holder<Biome>> all = new ArrayList<>();
        registry.holders().forEach(all::add);
        Random random = new Random(23);
        for (int run = 0; run < 8; run++) {
            Collections.shuffle(all, random);
            List<Holder<Biome>> values = new ArrayList<>(all.subList(0, Math.min(all.size(), new int[] { 2, 3, 5, 9, 17, 33, 50, 64 }[run])));
            long seed = random.nextLong();
            PalettedContainer<Holder<Biome>> fast = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            PalettedContainer<Holder<Biome>> vanilla = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            replay(fast, vanilla, values, seed, 4);
        }
    }

    private static PalettedContainer<BlockState> states() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    /** The same random writes on both containers, one copying the GA2 way and one vanilla's. Compared after every resize. */
    private static <T> int replay(PalettedContainer<T> fast, PalettedContainer<T> vanilla, List<T> values, long seed, int side) {
        Random a = new Random(seed);
        int size = side * side * side;
        int resizes = 0;
        int lastBits = -1;
        for (int i = 0; i < 3 * size; i++) {
            int index = a.nextInt(size);
            // new values come in slowly, so the palette grows one value (and one resize) at a time
            T value = values.get(a.nextInt(Math.min(values.size(), 1 + i / 24)));
            int x = index % side, z = (index / side) % side, y = index / (side * side);
            set(fast, x, y, z, value, true);
            set(vanilla, x, y, z, value, false);
            int bits = fast.data.storage().getBits();
            if (bits != lastBits) {
                lastBits = bits;
                resizes++;
                same(fast, vanilla, "after write " + i);
            }
        }
        same(fast, vanilla, "at the end");
        return resizes;
    }

    private static <T> void set(PalettedContainer<T> container, int x, int y, int z, T value, boolean fast) {
        boolean was = PaletteCopy.ENABLED;
        try {
            PaletteCopy.ENABLED = fast;
            container.getAndSetUnchecked(x, y, z, value);
        } finally {
            PaletteCopy.ENABLED = was;
        }
    }

    private static <T> void same(PalettedContainer<T> fast, PalettedContainer<T> vanilla, String where) {
        PalettedContainer.Data<T> f = fast.data, v = vanilla.data;
        assertEquals(v.storage().getBits(), f.storage().getBits(), where + " bits");
        assertSame(v.palette().getClass(), f.palette().getClass(), where + " palette kind");
        assertEquals(v.palette().getSize(), f.palette().getSize(), where + " palette size");
        if (!(v.palette() instanceof GlobalPalette<?>)) {
            for (int i = 0; i < v.palette().getSize(); i++) {
                assertSame(v.palette().valueFor(i), f.palette().valueFor(i), where + " palette entry " + i);
            }
        }
        assertArrayEquals(v.storage().getRaw(), f.storage().getRaw(), where + " storage");
    }
}
