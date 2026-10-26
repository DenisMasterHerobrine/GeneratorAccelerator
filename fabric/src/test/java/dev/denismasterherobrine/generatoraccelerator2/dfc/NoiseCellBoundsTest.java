package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

/**
 * Whole cells decided with noise leaves bounded over the cell ({@link CellBounds}, second pass) leave the chunk as vanilla's
 * per-block fill leaves it. The overworld's final density gets per-block noises the way datapacks add them: a plain noise, a
 * noise shifted by {@code shift_a}/{@code shift_b}, and a noise under a {@code cache_2d}. Vanilla's own routers keep their
 * noises under {@code interpolated}, so the second pass never runs on them.
 */
public class NoiseCellBoundsTest {
    private static final int[][] CHUNKS = { { 0, 0 }, { 17, -5 }, { -300, 412 }, { 2800, 2800 }, { -41, 9 }, { 123, 456 } };

    @Test
    void wholeCellsWithNoiseLeavesMatchVanilla() {
        HarnessWorld.Registries2 registries = HarnessWorld.registries();
        var lookup = registries.lookup;
        NoiseGeneratorSettings overworld = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        HolderGetter<NormalNoise.NoiseParameters> noises = lookup.lookupOrThrow(Registries.NOISE);
        NoiseGeneratorSettings settings = withPerBlockNoise(overworld, noises);
        DimensionType dim = lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        var biomeSource = MultiNoiseBiomeSource.createFromPreset(Holder.direct(
            new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, registries.biomes.asLookup())));
        long wonBefore = CellBounds.TIGHT_WON.sum();
        long triedBefore = CellBounds.TIGHT_TRIED.sum();
        int compared = 0;
        for (long seed : new long[] { 1234567L, -42L }) {
            TerrainHarness.World world = new TerrainHarness.World(registries, Holder.direct(settings), biomeSource, noises, dim, seed);
            for (int[] c : CHUNKS) {
                ProtoChunk vanilla;
                try {
                    FastFill.ENABLED = false;
                    vanilla = world.noise(c[0], c[1]);
                } finally {
                    FastFill.ENABLED = true;
                }
                ProtoChunk whole = world.noise(c[0], c[1]);
                sameChunk(vanilla, whole, "seed " + seed + " chunk " + c[0] + "," + c[1]);
                compared++;
            }
        }
        long tried = CellBounds.TIGHT_TRIED.sum() - triedBefore;
        long won = CellBounds.TIGHT_WON.sum() - wonBefore;
        assertTrue(CellBounds.TIGHT, "the second pass is on");
        assertTrue(tried > 0, "mixed cells were bounded again");
        assertTrue(won > 0, "the second pass decided cells (tried " + tried + ")");
        System.out.println("NoiseCellBoundsTest: " + compared + " chunks identical to vanilla's fill; second pass tried " + tried + " cells, decided " + won);
    }

    /** The overworld's final density plus three per-block noise terms, small enough to keep most of the terrain. */
    static NoiseGeneratorSettings withPerBlockNoise(NoiseGeneratorSettings s, HolderGetter<NormalNoise.NoiseParameters> noises) {
        NoiseRouter r = s.noiseRouter();
        DensityFunction plain = DensityFunctions.mul(DensityFunctions.constant(0.35), DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.1, 0.1));
        DensityFunction shifted = DensityFunctions.mul(DensityFunctions.constant(0.3), DensityFunctions.shiftedNoise2d(
            DensityFunctions.shiftA(noises.getOrThrow(Noises.SHIFT)), DensityFunctions.shiftB(noises.getOrThrow(Noises.SHIFT)), 0.25,
            noises.getOrThrow(Noises.CAVE_LAYER)));
        DensityFunction column = DensityFunctions.mul(DensityFunctions.constant(0.2), DensityFunctions.cache2d(DensityFunctions.noise(noises.getOrThrow(Noises.SURFACE), 1.0, 0.0)));
        DensityFunction fin = DensityFunctions.add(r.finalDensity(), DensityFunctions.add(plain, DensityFunctions.add(shifted, column)));
        NoiseRouter router = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(),
            r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(), r.initialDensityWithoutJaggedness(), fin, r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), router, s.surfaceRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(), s.useLegacyRandomSource());
    }

    static void sameChunk(ProtoChunk a, ProtoChunk b, String where) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            assertEquals(sa[s].nonEmptyBlockCount, sb[s].nonEmptyBlockCount, "non-empty count, section " + s + ", " + where);
            assertEquals(sa[s].tickingBlockCount, sb[s].tickingBlockCount, "ticking count, section " + s + ", " + where);
            assertEquals(sa[s].tickingFluidCount, sb[s].tickingFluidCount, "fluid count, section " + s + ", " + where);
            var da = sa[s].getStates().data;
            var db = sb[s].getStates().data;
            assertEquals(da.palette().getSize(), db.palette().getSize(), "palette size, section " + s + ", " + where);
            for (int p = 0; p < da.palette().getSize(); p++) {
                assertSame(da.palette().valueFor(p), db.palette().valueFor(p), "palette entry " + p + ", section " + s + ", " + where);
            }
            assertArrayEquals(da.storage().getRaw(), db.storage().getRaw(), "storage, section " + s + ", " + where);
        }
        for (var type : Heightmap.Types.values()) {
            assertEquals(a.hasPrimedHeightmap(type), b.hasPrimedHeightmap(type), "heightmap presence " + type + ", " + where);
            if (a.hasPrimedHeightmap(type)) {
                assertArrayEquals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData(), "heightmap " + type + ", " + where);
            }
        }
        assertEquals(Arrays.deepToString(a.getPostProcessing()), Arrays.deepToString(b.getPostProcessing()), "post-processing, " + where);
    }
}
