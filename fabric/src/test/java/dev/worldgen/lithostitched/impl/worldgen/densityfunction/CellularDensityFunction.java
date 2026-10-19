package dev.worldgen.lithostitched.impl.worldgen.densityfunction;

import dev.worldgen.lithostitched.api.worldgen.densityfunction.SimpleContext;
import dev.worldgen.lithostitched.impl.LithostitchedInternalHooks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/** Stand-in for Lithostitched 1.8.1's {@code cellular} (same class name, record shapes and code), for GA2's tests without the mod. */
public record CellularDensityFunction(Optional<CellularDensityFunction.CellCondition> condition, CellularDensityFunction.GridDimensions gridDimensions, float jitter,
                                      CellularDensityFunction.ReturnType returnType, int salt) implements DensityFunction {
    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        return this.gridDimensions.is2d() ? this.sample2D(x, z) : this.sample3D(x, y, z);
    }

    private boolean isCellPositionInvalid(CellCondition cellCondition, BlockPos pos) {
        return !cellCondition.range.isValueInRange(cellCondition.function.compute(SimpleContext.of(pos)));
    }

    @Override
    public void fillArray(double[] output, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(output, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new CellularDensityFunction(this.condition.map(c -> c.mapAll(visitor)), this.gridDimensions, this.jitter, this.returnType, this.salt);
    }

    @Override
    public double minValue() {
        return Math.min(this.condition.map(CellCondition::fallbackValue).orElse(0.0), 0.0);
    }

    @Override
    public double maxValue() {
        return 2.147483647E9;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }

    public double sample2D(int blockX, int blockZ) {
        int spacedGridX = Math.floorDiv(blockX, this.gridDimensions.horizontal);
        int spacedGridZ = Math.floorDiv(blockZ, this.gridDimensions.horizontal);
        BlockPos pos1 = null;
        float distance1 = Float.MAX_VALUE;
        BlockPos pos2 = null;
        float distance2 = Float.MAX_VALUE;
        float nearestCellValue = Float.MAX_VALUE;
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        for (int xo = -1; xo <= 1; xo++) {
            for (int zo = -1; zo <= 1; zo++) {
                random.setLargeFeatureWithSalt(LithostitchedInternalHooks.WORLD_SEED, spacedGridX + xo, spacedGridZ + zo, this.salt);
                int targetX = (spacedGridX + xo) * this.gridDimensions.horizontal
                    + random.nextIntBetweenInclusive(0, (int) ((float) this.gridDimensions.horizontal * this.jitter));
                int targetZ = (spacedGridZ + zo) * this.gridDimensions.horizontal
                    + random.nextIntBetweenInclusive(0, (int) ((float) this.gridDimensions.horizontal * this.jitter));
                float cellValue = random.nextFloat();
                int dx = targetX - blockX;
                int dz = targetZ - blockZ;
                float distance = (float) Mth.length((double) dx, (double) dz);
                if (distance < distance2) {
                    if (distance < distance1) {
                        distance2 = distance1;
                        pos2 = pos1;
                        distance1 = distance;
                        pos1 = new BlockPos(targetX, 0, targetZ);
                        nearestCellValue = cellValue;
                    } else {
                        distance2 = distance;
                        pos2 = new BlockPos(targetX, 0, targetZ);
                    }
                }
            }
        }
        return finish(nearestCellValue, distance1, distance2, pos1, pos2);
    }

    public double sample3D(int blockX, int blockY, int blockZ) {
        int spacedGridX = Math.floorDiv(blockX, this.gridDimensions.horizontal);
        int spacedGridY = Math.floorDiv(blockY, this.gridDimensions.vertical);
        int spacedGridZ = Math.floorDiv(blockZ, this.gridDimensions.horizontal);
        BlockPos pos1 = null;
        float distance1 = Float.MAX_VALUE;
        BlockPos pos2 = null;
        float distance2 = Float.MAX_VALUE;
        float nearestCellValue = Float.MAX_VALUE;
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        for (int xo = -1; xo <= 1; xo++) {
            for (int yo = -1; yo <= 1; yo++) {
                for (int zo = -1; zo <= 1; zo++) {
                    random.setSeed((long) (spacedGridX + xo) * 341873128712L + (long) (spacedGridY + yo) * 297845856431L + (long) (spacedGridZ + zo) * 132897987541L
                        + LithostitchedInternalHooks.WORLD_SEED + (long) this.salt);
                    int targetX = (spacedGridX + xo) * this.gridDimensions.horizontal
                        + random.nextIntBetweenInclusive(0, (int) ((float) this.gridDimensions.horizontal * this.jitter));
                    int targetY = (spacedGridY + yo) * this.gridDimensions.vertical
                        + random.nextIntBetweenInclusive(0, (int) ((float) this.gridDimensions.vertical * this.jitter));
                    int targetZ = (spacedGridZ + zo) * this.gridDimensions.horizontal
                        + random.nextIntBetweenInclusive(0, (int) ((float) this.gridDimensions.horizontal * this.jitter));
                    float cellValue = random.nextFloat();
                    int dx = targetX - blockX;
                    int dy = targetY - blockY;
                    int dz = targetZ - blockZ;
                    float distance = (float) Mth.length((double) dx, (double) dy, (double) dz);
                    if (distance < distance2) {
                        if (distance < distance1) {
                            distance2 = distance1;
                            pos2 = pos1;
                            distance1 = distance;
                            pos1 = new BlockPos(targetX, targetY, targetZ);
                            nearestCellValue = cellValue;
                        } else {
                            distance2 = distance;
                            pos2 = new BlockPos(targetX, targetY, targetZ);
                        }
                    }
                }
            }
        }
        return finish(nearestCellValue, distance1, distance2, pos1, pos2);
    }

    private double finish(float nearestCellValue, float distance1, float distance2, BlockPos pos1, BlockPos pos2) {
        double returnValue = switch (this.returnType) {
            case CELL_VALUE -> (double) nearestCellValue;
            case DISTANCE_1 -> (double) distance1;
            case DISTANCE_2 -> (double) distance2;
            case DISTANCE_2_ADD -> (double) (distance2 + distance1) * 0.5;
            case DISTANCE_2_SUB -> (double) (distance2 - distance1);
            case DISTANCE_2_MUL -> (double) (distance2 * distance1) * 0.5;
            case DISTANCE_2_DIV -> (double) (distance1 / distance2);
        };
        if (this.condition.isPresent()) {
            CellCondition cellCondition = this.condition.get();
            if (this.returnType != ReturnType.DISTANCE_2 && this.isCellPositionInvalid(cellCondition, pos1)) {
                returnValue = cellCondition.fallbackValue;
            }
            if (this.returnType != ReturnType.DISTANCE_1 && this.returnType != ReturnType.CELL_VALUE && this.isCellPositionInvalid(cellCondition, pos2)) {
                returnValue = cellCondition.fallbackValue;
            }
        }
        return returnValue;
    }

    public record CellCondition(DensityFunction function, InclusiveRange<Double> range, double fallbackValue) {
        public CellCondition mapAll(Visitor visitor) {
            return new CellCondition(this.function.mapAll(visitor), this.range, this.fallbackValue);
        }
    }

    public record GridDimensions(int horizontal, int vertical) {
        public boolean is2d() {
            return this.vertical == 0;
        }
    }

    public enum ReturnType {
        CELL_VALUE, DISTANCE_1, DISTANCE_2, DISTANCE_2_ADD, DISTANCE_2_SUB, DISTANCE_2_MUL, DISTANCE_2_DIV
    }
}
