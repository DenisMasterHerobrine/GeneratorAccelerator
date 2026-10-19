package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.CacheAllInCellAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Shared harness: generate a chunk's corners and cells with the vanilla path and the compiled path, compare raw bits. */
final class ChunkCompare {
    private ChunkCompare() {}

    static final Aquifer.FluidPicker PICKER = (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());

    /** @return number of values compared */
    static long compareChunk(String label, NoiseGeneratorSettings settings, RandomState state, int chunkX, int chunkZ,
                             DensityFunctions.BeardifierOrMarker vanillaBeard, DensityFunctions.BeardifierOrMarker compiledBeard) {
        return compareChunk(label, settings, state, chunkX, chunkZ, vanillaBeard, compiledBeard, true);
    }

    /** @param cellPrograms whether every cell cache must be filled by a cell program (false: some may keep vanilla's fill) */
    static long compareChunk(String label, NoiseGeneratorSettings settings, RandomState state, int chunkX, int chunkZ,
                             DensityFunctions.BeardifierOrMarker vanillaBeard, DensityFunctions.BeardifierOrMarker compiledBeard, boolean cellPrograms) {
        return compareChunk(label, settings, state, chunkX, chunkZ, vanillaBeard, compiledBeard, cellPrograms, true);
    }

    /**
     * @param allInterpolators whether the column program must fill every interpolator (false: an interpolator the program never
     *                         reads, such as one inside a mod's shifted function, may keep vanilla's fill)
     */
    static long compareChunk(String label, NoiseGeneratorSettings settings, RandomState state, int chunkX, int chunkZ,
                             DensityFunctions.BeardifierOrMarker vanillaBeard, DensityFunctions.BeardifierOrMarker compiledBeard, boolean cellPrograms,
                             boolean allInterpolators) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        NoiseChunk[] holder = new NoiseChunk[1];
        DfcHooks.runVanilla(() -> holder[0] = new NoiseChunk(4, state, minX, minZ, settings.noiseSettings(), vanillaBeard, settings, PICKER, Blender.empty()));
        NoiseChunk vanilla = holder[0];
        NoiseChunk compiled = new NoiseChunk(4, state, minX, minZ, settings.noiseSettings(), compiledBeard, settings, PICKER, Blender.empty());
        ChunkBinding binding = ((Ga2NoiseChunk) compiled).ga2$binding();
        assertNotNull(binding, label + ": no binding");
        List<NoiseChunk.NoiseInterpolator> vi = ((NoiseChunkAccessor) vanilla).ga2$interpolators();
        List<NoiseChunk.NoiseInterpolator> ci = ((NoiseChunkAccessor) compiled).ga2$interpolators();
        assertEquals(vi.size(), ci.size(), label + ": interpolator count");
        for (NoiseChunk.NoiseInterpolator it : ci) {
            assertTrue(!allInterpolators || binding.handles(it), label + ": interpolator not covered by the column program");
        }
        List<DensityFunction> vc = ((NoiseChunkAccessor) vanilla).ga2$cellCaches();
        List<DensityFunction> cc = ((NoiseChunkAccessor) compiled).ga2$cellCaches();
        for (DensityFunction cache : cc) {
            assertTrue(!cellPrograms || binding.handlesCell(((DensityFunctions.MarkerOrMarked) cache).wrapped()), label + ": cell cache not covered");
        }
        int cellCountY = ((NoiseChunkAccessor) compiled).ga2$cellCountY();
        long n = 0;

        DfcHooks.runVanilla(() -> { vanilla.initializeForFirstCellX(); vanilla.advanceCellX(0); });
        compiled.initializeForFirstCellX();
        compiled.advanceCellX(0);
        for (int k = 0; k < 4; k++) {
            if (k > 0) {
                final int kk = k;
                DfcHooks.runVanilla(() -> { vanilla.swapSlices(); vanilla.advanceCellX(kk); });
                compiled.swapSlices();
                compiled.advanceCellX(k);
            }
            n += compareSlices(label + " cellX=" + k, vi, ci);
            for (int cellZ = 0; cellZ < 4; cellZ++) {
                for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                    final int cy = cellY, cz = cellZ;
                    DfcHooks.runVanilla(() -> vanilla.selectCellYZ(cy, cz));
                    compiled.selectCellYZ(cy, cz);
                    for (int i = 0; i < vc.size(); i++) {
                        double[] e = ((CacheAllInCellAccessor) vc.get(i)).ga2$values();
                        double[] a = ((CacheAllInCellAccessor) cc.get(i)).ga2$values();
                        for (int j = 0; j < e.length; j++) {
                            if (Double.doubleToRawLongBits(e[j]) != Double.doubleToRawLongBits(a[j])) {
                                throw new AssertionError(label + " cellX=" + k + " cellY=" + cellY + " cellZ=" + cellZ + " cache " + i + " index " + j + ": vanilla=" + e[j] + " dfc=" + a[j]);
                            }
                            n++;
                        }
                    }
                }
            }
        }
        assertTrue(binding.columnsFilled > 0 && (!cellPrograms || binding.cellsFilled > 0), label + ": compiled programs were not used");
        return n;
    }

    private static long compareSlices(String label, List<NoiseChunk.NoiseInterpolator> vi, List<NoiseChunk.NoiseInterpolator> ci) {
        long n = 0;
        for (int i = 0; i < vi.size(); i++) {
            NoiseInterpolatorAccessor a = (NoiseInterpolatorAccessor) vi.get(i);
            NoiseInterpolatorAccessor b = (NoiseInterpolatorAccessor) ci.get(i);
            n += compare(label + " interpolator " + i + " slice0", a.ga2$slice0(), b.ga2$slice0());
            n += compare(label + " interpolator " + i + " slice1", a.ga2$slice1(), b.ga2$slice1());
        }
        return n;
    }

    private static long compare(String what, double[][] expected, double[][] actual) {
        long n = 0;
        for (int z = 0; z < expected.length; z++) {
            for (int y = 0; y < expected[z].length; y++) {
                if (Double.doubleToRawLongBits(expected[z][y]) != Double.doubleToRawLongBits(actual[z][y])) {
                    throw new AssertionError(what + " [z=" + z + ",y=" + y + "]: vanilla=" + expected[z][y] + " dfc=" + actual[z][y]);
                }
                n++;
            }
        }
        return n;
    }
}
