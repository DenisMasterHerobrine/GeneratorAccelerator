package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;

/**
 * {@link LithostitchedRegions}: the functions it calls column-only give the same bits at every height (negative heights
 * included, where {@code y * 0} is {@code -0.0}), the ones that read y are not called column-only, and the memo answers
 * exactly what the function computes, in the biome fill's order.
 */
public class RegionColumnTest {
    private static DensityFunction.NoiseHolder noise(RandomState state, HolderGetter<NormalNoise.NoiseParameters> noises,
                                                     ResourceKey<NormalNoise.NoiseParameters> key) {
        Holder<NormalNoise.NoiseParameters> holder = noises.getOrThrow(key);
        return new DensityFunction.NoiseHolder(holder, state.getOrCreateNoise(key));
    }

    @Test
    void columnOnlyFunctionsIgnoreY() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        HolderGetter<NormalNoise.NoiseParameters> noises = lookup.lookupOrThrow(Registries.NOISE);
        RandomState state = RandomState.create(settings, noises, 1234567L);
        DensityFunction.NoiseHolder offset = noise(state, noises, Noises.SHIFT);
        DensityFunction.NoiseHolder temperature = noise(state, noises, Noises.TEMPERATURE);
        DensityFunction.NoiseHolder erosion = noise(state, noises, Noises.EROSION);

        DensityFunction shiftX = new DensityFunctions.Marker(DensityFunctions.Marker.Type.FlatCache,
            new DensityFunctions.Marker(DensityFunctions.Marker.Type.Cache2D, new DensityFunctions.ShiftA(offset)));
        DensityFunction shiftZ = new DensityFunctions.Marker(DensityFunctions.Marker.Type.FlatCache,
            new DensityFunctions.Marker(DensityFunctions.Marker.Type.Cache2D, new DensityFunctions.ShiftB(offset)));
        DensityFunction shifted = new DensityFunctions.ShiftedNoise(shiftX, DensityFunctions.zero(), shiftZ, 0.25, 0.0, temperature);
        DensityFunction region = DensityFunctions.mul(DensityFunctions.constant(0.5),
            DensityFunctions.add(DensityFunctions.constant(1.0), new DensityFunctions.Noise(erosion, 0.5, 0.0)));
        DensityFunction spline = new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(
                new DensityFunctions.Spline.Coordinate(Holder.direct(shifted)))
            .addPoint(-0.5f, -1.0f, 0.0f).addPoint(0.0f, 0.25f, 0.5f).addPoint(0.6f, 1.0f, 0.0f).build());
        DensityFunction mixed = new DensityFunctions.RangeChoice(region, 0.0, 0.5, DensityFunctions.mul(spline, shifted), DensityFunctions.max(region, spline));
        List<DensityFunction> columnOnly = List.of(shiftX, shifted, region, spline, mixed,
            new DensityFunctions.HolderHolder(Holder.direct(DensityFunctions.add(shiftX, shiftZ))));

        DensityFunction yNoise = new DensityFunctions.Noise(erosion, 0.5, 0.25);
        DensityFunction gradient = DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0);
        DensityFunction ySpline = new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(
                new DensityFunctions.Spline.Coordinate(Holder.direct(gradient)))
            .addPoint(-1.0f, 0.0f, 0.0f).addPoint(1.0f, 1.0f, 0.0f).build());
        List<DensityFunction> readY = List.of(yNoise, gradient, ySpline, DensityFunctions.add(region, gradient),
            new DensityFunctions.ShiftedNoise(shiftX, gradient, shiftZ, 0.25, 0.0, temperature), new DensityFunctions.Shift(offset));

        for (DensityFunction f : columnOnly) {
            assertTrue(LithostitchedRegions.columnOnly(f), "column-only: " + f);
        }
        for (DensityFunction f : readY) {
            assertFalse(LithostitchedRegions.columnOnly(f), "reads y: " + f);
        }
        Random r = new Random(9);
        int[] heights = {-64, -63, -1, 0, 1, 2, 37, 255, 319};
        for (int i = 0; i < 20_000; i++) {
            int x = r.nextInt(200_000) - 100_000;
            int z = r.nextInt(200_000) - 100_000;
            for (DensityFunction f : columnOnly) {
                long bits = Double.doubleToRawLongBits(f.compute(new DensityFunction.SinglePointContext(x, heights[0], z)));
                for (int y : heights) {
                    assertEquals(bits, Double.doubleToRawLongBits(f.compute(new DensityFunction.SinglePointContext(x, y, z))), f + " at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void memoAnswersWhatTheFunctionComputes() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        HolderGetter<NormalNoise.NoiseParameters> noises = lookup.lookupOrThrow(Registries.NOISE);
        RandomState state = RandomState.create(settings, noises, 42L);
        DensityFunction region = DensityFunctions.mul(DensityFunctions.constant(0.5),
            DensityFunctions.add(DensityFunctions.constant(1.0), new DensityFunctions.Noise(noise(state, noises, Noises.EROSION), 0.5, 0.0)));
        DensityFunction other = DensityFunctions.add(region, DensityFunctions.constant(0.125));
        Operation<Double> compute = args -> ((DensityFunction) args[0]).compute((DensityFunction.FunctionContext) args[1]);
        long before = LithostitchedRegions.REMEMBERED.sum();
        // the biome fill's order: quart x, then y, then z, per section. Two functions are interleaved now and then
        for (int chunk = 0; chunk < 40; chunk++) {
            int bx = (chunk % 7 - 3) * 16;
            int bz = (chunk / 7 - 3) * 16;
            for (int qy = -16; qy < 80; qy++) {
                for (int qx = 0; qx < 4; qx++) {
                    for (int qz = 0; qz < 4; qz++) {
                        DensityFunction f = (qy == 5 && qz == 2) ? other : region;
                        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(bx + qx * 4, qy * 4, bz + qz * 4);
                        assertEquals(Double.doubleToRawLongBits(f.compute(ctx)), Double.doubleToRawLongBits(LithostitchedRegions.value(f, ctx, compute)));
                    }
                }
            }
        }
        assertTrue(LithostitchedRegions.REMEMBERED.sum() - before > 40 * 16 * 80, "the memo answered most heights");
    }
}
