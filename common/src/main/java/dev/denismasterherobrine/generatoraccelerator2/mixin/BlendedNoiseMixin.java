package dev.denismasterherobrine.generatoraccelerator2.mixin;

import dev.denismasterherobrine.generatoraccelerator2.noise.Ga2ImprovedNoise;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlendedNoise.class)
public abstract class BlendedNoiseMixin {
    @Shadow @Final private double xzMultiplier;
    @Shadow @Final private double yMultiplier;
    @Shadow @Final private double xzFactor;
    @Shadow @Final private double yFactor;
    @Shadow @Final private double smearScaleMultiplier;

    // Octave data in getOctaveNoise(i) order (index 0 = highest frequency). Null entries mirror vanilla's null levels.
    @Unique private int[][] ga2$mainPerm;
    @Unique private double[] ga2$mainXo, ga2$mainYo, ga2$mainZo;
    @Unique private int[][] ga2$minPerm;
    @Unique private double[] ga2$minXo, ga2$minYo, ga2$minZo;
    @Unique private int[][] ga2$maxPerm;
    @Unique private double[] ga2$maxXo, ga2$maxYo, ga2$maxZo;

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;DDDDD)V",
        at = @At("RETURN")
    )
    private void ga2$captureOctaves(PerlinNoise min, PerlinNoise max, PerlinNoise main, double xzScale, double yScale, double xzF, double yF, double smear, CallbackInfo ci) {
        int[][] p = new int[8][];
        double[] xo = new double[8], yo = new double[8], zo = new double[8];
        ga2$fill(main, 8, p, xo, yo, zo);
        this.ga2$mainPerm = p; this.ga2$mainXo = xo; this.ga2$mainYo = yo; this.ga2$mainZo = zo;

        p = new int[16][]; xo = new double[16]; yo = new double[16]; zo = new double[16];
        ga2$fill(min, 16, p, xo, yo, zo);
        this.ga2$minPerm = p; this.ga2$minXo = xo; this.ga2$minYo = yo; this.ga2$minZo = zo;

        p = new int[16][]; xo = new double[16]; yo = new double[16]; zo = new double[16];
        ga2$fill(max, 16, p, xo, yo, zo);
        this.ga2$maxPerm = p; this.ga2$maxXo = xo; this.ga2$maxYo = yo; this.ga2$maxZo = zo;
    }

    @Unique
    private static void ga2$fill(PerlinNoise noise, int count, int[][] perm, double[] xo, double[] yo, double[] zo) {
        for (int i = 0; i < count; i++) {
            ImprovedNoise level = noise.getOctaveNoise(i);
            if (level != null) {
                perm[i] = ((Ga2ImprovedNoise) (Object) level).ga2$perm();
                xo[i] = level.xo;
                yo[i] = level.yo;
                zo[i] = level.zo;
            }
        }
    }

    /** The body of {@code compute(ctx)}. Same arithmetic as the original compute(), with the octave kernels fused in. Bit-exact. */
    @Unique
    private double ga2$compute(DensityFunction.FunctionContext ctx) {
        double d0 = (double) ctx.blockX() * this.xzMultiplier;
        double d1 = (double) ctx.blockY() * this.yMultiplier;
        double d2 = (double) ctx.blockZ() * this.xzMultiplier;
        double d3 = d0 / this.xzFactor;
        double d4 = d1 / this.yFactor;
        double d5 = d2 / this.xzFactor;
        double d6 = this.yMultiplier * this.smearScaleMultiplier;
        double d7 = d6 / this.yFactor;
        double d8 = 0.0;
        double d9 = 0.0;
        double d10 = 0.0;
        double d11 = 1.0;

        int[][] mainPerm = this.ga2$mainPerm;
        double[] mainXo = this.ga2$mainXo, mainYo = this.ga2$mainYo, mainZo = this.ga2$mainZo;
        for (int i = 0; i < 8; i++) {
            int[] perm = mainPerm[i];
            if (perm != null) {
                d10 += FusedNoise.noiseY(perm, mainXo[i], mainYo[i], mainZo[i],
                    FusedNoise.wrap(d3 * d11), FusedNoise.wrap(d4 * d11), FusedNoise.wrap(d5 * d11), d7 * d11, d4 * d11) / d11;
            }
            d11 /= 2.0;
        }

        double d16 = (d10 / 10.0 + 1.0) / 2.0;
        boolean flag1 = d16 >= 1.0;
        boolean flag2 = d16 <= 0.0;
        d11 = 1.0;

        int[][] minPerm = this.ga2$minPerm, maxPerm = this.ga2$maxPerm;
        double[] minXo = this.ga2$minXo, minYo = this.ga2$minYo, minZo = this.ga2$minZo;
        double[] maxXo = this.ga2$maxXo, maxYo = this.ga2$maxYo, maxZo = this.ga2$maxZo;
        for (int j = 0; j < 16; j++) {
            double d12 = FusedNoise.wrap(d0 * d11);
            double d13 = FusedNoise.wrap(d1 * d11);
            double d14 = FusedNoise.wrap(d2 * d11);
            double d15 = d6 * d11;
            if (!flag1) {
                int[] perm = minPerm[j];
                if (perm != null) {
                    d8 += FusedNoise.noiseY(perm, minXo[j], minYo[j], minZo[j], d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            if (!flag2) {
                int[] perm = maxPerm[j];
                if (perm != null) {
                    d9 += FusedNoise.noiseY(perm, maxXo[j], maxYo[j], maxZo[j], d12, d13, d14, d15, d1 * d11) / d11;
                }
            }
            d11 /= 2.0;
        }

        // Mth.clampedLerp(d8 / 512.0, d9 / 512.0, d16) / 128.0
        double lo = d8 / 512.0;
        double hi = d9 / 512.0;
        double r;
        if (d16 < 0.0) {
            r = lo;
        } else if (d16 > 1.0) {
            r = hi;
        } else {
            r = lo + d16 * (hi - lo);
        }
        return r / 128.0;
    }
}
