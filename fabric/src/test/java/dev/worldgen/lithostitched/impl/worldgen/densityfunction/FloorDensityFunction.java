package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's math function, for GA2's tests without the mod. */
public class FloorDensityFunction extends TransformerDensityFunction {
    public FloorDensityFunction(DensityFunction argument) {
        super(argument);
    }

    @Override
    public double transform(double value) {
        return Math.floor(value);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FloorDensityFunction(this.argument().mapAll(visitor));
    }
}
