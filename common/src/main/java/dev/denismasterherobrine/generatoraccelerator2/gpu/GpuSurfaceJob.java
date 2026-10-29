package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceSpec;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.StructureManagerLevelAccessor;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.QuietWait;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A chunk's surface on the GPU. It is requested when the chunk's NOISE step writes the GPU's blocks. At that point the neighbours
 * have their biomes with ties resolved, and nothing changes the chunk between NOISE and SURFACE. The chunk's SURFACE step writes it
 * into the chunk instead of running GA2's surface builder ({@link Apply#surface}). The SURFACE step builds the surface on the CPU as
 * before when the answer is late, says the chunk needs the CPU, or cannot be written.
 */
public record GpuSurfaceJob(GpuWorldgen wg, CompletableFuture<SurfaceBatches.Result> future, AtomicBoolean waited) {
    /** How long a SURFACE step waits for the GPU's answer before building the surface on the CPU. */
    private static final long WAIT_MS = 250L;

    /**
     * The SURFACE step's wait for the answer, without blocking a thread. {@code ParallelSteps.surface} runs the step when this future
     * completes. It completes when the answer is in or {@link #WAIT_MS} has passed, not counting the time the whole JVM stood still
     * ({@link GpuWorldgen#deadline}). Returns null when the chunk has no answer to wait for, or the answer is already in.
     */
    public static CompletableFuture<Void> whenReady(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        GpuSurfaceJob job = gc == null ? null : gc.ga2$gpuSurface();
        if (job == null || job.future.isDone()) {
            return null;
        }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Runnable over = () -> {
            job.waited.set(true);
            ready.complete(null);
        };
        job.future.whenComplete((r, e) -> over.run());
        GpuWorldgen.deadline(job.future, WAIT_MS, over);
        return ready;
    }

    /** Conditions under which every chunk builds its surface on the CPU for now (another mod's code would act in it). */
    private static final List<BooleanSupplier> CPU_CONDITIONS = new CopyOnWriteArrayList<>();

    /** Adds a condition under which every chunk builds its surface on the CPU ({@code compat/<modid>}). */
    public static void addCpuCondition(BooleanSupplier condition) {
        CPU_CONDITIONS.add(condition);
    }

    private static boolean cpuNow() {
        for (BooleanSupplier c : CPU_CONDITIONS) {
            if (c.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    public static final LongAdder ASKED = new LongAdder();
    public static final LongAdder APPLIED = new LongAdder();
    public static final LongAdder CPU = new LongAdder();
    public static final LongAdder LATE = new LongAdder();

    /** Why {@link #ask} left a chunk's surface to the CPU. Counted for {@code /ga2 gpu}. */
    enum Decline {
        NOT_BUILT("no surface program"),
        NOT_ALLOWED("another mod in the step"),
        NOT_A_REGION("no generation region"),
        STALE("tags or rules changed since the build"),
        NO_NEIGHBOUR("a neighbour without biomes"),
        OTHER_BIOME("a biome another mod writes in or the program lacks");

        final String what;

        Decline(String what) {
            this.what = what;
        }
    }

    private static final LongAdder[] DECLINED = new LongAdder[Decline.values().length];

    static {
        for (int i = 0; i < DECLINED.length; i++) {
            DECLINED[i] = new LongAdder();
        }
    }

    private static boolean decline(Decline why) {
        DECLINED[why.ordinal()].increment();
        return false;
    }

    /** The chunks {@link #ask} left to the CPU, by reason, e.g. "stale 1,234". Empty when there were none. */
    public static String declines() {
        StringBuilder sb = new StringBuilder();
        for (Decline d : Decline.values()) {
            long n = DECLINED[d.ordinal()].sum();
            if (n > 0) {
                sb.append(sb.isEmpty() ? "" : ", ").append(d.what).append(' ').append(String.format(Locale.ROOT, "%,d", n));
            }
        }
        return sb.toString();
    }
    private static final HookTargets.Hook SURFACE_ENTRY = HookTargets.hook(HookTargets.SURFACE_ENTRY);
    private static final HookTargets.Hook SURFACE_STEP = HookTargets.hook(HookTargets.SURFACE_STEP);
    private static final HookTargets.Hook SURFACE_STEP_BODY = HookTargets.hook(HookTargets.SURFACE_STEP_BODY);
    private static final HookTargets.Hook GPU_SURFACE = HookTargets.hook(HookTargets.GPU_SURFACE);

    /**
     * Whether the GPU's surface is the surface step's whole work. No other mod's code may be in the step, its entry or the surface build.
     * Code before the step's generator call is the exception when GA2 knows the biomes it writes in
     * ({@code SurfaceSpec.declareStepWriter}). Chunks near those biomes build the surface on the CPU.
     */
    static boolean allowed() {
        return GpuTerrain.enabled() && GPU_SURFACE.allowed() && SURFACE_ENTRY.unchanged() && HookTargets.handled(HookTargets.SURFACE_ENTRY).isEmpty()
            && SURFACE_STEP.unchanged() && SURFACE_STEP_BODY.unchanged()
            && SurfaceSpec.stepWritersDeclared(HookTargets.handledMixins(HookTargets.SURFACE_STEP_BODY));
    }

    /** Why {@link #allowed} is false, naming the other mods whose changes keep the surface on the CPU. Null when it is true. */
    public static String disallowed() {
        if (allowed()) {
            return null;
        }
        Set<String> mods = new TreeSet<>();
        for (String key : new String[] { HookTargets.GPU_SURFACE, HookTargets.SURFACE_ENTRY, HookTargets.SURFACE_STEP, HookTargets.SURFACE_BUILD,
            HookTargets.SURFACE_TOP_MATERIAL, HookTargets.GPU_TERRAIN }) {
            mods.addAll(HookTargets.foreign(key));
            if (!key.equals(HookTargets.GPU_SURFACE)) {
                // The GPU's surface runs its own handled changes (Tectonic's snow offset). For the other keys, GA2's CPU code runs the
                // handled changes instead of the GPU surface.
                mods.addAll(HookTargets.handledMixins(key));
            }
        }
        // code before the step's generator call keeps the surface on the CPU unless GA2 knows the biomes it writes in
        mods.addAll(HookTargets.foreign(HookTargets.SURFACE_STEP_BODY));
        for (String m : HookTargets.handledMixins(HookTargets.SURFACE_STEP_BODY)) {
            if (!SurfaceSpec.stepWritersDeclared(List.of(m))) {
                mods.add(m);
            }
        }
        if (!MixinTargets.aquiferVanilla()) {
            mods.addAll(MixinTargets.aquiferChanges());
        }
        // the first condition that fails, so the line says why rather than only who is near
        String why;
        if (!GpuTerrain.enabled()) {
            why = "no GPU terrain: " + GpuTerrain.disabledReason();
        } else if (!GPU_SURFACE.allowed()) {
            why = "gpu.surface off";
        } else if (!SURFACE_ENTRY.unchanged() || !HookTargets.handled(HookTargets.SURFACE_ENTRY).isEmpty()) {
            why = "the step's entry is changed";
        } else if (!SURFACE_STEP.unchanged() || !SURFACE_STEP_BODY.unchanged()) {
            why = "the step is changed";
        } else {
            why = "code before the step's generator call writes in biomes not declared";
        }
        return why + "; other mods in the surface step " + (mods.isEmpty() ? "(or its hooks are not decided)" : mods.toString());
    }

    /** Asks the GPU for the chunk's surface at the NOISE step, after the blocks are written. False when the GPU cannot build it. */
    static boolean ask(GpuWorldgen wg, ChunkAccess chunk, StructureManager structures, GpuTerrain terrain, GpuWorldgen.NoiseResult res) {
        SurfaceBatches batches = wg.surfaces;
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (batches == null || terrain == null || gc == null) {
            return decline(Decline.NOT_BUILT);
        }
        if (!allowed() || structures == null || cpuNow()) {
            return decline(Decline.NOT_ALLOWED);
        }
        if (GpuWorldgen.SHARE && batches.behind()) {
            // the GPU's surface batches are behind: the SURFACE step builds this one on the CPU
            GpuWorldgen.SHARED_SURFACES.increment();
            return false;
        }
        LevelAccessor level = ((StructureManagerLevelAccessor) structures).ga2$level();
        if (!(level instanceof WorldGenRegion region)) {
            return decline(Decline.NOT_A_REGION);
        }
        var spec = wg.surfaceSpec;
        if (!spec.current()) {
            return decline(Decline.STALE);
        }
        ChunkPos pos = chunk.getPos();
        // The stored quart biomes the zoom reads: quart columns 4x - 1 .. 4x + 4 on both axes, every quart layer. Each neighbour is
        // looked up the way the zoom's getNoiseBiome does it (at BIOMES, or none). If the zoom would not read stored biomes from a
        // neighbour, the surface goes to the CPU. Distant Horizons' region hands out blank chunks past its batch, for example.
        ChunkAccess[] around = new ChunkAccess[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkAccess c = dx == 0 && dz == 0 ? chunk : region.getChunk(pos.x + dx, pos.z + dz, ChunkStatus.BIOMES, false);
                if (c == null) {
                    return decline(Decline.NO_NEIGHBOUR);
                }
                around[(dz + 1) * 3 + dx + 1] = c;
            }
        }
        short[] win = new short[spec.windowInts];
        int qy0 = wg.minY >> 2, qx0 = pos.x * 4 - 1, qz0 = pos.z * 4 - 1;
        Holder<Biome> last = null;
        int lastIndex = 0;
        // A chunk's window holds few biomes. Remember them, so the index map is queried once per biome rather than per change.
        @SuppressWarnings("unchecked")
        Holder<Biome>[] seen = new Holder[4];
        int[] seenIndex = new int[4];
        int seenNext = 0;
        for (int iz = 0; iz < 6; iz++) {
            for (int ix = 0; ix < 6; ix++) {
                int qx = qx0 + ix, qz = qz0 + iz;
                ChunkAccess c = around[((qz >> 2) - pos.z + 1) * 3 + (qx >> 2) - pos.x + 1];
                for (int iy = 0; iy < spec.quartsY; iy++) {
                    Holder<Biome> h = c.getNoiseBiome(qx, qy0 + iy, qz);
                    if (h != last) {
                        int found = -1;
                        for (int k = 0; k < 4; k++) {
                            if (seen[k] == h) {
                                found = seenIndex[k];
                                break;
                            }
                        }
                        if (found < 0) {
                            Integer b = wg.biomeIndex.get(h);
                            if (b == null || spec.stepWriter(b)) {
                                // another mod writes this chunk before its surface (SurfaceSpec.declareStepWriter): the CPU builds it after that
                                return decline(Decline.OTHER_BIOME);
                            }
                            found = b;
                            seen[seenNext] = h;
                            seenIndex[seenNext] = found;
                            seenNext = (seenNext + 1) & 3;
                        }
                        last = h;
                        lastIndex = found;
                    }
                    win[(iy * 6 + iz) * 6 + ix] = (short) lastIndex;
                }
            }
        }
        // WORLD_SURFACE_WG first-available per column (x * 16 + z), as the NOISE step left it
        int[] tops = new int[256];
        int[] t = res.tops();
        for (int col = 0; col < 256; col++) {
            int top = t[col * 2];
            // the NOISE result's columns are z * 16 + x
            tops[(col & 15) * 16 + (col >> 4)] = top == Integer.MIN_VALUE ? wg.minY : top + 1;
        }
        int bx = pos.getMinBlockX(), bz = pos.getMinBlockZ();
        int[] cpar = { pos.x, pos.z, terrain.preliminarySurfaceLevel(bx, bz), terrain.preliminarySurfaceLevel(bx + 16, bz), terrain.preliminarySurfaceLevel(bx, bz + 16),
            terrain.preliminarySurfaceLevel(bx + 16, bz + 16), 0, 0 };
        gc.ga2$setGpuSurface(new GpuSurfaceJob(wg, batches.submit(pos, res.storage(), res.meta(), tops, win, cpar), new AtomicBoolean()));
        ASKED.increment();
        return true;
    }

    /** For a SURFACE step the CPU builds regardless of the answer. Drops the job from the chunk instead of keeping it until it completes. */
    public static void discard(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (gc != null && gc.ga2$gpuSurface() != null) {
            gc.ga2$setGpuSurface(null);
        }
    }

    /**
     * The SURFACE step. Writes the GPU's surface into the chunk. Returns false when the CPU must build it: no answer, a late answer, or a
     * chunk the GPU left to the CPU.
     */
    public static boolean apply(ChunkAccess chunk, Consumer<ChunkAccess> cpuSurface, Runnable records) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        GpuSurfaceJob job = gc == null ? null : gc.ga2$gpuSurface();
        if (job == null) {
            return false;
        }
        gc.ga2$setGpuSurface(null);
        SurfaceBatches.Result r;
        try {
            if (job.waited.get() && !job.future.isDone()) {
                // The step already waited its full time for the answer (whenReady).
                throw new TimeoutException();
            }
            r = QuietWait.get(job.future, WAIT_MS);
        } catch (TimeoutException e) {
            LATE.increment();
            // A late surface means the GPU is busy elsewhere. The governor counts it with the late blocks and biomes.
            job.wg.answered(true);
            return false;
        } catch (Exception e) {
            GpuSteps.GPU_ERRORS.increment();
            return false;
        }
        if (r == null || r.cpu()) {
            CPU.increment();
            return false;
        }
        Runnable compare = GpuVerify.surfaceBefore(chunk, cpuSurface);
        // other mods' code that records the columns (Ecliptic Seasons' biome map), on the chunk as the noise step left it
        records.run();
        if (!Apply.surface(job.wg, chunk, r)) {
            CPU.increment();
            return false;
        }
        APPLIED.increment();
        if (compare != null) {
            compare.run();
        }
        return true;
    }
}
