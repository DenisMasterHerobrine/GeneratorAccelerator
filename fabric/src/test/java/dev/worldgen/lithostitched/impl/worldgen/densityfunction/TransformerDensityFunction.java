package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's base of the math functions, for GA2's tests without the mod. */
public abstract class TransformerDensityFunction implements DensityFunction {
    private final DensityFunction argument;

    public TransformerDensityFunction(DensityFunction argument) {
        this.argument = argument;
    }

    public DensityFunction argument() {
        return this.argument;
    }

    public abstract double transform(double value);

    @Override
    public double compute(FunctionContext functionContext) {
        return this.transform(this.argument.compute(functionContext));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider context) {
        this.argument.fillArray(densities, context);
        for (int i = 0; i < densities.length; i++) {
            densities[i] = this.transform(densities[i]);
        }
    }

    @Override
    public double minValue() {
        return this.transform(this.argument.minValue());
    }

    @Override
    public double maxValue() {
        return this.transform(this.argument.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }
}
