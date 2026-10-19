package dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's merged wrapper, for GA2's tests without the mod. It computes as its full function. */
public record MergedDensityFunction(DensityFunction original, DensityFunction wrapped, DensityFunction full) implements DensityFunction {
    @Override
    public double compute(FunctionContext context) {
        return this.full.compute(context);
    }

    @Override
    public void fillArray(double[] doubles, ContextProvider contextProvider) {
        this.full.fillArray(doubles, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return this.full.mapAll(visitor);
    }

    @Override
    public double minValue() {
        return this.full.minValue();
    }

    @Override
    public double maxValue() {
        return this.full.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }
}
