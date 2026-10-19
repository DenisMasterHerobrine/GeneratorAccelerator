package dev.denismasterherobrine.generatoraccelerator2.mixin;

import dev.denismasterherobrine.generatoraccelerator2.noise.Ga2ImprovedNoise;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMixin implements Ga2ImprovedNoise {
    @Shadow @Final private byte[] p;
    @Shadow @Final public double xo;
    @Shadow @Final public double yo;
    @Shadow @Final public double zo;

    @Unique
    private int[] ga2$perm;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga2$buildPermutation(RandomSource random, CallbackInfo ci) {
        this.ga2$perm = FusedNoise.expand(this.p);
    }

    @Override
    public int[] ga2$perm() {
        return this.ga2$perm;
    }

    @Override
    public byte[] ga2$p() {
        return this.p;
    }

    /** The body of {@code noise(x, y, z)}. Bit-exact fused kernel (see FusedNoise). The original builds the same value from a byte table and a 2D gradient array. */
    @Unique
    private double ga2$noise(double x, double y, double z) {
        return FusedNoise.noise(this.ga2$perm, this.xo, this.yo, this.zo, x, y, z);
    }

    /** The body of {@code noise(x, y, z, yScale, yMax)}: bit-exact fused kernel with the y-clamp path used by BlendedNoise. */
    @Unique
    private double ga2$noise(double x, double y, double z, double yScale, double yMax) {
        return FusedNoise.noiseY(this.ga2$perm, this.xo, this.yo, this.zo, x, y, z, yScale, yMax);
    }
}
