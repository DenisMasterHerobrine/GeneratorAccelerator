package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import net.minecraft.core.Direction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's {@code axis}, for GA2's tests without the mod. */
public record AxisDensityFunction(Direction.Axis axis) implements DensityFunction {
    @Override
    public double compute(FunctionContext context) {
        return switch (this.axis) {
            case X -> (double) context.blockX();
            case Y -> (double) context.blockY();
            case Z -> (double) context.blockZ();
        };
    }

    @Override
    public void fillArray(double[] densities, ContextProvider context) {
        context.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return this;
    }

    @Override
    public double minValue() {
        return this.axis == Direction.Axis.Y ? -4064.0 : -Double.MAX_VALUE;
    }

    @Override
    public double maxValue() {
        return this.axis == Direction.Axis.Y ? 4064.0 : Double.MAX_VALUE;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }
}
