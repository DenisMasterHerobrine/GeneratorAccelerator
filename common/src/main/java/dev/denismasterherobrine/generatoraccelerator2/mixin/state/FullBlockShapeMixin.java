package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.state.ShapeFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code Block.isShapeFullBlock}, remembered on the shape instead of in vanilla's bounded Guava cache. Every read of that cache
 * updates its access order with a queue offer and periodic drains. Support-face checks (vines, lichen, sculk veins, plants)
 * call the method constantly. The answer is the cache loader's own expression, and shapes never change. Put in place once the
 * class is complete, when no other mod changed the method ({@code compat.LateOverwrites}).
 */
@Mixin(value = Block.class, priority = 5000)
public abstract class FullBlockShapeMixin {
    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.FULL_BLOCK_SHAPE);

    /** The body of {@code isShapeFullBlock(shape)}: the answer kept on the shape. */
    @Unique
    private static boolean ga2$isShapeFullBlock(VoxelShape shape) {
        ShapeFlags.Holder h = (ShapeFlags.Holder) shape;
        byte f = h.ga2$full();
        if (f != 0) {
            return f == 1;
        }
        boolean full = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
        if ((dev.denismasterherobrine.generatoraccelerator2.state.StateFast.ENABLED && GA2$HOOK.allowed())) {
            h.ga2$setFull(full ? (byte) 1 : (byte) 2);
        }
        return full;
    }
}
