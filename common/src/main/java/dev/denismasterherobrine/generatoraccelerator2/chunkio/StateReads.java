package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * {@code LevelChunkSection.getBlockState} without the generic calls. Vanilla's {@code PalettedContainer.get} reaches the container's
 * strategy, storage and palette through interfaces that every container shares (block states and biomes, four kinds of palette).
 * Each read in a worldgen loop therefore pays two or three megamorphic dispatches. Here the index is spelled out, and the storage
 * and palette are tested for the classes vanilla uses. Each call is then direct and inlined. Any other kind, e.g. another mod's
 * palette, goes through its own method. The answer is the container's own: the same data, the same methods.
 */
public final class StateReads {
    private StateReads() {}

    /** The state at {@code x, y, z} (0..15 each) of the section, as {@code section.getBlockState(x, y, z)}. */
    public static BlockState get(LevelChunkSection section, int x, int y, int z) {
        return get(section.getStates().data, (y << 8 | z << 4) | x);
    }

    /** {@code PalettedContainer.get(index)} for the container's current data. */
    public static BlockState get(PalettedContainer.Data<BlockState> data, int index) {
        BitStorage storage = data.storage();
        int id = storage instanceof SimpleBitStorage simple ? simple.get(index) : storage.get(index);
        Palette<BlockState> palette = data.palette();
        if (palette instanceof LinearPalette<BlockState> linear) {
            return linear.valueFor(id);
        }
        if (palette instanceof HashMapPalette<BlockState> hash) {
            return hash.valueFor(id);
        }
        if (palette instanceof SingleValuePalette<BlockState> single) {
            return single.valueFor(id);
        }
        return palette.valueFor(id);
    }
}
