package dev.denismasterherobrine.generatoraccelerator2.testutil;

import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import java.lang.reflect.Method;

/** Runs NoiseBasedChunkGenerator.doFill's block loop on a bare NoiseChunk and records what it would place. */
public final class FillHarness {
    private FillHarness() {}

    /**
     * The picker {@code NoiseBasedChunkGenerator.createFluidPicker} makes, its own lambda, so GA2 recognizes it as the game does
     * ({@code CellAir.vanillaPicker}). A transcription of it when the method cannot be called.
     */
    public static Aquifer.FluidPicker vanillaPicker(NoiseGeneratorSettings settings) {
        try {
            Method m = NoiseBasedChunkGenerator.class.getDeclaredMethod("createFluidPicker", NoiseGeneratorSettings.class);
            m.setAccessible(true);
            return (Aquifer.FluidPicker) m.invoke(null, settings);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return copiedPicker(settings);
        }
    }

    /** {@code NoiseBasedChunkGenerator.createFluidPicker}'s rule in a lambda of this class. */
    public static Aquifer.FluidPicker copiedPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    /** A picker that allocates a fresh status per call, so statuses are equal by content only. */
    public static Aquifer.FluidPicker allocatingPicker(NoiseGeneratorSettings settings) {
        int sea = settings.seaLevel();
        return (x, y, z) -> y < Math.min(-54, sea) ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
            : new Aquifer.FluidStatus(sea, settings.defaultFluid());
    }

    public static NoiseChunk chunk(RandomState state, NoiseGeneratorSettings settings, int chunkX, int chunkZ, DensityFunctions.BeardifierOrMarker beard,
                            Aquifer.FluidPicker picker) {
        int cells = 16 / settings.noiseSettings().getCellWidth();
        return new NoiseChunk(cells, state, chunkX * 16, chunkZ * 16, settings.noiseSettings(), beard, settings, picker, Blender.empty());
    }

    public static final class Fill {
        public final BlockState[] states;
        public final boolean[] flags;
        public final boolean[] flagRead;
        public long nonAir;

        Fill(int size) {
            states = new BlockState[size];
            flags = new boolean[size];
            flagRead = new boolean[size];
        }
    }

    public static int index(int lx, int ly, int lz) {
        return (ly * 16 + lz) * 16 + lx;
    }

    /**
     * doFill's loop. {@code everyFlag} reads {@code shouldScheduleFluidUpdate} after every block
     * (stricter than doFill). Otherwise it reads the flag only after non-air blocks, exactly like doFill.
     */
    public static Fill fill(NoiseChunk chunk, NoiseGeneratorSettings settings, int chunkX, int chunkZ, boolean everyFlag) {
        NoiseChunkAccessor acc = (NoiseChunkAccessor) chunk;
        int cellWidth = acc.ga2$cellWidth();
        int cellHeight = acc.ga2$cellHeight();
        int cellCountY = acc.ga2$cellCountY();
        int cellMinY = acc.ga2$cellNoiseMinY();
        int minY = settings.noiseSettings().minY();
        int cellsXZ = 16 / cellWidth;
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        Fill out = new Fill(16 * 16 * settings.noiseSettings().height());
        Aquifer aquifer = chunk.aquifer();
        BlockState defaultBlock = settings.defaultBlock();
        chunk.initializeForFirstCellX();
        for (int cx = 0; cx < cellsXZ; cx++) {
            chunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsXZ; cz++) {
                for (int cy = cellCountY - 1; cy >= 0; cy--) {
                    chunk.selectCellYZ(cy, cz);
                    for (int iy = cellHeight - 1; iy >= 0; iy--) {
                        int y = (cellMinY + cy) * cellHeight + iy;
                        chunk.updateForY(y, (double) iy / (double) cellHeight);
                        for (int ix = 0; ix < cellWidth; ix++) {
                            int x = minX + cx * cellWidth + ix;
                            chunk.updateForX(x, (double) ix / (double) cellWidth);
                            for (int iz = 0; iz < cellWidth; iz++) {
                                int z = minZ + cz * cellWidth + iz;
                                chunk.updateForZ(z, (double) iz / (double) cellWidth);
                                BlockState state = acc.ga2$getInterpolatedState();
                                if (state == null) {
                                    state = defaultBlock;
                                }
                                int i = index(x - minX, y - minY, z - minZ);
                                out.states[i] = state;
                                boolean air = state == Blocks.AIR.defaultBlockState();
                                if (!air) {
                                    out.nonAir++;
                                }
                                if (everyFlag || !air) {
                                    out.flags[i] = aquifer.shouldScheduleFluidUpdate();
                                    out.flagRead[i] = true;
                                }
                            }
                        }
                    }
                }
            }
            chunk.swapSlices();
        }
        chunk.stopInterpolation();
        return out;
    }

    /** Pieces and junctions resembling a jigsaw village crossing the chunk, plus every adjustment type nearby. */
    public static Beardifier village(int chunkX, int chunkZ, int groundY, long seed) {
        Random r = new Random(seed);
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        ObjectArrayList<Beardifier.Rigid> pieces = new ObjectArrayList<>();
        ObjectArrayList<JigsawJunction> junctions = new ObjectArrayList<>();
        for (int i = 0; i < 24; i++) {
            int x0 = minX - 28 + r.nextInt(64);
            int z0 = minZ - 28 + r.nextInt(64);
            int y0 = groundY - 3 + r.nextInt(6);
            BoundingBox box = new BoundingBox(x0, y0, z0, x0 + 3 + r.nextInt(9), y0 + 4 + r.nextInt(10), z0 + 3 + r.nextInt(9));
            TerrainAdjustment adj = i % 6 == 5 ? TerrainAdjustment.NONE : TerrainAdjustment.BEARD_THIN;
            pieces.add(new Beardifier.Rigid(box, adj, r.nextInt(3) - 1));
        }
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX + 3, groundY - 20, minZ + 3, minX + 12, groundY - 5, minZ + 12), TerrainAdjustment.BEARD_BOX, 2));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX - 9, groundY + 10, minZ + 4, minX + 2, groundY + 20, minZ + 9), TerrainAdjustment.BURY, -4));
        pieces.add(new Beardifier.Rigid(new BoundingBox(minX + 8, groundY - 40, minZ - 5, minX + 20, groundY - 30, minZ + 6), TerrainAdjustment.ENCAPSULATE, 0));
        for (int i = 0; i < 40; i++) {
            junctions.add(new JigsawJunction(minX - 12 + r.nextInt(40), groundY - 2 + r.nextInt(5), minZ - 12 + r.nextInt(40), r.nextInt(3) - 1,
                i % 2 == 0 ? StructureTemplatePool.Projection.RIGID : StructureTemplatePool.Projection.TERRAIN_MATCHING));
        }
        return beardifier(pieces, junctions);
    }

    /** A beardifier of these pieces and junctions, with the box vanilla's {@code forStructuresInChunk} gives: all of them, inflated by 24. */
    public static Beardifier beardifier(List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions) {
        BoundingBox any = null;
        for (Beardifier.Rigid p : pieces) {
            any = any == null ? p.box() : BoundingBox.encapsulating(any, p.box());
        }
        for (JigsawJunction j : junctions) {
            BoundingBox b = new BoundingBox(new BlockPos(j.getSourceX(), j.getSourceGroundY(), j.getSourceZ()));
            any = any == null ? b : BoundingBox.encapsulating(any, b);
        }
        return any == null ? Beardifier.EMPTY : new Beardifier(List.copyOf(pieces), List.copyOf(junctions), any.inflatedBy(24));
    }
}
