package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.chunk.CarvingMask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A new carving mask starts with no words instead of all of them (12 KB at 384 blocks of height). The bit set grows as bits are set,
 * and every read, its stream and its saved form ({@code toLongArray}, which drops the trailing empty words) are the same. The
 * placement of {@code seagrass_simple} creates the liquid step's mask in every chunk it decorates, and that mask stays empty.
 * The handler is an instance method: Forge's Mixin 0.8.5 takes no static handler in a constructor after {@code super()}.
 */
@Mixin(CarvingMask.class)
public abstract class CarvingMaskSizeMixin {
    @ModifyArg(method = "<init>(II)V", at = @At(value = "INVOKE", target = "Ljava/util/BitSet;<init>(I)V"))
    private int ga2$noWordsYet(int bits) {
        return 0;
    }
}
