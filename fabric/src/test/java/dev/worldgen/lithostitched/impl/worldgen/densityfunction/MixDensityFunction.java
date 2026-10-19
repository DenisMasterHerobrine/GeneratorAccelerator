package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's {@code mix}, for GA2's tests without the mod. Its max is the smaller max, as the mod's is. */
public record MixDensityFunction(DensityFunction input, DensityFunction argument1, DensityFunction argument2) implements DensityFunction {
    @Override
    public double compute(FunctionContext context) {
        double input = this.input.compute(context);
        if (input <= 0.0) {
            return this.argument1.compute(context);
        } else if (input >= 1.0) {
            return this.argument2.compute(context);
        } else {
            double argument1 = this.argument1.compute(context);
            double argument2 = this.argument2.compute(context);
            return argument1 * (1.0 - input) + argument2 * input;
        }
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        applier.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new MixDensityFunction(this.input.mapAll(visitor), this.argument1.mapAll(visitor), this.argument2.mapAll(visitor));
    }

    @Override
    public double minValue() {
        return Math.min(this.argument1.minValue(), this.argument2.minValue());
    }

    @Override
    public double maxValue() {
        return Math.min(this.argument1.maxValue(), this.argument2.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }
}
