package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import dev.worldgen.lithostitched.api.worldgen.densityfunction.SimpleContext;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's {@code shift} (same class name, record shape and methods), for GA2's tests without the mod. */
public record ShiftDensityFunction(DensityFunction input, DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ) implements DensityFunction {
    @Override
    public double compute(FunctionContext context) {
        return this.input.compute(SimpleContext.of((double) context.blockX() + this.shiftX.compute(context), (double) context.blockY() + this.shiftY.compute(context),
            (double) context.blockZ() + this.shiftZ.compute(context)));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        applier.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new ShiftDensityFunction(this.input.mapAll(visitor), this.shiftX.mapAll(visitor), this.shiftY.mapAll(visitor), this.shiftZ.mapAll(visitor));
    }

    @Override
    public double minValue() {
        return this.input.minValue();
    }

    @Override
    public double maxValue() {
        return this.input.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }
}
