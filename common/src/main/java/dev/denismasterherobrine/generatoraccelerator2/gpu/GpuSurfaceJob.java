package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.Apply;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuWorldgen;
import dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.StructureManagerLevelAccessor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
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
public record GpuSurfaceJob(GpuWorldgen wg, CompletableFuture<SurfaceBatches.Result> future, java.util.concurrent.atomic.AtomicBoolean waited) {
    /** How long a SURFACE step waits for the GPU's answer before building the surface on the CPU. */
    private static final long WAIT_MS = 250L;

    /**
     * The SURFACE step's wait for the answer, without blocking a thread. {@code ParallelSteps.surface} runs the step when this future
     * completes. It completes when the answer is in or {@link #WAIT_MS} has passed. Returns null when the chunk has no answer to wait
     * for, or the answer is already in.
     */
    public static CompletableFuture<Void> whenReady(ChunkAccess chunk) {
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        GpuSurfaceJob job = gc == null ? null : gc.ga2$gpuSurface();
        if (job == null || job.future.isDone()) {
            return null;
        }
        return job.future.copy().completeOnTimeout(null, WAIT_MS, TimeUnit.MILLISECONDS).handle((r, e) -> {
            job.waited.set(true);
            return null;
        });
    }

    public static final LongAdder ASKED = new LongAdder();
    public static final LongAdder APPLIED = new LongAdder();
    public static final LongAdder CPU = new LongAdder();
    public static final LongAdder LATE = new LongAdder();
    private static final HookTargets.Hook SURFACE_ENTRY = HookTargets.hook(HookTargets.SURFACE_ENTRY);
    private static final HookTargets.Hook SURFACE_STEP = HookTargets.hook(HookTargets.SURFACE_STEP);
    private static final HookTargets.Hook GPU_SURFACE = HookTargets.hook(HookTargets.GPU_SURFACE);

    /** Whether the GPU's surface is the surface step's whole work. No other mod's code may be in the step, its entry or the surface build. */
    static boolean allowed() {
        return GpuTerrain.enabled() && GPU_SURFACE.allowed() && SURFACE_ENTRY.unchanged() && HookTargets.handled(HookTargets.SURFACE_ENTRY).isEmpty()
            && SURFACE_STEP.unchanged();
    }

    /** Why {@link #allowed} is false, naming the other mods whose changes keep the surface on the CPU. Null when it is true. */
    public static String disallowed() {
        if (allowed()) {
            return null;
        }
        java.util.Set<String> mods = new java.util.TreeSet<>();
        for (String key : new String[] { HookTargets.GPU_SURFACE, HookTargets.SURFACE_ENTRY, HookTargets.SURFACE_STEP, HookTargets.SURFACE_BUILD,
            HookTargets.SURFACE_TOP_MATERIAL, HookTargets.GPU_TERRAIN }) {
            mods.addAll(HookTargets.foreign(key));
            if (!key.equals(HookTargets.GPU_SURFACE)) {
                // The GPU's surface runs its own handled changes (Tectonic's snow offset). For the other keys, GA2's CPU code runs the
                // handled changes instead of the GPU surface.
                mods.addAll(HookTargets.handledMixins(key));
            }
        }
        if (!dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets.aquiferVanilla()) {
            mods.addAll(dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets.aquiferChanges());
        }
        return "other mods change the surface step " + (mods.isEmpty() ? "(or its hooks are not decided)" : mods.toString());
    }

    /** Asks the GPU for the chunk's surface at the NOISE step, after the blocks are written. False when the GPU cannot build it. */
    static boolean ask(GpuWorldgen wg, ChunkAccess chunk, StructureManager structures, GpuTerrain terrain, GpuWorldgen.NoiseResult res) {
        SurfaceBatches batches = wg.surfaces;
        Ga2GpuChunk gc = Ga2GpuChunk.of(chunk);
        if (batches == null || terrain == null || gc == null || !allowed() || structures == null) {
            return false;
        }
        LevelAccessor level = ((StructureManagerLevelAccessor) structures).ga2$level();
        if (!(level instanceof WorldGenRegion region)) {
            return false;
        }
        var spec = wg.surfaceSpec;
        if (!spec.current()) {
            return false;
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
                    return false;
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
                            if (b == null) {
                                return false;
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
        gc.ga2$setGpuSurface(new GpuSurfaceJob(wg, batches.submit(pos, res.storage(), res.meta(), tops, win, cpar), new java.util.concurrent.atomic.AtomicBoolean()));
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
    public static boolean apply(ChunkAccess chunk, java.util.function.Consumer<ChunkAccess> cpuSurface) {
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
                throw new java.util.concurrent.TimeoutException();
            }
            r = job.future.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LATE.increment();
            // A late surface means the GPU is busy elsewhere. The governor counts it with the late blocks and biomes.
            job.wg.answered(true);
            if (job.wg.decor != null) {
                job.wg.decor.drop(chunk.getPos());
            }
            return false;
        } catch (Exception e) {
            GpuSteps.GPU_ERRORS.increment();
            if (job.wg.decor != null) {
                job.wg.decor.drop(chunk.getPos());
            }
            return false;
        }
        if (r == null || r.cpu()) {
            CPU.increment();
            if (job.wg.decor != null) {
                job.wg.decor.drop(chunk.getPos());
            }
            return false;
        }
        Runnable compare = GpuVerify.surfaceBefore(chunk, cpuSurface);
        if (!Apply.surface(job.wg, chunk, r)) {
            CPU.increment();
            if (job.wg.decor != null) {
                job.wg.decor.drop(chunk.getPos());
            }
            return false;
        }
        APPLIED.increment();
        if (job.wg.decor != null) {
            // The GPU keeps the blocks it built. Every later write is logged and sent.
            job.wg.decor.track(chunk);
        }
        if (compare != null) {
            compare.run();
        }
        return true;
    }
}
