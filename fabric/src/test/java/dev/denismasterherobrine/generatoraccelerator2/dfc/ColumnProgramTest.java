package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.Test;

/**
 * The compiled column program must produce the same slice arrays as vanilla's per-interpolator
 * fillArray, on real NoiseChunks, for every preset and several chunk positions.
 */
public class ColumnProgramTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES,
        NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.CAVES, NoiseGeneratorSettings.FLOATING_ISLANDS);
    private static final int[][] CHUNKS = { { 0, 0 }, { 5, -3 }, { 1000, 1000 }, { -31250, 12345 }, { 1875000, -1875000 } };

    @Test
    void sliceArraysMatchVanilla() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        Aquifer.FluidPicker picker = (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());
        long compared = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 4242L);
            NoiseSettings ns = settings.noiseSettings();
            for (int[] c : CHUNKS) {
                int minX = c[0] * 16;
                int minZ = c[1] * 16;
                NoiseChunk[] vanillaHolder = new NoiseChunk[1];
                DfcHooks.runVanilla(() -> vanillaHolder[0] = new NoiseChunk(4, state, minX, minZ, ns, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty()));
                NoiseChunk vanilla = vanillaHolder[0];
                NoiseChunk compiled = new NoiseChunk(4, state, minX, minZ, ns, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty());
                ChunkBinding binding = ((Ga2NoiseChunk) compiled).ga2$binding();
                assertNotNull(binding, "no column binding for " + preset.identifier());
                List<NoiseChunk.NoiseInterpolator> vi = ((NoiseChunkAccessor) vanilla).ga2$interpolators();
                List<NoiseChunk.NoiseInterpolator> ci = ((NoiseChunkAccessor) compiled).ga2$interpolators();
                assertEquals(vi.size(), ci.size());
                for (NoiseChunk.NoiseInterpolator it : ci) {
                    assertTrue(binding.handles(it), "interpolator not covered by the column program on " + preset.identifier());
                }

                DfcHooks.runVanilla(() -> { vanilla.initializeForFirstCellX(); vanilla.advanceCellX(0); });
                compiled.initializeForFirstCellX();
                compiled.advanceCellX(0);
                compared += compareSlices(preset, c, vi, ci, "cell0");
                for (int k = 1; k < 4; k++) {
                    final int kk = k;
                    DfcHooks.runVanilla(() -> { vanilla.swapSlices(); vanilla.advanceCellX(kk); });
                    compiled.swapSlices();
                    compiled.advanceCellX(k);
                    compared += compareSlices(preset, c, vi, ci, "cell" + k);
                }
                assertTrue(binding.columnsFilled >= 25, "column program was not used: " + binding.columnsFilled);
            }
        }
        System.out.println("DFC column: " + compared + " corner values bit-identical to vanilla");
    }

    private static long compareSlices(ResourceKey<NoiseGeneratorSettings> preset, int[] chunk, List<NoiseChunk.NoiseInterpolator> vi, List<NoiseChunk.NoiseInterpolator> ci, String stage) {
        long n = 0;
        for (int i = 0; i < vi.size(); i++) {
            NoiseInterpolatorAccessor a = (NoiseInterpolatorAccessor) vi.get(i);
            NoiseInterpolatorAccessor b = (NoiseInterpolatorAccessor) ci.get(i);
            n += compare(preset, chunk, stage + " interpolator " + i + " slice0", a.ga2$slice0(), b.ga2$slice0());
            n += compare(preset, chunk, stage + " interpolator " + i + " slice1", a.ga2$slice1(), b.ga2$slice1());
        }
        return n;
    }

    private static long compare(ResourceKey<NoiseGeneratorSettings> preset, int[] chunk, String what, double[][] expected, double[][] actual) {
        long n = 0;
        for (int z = 0; z < expected.length; z++) {
            for (int y = 0; y < expected[z].length; y++) {
                if (Double.doubleToRawLongBits(expected[z][y]) != Double.doubleToRawLongBits(actual[z][y])) {
                    throw new AssertionError(preset.identifier() + " chunk " + chunk[0] + "," + chunk[1] + " " + what + " [z=" + z + ",y=" + y + "]: vanilla=" + expected[z][y] + " dfc=" + actual[z][y]);
                }
                n++;
            }
        }
        return n;
    }
}
