package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

/**
 * A dimension's global fluid picker as the GPU program's {@code picker(y)}: intervals of y with one fluid status each. Vanilla's
 * picker is two such intervals: lava at level -54 below {@code min(-54, sea level)}, and the default fluid at sea level from there
 * up. A generator may install another picker, such as The Undergarden's (air below y 1). The picker is code the GPU cannot read. So
 * it is probed at every y of the dimension and well beyond it, at columns far apart. Some pickers are rejected, and the dimension
 * stays on the CPU. That applies when the status at some y differs between those columns, when there are more than a few
 * intervals, or when the picker gives a fluid the program does not place.
 */
final class PickerShape {
    private PickerShape() {}

    /** Columns the picker is asked at for each y. A picker of y alone answers the same at all of them. */
    private static final int[][] COLUMNS = { { 0, 0 }, { 1, 1 }, { -1, 7 }, { 31, -17 }, { 1000, -3000 }, { 12345, 67890 }, { -7777, -555 },
        { -29999984, 29999984 }, { 29999983, -29999983 } };
    /** Heights far outside any dimension. The aquifer asks at a surface level plus 8, which wraps for a column with no surface. */
    private static final int[] FAR_BELOW = { Integer.MIN_VALUE, Integer.MIN_VALUE + 7, -(1 << 24), -(1 << 20), -100_000 };
    private static final int[] FAR_ABOVE = { 100_000, 1 << 20, 1 << 24, Integer.MAX_VALUE - 7, Integer.MAX_VALUE };
    private static final int MAX_INTERVALS = 8;

    /**
     * The picker's intervals as {@code {fromY, level, program id}}, in y order. The first starts at {@code Integer.MIN_VALUE}.
     * Returns null when the picker is vanilla's, which the program's own {@code picker} handles.
     *
     * @param ids the program's states by id ({@code ClEmitter.ID_*})
     * @param aquifers the dimension has aquifers, which also ask far beyond the world
     * @throws ClEmitter.Unsupported when the picker cannot be written as intervals of y
     */
    static List<int[]> of(Aquifer.FluidPicker picker, int minY, int height, int seaLevel, BlockState[] ids, boolean aquifers) {
        int lo = minY - 80, hi = minY + height + 80;
        List<int[]> out = new ArrayList<>();
        int[] last = null;
        for (int y = lo; y <= hi; y++) {
            Aquifer.FluidStatus s = status(picker, y);
            if (last == null || last[1] != level(s) || ids[last[2]] != type(s)) {
                last = new int[] { out.isEmpty() ? Integer.MIN_VALUE : y, level(s), id(type(s), ids) };
                out.add(last);
                if (out.size() > MAX_INTERVALS) {
                    throw new ClEmitter.Unsupported("a global fluid picker with more than " + MAX_INTERVALS + " heights where its fluid changes");
                }
            }
        }
        Aquifer.FluidStatus bottom = status(picker, lo), top = status(picker, hi);
        // Only an aquifer asks beyond the world (at a column's surface level plus 8). Otherwise only the world's blocks ask.
        for (int y : aquifers ? FAR_BELOW : new int[0]) {
            if (!same(status(picker, y), bottom)) {
                throw new ClEmitter.Unsupported("a global fluid picker that changes far below the world (at y " + y + ")");
            }
        }
        for (int y : aquifers ? FAR_ABOVE : new int[0]) {
            if (!same(status(picker, y), top)) {
                throw new ClEmitter.Unsupported("a global fluid picker that changes far above the world (at y " + y + ")");
            }
        }
        BlockState fluid = ids[ClEmitter.ID_WATER];
        int split = Math.min(-54, seaLevel);
        BlockState lava = Blocks.LAVA.defaultBlockState();
        boolean vanilla = out.size() == 2 && out.get(0)[1] == -54 && ids[out.get(0)[2]] == lava && out.get(1)[0] == split && out.get(1)[1] == seaLevel
            && ids[out.get(1)[2]] == fluid;
        return vanilla ? null : out;
    }

    /** The status at {@code y}, the same at every probed column. */
    private static Aquifer.FluidStatus status(Aquifer.FluidPicker picker, int y) {
        Aquifer.FluidStatus first = picker.computeFluid(COLUMNS[0][0], y, COLUMNS[0][1]);
        for (int i = 1; i < COLUMNS.length; i++) {
            Aquifer.FluidStatus s = picker.computeFluid(COLUMNS[i][0], y, COLUMNS[i][1]);
            if (!same(s, first)) {
                throw new ClEmitter.Unsupported("a global fluid picker that depends on x or z (at y " + y + ")");
            }
        }
        return first;
    }

    private static boolean same(Aquifer.FluidStatus a, Aquifer.FluidStatus b) {
        return level(a) == level(b) && type(a) == type(b);
    }

    private static int level(Aquifer.FluidStatus s) {
        return ((dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer.FluidStatusAccessor) (Object) s).ga2$fluidLevel();
    }

    private static BlockState type(Aquifer.FluidStatus s) {
        return ((dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer.FluidStatusAccessor) (Object) s).ga2$fluidType();
    }

    /** The program's id for a picker's fluid: air, the default fluid (the noise step's own) or lava. */
    private static int id(BlockState state, BlockState[] ids) {
        if (state == ids[ClEmitter.ID_AIR]) {
            return ClEmitter.ID_AIR;
        }
        if (state == ids[ClEmitter.ID_WATER]) {
            return ClEmitter.ID_WATER;
        }
        if (state == ids[ClEmitter.ID_LAVA]) {
            return ClEmitter.ID_LAVA;
        }
        throw new ClEmitter.Unsupported("a global fluid picker giving " + state + ", which the noise step does not place");
    }
}
