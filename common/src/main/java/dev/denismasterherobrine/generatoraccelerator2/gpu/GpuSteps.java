package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.LiquidRegions;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.distanthorizons.DHThreads;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BeardCuller;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Lat;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.BeardifierAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkFactoryInvoker;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepLane;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * The BIOMES and NOISE steps on the GPU. A chunk goes to the GPU when its dimension's program is ready and nothing in the chunk
 * needs the CPU. That means a fresh proto chunk without blending or below-zero retrogen, and structures whose beardifier is
 * vanilla's. The step then returns a future that completes once the GPU's result is written into the chunk on the step's executor.
 * A Distant Horizons generator thread waits for the step itself, so there the step returns already done. On any GPU failure, or
 * when the result cannot be written into the chunk exactly, the future runs the generator's own method instead.
 */
public final class GpuSteps {
    private GpuSteps() {}

    public static final LongAdder NOISE_GPU = new LongAdder();
    public static final LongAdder NOISE_CPU = new LongAdder();
    public static final LongAdder BIOMES_GPU = new LongAdder();
    public static final LongAdder BIOMES_CPU = new LongAdder();
    /** Chunks whose biome step ran on the CPU with the GPU's climate points and searches ({@code gpu.runtime.BiomeHand}). */
    public static final LongAdder BIOMES_HANDED = new LongAdder();
    public static final LongAdder GPU_ERRORS = new LongAdder();
    /** WAIT: from the step's call to the GPU's answer on the step's executor. APPLY: writing the result into the chunk there. */
    public static final Lat BIOMES_WAIT = new Lat(), BIOMES_APPLY = new Lat(), NOISE_WAIT = new Lat(), NOISE_APPLY = new Lat(), NOISE_CHUNK = new Lat();
    private static final ThreadLocal<int[]> CPU_ONLY = ThreadLocal.withInitial(() -> new int[1]);
    private static final HookTargets.Hook NOISE_HOOK = HookTargets.hook(HookTargets.GPU_NOISE);
    private static final HookTargets.Hook FILL_HOOK = HookTargets.hook(HookTargets.NOISE_FILL);
    private static final HookTargets.Hook STATE_HOOK = HookTargets.hook(HookTargets.NOISE_FILL_STATE);
    private static final HookTargets.Hook RULES_HOOK = HookTargets.hook(HookTargets.NOISE_MATERIAL_RULES);
    private static final HookTargets.Hook VEIN_HOOK = HookTargets.hook(HookTargets.NOISE_VEIN_RULE);
    private static final HookTargets.Hook PICKER_HOOK = HookTargets.hook(HookTargets.NOISE_FLUID_PICKER);

    /** Whether the GPU hooks stand aside on this thread ({@link #cpuOnly}). */
    public static boolean cpuOnlyNow() {
        return CPU_ONLY.get()[0] > 0;
    }

    /** Runs {@code body} with the generator's own steps. The GPU hooks stand aside on this thread meanwhile. */
    public static <T> T cpuOnly(Supplier<T> body) {
        int[] d = CPU_ONLY.get();
        d[0]++;
        try {
            return body.get();
        } finally {
            d[0]--;
        }
    }

    /** Ensures the hooks the GPU path reads are decided, with their classes loaded and inspected. See the static block. */
    public static void decideHooks() {
        // the class initializer does it
    }

    /**
     * Whether the noise fill as GA2 runs it matches what the GPU computes. This requires that no other mod changes the fill, its
     * rules, veins or fluids. FastFill knows some per-block state changes ({@code FillSteps}). YUNG's API and Iron's Spellbooks use
     * them to keep water and lava out of their structures. These changes do nothing in a chunk without those structures, so the GPU
     * fills such chunks. {@link #beard} leaves the other chunks to the CPU.
     */
    public static boolean noiseHooksAllowed() {
        return NOISE_HOOK.allowed() && FILL_HOOK.allowed() && dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillSteps.allKnown() && RULES_HOOK.allowed()
            && VEIN_HOOK.allowed() && PICKER_HOOK.allowed() && FastFill.ENABLED;
    }

    /** For the status line: why the GPU's noise stands aside ({@link #noiseHooksAllowed} false), or null when it does not. */
    public static String noiseHooksOff() {
        if (noiseHooksAllowed()) {
            return null;
        }
        if (!FastFill.ENABLED) {
            return "GA2's noise fill is off";
        }
        StringBuilder sb = new StringBuilder("other mods change the noise fill");
        for (String key : new String[] { HookTargets.GPU_NOISE, HookTargets.NOISE_FILL, HookTargets.NOISE_FILL_STATE, HookTargets.NOISE_MATERIAL_RULES,
            HookTargets.NOISE_VEIN_RULE, HookTargets.NOISE_FLUID_PICKER }) {
            java.util.List<String> foreign = HookTargets.foreign(key);
            if (!foreign.isEmpty() && !(key.equals(HookTargets.NOISE_FILL_STATE) && dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillSteps.allKnown())) {
                sb.append(" (").append(key).append(": ").append(foreign).append(')');
            }
        }
        return sb.toString();
    }

    static {
        // A hook is decided when its class is transformed and inspected for other mods' changes. If a class never loads, its hook
        // stays undecided, which reads as off. The ore veinifier loads with the first NoiseChunk of a dimension with ore veins. In a
        // pack whose overworld is not a noise generator (TerraFirmaCraft, Moderner Beta), no chunk ever loaded it, so GPU noise stayed
        // off in every dimension. Loading the noise hooks' classes here decides them before the first chunk asks.
        for (String name : new String[] { "net.minecraft.world.level.levelgen.OreVeinifier", "net.minecraft.world.level.levelgen.material.MaterialRuleList",
            "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell", "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer",
            "net.minecraft.world.level.levelgen.NoiseChunk", "net.minecraft.world.level.levelgen.SurfaceSystem" }) {
            try {
                Class.forName(Names.runtimeBinaryClass(name), true, NoiseBasedChunkGenerator.class.getClassLoader());
            } catch (ReflectiveOperationException | LinkageError e) {
                // Left undecided. The hooks read as off, as before.
            }
        }
    }

    private static boolean freshProto(ChunkAccess chunk, Blender blender) {
        return chunk instanceof ProtoChunk && !(chunk instanceof ImposterProtoChunk) && chunk.getBelowZeroRetrogen() == null && blender == Blender.empty();
    }

    /**
     * The step's executor, or null on a thread that waits for the step itself (Distant Horizons' generator, {@link DHThreads}). On such
     * a thread, the calling thread waits for the GPU's answer and writes it in ({@link #onAnswer}). The thread that completes the
     * answer does not.
     */
    @Nullable
    private static Executor executor() {
        Executor lane = StepLane.currentExecutor();
        if (lane != null) {
            return lane;
        }
        return DHThreads.onDhThread() ? null : Util.backgroundExecutor();
    }

    /** Runs {@code then} on the GPU's answer, on {@code ex}. With no executor ({@link #executor}), runs it here once the answer is in. */
    private static <R> CompletableFuture<ChunkAccess> onAnswer(CompletableFuture<R> gpu, Function<R, CompletableFuture<ChunkAccess>> then, @Nullable Executor ex) {
        if (ex != null) {
            return gpu.thenComposeAsync(then, ex);
        }
        try {
            return then.apply(gpu.join());
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    public static CompletableFuture<ChunkAccess> fillFromNoise(NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings,
                                                               Supplier<Aquifer.FluidPicker> picker, Blender blender, RandomState randomState,
                                                               StructureManager structures, ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        // The GPU's blocks requested at the biome step, when the structures were already known. They are taken off the chunk whatever
        // this step does. An answer the CPU's fill does not use (GPU paused, chunk unfit) is then not kept on the chunk until it completes.
        CompletableFuture<GpuWorldgen.NoiseResult> early = gc != null ? gc.ga2$gpuNoise() : null;
        if (early != null) {
            gc.ga2$setGpuNoise(null);
        }
        if (CPU_ONLY.get()[0] > 0 || !GpuConfig.enabled() || !freshProto(chunk, blender) || !noiseHooksAllowed()) {
            return null;
        }
        GpuWorldgen wg = Gpu.worldgen(generator, settings, picker, randomState, chunk);
        if (wg == null || !wg.ready() || !wg.asking() || !Apply.fits(wg, chunk)) {
            return null;
        }
        Executor ex = executor();
        ChunkPos pos = chunk.getPos();
        long t0 = System.nanoTime();
        CompletableFuture<GpuWorldgen.NoiseResult> gpu;
        // If the GPU does not answer in time (busy with other work), the chunk goes to the CPU (GpuWorldgen.timed).
        if (early != null) {
            NOISE_EARLY.increment();
            gpu = wg.timed(early);
        } else {
            gpu = CompletableFuture.supplyAsync(() -> beard(wg, randomState, structures, pos), ex != null ? ex : Runnable::run)
                .thenCompose(b -> b == null ? CompletableFuture.completedFuture(null) : wg.timed(wg.noise(pos, b)));
        }
        gpu = gpu.exceptionally(e -> {
            GPU_ERRORS.increment();
            return null;
        });
        return onAnswer(gpu, res -> {
            long t1 = System.nanoTime();
            NOISE_WAIT.add(t1 - t0);
            if (res != null && Apply.noise(wg, chunk, res)) {
                NOISE_GPU.increment();
                long t2 = System.nanoTime();
                NOISE_APPLY.add(t2 - t1);
                GpuTerrain.Spec spec = res.terrain() != null && gc != null && GpuTerrain.enabled() ? wg.terrainSpec(picker) : null;
                if (spec != null) {
                    // SURFACE and CARVERS read the GPU's surface levels and aquifer cells. No NoiseChunk is made.
                    GpuTerrain terrain = new GpuTerrain(spec, res.terrain(), pos.x, pos.z);
                    if (LiquidRegions.active()) {
                        // the carvers' aquifer applies Better Caves' step as the chunk's NoiseChunk aquifer would
                        terrain.liquids = LiquidRegions.table(LiquidRegions.regionsOf(randomState), pos.x, pos.z);
                    }
                    gc.ga2$setGpuTerrain(terrain);
                    GpuTerrain.CHUNKS.increment();
                    // The GPU computes the surface while the chunk waits for its SURFACE step.
                    GpuSurfaceJob.ask(wg, chunk, structures, terrain, res);
                } else {
                    // Vanilla's fill leaves a NoiseChunk on the chunk for SURFACE and CARVERS. Create it the way doFill does.
                    chunk.getOrCreateNoiseChunk(c -> ((NoiseChunkFactoryInvoker) (Object) generator).ga2$createNoiseChunk(c, structures, blender, randomState));
                }
                NOISE_CHUNK.since(t2);
                GpuVerify.noise(generator, settings, picker, randomState, chunk, structures, wg);
                return CompletableFuture.completedFuture(chunk);
            }
            NOISE_CPU.increment();
            return cpuOnly(() -> generator.fillFromNoise(blender, randomState, structures, chunk));
        }, ex);
    }

    public static CompletableFuture<ChunkAccess> createBiomes(NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings,
                                                              Supplier<Aquifer.FluidPicker> picker, RandomState randomState, Blender blender,
                                                              StructureManager structures, ChunkAccess chunk) {
        if (CPU_ONLY.get()[0] > 0 || !GpuConfig.enabled() || !freshProto(chunk, blender)) {
            return null;
        }
        GpuWorldgen wg = Gpu.worldgen(generator, settings, picker, randomState, chunk);
        if (wg == null || !wg.ready() || !wg.biomeKernels() || !wg.asking() || !Apply.fits(wg, chunk)) {
            return null;
        }
        Executor ex = executor();
        long t0 = System.nanoTime();
        CompletableFuture<GpuWorldgen.BiomeResult> gpu = wg.timed(wg.biomes(chunk.getPos())).exceptionally(e -> {
            GPU_ERRORS.increment();
            return null;
        });
        if (wg.handsBiomes()) {
            // other mods' lookups decide the biomes. The biome step runs as it is, here, with the GPU's points and searches handed to it
            return onAnswer(gpu, res -> {
                long t1 = System.nanoTime();
                BIOMES_WAIT.add(t1 - t0);
                if (res == null || res.hand() == null) {
                    BIOMES_CPU.increment();
                    return cpuOnly(() -> generator.createBiomes(randomState, blender, structures, chunk));
                }
                Object[] start = GpuVerify.active() ? GpuVerify.handState(wg) : null;
                CompletableFuture<ChunkAccess> f = dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.BiomeHand.fill(wg, chunk, res,
                    () -> cpuOnly(() -> generator.createBiomes(randomState, blender, structures, chunk)));
                BIOMES_APPLY.since(t1);
                BIOMES_HANDED.increment();
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    // the step ran here, so this thread's last search results are the fill's
                    GpuVerify.handBiomes(generator, randomState, chunk, structures, wg, start);
                    askNoise(wg, randomState, structures, blender, chunk);
                }
                return f;
            }, ex);
        }
        // Ties go to the biome source through the chunk's own climate sampler. The sampler comes from the NoiseChunk that vanilla's
        // biome step makes, which is kept for NOISE.
        Supplier<net.minecraft.world.level.biome.Climate.Sampler> tieSampler = () -> ((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkSamplerInvoker)
            chunk.getOrCreateNoiseChunk(c -> ((NoiseChunkFactoryInvoker) (Object) generator).ga2$createNoiseChunk(c, structures, blender, randomState)))
            .ga2$cachedClimateSampler(randomState.router(), settings.value().spawnTarget());
        return onAnswer(gpu, res -> {
            long t1 = System.nanoTime();
            BIOMES_WAIT.add(t1 - t0);
            // The thread's last climate search results before the fill. Verification also starts the CPU's fill from them.
            var start = res != null && GpuVerify.active() ? wg.lastResults() : null;
            if (res != null && Apply.biomes(wg, chunk, res, tieSampler)) {
                BIOMES_APPLY.since(t1);
                BIOMES_GPU.increment();
                GpuVerify.biomes(generator, randomState, chunk, structures, wg, res, start);
                askNoise(wg, randomState, structures, blender, chunk);
                return CompletableFuture.completedFuture(chunk);
            }
            BIOMES_CPU.increment();
            return cpuOnly(() -> generator.createBiomes(randomState, blender, structures, chunk));
        }, ex);
    }

    /** {@code -Dgeneratoraccelerator2.gpu.earlyNoise=false} requests the GPU's blocks at the NOISE step instead (for comparisons). */
    private static final boolean EARLY_NOISE = !"false".equals(System.getProperty("generatoraccelerator2.gpu.earlyNoise"));
    public static final LongAdder NOISE_EARLY = new LongAdder();
    /** Chunks whose other mods' structure pieces (YUNG's API, Integrated API) went to the GPU as data. */
    public static final LongAdder ENHANCED_CHUNKS = new LongAdder();

    /**
     * Asks the GPU for the chunk's blocks as soon as the chunk's biomes are in. The NOISE step's beardifier reads the chunk's structure
     * references and the starts they point to. These are final from the biome step on, since the chunk has the same structure starts
     * around it then. So the GPU decides the blocks while the chunk waits for its neighbours' biomes, and the NOISE step finds them
     * ready. The NOISE step checks again whether the GPU may fill the chunk. If not, the answer is dropped.
     */
    private static void askNoise(GpuWorldgen wg, RandomState randomState, StructureManager structures, Blender blender, ChunkAccess chunk) {
        if (!EARLY_NOISE || !noiseHooksAllowed() || !freshProto(chunk, blender)) {
            return;
        }
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (gc == null) {
            return;
        }
        GpuWorldgen.Beard b = beard(wg, randomState, structures, chunk.getPos());
        if (b != null) {
            gc.ga2$setGpuNoise(wg.noise(chunk.getPos(), b));
        }
    }

    /** {@link #liquids}' answer when a column's liquid is a state the program has no id for. The CPU fills the chunk. */
    public static final int[] LIQUID_WITHOUT_ID = new int[0];

    /** Better Caves' liquid regions of the chunk, as the GPU's block kernels apply them ({@code aquifer.LiquidRegions.gpu}). Null without them. */
    @javax.annotation.Nullable
    public static int[] liquids(GpuWorldgen wg, RandomState randomState, ChunkPos pos) {
        if (!wg.liquidRegions) {
            return null;
        }
        LiquidRegions.Table table = LiquidRegions.table(LiquidRegions.regionsOf(randomState), pos.x, pos.z);
        if (table == null) {
            return null;
        }
        int[] liquids = LiquidRegions.gpu(table, wg.states);
        return liquids == null ? LIQUID_WITHOUT_ID : liquids;
    }

    /**
     * The chunk's structures as the NOISE step's beardifier sees them ({@code Beardifier.forStructuresInChunk}, including other mods'
     * changes to it). Returns null when the beardifier is not vanilla's sum for this chunk. The CPU then fills the chunk.
     */
    public static GpuWorldgen.Beard beard(GpuWorldgen wg, RandomState randomState, StructureManager structures, ChunkPos pos) {
        int[] liquids = liquids(wg, randomState, pos);
        if (liquids == LIQUID_WITHOUT_ID) {
            return null;
        }
        Beardifier beardifier = Beardifier.forStructuresInChunk(structures, pos);
        // other mods' pieces (YUNG's API, Integrated API): their terrain adaptation goes to the GPU as data (beardifier.EnhancedBeard)
        dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard.Chunk enhanced = null;
        int overrideId = -1;
        if (!BeardCuller.mayCull(beardifier)) {
            enhanced = dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardExtras.enhancedFor(beardifier, pos);
            if (enhanced == null) {
                return null;
            }
            if (enhanced.isEmpty()) {
                enhanced = null;
            } else if (enhanced.override() != null) {
                // YUNG's aquifer override at the marked blocks: the default block, or a named state the program must have an id for
                dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard.Adaptation o = enhanced.override();
                if (o.overrideKind() == dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard.OVERRIDE_SOLIDIFY) {
                    overrideId = dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter.ID_STONE;
                } else {
                    for (int i = 0; i < wg.states.length; i++) {
                        if (wg.states[i] == o.overrideState()) {
                            overrideId = i;
                        }
                    }
                    if (overrideId < 0) {
                        return null;
                    }
                }
            }
            ENHANCED_CHUNKS.increment();
        }
        if (!STATE_HOOK.allowed() && dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillSteps.mayActForGpu(structures, pos, beardifier, enhanced != null)) {
            return null; // Another mod's fluid step acts in the chunk (an Iron's Spellbooks structure). The CPU runs it.
        }
        BeardifierAccessor acc = (BeardifierAccessor) beardifier;
        ObjectListIterator<Beardifier.Rigid> pieces = acc.ga2$pieceIterator();
        ObjectListIterator<JigsawJunction> junctions = acc.ga2$junctionIterator();
        IntArrayList p = new IntArrayList();
        IntArrayList j = new IntArrayList();
        while (pieces.hasNext()) {
            Beardifier.Rigid r = pieces.next();
            BoundingBox b = r.box();
            p.add(b.minX());
            p.add(b.minY());
            p.add(b.minZ());
            p.add(b.maxX());
            p.add(b.maxY());
            p.add(b.maxZ());
            p.add(r.terrainAdjustment().ordinal());
            p.add(r.groundLevelDelta());
        }
        pieces.back(Integer.MAX_VALUE);
        while (junctions.hasNext()) {
            JigsawJunction jj = junctions.next();
            j.add(jj.getSourceX());
            j.add(jj.getSourceGroundY());
            j.add(jj.getSourceZ());
        }
        junctions.back(Integer.MAX_VALUE);
        if (p.isEmpty() && j.isEmpty() && liquids == null && enhanced == null) {
            return GpuWorldgen.Beard.NONE;
        }
        return new GpuWorldgen.Beard(p.toIntArray(), j.toIntArray(), liquids, enhanced, overrideId);
    }
}
