package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.CellAir;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.Ga2Aquifer;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWriter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.CacheAllInCellAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import java.lang.invoke.MethodHandle;

/**
 * {@code NoiseBasedChunkGenerator.doFill}, transcribed with the per-block work done in place.
 *
 * <p>Vanilla's loop asks the NoiseChunk for every one of a chunk's ~98k blocks. It calls {@code updateForZ},
 * then {@code getInterpolatedState}. That call walks the material rule list into the aquifer rule, a lambda
 * that reads the final density from the cell cache and asks the aquifer. When the aquifer leaves the block
 * solid, the walk continues into the ore vein rule. The loop then updates two heightmaps. This loop makes
 * exactly the same calls on the same objects in the same order (the NoiseChunk's own interpolation steps,
 * the aquifer, the vein rule, the section write, the post-processing mark). But it reads the final density
 * straight from the cell cache array, steps z without a callback and keeps the two worldgen heightmaps per
 * column ({@link FillHeights}).
 *
 * <p>It only runs when the NoiseChunk's material rules are exactly the two vanilla made in its constructor
 * (recorded there), its final density is a cell cache, and no other mod changed the loop it replaces.
 * Otherwise vanilla's {@code doFill} runs. Another mod may change the per-block state ({@code getInterpolatedState}
 * or what it runs). Then the loop asks the NoiseChunk's own {@code getInterpolatedState} for each block instead.
 * That call sees the same position vanilla's loop sets.
 */
public final class FastFill {
    private FastFill() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public static final LongAdder FILLS = new LongAdder();
    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastFill"));
    /** Whole-cell paths over {@code CellBounds}. Not final so the exactness test can compare both ways. */
    public static boolean CLASSIFY_CELLS = !"false".equals(System.getProperty("generatoraccelerator2.classifyCells"));
    public static final LongAdder CELLS_SKY = new LongAdder();
    public static final LongAdder CELLS_SOLID = new LongAdder();
    public static final LongAdder CELLS_SOLID_VEINS = new LongAdder();

    /** What the NoiseChunk recorded about its own material rules. */
    public interface Rules {
        /** The two rules vanilla added in the constructor, in order ({@code null} when not recorded). */
        Object[] ga2$materialRules();

        /** The final density cell cache the aquifer rule reads (a {@code NoiseChunk.CacheAllInCell}). */
        DensityFunction ga2$finalDensityCache();

        NoiseChunk.BlockStateFiller ga2$blockStateRule();

        /** {@code updateForZ} without its callback (lazy interpolation must be on). */
        void ga2$stepZ(int blockZ, double dz);

        /** {@code updateForY} as GA2's hook does it, without the hook's callback object ({@link HookTargets#NOISE_FILL_STEPS}). */
        void ga2$stepY(int blockY, double dy);

        /** {@code updateForX} as GA2's hook does it, without the hook's callback object ({@link HookTargets#NOISE_FILL_STEPS}). */
        void ga2$stepX(int blockX, double dx);

        /** The wrapped router's {@code vein_toggle}, or null (an interpolator when the datapack interpolates it, as vanilla's does). */
        DensityFunction ga2$veinToggle();

        /** {@code rule}'s allocation-free transcription ({@link VeinRule}) when it is the vein rule the constructor built, else {@code rule}. */
        NoiseChunk.BlockStateFiller ga2$fastVeinRule(NoiseChunk.BlockStateFiller rule);

        /** Whether {@code rule} is the vein rule the constructor built and that rule never places a vein ({@code GtceuVeins}). */
        boolean ga2$placesNoVein(Object rule);
    }

    /** @return the vein rule (or {@code this} marker when there is none), or {@code null} when the chunk does not qualify */
    private static Object veinRule(NoiseChunk noiseChunk) {
        // the NoiseChunk hooks may be skipped (takeover off next to a mod that patches NoiseChunk)
        if (!((Object) noiseChunk instanceof Rules rules) || !((Object) noiseChunk instanceof NoiseChunkAccessor)) {
            return null;
        }
        Object[] made = rules.ga2$materialRules();
        if (made == null || made[0] == null || !(rules.ga2$finalDensityCache() instanceof CacheAllInCellAccessor)) {
            return null;
        }
        if (!(rules.ga2$blockStateRule() instanceof MaterialRuleList list)) {
            return null;
        }
        List<NoiseChunk.BlockStateFiller> fillers = list.materialRuleList();
        int expected = made[1] == null ? 1 : 2;
        if (fillers.size() != expected || fillers.get(0) != made[0] || (expected == 2 && fillers.get(1) != made[1])) {
            return null;
        }
        if (expected == 1 || rules.ga2$placesNoVein(made[1])) {
            return NO_VEINS;
        }
        return made[1];
    }

    private static final Object NO_VEINS = new Object();
    private static final HookTargets.Hook VEIN_HOOK = HookTargets.hook(HookTargets.NOISE_VEIN_RULE);

    public static boolean applies(NoiseChunk noiseChunk) {
        return ENABLED && LazyInterpolation.ENABLED && HookTargets.allowed(HookTargets.NOISE_FILL) && veinRule(noiseChunk) != null;
    }

    /** {@code doFill} for a chunk {@link #applies} accepted; returns the chunk. */
    public static ChunkAccess fill(ChunkAccess chunk, NoiseChunk noisechunk, BlockState defaultBlock, int minCellY, int cellCountY) {
        FILLS.increment();
        // cells by class, added to the counters once per chunk
        int skyCells = 0, solidCells = 0, veinCells = 0;
        Rules rules = (Rules) (Object) noisechunk;
        Ga2NoiseChunk duck = (Ga2NoiseChunk) (Object) noisechunk;
        Object vein = veinRule(noisechunk);
        NoiseChunk.BlockStateFiller veinRule = vein == NO_VEINS ? null : (NoiseChunk.BlockStateFiller) vein;
        if (veinRule != null && VEIN_HOOK.allowed()) {
            // vanilla's rule, as is. The transcription gives the same answers without a random source per block
            veinRule = rules.ga2$fastVeinRule(veinRule);
        }
        double[] cellValues = ((CacheAllInCellAccessor) rules.ga2$finalDensityCache()).ga2$values();
        // another mod changes the per-block state: ask the NoiseChunk itself. The exception is a change FastFill knows (FillSteps:
        // YUNG's API, Iron's Spellbooks, which keep fluids out of their structures). A known change needs nothing in a chunk where
        // it does not act, and runs its own step on water and lava where it does
        Object plan = FillSteps.plan(noisechunk);
        boolean inline = plan != FillSteps.SLOW;
        MethodHandle fluidStep = plan instanceof MethodHandle h ? h : null;
        NoiseChunkAccessor access = (NoiseChunkAccessor) noisechunk;

        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        boolean columns = FillHeights.ENABLED && unset(heightmap) && unset(heightmap1);
        int[] top = columns ? new int[256] : null;
        int[] top1 = columns ? new int[256] : null;
        if (columns) {
            Arrays.fill(top, Integer.MIN_VALUE);
            Arrays.fill(top1, Integer.MIN_VALUE);
        }
        Predicate<BlockState> opaque = ((FillHeights.Ga2Heightmap) (Object) heightmap).ga2$isOpaque();
        Predicate<BlockState> opaque1 = ((FillHeights.Ga2Heightmap) (Object) heightmap1).ga2$isOpaque();

        ChunkPos chunkpos = chunk.getPos();
        boolean voidTerrain = SharedConstants.debugVoidTerrain(chunkpos);
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        Aquifer aquifer = noisechunk.aquifer();
        // GA2's aquifer fast path called directly, and the interpolators stepped directly, when nothing else changes those methods.
        // Their hooks made a callback object per block (row), and escape analysis does not always remove it
        Ga2Aquifer fastAquifer =
            aquifer instanceof Ga2Aquifer a && a.ga2$fast() ? a : null;
        boolean steps = HookTargets.allowed(HookTargets.NOISE_FILL_STEPS);
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        int k = ((NoiseChunkAccessor) noisechunk).ga2$cellWidth();
        int l = ((NoiseChunkAccessor) noisechunk).ga2$cellHeight();
        int i1 = 16 / k;
        int j1 = 16 / k;

        // Whole cells: while classification is on, the final density's cell program classifies each cell from its corners
        // (CellBounds). It leaves the per-block densities of an all-air or all-solid cell unfilled. This loop then writes
        // nothing for the air cell. For the solid cell it writes the default block, or runs the vein rule alone per block.
        ChunkBinding binding = duck.ga2$binding();
        CellSkipper skipper = CLASSIFY_CELLS && DfcConfig.wholeCells() && inline && binding != null && defaultBlock.getFluidState().isEmpty() ? CellAir.of(aquifer) : null;
        // vanilla's vein rule gives no vein where |toggle| stays below the threshold or outside both vein types' y ranges.
        // An interpolated toggle lies within its corners over the cell, and reading the corners computes nothing
        NoiseInterpolatorAccessor toggle = skipper != null && veinRule != null && HookTargets.allowed(HookTargets.NOISE_VEIN_RULE)
            && rules.ga2$veinToggle() instanceof NoiseChunk.NoiseInterpolator t ? (NoiseInterpolatorAccessor) t : null;
        boolean defaultOpaque = opaque.test(defaultBlock);
        boolean defaultOpaque1 = opaque1.test(defaultBlock);
        int blocksPerCell = k * k * l;
        if (skipper != null) {
            binding.classifyCells(cellValues, skipper);
            if (VERIFY_EVERY == 0) {
                // before the first slice: the plan decides which corners its columns compute
                binding.planSky(i, j, CellAir.skyPicker(aquifer));
            }
        }
        noisechunk.initializeForFirstCellX();
        // block ids per section, packed once at the end (SectionWriter). The heightmaps then come from the column tops
        SectionWriter writer = columns && !voidTerrain ? SectionWriter.begin(chunk) : null;
        short defaultId = writer != null ? writer.id(defaultBlock) : 0;

        try {
            for (int k1 = 0; k1 < i1; k1++) {
                noisechunk.advanceCellX(k1);

                for (int l1 = 0; l1 < j1; l1++) {
                    int i2 = chunk.getSectionsCount() - 1;
                    LevelChunkSection levelchunksection = chunk.getSection(i2);
                    if (writer != null) {
                        writer.select(i2);
                    }

                    for (int j2 = cellCountY - 1; j2 >= 0; j2--) {
                        duck.ga2$setCellClass(CellBounds.MIXED);
                        noisechunk.selectCellYZ(j2, l1);
                        int cls = duck.ga2$cellClass();

                        if (cls != CellBounds.MIXED) {
                            int y0 = (minCellY + j2) * l;
                            if (cls == CellBounds.SKY) {
                                // every block is air: nothing to write, and air never changes a heightmap in a top-down fill
                                skyCells++;
                                duck.ga2$advanceInterpolationCounter(blocksPerCell);
                                if (VERIFY_EVERY > 0) {
                                    verifyCell(noisechunk, duck, rules, access, defaultBlock, AIR, i + k1 * k, y0, j + l1 * k, k, l);
                                }
                                continue;
                            }
                            if (veinRule == null || toggle != null && noVeins(toggle, y0, y0 + l - 1)) {
                                // every block is the default block (the aquifer gives nothing for a positive density)
                                solidCells++;
                                duck.ga2$advanceInterpolationCounter(blocksPerCell);
                                if (!voidTerrain) {
                                    for (int k2 = l - 1; k2 >= 0; k2--) {
                                        int l2 = y0 + k2;
                                        int i3 = l2 & 15;
                                        int j3 = chunk.getSectionIndex(l2);
                                        if (i2 != j3) {
                                            i2 = j3;
                                            levelchunksection = chunk.getSection(j3);
                                            if (writer != null) {
                                                writer.select(j3);
                                            }
                                        }
                                        for (int k3 = 0; k3 < k; k3++) {
                                            int i4 = (i + k1 * k + k3) & 15;
                                            for (int j4 = 0; j4 < k; j4++) {
                                                int l4 = (j + l1 * k + j4) & 15;
                                                if (writer != null) {
                                                    writer.put((i3 << 8) | (l4 << 4) | i4, defaultId);
                                                } else {
                                                    levelchunksection.setBlockState(i4, i3, l4, defaultBlock, false);
                                                }
                                                if (!columns) {
                                                    heightmap.update(i4, l2, l4, defaultBlock);
                                                    heightmap1.update(i4, l2, l4, defaultBlock);
                                                }
                                            }
                                        }
                                    }
                                    if (columns) {
                                        // only the cell's top row can raise a column
                                        int y1 = y0 + l - 1;
                                        for (int k3 = 0; k3 < k; k3++) {
                                            for (int j4 = 0; j4 < k; j4++) {
                                                int index = ((i + k1 * k + k3) & 15) + ((j + l1 * k + j4) & 15) * 16;
                                                if (defaultOpaque && y1 > top[index]) {
                                                    top[index] = y1;
                                                }
                                                if (defaultOpaque1 && y1 > top1[index]) {
                                                    top1[index] = y1;
                                                }
                                            }
                                        }
                                    }
                                }
                                if (VERIFY_EVERY > 0) {
                                    verifyCell(noisechunk, duck, rules, access, defaultBlock, defaultBlock, i + k1 * k, y0, j + l1 * k, k, l);
                                }
                                continue;
                            }
                            // every block is solid, so the aquifer gives nothing and leaves its flag false. Only the vein rule runs, per block
                            veinCells++;
                            if (VERIFY_EVERY > 0) {
                                duck.ga2$fillCurrentCell();
                            }
                            for (int k2 = l - 1; k2 >= 0; k2--) {
                                int l2 = y0 + k2;
                                int i3 = l2 & 15;
                                int j3 = chunk.getSectionIndex(l2);
                                if (i2 != j3) {
                                    i2 = j3;
                                    levelchunksection = chunk.getSection(j3);
                                    if (writer != null) {
                                        writer.select(j3);
                                    }
                                }
                                if (steps) {
                                    rules.ga2$stepY(l2, (double) k2 / (double) l);
                                } else {
                                    noisechunk.updateForY(l2, (double) k2 / (double) l);
                                }

                                for (int k3 = 0; k3 < k; k3++) {
                                    int l3 = i + k1 * k + k3;
                                    int i4 = l3 & 15;
                                    if (steps) {
                                        rules.ga2$stepX(l3, (double) k3 / (double) k);
                                    } else {
                                        noisechunk.updateForX(l3, (double) k3 / (double) k);
                                    }

                                    for (int j4 = 0; j4 < k; j4++) {
                                        int k4 = j + l1 * k + j4;
                                        int l4 = k4 & 15;
                                        rules.ga2$stepZ(k4, (double) j4 / (double) k);
                                        BlockState blockstate = veinRule.calculate(noisechunk);
                                        if (fluidStep != null) {
                                            blockstate = FillSteps.run(fluidStep, noisechunk, blockstate);
                                        }
                                        if (VERIFY_EVERY > 0 && VERIFY_SEEN.incrementAndGet() % VERIFY_EVERY == 0) {
                                            verify(access.ga2$getInterpolatedState(), blockstate, l3, l2, k4);
                                        }
                                        if (blockstate == null) {
                                            blockstate = defaultBlock;
                                        }

                                        if (blockstate != AIR && !voidTerrain) {
                                            if (writer != null) {
                                                writer.put((i3 << 8) | (l4 << 4) | i4, writer.id(blockstate));
                                            } else {
                                                levelchunksection.setBlockState(i4, i3, l4, blockstate, false);
                                            }
                                            if (columns) {
                                                int index = i4 + l4 * 16;
                                                if (l2 > top[index] && opaque.test(blockstate)) {
                                                    top[index] = l2;
                                                }
                                                if (l2 > top1[index] && opaque1.test(blockstate)) {
                                                    top1[index] = l2;
                                                }
                                            } else {
                                                heightmap.update(i4, l2, l4, blockstate);
                                                heightmap1.update(i4, l2, l4, blockstate);
                                            }
                                            // no post-processing mark: the aquifer's flag is false for every solid block
                                        }
                                    }
                                }
                            }
                            continue;
                        }

                        for (int k2 = l - 1; k2 >= 0; k2--) {
                            int l2 = (minCellY + j2) * l + k2;
                            int i3 = l2 & 15;
                            int j3 = chunk.getSectionIndex(l2);
                            if (i2 != j3) {
                                i2 = j3;
                                levelchunksection = chunk.getSection(j3);
                                if (writer != null) {
                                    writer.select(j3);
                                }
                            }

                            double d0 = (double) k2 / (double) l;
                            if (steps) {
                                rules.ga2$stepY(l2, d0);
                            } else {
                                noisechunk.updateForY(l2, d0);
                            }
                            int rowBase = (l - 1 - k2) * k;

                            for (int k3 = 0; k3 < k; k3++) {
                                int l3 = i + k1 * k + k3;
                                int i4 = l3 & 15;
                                double d1 = (double) k3 / (double) k;
                                if (steps) {
                                    rules.ga2$stepX(l3, d1);
                                } else {
                                    noisechunk.updateForX(l3, d1);
                                }
                                int cellBase = (rowBase + k3) * k;

                                for (int j4 = 0; j4 < k; j4++) {
                                    int k4 = j + l1 * k + j4;
                                    int l4 = k4 & 15;
                                    double d2 = (double) j4 / (double) k;
                                    rules.ga2$stepZ(k4, d2);
                                    BlockState blockstate;
                                    if (inline) {
                                        // getInterpolatedState: the aquifer rule (final density from the cell cache), then the vein rule
                                        blockstate = fastAquifer != null ? fastAquifer.ga2$substance(noisechunk, cellValues[cellBase + j4])
                                            : aquifer.computeSubstance(noisechunk, cellValues[cellBase + j4]);
                                        if (blockstate == null && veinRule != null) {
                                            blockstate = veinRule.calculate(noisechunk);
                                        }
                                        if (fluidStep != null) {
                                            blockstate = FillSteps.run(fluidStep, noisechunk, blockstate);
                                        }
                                        if (VERIFY_EVERY > 0 && VERIFY_SEEN.incrementAndGet() % VERIFY_EVERY == 0) {
                                            verify(access.ga2$getInterpolatedState(), blockstate, l3, l2, k4);
                                        }
                                    } else {
                                        blockstate = access.ga2$getInterpolatedState();
                                    }
                                    if (blockstate == null) {
                                        blockstate = defaultBlock;
                                    }

                                    if (blockstate != AIR && !voidTerrain) {
                                        if (writer != null) {
                                            writer.put((i3 << 8) | (l4 << 4) | i4, writer.id(blockstate));
                                        } else {
                                            levelchunksection.setBlockState(i4, i3, l4, blockstate, false);
                                        }
                                        if (columns) {
                                            int index = i4 + l4 * 16;
                                            if (l2 > top[index] && opaque.test(blockstate)) {
                                                top[index] = l2;
                                            }
                                            if (l2 > top1[index] && opaque1.test(blockstate)) {
                                                top1[index] = l2;
                                            }
                                        } else {
                                            heightmap.update(i4, l2, l4, blockstate);
                                            heightmap1.update(i4, l2, l4, blockstate);
                                        }
                                        // GA2's aquifer resolves its flag only when read, so it is read for fluids alone (nothing else uses it)
                                        if (fastAquifer != null ? !blockstate.getFluidState().isEmpty() && fastAquifer.ga2$scheduleFlag()
                                            : aquifer.shouldScheduleFluidUpdate() && !blockstate.getFluidState().isEmpty()) {
                                            blockpos$mutableblockpos.set(l3, l2, k4);
                                            chunk.markPosForPostprocessing(blockpos$mutableblockpos);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                noisechunk.swapSlices();
            }
            if (writer != null) {
                writer.finish();
            }
        } finally {
            if (skipper != null) {
                binding.stopClassifying();
            }
            if (writer != null) {
                writer.close();
            }
        }

        noisechunk.stopInterpolation();
        if (columns) {
            setColumns(heightmap, top);
            setColumns(heightmap1, top1);
        }
        CELLS_SKY.add(skyCells);
        CELLS_SOLID.add(solidCells);
        CELLS_SOLID_VEINS.add(veinCells);
        return chunk;
    }

    /** {@code (double) 0.4F}: vanilla compares a double sum against its float threshold. */
    private static final double VEIN_THRESHOLD = 0.4F;

    /**
     * True when vanilla's vein rule returns null for every block of a cell: no vein type's y range meets the cell, or the
     * toggle's magnitude cannot reach the threshold (its edge round-off only lowers the sum). The toggle's eight corners
     * bound its interpolated value over the cell.
     */
    private static boolean noVeins(NoiseInterpolatorAccessor toggle, int y0, int y1) {
        boolean inRange = false;
        for (OreVeinifier.VeinType type : OreVeinifier.VeinType.values()) {
            if (y1 >= type.minY && y0 <= type.maxY) {
                inRange = true;
                break;
            }
        }
        if (!inRange) {
            return true;
        }
        double m = Math.abs(toggle.ga2$noise000());
        m = Math.max(m, Math.abs(toggle.ga2$noise100()));
        m = Math.max(m, Math.abs(toggle.ga2$noise010()));
        m = Math.max(m, Math.abs(toggle.ga2$noise110()));
        m = Math.max(m, Math.abs(toggle.ga2$noise001()));
        m = Math.max(m, Math.abs(toggle.ga2$noise101()));
        m = Math.max(m, Math.abs(toggle.ga2$noise011()));
        m = Math.max(m, Math.abs(toggle.ga2$noise111()));
        return m < VEIN_THRESHOLD;
    }

    /**
     * Debug ({@code -Dgeneratoraccelerator2.verify.fill=N}): every block of every N-th cell handled whole is checked against
     * the NoiseChunk's own state, after filling the cell's densities after all. The checks themselves ask the aquifer for
     * blocks the fill skipped, so in a world whose aquifer statuses depend on evaluation order they can change later blocks.
     */
    private static void verifyCell(NoiseChunk noisechunk, Ga2NoiseChunk duck, Rules rules, NoiseChunkAccessor access, BlockState defaultBlock, BlockState expected,
                                   int x0, int y0, int z0, int k, int l) {
        if (VERIFY_CELLS.incrementAndGet() % VERIFY_EVERY != 0) {
            return;
        }
        String diag = DIAG_CELLS.get() < 3 ? duck.ga2$binding().debugLastCell() : null;
        duck.ga2$fillCurrentCell();
        double[] values = ((CacheAllInCellAccessor) rules.ga2$finalDensityCache()).ga2$values();
        boolean logged = false;
        for (int k2 = l - 1; k2 >= 0; k2--) {
            int y = y0 + k2;
            noisechunk.updateForY(y, (double) k2 / (double) l);
            for (int k3 = 0; k3 < k; k3++) {
                int x = x0 + k3;
                noisechunk.updateForX(x, (double) k3 / (double) k);
                for (int j4 = 0; j4 < k; j4++) {
                    int z = z0 + j4;
                    rules.ga2$stepZ(z, (double) j4 / (double) k);
                    BlockState vanilla = access.ga2$getInterpolatedState();
                    if (vanilla == null) {
                        vanilla = defaultBlock;
                    }
                    if (vanilla != expected && diag != null && !logged && DIAG_CELLS.getAndIncrement() < 3) {
                        logged = true;
                        Ga2Log.get("Verify").warn("cell {},{},{} handled whole as {}, vanilla {}, density {}:{}",
                            x0, y0, z0, expected, vanilla, values[((l - 1 - k2) * k + k3) * k + j4], diag);
                    }
                    verify(vanilla, expected, x, y, z);
                }
            }
        }
    }

    private static final AtomicInteger DIAG_CELLS = new AtomicInteger();

    /** {@code -Dgeneratoraccelerator2.verify.fill=N}: every N-th state FastFill computes in place is checked against the NoiseChunk's own. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.fill", 0);
    private static final AtomicLong VERIFY_SEEN = new AtomicLong();
    private static final AtomicLong VERIFY_CELLS = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();

    private static void verify(BlockState expected, BlockState actual, int x, int y, int z) {
        VERIFIED.incrementAndGet();
        if (expected != actual && DIFFERING.incrementAndGet() <= 10) {
            Ga2Log.get("Verify").warn("fill mismatch at {},{},{}: expected {}, got {}",
                x, y, z, expected, actual);
        }
    }

    private static boolean unset(Heightmap heightmap) {
        for (long word : heightmap.getRawData()) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    private static void setColumns(Heightmap heightmap, int[] top) {
        FillHeights.Ga2Heightmap access = (FillHeights.Ga2Heightmap) (Object) heightmap;
        for (int index = 0; index < 256; index++) {
            if (top[index] != Integer.MIN_VALUE) {
                access.ga2$setHeight(index & 15, index >> 4, top[index] + 1);
            }
        }
    }
}
