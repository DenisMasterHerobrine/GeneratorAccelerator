package dev.denismasterherobrine.generatoraccelerator2.carver;

import java.util.Arrays;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks {@code WorldCarver.carveBlock} tests. The carver runner tests them the same way. A grass or mycelium block
 * marks the column's surface. Dirt under a carved block below the surface takes the biome's top material. Mods that
 * extend those tests add their blocks here before worldgen starts. Their mixins on {@code carveBlock} are handled in
 * {@code compat/<modid>}.
 */
public final class CarveBlocks {
    private CarveBlocks() {}

    private static volatile Block[] surface;
    private static volatile Block[] dirt;

    /** {@code state.is(GRASS_BLOCK) || state.is(MYCELIUM)}, with the blocks other mods add to that test. */
    public static boolean surface(BlockState state) {
        Block[] blocks = surface;
        if (blocks == null) {
            return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM);
        }
        for (Block b : blocks) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    /** {@code state.is(DIRT)}, with the blocks other mods add to that test. */
    public static boolean dirt(BlockState state) {
        Block[] blocks = dirt;
        if (blocks == null) {
            return state.is(Blocks.DIRT);
        }
        for (Block b : blocks) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void addSurface(Block... blocks) {
        surface = join(surface == null ? new Block[] {Blocks.GRASS_BLOCK, Blocks.MYCELIUM} : surface, blocks);
    }

    public static synchronized void addDirt(Block... blocks) {
        dirt = join(dirt == null ? new Block[] {Blocks.DIRT} : dirt, blocks);
    }

    private static Block[] join(Block[] a, Block[] b) {
        Block[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
