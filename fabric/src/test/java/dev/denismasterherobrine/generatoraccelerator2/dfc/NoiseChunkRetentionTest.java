package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.Test;

/**
 * What GA2 attaches to a NoiseChunk must not keep the NoiseChunk alive once vanilla lets go of it. The NoiseChunk's
 * interpolation slices are released after the fill and made again for another fill.
 *
 * <p>Every chunk's climate sampler is bound to the compiled program. A static map keyed by temperature function (for
 * Blueprint's samplers) once held each binding strongly. The binding holds that function, and the function holds the
 * NoiseChunk. So every chunk ever generated stayed in memory. A 1 GB server ran out of memory within a few thousand chunks.
 */
public class NoiseChunkRetentionTest {
    private static final Aquifer.FluidPicker PICKER = (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());

    private static RandomState overworld(HolderLookup.Provider lookup, NoiseGeneratorSettings settings) {
        return RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 20261012L);
    }

    @Test
    void noiseChunksAreNotKeptAlive() throws InterruptedException {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState state = overworld(lookup, settings);
        assertEquals(0, alive(make(state, settings, 0, false, false), null), "NoiseChunks (constructed only) still reachable");
        // the bindings' weak maps drop an entry with a collected key on a later write to the entry's segment. The next
        // chunks' bindings make such writes
        Runnable laterChunks = () -> make(state, settings, 1000, true, false);
        assertEquals(0, alive(make(state, settings, 100, true, false), laterChunks), "NoiseChunks with a climate sampler still reachable");
        assertEquals(0, alive(make(state, settings, 200, true, true), laterChunks), "NoiseChunks whose sampler sampled still reachable");
    }

    /** Eight NoiseChunks (their climate samplers made and used, if asked), only weakly referenced once this returns. */
    private static List<WeakReference<NoiseChunk>> make(RandomState state, NoiseGeneratorSettings settings, int at, boolean sampler, boolean sample) {
        List<WeakReference<NoiseChunk>> chunks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            NoiseChunk chunk = new NoiseChunk(4, state, (at + i) * 16, -(at + i) * 16, settings.noiseSettings(), DensityFunctions.BeardifierMarker.INSTANCE, settings,
                PICKER, Blender.empty());
            if (sampler) {
                Climate.Sampler s = ((NoiseChunkAccessor) chunk).ga2$cachedClimateSampler(state.router(), List.of());
                assertNotNull(BoundSampler.lookup(s), "the chunk sampler is bound (else this test checks nothing)");
                if (sample) {
                    s.sample(at + i, 10, -(at + i));
                }
            }
            chunks.add(new WeakReference<>(chunk));
        }
        return chunks;
    }

    private static int alive(List<WeakReference<NoiseChunk>> chunks, Runnable between) throws InterruptedException {
        int alive = chunks.size();
        for (int round = 0; round < 40 && alive > 0; round++) {
            System.gc();
            Thread.sleep(25);
            if (between != null) {
                between.run();
            }
            alive = 0;
            for (WeakReference<NoiseChunk> ref : chunks) {
                if (ref.get() != null) {
                    alive++;
                }
            }
        }
        return alive;
    }

    @Test
    void slicesReleasedAfterTheFillAndMadeAgain() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState state = overworld(lookup, settings);
        NoiseChunk chunk = new NoiseChunk(4, state, 32, 48, settings.noiseSettings(), DensityFunctions.BeardifierMarker.INSTANCE, settings, PICKER, Blender.empty());
        NoiseChunkAccessor acc = (NoiseChunkAccessor) chunk;
        List<NoiseChunk.NoiseInterpolator> interpolators = acc.ga2$interpolators();
        assertTrue(!interpolators.isEmpty(), "the overworld router interpolates");
        int rows = acc.ga2$cellCountXZ() + 1;

        chunk.initializeForFirstCellX();
        List<double[][]> first = new ArrayList<>();
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            double[][] slice = ((NoiseInterpolatorAccessor) interpolator).ga2$slice0();
            double[][] copy = new double[slice.length][];
            for (int z = 0; z < slice.length; z++) {
                copy[z] = slice[z].clone();
            }
            first.add(copy);
        }
        chunk.stopInterpolation();
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            assertEquals(0, ((NoiseInterpolatorAccessor) interpolator).ga2$slice0().length, "slice0 released after the fill");
            assertEquals(0, ((NoiseInterpolatorAccessor) interpolator).ga2$slice1().length, "slice1 released after the fill");
        }

        // a second fill gets its slices back and fills them with the same values
        chunk.initializeForFirstCellX();
        for (int i = 0; i < interpolators.size(); i++) {
            NoiseInterpolatorAccessor slices = (NoiseInterpolatorAccessor) interpolators.get(i);
            assertEquals(rows, slices.ga2$slice0().length, "slice0 made again");
            assertEquals(rows, slices.ga2$slice1().length, "slice1 made again");
            for (int z = 0; z < rows; z++) {
                assertArrayEquals(first.get(i)[z], slices.ga2$slice0()[z], "interpolator " + i + " row " + z);
            }
        }
        chunk.advanceCellX(0);
        chunk.swapSlices();
        chunk.stopInterpolation();
    }
}
