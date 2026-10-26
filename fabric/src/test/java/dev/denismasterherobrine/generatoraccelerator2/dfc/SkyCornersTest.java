package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.SkyBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The noise fill with sky corners ({@link SkyBounds}) leaves every chunk as the fill without them, and as vanilla's fill: sections
 * as stored, counts, heightmaps, post-processing. Each preset's chunks are filled in a world of their own per way, so the corner
 * columns neighbours share hold only that way's corners, and a third world alternates the two ways, so that partly computed
 * columns are taken from the neighbours' cache and completed.
 */
public class SkyCornersTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 }, { 17, -5 }, { 18, -5 }, { -300, 412 }, { -299, 412 },
        { 2800, 2800 }, { -41, 9 }, { 123, 456 }, { 124, 456 }, { 640, -1290 }, { 641, -1290 } };

    @Test
    void skyCornersLeaveTheFillAsIs() {
        long skipped = ChunkBinding.SKY_CORNERS_SKIPPED.sum();
        long completed = ChunkBinding.SKY_CORNERS_COMPLETED.sum();
        long plans = ChunkBinding.SKY_PLANS.sum();
        int compared = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            for (long seed : new long[] { 1234567L, -42L }) {
                compared += compare(() -> new TerrainHarness.World(preset, seed), preset + " seed " + seed);
            }
        }
        // a datapack's per-block noise terms on top of the overworld
        HarnessWorld.Registries2 registries = HarnessWorld.registries();
        var lookup = registries.lookup;
        NoiseGeneratorSettings overworld = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        HolderGetter<NormalNoise.NoiseParameters> noises = lookup.lookupOrThrow(Registries.NOISE);
        NoiseGeneratorSettings settings = NoiseCellBoundsTest.withPerBlockNoise(overworld, noises);
        DimensionType dim = lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        var biomeSource = MultiNoiseBiomeSource.createFromPreset(Holder.direct(
            new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, registries.biomes.asLookup())));
        compared += compare(() -> new TerrainHarness.World(registries, Holder.direct(settings), biomeSource, noises, dim, 99L), "per-block noise");

        skipped = ChunkBinding.SKY_CORNERS_SKIPPED.sum() - skipped;
        completed = ChunkBinding.SKY_CORNERS_COMPLETED.sum() - completed;
        plans = ChunkBinding.SKY_PLANS.sum() - plans;
        assertTrue(plans > 0, "chunks were planned");
        assertTrue(skipped > 0, "corners were left out");
        assertTrue(completed > 0, "corners were filled later");
        System.out.println("SkyCornersTest: " + compared + " chunks identical; " + plans + " plans, " + skipped + " corners left out of the first fill, "
            + completed + " filled later");
    }

    /** The old blended noise stays within {@code BlendedSpec.bound()} (the sky bounds' leaf for it), at random blocks of every preset. */
    @Test
    void blendedNoiseStaysWithinItsBound() {
        Random random = new Random(7);
        int checked = 0;
        for (TerrainHarness.Preset preset : TerrainHarness.Preset.values()) {
            var set = ((Ga2RandomState) (Object) TerrainHarness.world(preset, 1234567L).randomState).ga2$programs();
            for (var spec : set.blendedSpecs()) {
                double bound = spec.bound();
                double seen = 0.0;
                for (int i = 0; i < 200_000; i++) {
                    int x = random.nextInt(4_000_000) - 2_000_000;
                    int y = random.nextInt(4064) - 2032;
                    int z = random.nextInt(4_000_000) - 2_000_000;
                    double v = spec.compute(x, y, z);
                    seen = Math.max(seen, Math.abs(v));
                    assertTrue(Math.abs(v) <= bound, preset + " at " + x + "," + y + "," + z + ": " + v + " past " + bound);
                    checked++;
                }
                assertTrue(bound < spec.maxValue, preset + ": " + bound + " tighter than vanilla's " + spec.maxValue);
                System.out.println("SkyCornersTest: " + preset + " old blended noise reached " + seen + " of its bound " + bound + " (vanilla's " + spec.maxValue + ")");
            }
        }
        assertTrue(checked > 0, "an old blended noise was checked");
    }

    private static int compare(Supplier<TerrainHarness.World> worlds, String what) {
        boolean was = SkyBounds.ENABLED;
        try {
            TerrainHarness.World off = worlds.get();
            TerrainHarness.World on = worlds.get();
            TerrainHarness.World mixed = worlds.get();
            TerrainHarness.World forced = worlds.get();
            int n = 0;
            for (int i = 0; i < CHUNKS.length; i++) {
                int[] c = CHUNKS[i];
                String where = what + " chunk " + c[0] + "," + c[1];
                SkyBounds.ENABLED = false;
                ProtoChunk without = off.noise(c[0], c[1]);
                SkyBounds.ENABLED = true;
                ProtoChunk with = on.noise(c[0], c[1]);
                NoiseCellBoundsTest.sameChunk(without, with, where);
                SkyBounds.ENABLED = i % 2 == 0;
                NoiseCellBoundsTest.sameChunk(without, mixed.noise(c[0], c[1]), where + " (alternating)");
                // a planned cell the aquifer would keep fluid in is filled block by block, with its corners filled then
                SkyBounds.ENABLED = true;
                ChunkBinding.COMPLETE_EVERY = 3;
                try {
                    NoiseCellBoundsTest.sameChunk(without, forced.noise(c[0], c[1]), where + " (corners filled later)");
                } finally {
                    ChunkBinding.COMPLETE_EVERY = 0;
                }
                if (i % 5 == 0) {
                    ProtoChunk vanilla;
                    try {
                        FastFill.ENABLED = false;
                        vanilla = off.noise(c[0] + 7, c[1] - 3);
                    } finally {
                        FastFill.ENABLED = true;
                    }
                    SkyBounds.ENABLED = true;
                    NoiseCellBoundsTest.sameChunk(vanilla, on.noise(c[0] + 7, c[1] - 3), where + " (+7,-3 against vanilla)");
                }
                n++;
            }
            return n;
        } finally {
            SkyBounds.ENABLED = was;
        }
    }
}
