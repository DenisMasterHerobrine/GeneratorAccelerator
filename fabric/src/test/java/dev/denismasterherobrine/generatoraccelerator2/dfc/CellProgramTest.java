package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.CacheAllInCellAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.FillHarness;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.Test;

/**
 * The compiled cell program must fill every cell cache exactly like vanilla, with and without
 * structure pieces (every terrain adjustment type plus jigsaw junctions) near the chunk.
 */
public class CellProgramTest {
    private static final List<ResourceKey<NoiseGeneratorSettings>> PRESETS = List.of(
        NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END, NoiseGeneratorSettings.FLOATING_ISLANDS);

    @Test
    void cellValuesMatchVanilla() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        Aquifer.FluidPicker picker = (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());
        long compared = 0;
        for (ResourceKey<NoiseGeneratorSettings> preset : PRESETS) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 777L);
            NoiseSettings ns = settings.noiseSettings();
            for (int variant = 0; variant < 2; variant++) {
                int minX = 320;
                int minZ = -480;
                boolean withPieces = variant == 1;
                DensityFunctions.BeardifierOrMarker vb = withPieces ? beardifier(minX, minZ) : DensityFunctions.BeardifierMarker.INSTANCE;
                DensityFunctions.BeardifierOrMarker cb = withPieces ? beardifier(minX, minZ) : DensityFunctions.BeardifierMarker.INSTANCE;
                NoiseChunk[] holder = new NoiseChunk[1];
                DfcHooks.runVanilla(() -> holder[0] = new NoiseChunk(4, state, minX, minZ, ns, vb, settings, picker, Blender.empty()));
                NoiseChunk vanilla = holder[0];
                NoiseChunk compiled = new NoiseChunk(4, state, minX, minZ, ns, cb, settings, picker, Blender.empty());
                ChunkBinding binding = ((Ga2NoiseChunk) compiled).ga2$binding();
                assertNotNull(binding);
                List<DensityFunction> vc = ((NoiseChunkAccessor) vanilla).ga2$cellCaches();
                List<DensityFunction> cc = ((NoiseChunkAccessor) compiled).ga2$cellCaches();
                assertEquals(vc.size(), cc.size());
                for (DensityFunction cache : cc) {
                    assertTrue(binding.handlesCell(((DensityFunctions.MarkerOrMarked) cache).wrapped()), "cell cache not covered on " + preset.identifier());
                }
                int cellCountY = ((NoiseChunkAccessor) compiled).ga2$cellCountY();

                DfcHooks.runVanilla(() -> { vanilla.initializeForFirstCellX(); vanilla.advanceCellX(0); });
                compiled.initializeForFirstCellX();
                compiled.advanceCellX(0);
                for (int cellZ = 0; cellZ < 4; cellZ++) {
                    for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                        final int cy = cellY, cz = cellZ;
                        DfcHooks.runVanilla(() -> vanilla.selectCellYZ(cy, cz));
                        compiled.selectCellYZ(cy, cz);
                        for (int i = 0; i < vc.size(); i++) {
                            double[] expected = ((CacheAllInCellAccessor) vc.get(i)).ga2$values();
                            double[] actual = ((CacheAllInCellAccessor) cc.get(i)).ga2$values();
                            for (int k = 0; k < expected.length; k++) {
                                if (Double.doubleToRawLongBits(expected[k]) != Double.doubleToRawLongBits(actual[k])) {
                                    throw new AssertionError(preset.identifier() + (withPieces ? " with pieces" : "") + " cell y=" + cellY + " z=" + cellZ + " cache " + i + " index " + k + ": vanilla=" + expected[k] + " dfc=" + actual[k]);
                                }
                                compared++;
                            }
                        }
                    }
                }
                assertTrue(binding.cellsFilled > 0, "cell program was not used");
                if (withPieces) {
                    assertTrue(binding.cellsWithBeard > 0 && binding.cellsWithBeard < binding.cellsFilled,
                        "beardifier culling did not split cells: " + binding.cellsWithBeard + "/" + binding.cellsFilled);
                }
            }
        }
        System.out.println("DFC cell: " + compared + " block values bit-identical to vanilla");
    }

    /** Pieces of every adjustment type plus junctions, placed so that some cells of the chunk are in range and others are not. */
    static Beardifier beardifier(int minX, int minZ) {
        ObjectArrayList<Beardifier.Rigid> pieces = new ObjectArrayList<>();
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX + 2, 60, minZ + 2, minX + 9, 70, minZ + 9), TerrainAdjustment.BEARD_THIN, 1));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX - 30, 40, minZ + 20, minX - 20, 48, minZ + 28), TerrainAdjustment.BEARD_BOX, 0));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX + 10, 100, minZ - 14, minX + 14, 110, minZ - 6), TerrainAdjustment.BURY, -3));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX + 12, 20, minZ + 12, minX + 15, 30, minZ + 15), TerrainAdjustment.ENCAPSULATE, 2));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX, -40, minZ, minX + 15, -30, minZ + 15), TerrainAdjustment.NONE, 0));
        ObjectArrayList<JigsawJunction> junctions = new ObjectArrayList<>();
        junctions.add(new JigsawJunction(minX + 6, 64, minZ + 6, 0, StructureTemplatePool.Projection.RIGID));
        junctions.add(new JigsawJunction(minX - 40, 64, minZ - 40, 0, StructureTemplatePool.Projection.TERRAIN_MATCHING));
        return FillHarness.beardifier(pieces, junctions);
    }
}
