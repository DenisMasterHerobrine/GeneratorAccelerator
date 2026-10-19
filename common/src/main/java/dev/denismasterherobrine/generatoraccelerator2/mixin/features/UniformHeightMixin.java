package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The "empty height range" warning set of a shared height provider, updated under its own monitor. */
@Mixin(UniformHeight.class)
public abstract class UniformHeightMixin {
    @WrapOperation(method = "sample", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSet;add(J)Z"))
    private boolean ga2$syncWarned(LongSet set, long key, Operation<Boolean> original) {
        synchronized (set) {
            return original.call(set, key);
        }
    }
}
