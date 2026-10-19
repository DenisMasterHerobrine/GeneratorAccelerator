package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's math function, for GA2's tests without the mod. */
public class SinDensityFunction extends TransformerDensityFunction {
    public SinDensityFunction(DensityFunction argument) {
        super(argument);
    }

    @Override
    public double transform(double value) {
        return Math.sin(value);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new SinDensityFunction(this.argument().mapAll(visitor));
    }
}
