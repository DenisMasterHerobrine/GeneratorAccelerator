package dev.denismasterherobrine.generatoraccelerator2.mixin.gpu;

import net.minecraft.world.level.levelgen.Beardifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's beard kernel table, uploaded to the GPU as it is ({@code Math.pow} is not reproducible there). */
@Mixin(Beardifier.class)
public interface BeardKernelAccessor {
    @Accessor("BEARD_KERNEL")
    static float[] ga2$beardKernel() {
        throw new AssertionError();
    }
}
