package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import java.util.List;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched 1.8.1's {@code select}, for GA2's tests without the mod. */
public record SelectDensityFunction(DensityFunction input, DensityFunction fallback, List<SelectDensityFunction.Selection> selections, double min, double max)
    implements DensityFunction {
    public static SelectDensityFunction create(DensityFunction input, DensityFunction fallback, List<Selection> selections) {
        double min = -Double.MAX_VALUE;
        double max = Double.MAX_VALUE;
        for (Selection selection : selections) {
            min = Math.min(min, selection.function.minValue());
            max = Math.max(max, selection.function.maxValue());
        }
        return new SelectDensityFunction(input, fallback, selections, min, max);
    }

    @Override
    public double compute(FunctionContext context) {
        double value = this.input.compute(context);
        for (Selection selection : this.selections) {
            if (selection.range.isValueInRange(value)) {
                return selection.function.compute(context);
            }
        }
        return this.fallback.compute(context);
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        applier.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new SelectDensityFunction(this.input.mapAll(visitor), this.fallback.mapAll(visitor), this.selections.stream().map(s -> s.mapAll(visitor)).toList(),
            this.min, this.max);
    }

    @Override
    public double minValue() {
        return this.min;
    }

    @Override
    public double maxValue() {
        return this.max;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }

    public record Selection(InclusiveRange<Double> range, DensityFunction function) {
        public Selection mapAll(Visitor visitor) {
            return new Selection(this.range, this.function.mapAll(visitor));
        }
    }
}
