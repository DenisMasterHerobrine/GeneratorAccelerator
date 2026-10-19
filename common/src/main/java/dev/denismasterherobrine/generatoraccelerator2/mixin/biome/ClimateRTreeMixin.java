package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateLeaf;
import dev.denismasterherobrine.generatoraccelerator2.biome.Ga2ClimateTree;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Holds the tree's flat copy, built once on first use and published through a volatile field. */
@Mixin(Climate.RTree.class)
public abstract class ClimateRTreeMixin implements Ga2ClimateTree {
    @Unique
    private volatile FlatClimateTree ga2$flat;

    @Override
    public FlatClimateTree ga2$flat() {
        FlatClimateTree flat = this.ga2$flat;
        if (flat == null) {
            synchronized (this) {
                flat = this.ga2$flat;
                if (flat == null) {
                    flat = FlatClimateTree.build((Climate.RTree<?>) (Object) this, (leaf, id) -> ((Ga2ClimateLeaf) (Object) leaf).ga2$setIndex(id));
                    this.ga2$flat = flat;
                }
            }
        }
        return flat;
    }
}
