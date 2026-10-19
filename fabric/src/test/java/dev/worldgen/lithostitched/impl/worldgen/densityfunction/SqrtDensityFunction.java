package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's {@code sqrt} (signed), for GA2's tests without the mod. */
public class SqrtDensityFunction extends TransformerDensityFunction {
    public SqrtDensityFunction(DensityFunction argument) {
        super(argument);
    }

    @Override
    public double transform(double value) {
        if (value == 0.0) {
            return 0.0;
        } else {
            return value > 0.0 ? Math.sqrt(value) : -Math.sqrt(-value);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new SqrtDensityFunction(this.argument().mapAll(visitor));
    }
}
