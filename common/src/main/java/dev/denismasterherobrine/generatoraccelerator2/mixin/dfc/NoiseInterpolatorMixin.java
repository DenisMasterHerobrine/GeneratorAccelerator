package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.LazyInterpolation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link LazyInterpolation}: the interpolated value is computed when read, from the last z fraction. */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin {
    /**
     * The NoiseChunk the interpolator belongs to. It is the outer instance, passed as the first constructor argument. javac's
     * field for the outer instance has another name on each loader, so the field is not shadowed.
     */
    @Unique
    private NoiseChunk ga2$chunk;
    @Shadow private double valueZ0;
    @Shadow private double valueZ1;
    @Shadow private double value;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$captureChunk(NoiseChunk chunk, DensityFunction filler, CallbackInfo ci) {
        this.ga2$chunk = chunk;
    }

    @Redirect(method = "compute", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;value:D", opcode = 180))
    private double ga2$lazyValue(NoiseChunk.NoiseInterpolator self) {
        // the NoiseChunk hooks may be skipped (takeover off next to a mod that patches NoiseChunk). Then vanilla keeps value
        if (LazyInterpolation.ENABLED && (Object) this.ga2$chunk instanceof Ga2NoiseChunk chunk) {
            return Mth.lerp(chunk.ga2$dz(), this.valueZ0, this.valueZ1);
        }
        return this.value;
    }
}
