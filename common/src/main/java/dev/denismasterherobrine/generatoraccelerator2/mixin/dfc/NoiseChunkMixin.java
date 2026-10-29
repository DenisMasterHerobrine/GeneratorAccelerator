package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.google.common.collect.ImmutableList;
import dev.denismasterherobrine.generatoraccelerator2.compat.gtceu.GtceuVeins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.Compiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.LazyInterpolation;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MapAllMemo;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.DfcHooks;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.MarkerPairing;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SlicePool;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.VeinRule;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Binds the compiled programs to each NoiseChunk and routes {@code fillSlice} and {@code selectCellYZ} through them. */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements Ga2NoiseChunk, FastFill.Rules {
    @Shadow int cellStartBlockY;
    @Shadow int inCellX;
    @Shadow int inCellY;
    @Shadow int inCellZ;
    @Shadow int arrayIndex;
    @Shadow long interpolationCounter;
    @Shadow int cellStartBlockZ;
    @Shadow @Final private NoiseChunk.BlockStateFiller blockStateRule;

    /** {@link FastFill}: the material rules the constructor itself added, in order. {@code null} if the constructor added others too. */
    @Unique
    private Object[] ga2$materialRules;
    @Unique
    private int ga2$ruleCount;
    /** {@link FastFill}: the final density cell cache the aquifer rule reads. */
    @Unique
    private DensityFunction ga2$finalDensity;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/google/common/collect/ImmutableList$Builder;add(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;"))
    private ImmutableList.Builder<Object> ga2$recordRule(ImmutableList.Builder<Object> builder, Object rule,
                                                                                 Operation<ImmutableList.Builder<Object>> original) {
        if (this.ga2$materialRules == null) {
            this.ga2$materialRules = new Object[2];
        }
        if (this.ga2$ruleCount < 2) {
            this.ga2$materialRules[this.ga2$ruleCount] = rule;
        }
        this.ga2$ruleCount++;
        return original.call(builder, rule);
    }

    @Override
    public Object[] ga2$materialRules() {
        return this.ga2$ruleCount <= 2 ? this.ga2$materialRules : null;
    }

    /** {@link FastFill}: the vein rule the constructor built and its transcription over the same arguments ({@link VeinRule}), or null. */
    @Unique
    private NoiseChunk.BlockStateFiller ga2$veinFiller;
    @Unique
    private VeinRule ga2$veinTranscription;

    @WrapOperation(method = "<init>", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;"
            + "Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"))
    private NoiseChunk.BlockStateFiller ga2$recordVeins(DensityFunction toggle, DensityFunction ridged, DensityFunction gap, PositionalRandomFactory random,
                                                         Operation<NoiseChunk.BlockStateFiller> original) {
        NoiseChunk.BlockStateFiller filler = original.call(toggle, ridged, gap, random);
        this.ga2$veinFiller = filler;
        // GregTech's handler reads its setting in the same call. Read right after, the answer matches the rule it returned
        this.ga2$noVeins = GtceuVeins.removesVeins();
        this.ga2$veinTranscription = this.ga2$noVeins ? null : VeinRule.of(toggle, ridged, gap, random);
        return filler;
    }

    /** {@link FastFill}: the vein rule the constructor built never places a vein ({@link GtceuVeins}). */
    @Unique
    private boolean ga2$noVeins;

    @Override
    public NoiseChunk.BlockStateFiller ga2$fastVeinRule(NoiseChunk.BlockStateFiller rule) {
        VeinRule fast = this.ga2$veinTranscription;
        return fast != null && rule == this.ga2$veinFiller ? fast : rule;
    }

    @Override
    public boolean ga2$placesNoVein(Object rule) {
        return this.ga2$noVeins && rule == this.ga2$veinFiller;
    }

    @Override
    public DensityFunction ga2$finalDensityCache() {
        return this.ga2$finalDensity;
    }

    @Override
    public NoiseChunk.BlockStateFiller ga2$blockStateRule() {
        return this.blockStateRule;
    }

    @Override
    public void ga2$stepZ(int blockZ, double dz) {
        this.inCellZ = blockZ - this.cellStartBlockZ;
        this.interpolationCounter++;
        this.ga2$dz = dz;
    }
    @Shadow int cellStartBlockX;
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;

    /** The interpolators as an array, for the per-row and per-cell steps (vanilla walks the list with a capturing lambda). */
    @Unique
    private NoiseChunk.NoiseInterpolator[] ga2$interpolatorArray = new NoiseChunk.NoiseInterpolator[0];

    @Unique
    private NoiseChunk.NoiseInterpolator[] ga2$interpolators() {
        NoiseChunk.NoiseInterpolator[] array = this.ga2$interpolatorArray;
        if (array.length != this.interpolators.size()) {
            // interpolators are only ever added (each one registers itself when created)
            array = this.interpolators.toArray(new NoiseChunk.NoiseInterpolator[0]);
            this.ga2$interpolatorArray = array;
        }
        return array;
    }

    @Inject(method = "updateForY", at = @At("HEAD"), cancellable = true)
    private void ga2$updateForY(int blockY, double dy, CallbackInfo ci) {
        this.ga2$stepY(blockY, dy);
        ci.cancel();
    }

    @Inject(method = "updateForX", at = @At("HEAD"), cancellable = true)
    private void ga2$updateForX(int blockX, double dx, CallbackInfo ci) {
        this.ga2$stepX(blockX, dx);
        ci.cancel();
    }

    @Override
    public void ga2$stepY(int blockY, double dy) {
        this.inCellY = blockY - this.cellStartBlockY;
        for (NoiseChunk.NoiseInterpolator interpolator : this.ga2$interpolators()) {
            interpolator.updateForY(dy);
        }
    }

    @Override
    public void ga2$stepX(int blockX, double dx) {
        this.inCellX = blockX - this.cellStartBlockX;
        for (NoiseChunk.NoiseInterpolator interpolator : this.ga2$interpolators()) {
            interpolator.updateForX(dx);
        }
    }

    @Redirect(method = "selectCellYZ", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void ga2$selectCellYZ(List<NoiseChunk.NoiseInterpolator> list, Consumer<NoiseChunk.NoiseInterpolator> action,
                                  @Local(argsOnly = true, ordinal = 0) int cellY, @Local(argsOnly = true, ordinal = 1) int cellZ) {
        for (NoiseChunk.NoiseInterpolator interpolator : this.ga2$interpolators()) {
            interpolator.selectCellYZ(cellY, cellZ);
        }
    }

    /** {@link LazyInterpolation}: the z fraction of the last {@code updateForZ}. */
    @Unique
    private double ga2$dz;

    @Inject(method = "updateForZ", at = @At("HEAD"), cancellable = true)
    private void ga2$lazyUpdateForZ(int blockZ, double dz, CallbackInfo ci) {
        if (LazyInterpolation.ENABLED) {
            this.inCellZ = blockZ - this.cellStartBlockZ;
            this.interpolationCounter++;
            this.ga2$dz = dz;
            ci.cancel();
        }
    }

    @Unique
    private ChunkBinding ga2$binding;
    /** The compiled surface level walk for this chunk. Kept after the fill, because the surface step and carvers ask levels too. */
    @Unique
    private SurfaceLevels ga2$levelProgram;
    @Unique
    private RandomState ga2$randomState;
    /** The router the constructor wrapped, kept until {@link #ga2$bind} has bound the programs to it. */
    @Unique
    private NoiseRouter ga2$wrappedRouter;

    /** {@link MapAllMemo} of this chunk's {@code wrap}, kept until the climate sampler is mapped. */
    @Unique
    private IdentityHashMap<Object, DensityFunction> ga2$mapMemo;

    /**
     * The bindings of the climate samplers this chunk made, one per {@code cachedClimateSampler} call. The binding map holds them
     * weakly, so they live until the fill is released ({@link #ga2$releaseFill}) or the chunk goes.
     */
    @Unique
    private BoundSampler[] ga2$samplers;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter ga2$mapRouter(NoiseRouter router, DensityFunction.Visitor visitor, Operation<NoiseRouter> original) {
        IdentityHashMap<Object, DensityFunction> memo = new IdentityHashMap<>();
        this.ga2$mapMemo = memo;
        NoiseRouter wrapped = MapAllMemo.with(memo, () -> original.call(router, visitor));
        this.ga2$wrappedRouter = wrapped;
        return wrapped;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction ga2$mapCellCache(DensityFunction function, DensityFunction.Visitor visitor, Operation<DensityFunction> original) {
        IdentityHashMap<Object, DensityFunction> memo = this.ga2$mapMemo;
        DensityFunction mapped = memo == null ? original.call(function, visitor) : MapAllMemo.with(memo, () -> original.call(function, visitor));
        this.ga2$finalDensity = mapped;
        return mapped;
    }

    @WrapOperation(method = "cachedClimateSampler", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction ga2$mapClimate(DensityFunction function, DensityFunction.Visitor visitor, Operation<DensityFunction> original) {
        IdentityHashMap<Object, DensityFunction> memo = this.ga2$mapMemo;
        return memo == null ? original.call(function, visitor) : MapAllMemo.with(memo, () -> original.call(function, visitor));
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$bind(int cellCountXZ, RandomState randomState, int firstBlockX, int firstBlockZ, NoiseSettings noiseSettings,
                          DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker,
                          Blender blender, CallbackInfo ci) {
        this.ga2$randomState = randomState;
        NoiseRouter constructed = this.ga2$wrappedRouter;
        this.ga2$wrappedRouter = null;
        this.ga2$veinToggle = constructed == null ? null : constructed.veinToggle();
        if (!DfcHooks.enabled()) {
            return;
        }
        ProgramSet set = ((Ga2RandomState) (Object) randomState).ga2$programs();
        if (set == null) {
            return;
        }
        boolean levels = set.surfaceLevels() != null && blender == Blender.empty();
        boolean programs = set.column() != null || !set.cells().isEmpty();
        if (!levels && !programs) {
            return;
        }
        // which chunk object replaced which router object. One walk of the router serves both bindings below.
        MarkerPairing pairing;
        try {
            NoiseRouter base = randomState.router();
            // the constructor's own wrapped router. Wrapping again would return the same instances (wrap() is
            // memoised), at the cost of a second walk and hash of the whole router.
            NoiseRouter wrapped = constructed != null ? constructed : base.mapAll(((NoiseChunkAccessor) (Object) this)::ga2$wrap);
            pairing = MarkerPairing.of(base, wrapped);
        } catch (RuntimeException e) {
            if (DfcConfig.failOnCodegenError()) {
                throw e;
            }
            if (BIND_FAILURES.getAndIncrement() < 3) {
                Compiler.LOGGER.error("chunk density functions unmatched to compiled programs -> vanilla path for chunk", e);
            }
            return;
        }
        if (levels) {
            // before the chunk binding, which adds the chunk's beardifier to the pairing
            try {
                this.ga2$levelProgram = SurfaceLevels.bind(set.surfaceLevels(), pairing);
            } catch (RuntimeException e) {
                if (DfcConfig.failOnCodegenError()) {
                    throw e;
                }
                if (BIND_FAILURES.getAndIncrement() < 3) {
                    Compiler.LOGGER.error("surface level program bind failed -> vanilla surface levels for chunk", e);
                }
            }
        }
        if (!programs) {
            return;
        }
        try {
            this.ga2$binding = ChunkBinding.bind((NoiseChunk) (Object) this, set, pairing);
        } catch (RuntimeException e) {
            if (DfcConfig.failOnCodegenError()) {
                throw e;
            }
            if (BIND_FAILURES.getAndIncrement() < 3) {
                Compiler.LOGGER.error("program bind failed -> vanilla path for chunk", e);
            } else if (BIND_FAILURES.get() % 1000 == 0) {
                Compiler.LOGGER.error("program bind failed for {} chunks so far (vanilla path): {}", BIND_FAILURES.get(), e.toString());
            }
            this.ga2$binding = null;
        }
    }

    /** Errors are logged a few times, then counted. Otherwise the same failure would log a stack trace for every chunk. */
    @Unique
    private static final AtomicLong BIND_FAILURES = new AtomicLong();

    /**
     * The programs and their buffers are only used while the chunk's noise is filled. Afterwards they just take memory. The
     * same holds for the two slices of each interpolator. The slices hold every corner of a cell column, about 33 KB a chunk
     * in the overworld, and are otherwise kept until the chunk is complete. Only the fill reads them, so they are dropped
     * here. {@link #ga2$restoreSlices} makes them again if a fill ever starts again. The memo of the router mapping is
     * dropped too. The memo only makes a climate sampler mapped later share subtrees with the router. A mapping without
     * the memo still computes the same values.
     */
    @Inject(method = "stopInterpolation", at = @At("RETURN"))
    private void ga2$releaseBinding(CallbackInfo ci) {
        this.ga2$releaseFill();
    }

    @Override
    public void ga2$releaseFill() {
        this.ga2$binding = null;
        this.ga2$mapMemo = null;
        // The chunk's climate samplers serve only the biome step, which comes before the noise fill. Without its binding a
        // sampler would sample through vanilla's functions, with the same values.
        this.ga2$samplers = null;
        for (NoiseChunk.NoiseInterpolator interpolator : this.ga2$interpolators()) {
            NoiseInterpolatorAccessor slices = (NoiseInterpolatorAccessor) interpolator;
            double[][] slice0 = slices.ga2$slice0();
            double[][] slice1 = slices.ga2$slice1();
            slices.ga2$setSlice0(RELEASED_SLICE);
            slices.ga2$setSlice1(RELEASED_SLICE);
            // for the interpolators of the next NoiseChunks (SlicePool, see also mixin.dfc.NoiseInterpolatorPoolMixin)
            if (slice0 != RELEASED_SLICE) {
                SlicePool.give(slice0);
            }
            if (slice1 != RELEASED_SLICE && slice1 != slice0) {
                SlicePool.give(slice1);
            }
        }
        this.ga2$slicesReleased = true;
    }

    /** Marks a released slice. Nothing reads a slice outside the fill, and the fill makes its slices again first. */
    @Unique
    private static final double[][] RELEASED_SLICE = new double[0][];
    @Unique
    private boolean ga2$slicesReleased;

    /** A fill after {@link #ga2$releaseBinding}: the interpolators get slices again, as their constructor makes them. */
    @Inject(method = "fillSlice", at = @At("HEAD"))
    private void ga2$restoreSlices(boolean first, int cellX, CallbackInfo ci) {
        if (this.ga2$slicesReleased) {
            this.ga2$slicesReleased = false;
            NoiseChunkAccessor acc = (NoiseChunkAccessor) (Object) this;
            int rows = acc.ga2$cellCountXZ() + 1;
            int height = acc.ga2$cellCountY() + 1;
            for (NoiseChunk.NoiseInterpolator interpolator : this.ga2$interpolators()) {
                NoiseInterpolatorAccessor slices = (NoiseInterpolatorAccessor) interpolator;
                if (slices.ga2$slice0() == RELEASED_SLICE) {
                    double[][] slice = SlicePool.take(rows, height);
                    slices.ga2$setSlice0(slice != null ? slice : new double[rows][height]);
                }
                if (slices.ga2$slice1() == RELEASED_SLICE) {
                    double[][] slice = SlicePool.take(rows, height);
                    slices.ga2$setSlice1(slice != null ? slice : new double[rows][height]);
                }
            }
        }
    }

    @Redirect(
        method = "fillSlice",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V")
    )
    private void ga2$fillSliceColumn(NoiseChunk.NoiseInterpolator interpolator, double[] slice, DensityFunction.ContextProvider provider) {
        ChunkBinding b = this.ga2$binding;
        if (b != null && DfcHooks.enabled() && b.handles(interpolator)) {
            b.fillSliceColumn(interpolator, slice);
        } else {
            interpolator.fillArray(slice, provider);
        }
    }

    @Redirect(
        method = "selectCellYZ",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V")
    )
    private void ga2$fillCell(DensityFunction filler, double[] values, DensityFunction.ContextProvider provider) {
        ChunkBinding b = this.ga2$binding;
        if (b != null && DfcHooks.enabled() && b.handlesCell(filler)) {
            b.fillCell(filler, values);
        } else {
            filler.fillArray(values, provider);
        }
    }

    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void ga2$bindSampler(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget, CallbackInfoReturnable<Climate.Sampler> cir) {
        this.ga2$mapMemo = null;
        if (!DfcHooks.enabled() || !DfcConfig.pointPrograms() || this.ga2$randomState == null) {
            return;
        }
        ProgramSet set = ((Ga2RandomState) (Object) this.ga2$randomState).ga2$programs();
        if (set == null || set.chunkSampler() == null) {
            return;
        }
        try {
            BoundSampler bound = BoundSampler.bind(cir.getReturnValue(), set.chunkSampler(), this.ga2$randomState.router());
            BoundSampler[] held = this.ga2$samplers;
            if (held == null) {
                this.ga2$samplers = new BoundSampler[] { bound };
            } else {
                held = Arrays.copyOf(held, held.length + 1);
                held[held.length - 1] = bound;
                this.ga2$samplers = held;
            }
        } catch (RuntimeException e) {
            if (DfcConfig.failOnCodegenError()) {
                throw e;
            }
            if (BIND_FAILURES.getAndIncrement() < 3) {
                Compiler.LOGGER.error("climate sampler bind failed -> vanilla biome sampling for chunk", e);
            }
        }
    }

    @Override
    public void ga2$setSliceY(int blockY, int index) {
        this.cellStartBlockY = blockY;
        this.interpolationCounter++;
        this.inCellY = 0;
        this.arrayIndex = index;
    }

    @Override
    public void ga2$setCell(int x, int y, int z, int index) {
        this.inCellX = x;
        this.inCellY = y;
        this.inCellZ = z;
        this.arrayIndex = index;
    }

    @Override
    public void ga2$advanceInterpolationCounter(long by) {
        this.interpolationCounter += by;
    }

    @Override
    public double ga2$dz() {
        return this.ga2$dz;
    }

    @Unique
    private int ga2$cellClass;
    /** The wrapped router's vein toggle (an interpolator when the datapack interpolates it), for FastFill's per-cell vein check. */
    @Unique
    private DensityFunction ga2$veinToggle;

    @Override
    public int ga2$cellClass() {
        return this.ga2$cellClass;
    }

    @Override
    public void ga2$setCellClass(int cellClass) {
        this.ga2$cellClass = cellClass;
    }

    @Override
    public void ga2$fillCurrentCell() {
        ChunkBinding b = this.ga2$binding;
        if (b != null) {
            b.fillCurrentCell();
        }
        this.ga2$cellClass = CellBounds.MIXED;
    }

    @Override
    public DensityFunction ga2$veinToggle() {
        return this.ga2$veinToggle;
    }

    @Override
    public ChunkBinding ga2$binding() {
        return this.ga2$binding;
    }

    @Override
    public SurfaceLevels ga2$levelProgram() {
        return this.ga2$levelProgram;
    }
}
