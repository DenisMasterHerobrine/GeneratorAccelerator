package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateLeaf;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The leaf's id in the tree's flat copy. Written while the copy is built, before the copy is published. */
@Mixin(Climate.RTree.Leaf.class)
public abstract class ClimateLeafMixin implements Ga2ClimateLeaf {
    @Unique
    private int ga2$index = -1;

    @Override
    public int ga2$index() {
        return this.ga2$index;
    }

    @Override
    public void ga2$setIndex(int index) {
        this.ga2$index = index;
    }
}
