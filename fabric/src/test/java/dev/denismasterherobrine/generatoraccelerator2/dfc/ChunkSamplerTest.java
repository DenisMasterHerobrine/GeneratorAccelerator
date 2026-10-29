package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
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
 * The chunk climate sampler ({@code NoiseChunk.cachedClimateSampler}, the one biome placement uses)
 * must produce the same target points through the compiled six-root program as through vanilla's
 * wrapped density functions, on real NoiseChunks, for every preset and several chunk positions.
 * This is the path that decides biomes, so it is checked on the exact quart grid vanilla samples.
 */
public class ChunkSamplerTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED, NoiseGeneratorSettings.LARGE_BIOMES,
        NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.CAVES, NoiseGeneratorSettings.FLOATING_ISLANDS);
    private static final int[][] CHUNKS = { { 0, 0 }, { 5, -3 }, { -7, 9 }, { 1000, 1000 }, { -31250, 12345 }, { 1875000, -1875000 } };

    @Test
    void chunkSamplerMatchesVanilla() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        Aquifer.FluidPicker picker = (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());
        long compared = 0;
        int distinctBiomeInputs = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 20261002L);
            NoiseSettings ns = settings.noiseSettings();
            for (int[] c : CHUNKS) {
                int minX = c[0] * 16;
                int minZ = c[1] * 16;
                NoiseChunk chunk = new NoiseChunk(4, state, minX, minZ, ns, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty());
                Climate.Sampler sampler = ((NoiseChunkAccessor) chunk).ga2$cachedClimateSampler(state.router(), List.of());
                BoundSampler bound = BoundSampler.lookup(sampler);
                assertNotNull(bound, "chunk sampler not bound on " + preset.identifier());

                int minQuartY = QuartPos.fromBlock(ns.minY());
                int maxQuartY = QuartPos.fromBlock(ns.minY() + ns.height()) - 1;
                int firstQuartX = QuartPos.fromBlock(minX);
                int firstQuartZ = QuartPos.fromBlock(minZ);
                long before = bound.samples;
                for (int qy = minQuartY; qy <= maxQuartY; qy++) {
                    for (int qx = firstQuartX; qx < firstQuartX + 4; qx++) {
                        for (int qz = firstQuartZ; qz < firstQuartZ + 4; qz++) {
                            final int fx = qx, fy = qy, fz = qz;
                            Climate.TargetPoint[] vanilla = new Climate.TargetPoint[1];
                            DfcHooks.runVanilla(() -> vanilla[0] = sampler.sample(fx, fy, fz));
                            Climate.TargetPoint compiled = sampler.sample(fx, fy, fz);
                            String where = preset.identifier() + " chunk " + c[0] + "," + c[1] + " quart " + qx + "," + qy + "," + qz;
                            assertEquals(vanilla[0].temperature(), compiled.temperature(), where + " temperature");
                            assertEquals(vanilla[0].humidity(), compiled.humidity(), where + " humidity");
                            assertEquals(vanilla[0].continentalness(), compiled.continentalness(), where + " continentalness");
                            assertEquals(vanilla[0].erosion(), compiled.erosion(), where + " erosion");
                            assertEquals(vanilla[0].depth(), compiled.depth(), where + " depth");
                            assertEquals(vanilla[0].weirdness(), compiled.weirdness(), where + " weirdness");
                            compared++;
                            if (compiled.weirdness() != 0L || compiled.continentalness() != 0L) {
                                distinctBiomeInputs++;
                            }
                        }
                    }
                }
                assertTrue(bound.samples > before, "compiled sampler was not used on " + preset.identifier());
            }
        }
        assertTrue(compared > 10000, "too few comparisons: " + compared);
        // Guards against the all-zero failure mode (every parameter quantized to 0 selects the valley band everywhere).
        assertTrue(distinctBiomeInputs > compared / 4, "climate parameters are degenerate: " + distinctBiomeInputs + " of " + compared);
        System.out.println("ChunkSamplerTest: compared " + compared + " target points");
    }
}
