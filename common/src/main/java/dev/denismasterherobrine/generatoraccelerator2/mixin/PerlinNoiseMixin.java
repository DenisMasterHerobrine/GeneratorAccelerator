package dev.denismasterherobrine.generatoraccelerator2.mixin;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.noise.Ga2ImprovedNoise;
import dev.denismasterherobrine.generatoraccelerator2.noise.FusedNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin {
    @Shadow @Final private ImprovedNoise[] noiseLevels;
    @Shadow @Final private DoubleList amplitudes;
    @Shadow @Final private double lowestFreqValueFactor;
    @Shadow @Final private double lowestFreqInputFactor;

    // Compacted octave data: only non-null levels, in ascending index order.
    @Unique private int ga2$count;
    @Unique private int[][] ga2$perm;
    @Unique private double[] ga2$xo;
    @Unique private double[] ga2$yo;
    @Unique private double[] ga2$zo;
    @Unique private double[] ga2$amp;
    @Unique private double[] ga2$inputFactor;
    @Unique private double[] ga2$valueFactor;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga2$compactOctaves(RandomSource random, Pair<Integer, DoubleList> octaves, boolean positional, CallbackInfo ci) {
        int count = 0;
        for (ImprovedNoise level : this.noiseLevels) {
            if (level != null) {
                count++;
            }
        }
        int[][] perm = new int[count][];
        double[] xo = new double[count];
        double[] yo = new double[count];
        double[] zo = new double[count];
        double[] amp = new double[count];
        double[] inputFactor = new double[count];
        double[] valueFactor = new double[count];
        // Same running products as the vanilla loop: d1 *= 2.0 and d2 /= 2.0 per index, null levels included.
        double in = this.lowestFreqInputFactor;
        double val = this.lowestFreqValueFactor;
        int k = 0;
        for (int i = 0; i < this.noiseLevels.length; i++) {
            ImprovedNoise level = this.noiseLevels[i];
            if (level != null) {
                perm[k] = ((Ga2ImprovedNoise) (Object) level).ga2$perm();
                xo[k] = level.xo;
                yo[k] = level.yo;
                zo[k] = level.zo;
                amp[k] = this.amplitudes.getDouble(i);
                inputFactor[k] = in;
                valueFactor[k] = val;
                k++;
            }
            in *= 2.0;
            val /= 2.0;
        }
        this.ga2$count = count;
        this.ga2$perm = perm;
        this.ga2$xo = xo;
        this.ga2$yo = yo;
        this.ga2$zo = zo;
        this.ga2$amp = amp;
        this.ga2$inputFactor = inputFactor;
        this.ga2$valueFactor = valueFactor;
    }

    /** The body of {@code getValue(x, y, z)}. Fused octave loop over precomputed per-octave data. Bit-exact with the original accumulation order. */
    @Unique
    private double ga2$getValue(double x, double y, double z) {
        double sum = 0.0;
        int[][] perm = this.ga2$perm;
        double[] xo = this.ga2$xo;
        double[] yo = this.ga2$yo;
        double[] zo = this.ga2$zo;
        double[] amp = this.ga2$amp;
        double[] inputFactor = this.ga2$inputFactor;
        double[] valueFactor = this.ga2$valueFactor;
        for (int i = 0, n = this.ga2$count; i < n; i++) {
            double f = inputFactor[i];
            double v = FusedNoise.noise(perm[i], xo[i], yo[i], zo[i], FusedNoise.wrap(x * f), FusedNoise.wrap(y * f), FusedNoise.wrap(z * f));
            sum += amp[i] * v * valueFactor[i];
        }
        return sum;
    }

    /** The body of {@code getValue(x, y, z, yScale, yMax, useOrigin)}. Fused octave loop, including the legacy y-clamp and origin-y options. Bit-exact. */
    @Unique
    private double ga2$getValue(double x, double y, double z, double yScale, double yMax, boolean useOrigin) {
        double sum = 0.0;
        int[][] perm = this.ga2$perm;
        double[] xo = this.ga2$xo;
        double[] yo = this.ga2$yo;
        double[] zo = this.ga2$zo;
        double[] amp = this.ga2$amp;
        double[] inputFactor = this.ga2$inputFactor;
        double[] valueFactor = this.ga2$valueFactor;
        for (int i = 0, n = this.ga2$count; i < n; i++) {
            double f = inputFactor[i];
            double yIn = useOrigin ? -yo[i] : FusedNoise.wrap(y * f);
            double v = FusedNoise.noiseY(perm[i], xo[i], yo[i], zo[i], FusedNoise.wrap(x * f), yIn, FusedNoise.wrap(z * f), yScale * f, yMax * f);
            sum += amp[i] * v * valueFactor[i];
        }
        return sum;
    }

    /** The body of {@code wrap(v)}: identity fast path for |v| < 1.6e7, where the original formula provably returns v unchanged. */
    @Unique
    private static double ga2$wrap(double v) {
        return FusedNoise.wrap(v);
    }
}
