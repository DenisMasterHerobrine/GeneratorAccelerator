package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What {@code features.tree.TreeBees} reads of a beehive decorator. */
@Mixin(BeehiveDecorator.class)
public interface BeehiveDecoratorAccessor {
    @Accessor("probability") float ga2$probability();

    @Accessor("WORLDGEN_FACING")
    static Direction ga2$worldgenFacing() {
        throw new AssertionError();
    }

    @Accessor("SPAWN_DIRECTIONS")
    static Direction[] ga2$spawnDirections() {
        throw new AssertionError();
    }
}
