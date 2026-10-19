package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A box fill holds the monitor of its block selector. The selector keeps the block picked for the
 * current position in a field between {@code next} and {@code getNext}. Some selectors are static
 * and shared by every structure of that kind, e.g. jungle temple stone and stronghold bricks.
 */
@Mixin(StructurePiece.class)
public abstract class StructurePieceMixin {
    @WrapMethod(method = "generateBox(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;IIIIIIZLnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;)V")
    private void ga2$holdSelector(WorldGenLevel level, BoundingBox box, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean alwaysReplace,
                                  RandomSource random, StructurePiece.BlockSelector selector, Operation<Void> original) {
        synchronized (selector) {
            original.call(level, box, minX, minY, minZ, maxX, maxY, maxZ, alwaysReplace, random, selector);
        }
    }
}
