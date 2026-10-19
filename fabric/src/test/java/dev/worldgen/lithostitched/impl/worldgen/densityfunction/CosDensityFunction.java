package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's math function, for GA2's tests without the mod. */
public class CosDensityFunction extends TransformerDensityFunction {
    public CosDensityFunction(DensityFunction argument) {
        super(argument);
    }

    @Override
    public double transform(double value) {
        return Math.cos(value);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new CosDensityFunction(this.argument().mapAll(visitor));
    }
}
