package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * A biome lookup that picks a climate tree per quart column, as TerraBlender's regions do
 * ({@code compat.terrablender.TerraBlenderBiomes}). A placeholder leaf sends the quart to the tree of region 0. The GPU searches the
 * column's tree ({@link GpuWorldgen}).
 */
public interface BiomeRegions {
    /** The tree of each region index. Region 0's tree is never null. Null for a region without a tree. */
    Climate.RTree<Holder<Biome>>[] trees();

    /** The region index of quart column {@code qx, qz}. */
    int region(int qx, int qz);

    /** The lookup's biome for {@code target} at quart {@code x, y, z}. Sets the last results of the trees it searches. */
    Holder<Biome> find(Climate.TargetPoint target, int x, int y, int z);

    /** Whether {@code biome} is the placeholder that sends the quart to region 0's tree. */
    boolean deferred(Holder<Biome> biome);
}
