package dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferFastPath;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.CellAir;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegions;
import dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes.FrostedCaves;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.Ga2Aquifer;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.XoroshiroFactoryAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Arrays;

/**
 * Exact fast path for {@code NoiseBasedAquifer.computeSubstance}.
 *
 * <p>Vanilla looks for the three nearest of 12 aquifer cells (2 x 3 x 2 around the block) for every
 * block that is not solid. That includes every air block of the open sky. Blocks that share the same
 * 12 cells form a neighbourhood. The first time a neighbourhood is seen, its 12 statuses are computed
 * with vanilla's own code and caches, and the neighbourhood is classified:
 * <ul>
 * <li>All 12 statuses have the same fluid level and type. Whichever three are nearest, the block is
 *     {@code status.at(y)} and every pressure term is 0. The result never depends on the search.</li>
 * <li>Otherwise, the block is at least 5 blocks above every fluid level of the 12. The block is air
 *     and every pressure term is negative, so the result is air. The barrier noise is not even sampled.</li>
 * </ul>
 * In both cases only the {@code shouldScheduleFluidUpdate} flag depends on the two nearest
 * distances. That flag is computed when someone reads it. Every other block runs vanilla's algorithm
 * with a branch-free top-3 search over cached cell centres. The search reproduces vanilla's tie order.
 *
 * <p>Classification computes statuses that vanilla might compute later or not at all. So it only runs
 * up front when the status fields read no order-sensitive chunk state (see {@code OrderIndependence}).
 * Otherwise only the search is replaced, and the search computes exactly the statuses vanilla computes,
 * in the same order. A neighbourhood is then classified as soon as the searches have computed all 12 of
 * its statuses. After that, the two short cuts above compute nothing vanilla would not compute. The
 * searches of the spared blocks would only have read cached statuses.
 *
 * <p>Consider the case where the only order-sensitive status field is the surface level function
 * ({@link AquiferFastPath#COLUMNS}). A status whose 13 surface columns are already in the chunk's memo reads nothing
 * order-sensitive. Its computation evaluates the point fields and reads memoised levels. So it gives the same status
 * whenever it runs, and it changes no state that a later value depends on. A neighbourhood is classified ahead as soon
 * as every status it lacks is of that kind. By induction over the fill, the memo then holds the same columns as vanilla's
 * memo at every block, computed by the same statuses in the same order. A status computed ahead never computes a column.
 * A block that the classification spares would only have computed statuses whose columns were already memoised.
 *
 * <p>{@link Ga2Aquifer#ga2$cellAir} answers the noise fill's whole-cell question from the same data.
 */
@Mixin(value = Aquifer.NoiseBasedAquifer.class, priority = 5000)
public abstract class NoiseBasedAquiferMixin implements Ga2Aquifer {
    @Shadow @Final private static double FLOWING_UPDATE_SIMULARITY;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final protected long[] aquiferLocationCache;
    @Shadow @Final private Aquifer.FluidStatus[] aquiferCache;
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow protected boolean shouldScheduleFluidUpdate;
    @Shadow @Final protected int minGridX;
    @Shadow @Final protected int minGridY;
    @Shadow @Final protected int minGridZ;
    @Shadow @Final protected int gridSizeX;
    @Shadow @Final protected int gridSizeZ;
    @Shadow @Final private NoiseChunk noiseChunk;
    @Shadow @Final private static int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS;

    // Better Caves' liquid regions this aquifer was made with, and the table of the chunk last asked (aquifer.LiquidRegions)
    @Unique private boolean ga2$lqResolved;
    @Unique private Object ga2$lqRegions;
    @Unique private LiquidRegions.Table ga2$lqTable;
    @Unique private int ga2$lqChunkX = Integer.MIN_VALUE;
    @Unique private int ga2$lqChunkZ = Integer.MIN_VALUE;

    /** The chunk's liquid-region table, or null when the mod's step changes nothing for this aquifer. */
    @Unique
    private LiquidRegions.Table ga2$lqTable(int chunkX, int chunkZ) {
        if (!this.ga2$lqResolved) {
            this.ga2$lqRegions = LiquidRegions.regionsOf((Aquifer) (Object) this);
            this.ga2$lqResolved = true;
        }
        if (this.ga2$lqRegions == null) {
            return null;
        }
        if (chunkX != this.ga2$lqChunkX || chunkZ != this.ga2$lqChunkZ) {
            this.ga2$lqTable = LiquidRegions.table(this.ga2$lqRegions, chunkX, chunkZ);
            this.ga2$lqChunkX = chunkX;
            this.ga2$lqChunkZ = chunkZ;
        }
        return this.ga2$lqTable;
    }

    /** The fast path's answer after Better Caves' step, as the mod's handler changes vanilla's answer at the method's return. */
    @Unique
    private BlockState ga2$liquids(int x, int y, int z, BlockState state) {
        if (state == null) {
            return null;
        }
        LiquidRegions.Table table = ga2$lqTable(x >> 4, z >> 4);
        return table == null ? state : LiquidRegions.apply(table, x, y, z, state);
    }

    @Shadow
    protected static double similarity(int a, int b) {
        throw new AssertionError();
    }

    @Shadow
    private Aquifer.FluidStatus getAquiferStatus(long location) {
        throw new AssertionError();
    }

    @Shadow
    private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble barrier, Aquifer.FluidStatus a, Aquifer.FluidStatus b) {
        throw new AssertionError();
    }

    @Unique private static final byte GA2_NEW = 0;
    @Unique private static final byte GA2_LOCATED = 1;
    @Unique private static final byte GA2_UNIFORM = 2;
    @Unique private static final byte GA2_MIXED = 3;
    /** Fluid levels within this bound keep every pressure term exact in int and double arithmetic. */
    @Unique private static final int GA2_LEVEL_LIMIT = 1 << 24;
    /** {@code y >= level + 5} drives every pressure term below -4 (see the class comment). */
    @Unique private static final int GA2_AIR_MARGIN = 5;

    @Unique private int ga2$mode;
    /** Each cell's Frosted Caves answer looked up ahead and the cell centres they were looked up at, or null to look up live (FrostedCaves). */
    @Unique private byte[] ga2$frosted;
    @Unique private long[] ga2$frostedAt;
    /** Whether the Frosted Caves answers were decided (before the first status, FrostedCaves). */
    @Unique private boolean ga2$frostedDecided;
    @Unique private int ga2$gridSizeY;
    /** Index offsets of the 12 candidate cells from the neighbourhood key, in vanilla's loop order. */
    @Unique private int[] ga2$offsets;
    /** Unpacked cell centres, three ints per cell, valid where {@link #ga2$located} is set. */
    @Unique private int[] ga2$centres;
    @Unique private boolean[] ga2$located;
    @Unique private byte[] ga2$kind;
    @Unique private Aquifer.FluidStatus[] ga2$uniform;
    @Unique private int[] ga2$airFrom;
    @Unique private boolean ga2$pending;
    @Unique private int ga2$pendingX;
    @Unique private int ga2$pendingY;
    @Unique private int ga2$pendingZ;
    @Unique private int ga2$pendingKey;
    /** The neighbourhood whose 12 centres are in {@link #ga2$nx}, {@link #ga2$ny}, {@link #ga2$nz} (vanilla's loop order). */
    @Unique private int ga2$nearKey = -1;
    @Unique private final int[] ga2$nx = new int[12];
    @Unique private final int[] ga2$ny = new int[12];
    @Unique private final int[] ga2$nz = new int[12];
    /** Per candidate of the current search: squared distance shifted left by 4, with {@code 15 - order} in the low bits. */
    @Unique private final int[] ga2$packed = new int[12];
    /** {@link AquiferFastPath#COLUMNS} mode. The chunk's surface level memo, and per neighbourhood the column the last classify attempt lacked. */
    @Unique private Long2IntMap ga2$levels;
    @Unique private long[] ga2$waitingFor;
    /** Per candidate: squared distance from the nearest and the farthest block of a box ({@link #ga2$classifyBox}). */
    @Unique private final int[] ga2$boxNear = new int[12];
    @Unique private final int[] ga2$boxFar = new int[12];
    @Unique private static final int GA2_BOX_SEARCH = 0;
    @Unique private static final int GA2_BOX_UNIFORM = 1;
    @Unique private static final int GA2_BOX_AIR = 2;
    /** The box that {@link #ga2$box} decided last (neighbourhood key, block box / 4), its result, and its status when uniform. */
    @Unique private int ga2$boxKey = -1;
    @Unique private int ga2$boxX;
    @Unique private int ga2$boxY;
    @Unique private int ga2$boxZ;
    @Unique private int ga2$boxEpoch;
    @Unique private int ga2$boxResult;
    @Unique private Aquifer.FluidStatus ga2$boxStatus;
    /** The status of the box {@link #ga2$box} remembers, when uniform. */
    @Unique private Aquifer.FluidStatus ga2$boxCachedStatus;
    /** Statuses computed by searches so far (see {@link #ga2$status}). */
    @Unique private int ga2$epoch;
    /** The barrier value holder of {@link #ga2$search}. The aquifer is used by one thread. */
    @Unique private final MutableDouble ga2$barrier = new MutableDouble(Double.NaN);
    /** The aquifer's random factory is vanilla's Xoroshiro factory. {@link #ga2$locate} draws from its seeds directly. */
    @Unique private boolean ga2$xoroshiro;
    @Unique private long ga2$seedLo;
    @Unique private long ga2$seedHi;
    /** The state of the current cell's draws ({@link #ga2$nextInt(int)}). */
    @Unique private long ga2$lo;
    @Unique private long ga2$hi;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$init(NoiseChunk noiseChunk, ChunkPos chunkPos, NoiseRouter router, PositionalRandomFactory random, int minY, int height,
                          Aquifer.FluidPicker fluidPicker, CallbackInfo ci) {
        int mode = AquiferFastPath.modeFor(this, random);
        int cells = this.aquiferLocationCache.length;
        int layer = this.gridSizeX * this.gridSizeZ;
        if (mode == AquiferFastPath.OFF || layer <= 0 || cells % layer != 0) {
            this.ga2$mode = AquiferFastPath.OFF;
            return;
        }
        this.ga2$gridSizeY = cells / layer;
        if (random.getClass() == XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class) {
            XoroshiroFactoryAccessor seeds = (XoroshiroFactoryAccessor) random;
            this.ga2$seedLo = seeds.ga2$seedLo();
            this.ga2$seedHi = seeds.ga2$seedHi();
            this.ga2$xoroshiro = true;
        }
        int[] offsets = new int[12];
        for (int o = 0; o < 12; o++) {
            // o = i3 * 6 + (j3 + 1) * 2 + k3, the order of vanilla's three nested loops
            offsets[o] = o / 6 + ((o >> 1) % 3) * layer + (o & 1) * this.gridSizeX;
        }
        this.ga2$offsets = offsets;
        this.ga2$centres = new int[cells * 3];
        this.ga2$located = new boolean[cells];
        this.ga2$kind = new byte[cells];
        this.ga2$uniform = new Aquifer.FluidStatus[cells];
        this.ga2$airFrom = new int[cells];
        // The floodedness reads a cache_2d over y (AquiferFastPath.cellCachesFor). Statuses computed ahead of vanilla keep
        // vanilla's values only when no two cells of this aquifer share a block column where such a function differs.
        DensityFunction[] cellCaches = mode != AquiferFastPath.SEARCH ? AquiferFastPath.cellCachesFor(random) : null;
        if (cellCaches != null) {
            AquiferFastPath.CELLS_CHECKED.increment();
            if (!this.ga2$cellsAgree(cellCaches)) {
                AquiferFastPath.CELLS_CONFLICT.increment();
                mode = AquiferFastPath.SEARCH;
            }
        }
        if (mode == AquiferFastPath.COLUMNS) {
            if ((Object) this.noiseChunk instanceof NoiseChunkAccessor chunk) {
                this.ga2$levels = chunk.ga2$surfaceLevelMemo();
                this.ga2$waitingFor = new long[cells];
            } else {
                mode = AquiferFastPath.SEARCH;
            }
        }
        this.ga2$mode = mode;
    }

    /**
     * Looks up every cell's Frosted Caves answer before the aquifer's first status (FrostedCaves). The aquifer is made with its
     * NoiseChunk at the biome step, and the mod notes the biome registry on that NoiseChunk only at the step's end, so this waits for the
     * fill. When an answer depends on the order of the lookups, the aquifer searches in vanilla's order instead.
     */
    @Unique
    private void ga2$frostedDecide() {
        this.ga2$frostedDecided = true;
        if (!FrostedCaves.active() || this.ga2$mode == AquiferFastPath.OFF || this.ga2$mode == AquiferFastPath.SEARCH) {
            return;
        }
        long[] at = new long[this.aquiferLocationCache.length];
        byte[] bits = FrostedCaves.cells(this.noiseChunk, this.positionalRandomFactory, this.minGridX, this.minGridY, this.minGridZ, this.gridSizeX,
            this.ga2$gridSizeY, this.gridSizeZ, at);
        if (bits == null) {
            this.ga2$mode = AquiferFastPath.SEARCH;
        } else if (bits != FrostedCaves.NO_STEP) {
            this.ga2$frosted = bits;
            this.ga2$frostedAt = at;
        }
    }

    /** YUNG's Cave Biomes' Frosted Caves water, where GA2 took its handler over: from the answer looked up ahead, or live. */
    @Inject(method = "computeFluid", at = @At("RETURN"), cancellable = true)
    private void ga2$frostedCaves(int x, int y, int z, CallbackInfoReturnable<Aquifer.FluidStatus> cir) {
        if (!FrostedCaves.active()) {
            return;
        }
        int known = -1;
        byte[] bits = this.ga2$frosted;
        if (bits != null) {
            // vanilla's gridX, gridY, gridZ and getIndex
            int gx = Math.floorDiv(x, 16) - this.minGridX, gy = Math.floorDiv(y, 12) - this.minGridY, gz = Math.floorDiv(z, 16) - this.minGridZ;
            int i = (gy * this.gridSizeZ + gz) * this.gridSizeX + gx;
            if (gx >= 0 && gx < this.gridSizeX && gz >= 0 && gz < this.gridSizeZ && i >= 0 && i < bits.length && this.ga2$frostedAt[i] == BlockPos.asLong(x, y, z)) {
                known = bits[i];
            }
        }
        Aquifer.FluidStatus status = cir.getReturnValue();
        Aquifer.FluidStatus out = FrostedCaves.apply(this.noiseChunk, x, y, z, status, known);
        if (out != status) {
            cir.setReturnValue(out);
        }
    }

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void ga2$computeSubstance(DensityFunction.FunctionContext context, double density, CallbackInfoReturnable<BlockState> cir) {
        if (this.ga2$mode != AquiferFastPath.OFF) {
            int x = context.blockX(), y = context.blockY(), z = context.blockZ();
            Object result = this.ga2$compute(context, density, x, y, z);
            if (result != AquiferFastPath.FALLBACK) {
                // this return is GA2's own, so Better Caves' handler at the method's returns does not run for it
                cir.setReturnValue(LiquidRegions.active() ? ga2$liquids(x, y, z, (BlockState) result) : (BlockState) result);
            }
        }
    }

    @Inject(method = "shouldScheduleFluidUpdate", at = @At("HEAD"))
    private void ga2$resolveFlag(CallbackInfoReturnable<Boolean> cir) {
        if (this.ga2$pending) {
            this.ga2$resolvePending();
        }
    }

    @Override
    public BlockState ga2$substance(DensityFunction.FunctionContext context, double density) {
        if (this.ga2$mode != AquiferFastPath.OFF) {
            int x = context.blockX(), y = context.blockY(), z = context.blockZ();
            Object result = this.ga2$compute(context, density, x, y, z);
            if (result != AquiferFastPath.FALLBACK) {
                return LiquidRegions.active() ? ga2$liquids(x, y, z, (BlockState) result) : (BlockState) result;
            }
        }
        // Off, or outside the chunk's cells. The computeSubstance hook falls back the same way and lets vanilla's body run.
        return ((Aquifer) (Object) this).computeSubstance(context, density);
    }

    @Override
    public boolean ga2$fast() {
        return this.ga2$mode != AquiferFastPath.OFF;
    }

    @Override
    public boolean ga2$scheduleFlag() {
        if (this.ga2$mode == AquiferFastPath.OFF) {
            return ((Aquifer) (Object) this).shouldScheduleFluidUpdate();
        }
        if (this.ga2$pending) {
            this.ga2$resolvePending();
        }
        return this.shouldScheduleFluidUpdate;
    }

    @Override
    public BlockState ga2$substanceAt(int x, int y, int z, double density) {
        if (this.ga2$mode != AquiferFastPath.OFF) {
            Object result = this.ga2$compute(null, density, x, y, z);
            if (result != AquiferFastPath.FALLBACK) {
                return LiquidRegions.active() ? ga2$liquids(x, y, z, (BlockState) result) : (BlockState) result;
            }
        }
        return ((Aquifer) (Object) this).computeSubstance(new DensityFunction.SinglePointContext(x, y, z), density);
    }

    /** The fast path at {@code x, y, z}. {@code context} is that position's, or null if the caller has none (made if needed). */
    @Unique
    private Object ga2$compute(DensityFunction.FunctionContext context, double density, int x, int y, int z) {
        if (!this.ga2$frostedDecided) {
            this.ga2$frostedDecide();
        }
        if (density > 0.0) {
            if (AquiferFastPath.STATS) {
                AquiferFastPath.SOLID.increment();
            }
            this.ga2$pending = false;
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        if (this.globalFluidPicker.computeFluid(x, y, z).at(y).is(Blocks.LAVA)) {
            if (AquiferFastPath.STATS) {
                AquiferFastPath.LAVA.increment();
            }
            this.ga2$pending = false;
            this.shouldScheduleFluidUpdate = false;
            return Blocks.LAVA.defaultBlockState();
        }
        // Same cell coordinates as vanilla: floorDiv(x - 5, 16), floorDiv(y + 1, 12), floorDiv(z - 5, 16).
        int gx = (x - 5) >> 4;
        int gy = Math.floorDiv(y + 1, 12);
        int gz = (z - 5) >> 4;
        int ix = gx - this.minGridX;
        int iy = gy - 1 - this.minGridY;
        int iz = gz - this.minGridZ;
        if (ix < 0 || ix + 1 >= this.gridSizeX || iz < 0 || iz + 1 >= this.gridSizeZ || iy < 0 || iy + 2 >= this.ga2$gridSizeY) {
            // Outside this chunk's cell grid. Leave the block to the original method, whatever it does there.
            if (AquiferFastPath.STATS) {
                AquiferFastPath.FALLBACKS.increment();
            }
            if (this.ga2$pending) {
                this.ga2$resolvePending();
            }
            return AquiferFastPath.FALLBACK;
        }
        int key = (iy * this.gridSizeZ + iz) * this.gridSizeX + ix;
        int kind = this.ga2$kind[key];
        if (kind == GA2_NEW) {
            kind = this.ga2$prepare(key, gx, gy, gz);
        } else if (kind == GA2_LOCATED && this.ga2$mode == AquiferFastPath.COLUMNS) {
            kind = this.ga2$retryAhead(key);
        }
        if (kind == GA2_UNIFORM) {
            if (AquiferFastPath.STATS) {
                AquiferFastPath.UNIFORM.increment();
            }
            this.ga2$defer(x, y, z, key);
            return this.ga2$uniform[key].at(y);
        }
        if (kind == GA2_MIXED && y >= this.ga2$airFrom[key]) {
            if (AquiferFastPath.STATS) {
                AquiferFastPath.ABOVE_FLUIDS.increment();
            }
            this.ga2$defer(x, y, z, key);
            return Blocks.AIR.defaultBlockState();
        }
        if (kind == GA2_LOCATED && AquiferFastPath.AIR_BOXES) {
            int box = this.ga2$box(key, gx, gy, gz, x, y, z);
            if (box != GA2_BOX_SEARCH) {
                if (AquiferFastPath.STATS) {
                    AquiferFastPath.BOX_BLOCKS.increment();
                }
                this.ga2$defer(x, y, z, key);
                return box == GA2_BOX_UNIFORM ? this.ga2$boxCachedStatus.at(y) : Blocks.AIR.defaultBlockState();
            }
        }
        if (AquiferFastPath.STATS) {
            AquiferFastPath.SEARCHED.increment();
        }
        this.ga2$pending = false;
        BlockState state = this.ga2$search(context, density, x, y, z, key);
        if (kind == GA2_LOCATED) {
            this.ga2$classifyIfComplete(key);
        }
        return state;
    }

    /**
     * Search mode. Once the searches have computed all 12 statuses of a neighbourhood (vanilla's order, vanilla's code),
     * classifying the neighbourhood reads only cached statuses. So do the searches of the blocks that the classification
     * then spares.
     */
    @Unique
    private void ga2$classifyIfComplete(int key) {
        int[] offsets = this.ga2$offsets;
        Aquifer.FluidStatus[] cache = this.aquiferCache;
        for (int o = 0; o < 12; o++) {
            if (cache[key + offsets[o]] == null) {
                return;
            }
        }
        if (AquiferFastPath.STATS) {
            AquiferFastPath.LATE_CLASSIFIED.increment();
        }
        this.ga2$kind[key] = this.ga2$classify(key);
    }

    @Override
    public Aquifer.FluidPicker ga2$skyPicker() {
        // cellAir asks the picker before anything else. Below a liquid altitude it says no without asking it
        return this.ga2$mode != AquiferFastPath.OFF && !LiquidRegions.active() && CellAir.vanillaPicker(this.globalFluidPicker) ? this.globalFluidPicker : null;
    }

    @Override
    public boolean ga2$cellAir(int x0, int y0, int z0, int w, int h) {
        if (!this.ga2$frostedDecided) {
            this.ga2$frostedDecide();
        }
        if (this.ga2$mode == AquiferFastPath.OFF || !CellAir.noLava(this.globalFluidPicker, x0, y0, z0, w, h)) {
            return false;
        }
        if (LiquidRegions.active()) {
            // below the liquid altitude the mod turns air into the column's liquid or into stone. Those cells go block by block
            LiquidRegions.Table table = ga2$lqTable(x0 >> 4, z0 >> 4);
            if (table != null && y0 <= table.altitude()) {
                return false;
            }
        }
        int gx0 = (x0 - 5) >> 4;
        int gx1 = (x0 + w - 6) >> 4;
        int gz0 = (z0 - 5) >> 4;
        int gz1 = (z0 + w - 6) >> 4;
        int gy0 = Math.floorDiv(y0 + 1, 12);
        int gy1 = Math.floorDiv(y0 + h, 12);
        for (int gy = gy0; gy <= gy1; gy++) {
            int iy = gy - 1 - this.minGridY;
            if (iy < 0 || iy + 2 >= this.ga2$gridSizeY) {
                return false;
            }
            for (int gx = gx0; gx <= gx1; gx++) {
                int ix = gx - this.minGridX;
                if (ix < 0 || ix + 1 >= this.gridSizeX) {
                    return false;
                }
                for (int gz = gz0; gz <= gz1; gz++) {
                    int iz = gz - this.minGridZ;
                    if (iz < 0 || iz + 1 >= this.gridSizeZ) {
                        return false;
                    }
                    int key = (iy * this.gridSizeZ + iz) * this.gridSizeX + ix;
                    int kind = this.ga2$kind[key];
                    if (kind == GA2_NEW) {
                        // The cell's blocks are not solid, so vanilla asks this neighbourhood too. Classifying the
                        // neighbourhood up front is allowed in CLASSIFY mode. In COLUMNS mode it is allowed only when the
                        // statuses it lacks read memoised surface levels. In SEARCH mode this only locates the
                        // neighbourhood's cells, which vanilla's first search would do anyway.
                        kind = this.ga2$prepare(key, gx, gy, gz);
                    } else if (kind == GA2_LOCATED && this.ga2$mode == AquiferFastPath.COLUMNS) {
                        kind = this.ga2$retryAhead(key);
                    }
                    // the part of the cell in this neighbourhood: gx = (x - 5) >> 4, gy = floorDiv(y + 1, 12), gz = (z - 5) >> 4
                    int sx0 = Math.max(x0, gx * 16 + 5);
                    int sx1 = Math.min(x0 + w - 1, gx * 16 + 20);
                    int sy0 = Math.max(y0, gy * 12 - 1);
                    int sy1 = Math.min(y0 + h - 1, gy * 12 + 10);
                    int sz0 = Math.max(z0, gz * 16 + 5);
                    int sz1 = Math.min(z0 + w - 1, gz * 16 + 20);
                    if (kind == GA2_UNIFORM) {
                        if (((FluidStatusAccessor) (Object) this.ga2$uniform[key]).ga2$fluidLevel() > sy0) {
                            return false;
                        }
                    } else if (kind == GA2_MIXED) {
                        if (this.ga2$airFrom[key] > sy0) {
                            return false;
                        }
                    } else {
                        int box = AquiferFastPath.AIR_BOXES ? this.ga2$classifyBox(key, sx0, sx1, sy0, sy1, sz0, sz1) : GA2_BOX_SEARCH;
                        if (box == GA2_BOX_SEARCH || box == GA2_BOX_UNIFORM && ((FluidStatusAccessor) (Object) this.ga2$boxStatus).ga2$fluidLevel() > sy0) {
                            return false;
                        }
                        if (AquiferFastPath.STATS) {
                            AquiferFastPath.BOX_AIR.increment();
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Decides a box of a neighbourhood that is not classified (search mode). The decision uses the statuses that vanilla's
     * searches have computed so far, in vanilla's order. The candidates are the neighbourhood's cells that can be among the
     * three nearest to some block of the box. Returns {@link #GA2_BOX_UNIFORM} when every candidate has its status and those
     * statuses are equal ({@link #ga2$boxStatus}). Every block of the box is then {@code status.at(y)} and every pressure term
     * is 0, as for a uniform neighbourhood. Returns {@link #GA2_BOX_AIR} when all candidates have fluid levels at least 5
     * below the box. Every block of the box is then air, and no pressure term samples the barrier noise (see the class
     * comment). In both cases vanilla's searches in the box would only read those statuses and compute nothing. A cell is
     * never among the three nearest if three other cells are strictly nearer at every block of the box, that is, if their
     * farthest distance is below the cell's nearest distance. Among equal distances vanilla ranks by loop order, so only
     * strictly nearer cells exclude a cell. Returns {@link #GA2_BOX_SEARCH} when a candidate's status is missing (vanilla's
     * search could compute it inside the box) or neither condition holds.
     */
    @Unique
    private int ga2$classifyBox(int key, int x0, int x1, int y0, int y1, int z0, int z1) {
        int[] offsets = this.ga2$offsets;
        int[] centres = this.ga2$centres;
        int[] nearest = this.ga2$boxNear;
        int[] farthest = this.ga2$boxFar;
        for (int o = 0; o < 12; o++) {
            int b = (key + offsets[o]) * 3;
            int cx = centres[b];
            int cy = centres[b + 1];
            int cz = centres[b + 2];
            int nx = cx < x0 ? x0 - cx : cx > x1 ? cx - x1 : 0;
            int ny = cy < y0 ? y0 - cy : cy > y1 ? cy - y1 : 0;
            int nz = cz < z0 ? z0 - cz : cz > z1 ? cz - z1 : 0;
            int fx = Math.max(Math.abs(cx - x0), Math.abs(cx - x1));
            int fy = Math.max(Math.abs(cy - y0), Math.abs(cy - y1));
            int fz = Math.max(Math.abs(cz - z0), Math.abs(cz - z1));
            nearest[o] = nx * nx + ny * ny + nz * nz;
            farthest[o] = fx * fx + fy * fy + fz * fz;
        }
        Aquifer.FluidStatus[] cache = this.aquiferCache;
        boolean below = true;
        boolean uniform = true;
        int firstLevel = 0;
        BlockState firstType = null;
        for (int o = 0; o < 12; o++) {
            int closer = 0;
            for (int a = 0; a < 12; a++) {
                if (a != o && farthest[a] < nearest[o]) {
                    closer++;
                }
            }
            if (closer >= 3) {
                continue;
            }
            Aquifer.FluidStatus status = cache[key + offsets[o]];
            if (status == null) {
                return GA2_BOX_SEARCH;
            }
            FluidStatusAccessor acc = (FluidStatusAccessor) (Object) status;
            int level = acc.ga2$fluidLevel();
            BlockState type = acc.ga2$fluidType();
            if (level > y0 - GA2_AIR_MARGIN || level < -GA2_LEVEL_LIMIT) {
                below = false;
            }
            if (firstType == null) {
                firstLevel = level;
                firstType = type;
                this.ga2$boxStatus = status;
            } else if (level != firstLevel || type != firstType) {
                uniform = false;
            }
            if (!below && !uniform) {
                return GA2_BOX_SEARCH;
            }
        }
        return uniform ? GA2_BOX_UNIFORM : GA2_BOX_AIR;
    }

    /** {@link #ga2$classifyBox} for the 4x4x4 block box around a block, remembered until a search computes another status. */
    @Unique
    private int ga2$box(int key, int gx, int gy, int gz, int x, int y, int z) {
        int bx = x >> 2;
        int by = y >> 2;
        int bz = z >> 2;
        if (key == this.ga2$boxKey && bx == this.ga2$boxX && by == this.ga2$boxY && bz == this.ga2$boxZ
            && (this.ga2$boxResult != GA2_BOX_SEARCH || this.ga2$boxEpoch == this.ga2$epoch)) {
            return this.ga2$boxResult;
        }
        // the box, clipped to the neighbourhood: gx = (x - 5) >> 4, gy = floorDiv(y + 1, 12), gz = (z - 5) >> 4
        int result = this.ga2$classifyBox(key, Math.max(bx << 2, gx * 16 + 5), Math.min((bx << 2) + 3, gx * 16 + 20),
            Math.max(by << 2, gy * 12 - 1), Math.min((by << 2) + 3, gy * 12 + 10), Math.max(bz << 2, gz * 16 + 5), Math.min((bz << 2) + 3, gz * 16 + 20));
        this.ga2$boxKey = key;
        this.ga2$boxX = bx;
        this.ga2$boxY = by;
        this.ga2$boxZ = bz;
        this.ga2$boxEpoch = this.ga2$epoch;
        this.ga2$boxResult = result;
        // Keep a separate copy. A whole-cell check classifies other boxes into ga2$boxStatus in between.
        this.ga2$boxCachedStatus = this.ga2$boxStatus;
        return result;
    }

    /** {@code getAquiferStatus}, counting the statuses it computes. A box answered "search" is decided again after each new status. */
    @Unique
    private Aquifer.FluidStatus ga2$status(int index, long location) {
        if (this.aquiferCache[index] == null) {
            this.ga2$epoch++;
        }
        return this.getAquiferStatus(location);
    }

    @Unique
    private void ga2$defer(int x, int y, int z, int key) {
        this.ga2$pending = true;
        this.ga2$pendingX = x;
        this.ga2$pendingY = y;
        this.ga2$pendingZ = z;
        this.ga2$pendingKey = key;
    }

    /** Makes the 12 cell centres of a neighbourhood available and, when allowed, classifies it. */
    @Unique
    private int ga2$prepare(int key, int gx, int gy, int gz) {
        int[] offsets = this.ga2$offsets;
        for (int o = 0; o < 12; o++) {
            int index = key + offsets[o];
            if (!this.ga2$located[index]) {
                this.ga2$locate(index, gx + o / 6, gy - 1 + ((o >> 1) % 3), gz + (o & 1));
            }
        }
        byte kind = this.ga2$mode == AquiferFastPath.CLASSIFY ? this.ga2$classify(key)
            : this.ga2$mode == AquiferFastPath.COLUMNS ? this.ga2$classifyAhead(key) : GA2_LOCATED;
        this.ga2$kind[key] = kind;
        return kind;
    }

    /**
     * {@link AquiferFastPath#COLUMNS} mode. Classifies a located neighbourhood when every status it lacks would read only
     * surface levels already in the chunk's memo. All 13 columns of vanilla's {@code computeFluid} must be memoised, whichever
     * column {@code computeFluid} would stop at. Otherwise remembers the first missing column and leaves the neighbourhood
     * located.
     */
    @Unique
    private byte ga2$classifyAhead(int key) {
        int[] offsets = this.ga2$offsets;
        Aquifer.FluidStatus[] cache = this.aquiferCache;
        int[] centres = this.ga2$centres;
        Long2IntMap levels = this.ga2$levels;
        for (int o = 0; o < 12; o++) {
            int index = key + offsets[o];
            if (cache[index] != null) {
                continue;
            }
            int x = centres[index * 3];
            int z = centres[index * 3 + 2];
            for (int[] offset : SURFACE_SAMPLING_OFFSETS_IN_CHUNKS) {
                // NoiseChunk.preliminarySurfaceLevel's key: the quart-aligned column of (x + 16 dx, z + 16 dz)
                long column = ColumnPos.asLong(
                    QuartPos.toBlock(QuartPos.fromBlock(x + SectionPos.sectionToBlockCoord(offset[0]))),
                    QuartPos.toBlock(QuartPos.fromBlock(z + SectionPos.sectionToBlockCoord(offset[1]))));
                if (!levels.containsKey(column)) {
                    this.ga2$waitingFor[key] = column;
                    return GA2_LOCATED;
                }
            }
        }
        AquiferFastPath.AHEAD_CLASSIFIED.increment();
        return this.ga2$classify(key);
    }

    /** Classifies ahead once the column the last attempt lacked is in the memo (one lookup otherwise). */
    @Unique
    private int ga2$retryAhead(int key) {
        if (!this.ga2$levels.containsKey(this.ga2$waitingFor[key])) {
            return GA2_LOCATED;
        }
        byte kind = this.ga2$classifyAhead(key);
        this.ga2$kind[key] = kind;
        return kind;
    }

    /** Classifies a neighbourhood whose 12 cell centres are located, computing (or reading) their statuses in vanilla's order. */
    @Unique
    private byte ga2$classify(int key) {
        int[] offsets = this.ga2$offsets;
        Aquifer.FluidStatus first = null;
        int firstLevel = 0;
        BlockState firstType = null;
        boolean uniform = true;
        boolean bounded = true;
        int maxLevel = Integer.MIN_VALUE;
        for (int o = 0; o < 12; o++) {
            Aquifer.FluidStatus status = this.getAquiferStatus(this.aquiferLocationCache[key + offsets[o]]);
            FluidStatusAccessor acc = (FluidStatusAccessor) (Object) status;
            int level = acc.ga2$fluidLevel();
            BlockState type = acc.ga2$fluidType();
            if (first == null) {
                first = status;
                firstLevel = level;
                firstType = type;
            } else if (level != firstLevel || type != firstType) {
                uniform = false;
            }
            maxLevel = Math.max(maxLevel, level);
            if (level < -GA2_LEVEL_LIMIT || level > GA2_LEVEL_LIMIT) {
                bounded = false;
            }
        }
        if (uniform) {
            this.ga2$uniform[key] = first;
            return GA2_UNIFORM;
        }
        this.ga2$airFrom[key] = bounded ? maxLevel + GA2_AIR_MARGIN : Integer.MAX_VALUE;
        return GA2_MIXED;
    }

    /**
     * Whether the chunk's single-column caches of {@code caches} give every status of this aquifer the value that a direct
     * evaluation gives, in any order of status computation. A status evaluates the floodedness once, at its cell's centre.
     * Such a cache answers a different height only when it was last asked in the same block column. Centres of different grid
     * columns never share a block column, because each centre lies in its own 16-block cell. So the check reduces to cells of
     * one grid column whose random offsets coincide. Each such cell is compared with the first cell of its block column, at
     * both heights. Locates every cell, as vanilla would when it first needs the cell. A centre is a function of the cell and
     * the seed only.
     */
    @Unique
    private boolean ga2$cellsAgree(DensityFunction[] caches) {
        int[] first = new int[100];
        int[] centres = this.ga2$centres;
        for (int iz = 0; iz < this.gridSizeZ; iz++) {
            int cellZ = this.minGridZ + iz;
            for (int ix = 0; ix < this.gridSizeX; ix++) {
                int cellX = this.minGridX + ix;
                Arrays.fill(first, -1);
                for (int iy = 0; iy < this.ga2$gridSizeY; iy++) {
                    int index = (iy * this.gridSizeZ + iz) * this.gridSizeX + ix;
                    if (!this.ga2$located[index]) {
                        this.ga2$locate(index, cellX, this.minGridY + iy, cellZ);
                    }
                    int b = index * 3;
                    // x and z offsets in the cell, each below 10
                    int column = (centres[b] - cellX * 16) * 10 + (centres[b + 2] - cellZ * 16);
                    int other = first[column];
                    if (other < 0) {
                        first[column] = index;
                        continue;
                    }
                    int x = centres[b];
                    int z = centres[b + 2];
                    int y0 = centres[other * 3 + 1];
                    int y1 = centres[b + 1];
                    for (DensityFunction f : caches) {
                        double v0 = f.compute(new DensityFunction.SinglePointContext(x, y0, z));
                        double v1 = f.compute(new DensityFunction.SinglePointContext(x, y1, z));
                        if (Double.doubleToRawLongBits(v0) != Double.doubleToRawLongBits(v1)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** Vanilla's lazy cell centre, written to vanilla's cache, plus an unpacked copy. */
    @Unique
    private void ga2$locate(int index, int cellX, int cellY, int cellZ) {
        long location = this.aquiferLocationCache[index];
        if (location == Long.MAX_VALUE) {
            if (this.ga2$xoroshiro) {
                // positionalRandomFactory.at(x, y, z): Xoroshiro128++ seeded getSeed(x, y, z) ^ seedLo, seedHi, without its objects
                long lo = Mth.getSeed(cellX, cellY, cellZ) ^ this.ga2$seedLo;
                long hi = this.ga2$seedHi;
                if ((lo | hi) == 0L) {
                    lo = -7046029254386353131L;
                    hi = 7640891576956012809L;
                }
                this.ga2$lo = lo;
                this.ga2$hi = hi;
                int bx = cellX * 16 + this.ga2$nextInt(10);
                int by = cellY * 12 + this.ga2$nextInt(9);
                location = BlockPos.asLong(bx, by, cellZ * 16 + this.ga2$nextInt(10));
            } else {
                RandomSource random = this.positionalRandomFactory.at(cellX, cellY, cellZ);
                location = BlockPos.asLong(cellX * 16 + random.nextInt(10), cellY * 12 + random.nextInt(9), cellZ * 16 + random.nextInt(10));
            }
            this.aquiferLocationCache[index] = location;
        }
        int b = index * 3;
        this.ga2$centres[b] = BlockPos.getX(location);
        this.ga2$centres[b + 1] = BlockPos.getY(location);
        this.ga2$centres[b + 2] = BlockPos.getZ(location);
        this.ga2$located[index] = true;
    }

    /** {@code XoroshiroRandomSource.nextInt(bound)} on the state in {@link #ga2$lo}, {@link #ga2$hi}. */
    @Unique
    private int ga2$nextInt(int bound) {
        long j = Integer.toUnsignedLong(this.ga2$nextInt()) * bound;
        long k = j & 0xFFFFFFFFL;
        if (k < bound) {
            for (int l = Integer.remainderUnsigned(-bound, bound); k < l; k = j & 0xFFFFFFFFL) {
                j = Integer.toUnsignedLong(this.ga2$nextInt()) * bound;
            }
        }
        return (int) (j >> 32);
    }

    /** {@code XoroshiroRandomSource.nextInt()}: the low half of Xoroshiro128++'s next long. */
    @Unique
    private int ga2$nextInt() {
        long lo = this.ga2$lo;
        long hi = this.ga2$hi;
        long r = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        this.ga2$lo = Long.rotateLeft(lo, 49) ^ hi ^ hi << 21;
        this.ga2$hi = Long.rotateLeft(hi, 28);
        return (int) r;
    }

    /**
     * Vanilla's algorithm. The three nearest cells are found with packed keys
     * {@code distance << 4 | (15 - order)}. Among equal distances, the later cell in vanilla's loop
     * order ranks nearer. Vanilla's {@code >=} insertion does the same.
     */
    @Unique
    private BlockState ga2$search(DensityFunction.FunctionContext context, double density, int x, int y, int z, int key) {
        int[] offsets = this.ga2$offsets;
        int[] nx = this.ga2$nx;
        int[] ny = this.ga2$ny;
        int[] nz = this.ga2$nz;
        if (key != this.ga2$nearKey) {
            // The neighbourhood changes every 12 to 16 blocks along an axis. Copy its centres side by side, in loop order.
            int[] centres = this.ga2$centres;
            for (int o = 0; o < 12; o++) {
                int b = (key + offsets[o]) * 3;
                nx[o] = centres[b];
                ny[o] = centres[b + 1];
                nz[o] = centres[b + 2];
            }
            this.ga2$nearKey = key;
        }
        int[] packed = this.ga2$packed;
        for (int o = 0; o < 12; o++) {
            int dx = nx[o] - x;
            int dy = ny[o] - y;
            int dz = nz[o] - z;
            packed[o] = (dx * dx + dy * dy + dz * dz) << 4 | (15 - o);
        }
        int m1 = Integer.MAX_VALUE;
        int m2 = Integer.MAX_VALUE;
        int m3 = Integer.MAX_VALUE;
        for (int o = 0; o < 12; o++) {
            int p = packed[o];
            int t = Math.max(m1, p);
            m1 = Math.min(m1, p);
            int u = Math.max(m2, t);
            m2 = Math.min(m2, t);
            m3 = Math.min(m3, u);
        }
        int k1 = m1 >> 4;
        int l1 = m2 >> 4;
        int i2 = m3 >> 4;
        int c1 = key + offsets[15 - (m1 & 15)];
        int c2 = key + offsets[15 - (m2 & 15)];
        int c3 = key + offsets[15 - (m3 & 15)];
        long j2 = this.aquiferLocationCache[c1];
        long k2 = this.aquiferLocationCache[c2];
        long l2 = this.aquiferLocationCache[c3];

        Aquifer.FluidStatus status1 = this.ga2$status(c1, j2);
        double d1 = similarity(k1, l1);
        BlockState state = status1.at(y);
        if (d1 <= 0.0) {
            this.shouldScheduleFluidUpdate = d1 >= FLOWING_UPDATE_SIMULARITY;
            return state;
        } else if (state.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(x, y - 1, z).at(y - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return state;
        }
        if (context == null) {
            // Called without a context (ga2$substanceAt). calculatePressure and the barrier noise read the block's context.
            context = new DensityFunction.SinglePointContext(x, y, z);
        }
        // Vanilla's per-block barrier value holder, reset. calculatePressure samples the barrier noise into it once.
        MutableDouble barrier = this.ga2$barrier;
        barrier.setValue(Double.NaN);
        Aquifer.FluidStatus status2 = this.ga2$status(c2, k2);
        double d2 = d1 * this.calculatePressure(context, barrier, status1, status2);
        if (density + d2 > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        Aquifer.FluidStatus status3 = this.ga2$status(c3, l2);
        double d0 = similarity(k1, i2);
        if (d0 > 0.0) {
            double d3 = d1 * d0 * this.calculatePressure(context, barrier, status1, status3);
            if (density + d3 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        double d4 = similarity(l1, i2);
        if (d4 > 0.0) {
            double d5 = d1 * d4 * this.calculatePressure(context, barrier, status2, status3);
            if (density + d5 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        this.shouldScheduleFluidUpdate = true;
        return state;
    }

    /**
     * Resolves the flag of a classified block. Vanilla sets {@code d1 >= FLOWING_UPDATE_SIMULARITY} when
     * the two nearest distances give {@code d1 <= 0}. On every other path such a block can take, vanilla
     * sets true.
     */
    @Unique
    private void ga2$resolvePending() {
        if (AquiferFastPath.STATS) {
            AquiferFastPath.LAZY_FLAGS.increment();
        }
        int[] offsets = this.ga2$offsets;
        int[] centres = this.ga2$centres;
        int x = this.ga2$pendingX;
        int y = this.ga2$pendingY;
        int z = this.ga2$pendingZ;
        int key = this.ga2$pendingKey;
        int m1 = Integer.MAX_VALUE;
        int m2 = Integer.MAX_VALUE;
        for (int o = 0; o < 12; o++) {
            int b = (key + offsets[o]) * 3;
            int dx = centres[b] - x;
            int dy = centres[b + 1] - y;
            int dz = centres[b + 2] - z;
            int d = dx * dx + dy * dy + dz * dz;
            m2 = Math.min(m2, Math.max(m1, d));
            m1 = Math.min(m1, d);
        }
        double d1 = similarity(m1, m2);
        this.shouldScheduleFluidUpdate = d1 <= 0.0 ? d1 >= FLOWING_UPDATE_SIMULARITY : true;
        this.ga2$pending = false;
    }
}
