package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWriter;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.PalettedContainerInvoker;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.function.Supplier;

/**
 * The CPU's part once the GPU has decided a chunk. It rebuilds what GA2's noise fill ({@code FastFill} with {@code SectionWriter})
 * and biome step leave in the chunk, from the GPU's packed sections. Each section gets vanilla's palette (air, then each state in
 * the order the fill first writes it), the GPU's 4-bit storage, and counters from the GPU's histogram. It also writes the fluid
 * update marks in the fill's order and the two worldgen heightmaps. The biomes go through the chunk's own
 * {@code fillBiomesFromNoise}.
 */
public final class Apply {
    private Apply() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final HookTargets.Hook SECTION_WRITES = HookTargets.hook(HookTargets.NOISE_SECTION_WRITES);

    /**
     * Whether {@code chunk}'s layout is one the program was made for. Vanilla clamps the noise settings to the chunk
     * ({@code NoiseSettings.clampToHeightAccessor}). The clamped range must equal the program's range exactly, on section boundaries.
     * A chunk may be taller, as in the Nether and the End (256 blocks, noise in 0..128). Its extra sections then stay air, and its
     * extra biomes come from the CPU.
     */
    public static boolean fits(GpuWorldgen wg, ChunkAccess chunk) {
        LevelHeightAccessor h = chunk.getHeightAccessorForGeneration();
        int lo = Math.max(wg.minY, h.getMinBuildHeight()), hi = Math.min(wg.minY + wg.height, h.getMaxBuildHeight());
        return lo == wg.minY && hi == wg.minY + wg.height && chunk.getMinBuildHeight() == h.getMinBuildHeight() && chunk.getHeight() == h.getHeight()
            && Math.floorMod(wg.minY - chunk.getMinBuildHeight(), 16) == 0;
    }

    /** The chunk's section index of the program's first section. */
    static int base(GpuWorldgen wg, ChunkAccess chunk) {
        return (wg.minY - chunk.getMinBuildHeight()) >> 4;
    }

    /**
     * Writes the GPU's blocks into a fresh chunk. Returns false, with nothing written, when the chunk is not fresh, or when GA2's own
     * fill would write block by block (another mod changes section writes). The CPU path then runs instead.
     */
    public static boolean noise(GpuWorldgen wg, ChunkAccess chunk, GpuWorldgen.NoiseResult r) {
        if (!SectionWriter.ENABLED || !SECTION_WRITES.allowed() || !FillHeights.ENABLED || !fits(wg, chunk)) {
            return false;
        }
        LevelChunkSection[] secs = chunk.getSections();
        int nsec = wg.height / 16, base = base(wg, chunk);
        if (secs.length < base + nsec) {
            return false;
        }
        for (LevelChunkSection section : secs) {
            if (section.getStates().data.palette().maybeHas(s -> s != AIR)) {
                return false;
            }
        }
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        if (!unset(ocean) || !unset(surface)) {
            return false;
        }
        BlockState[] states = wg.states;
        int[] meta = r.meta();
        for (LevelChunkSection section : secs) {
            section.acquire();
        }
        try {
            for (int sec = 0; sec < nsec; sec++) {
                int m = sec * ClEmitter.META;
                int psize = meta[m] & 0xff;
                if (psize == 0) {
                    continue; // Air only. The section stays as it is.
                }
                if (psize > 16) {
                    throw new IllegalStateException("GPU section palette of " + psize + " entries");
                }
                PalettedContainer<BlockState> container = secs[base + sec].getStates();
                @SuppressWarnings("unchecked")
                PalettedContainer.Data<BlockState> data = ((PalettedContainerInvoker) (Object) container).ga2$createOrReuseData(null, Mth.ceillog2(psize));
                Palette<BlockState> palette = data.palette();
                palette.idFor(AIR);
                for (int a = 1; a < psize; a++) {
                    palette.idFor(states[meta[m + a]]);
                }
                if (palette.getSize() != psize || !(data.storage() instanceof SimpleBitStorage) || data.storage().getBits() != 4 || data.storage().getRaw().length != 256) {
                    throw new IllegalStateException("unexpected section layout: palette " + palette.getSize() + " of " + psize + ", bits " + data.storage().getBits());
                }
                System.arraycopy(r.storage()[sec], 0, data.storage().getRaw(), 0, 256);
                container.data = data;
                int nonEmpty = 0, ticking = 0, fluids = 0;
                for (int id = 1; id < ClEmitter.ID_COUNT; id++) {
                    int c = meta[m + 16 + id];
                    if (c == 0) {
                        continue;
                    }
                    BlockState state = states[id];
                    if (!PlatformHooks.isEmpty(state)) {
                        nonEmpty += c;
                        if (state.isRandomlyTicking()) {
                            ticking += c;
                        }
                    }
                    if (!state.getFluidState().isEmpty()) {
                        fluids += c;
                    }
                }
                LevelChunkSection target = secs[base + sec];
                target.nonEmptyBlockCount = (short) (target.nonEmptyBlockCount + nonEmpty);
                target.tickingBlockCount = (short) (target.tickingBlockCount + ticking);
                target.tickingFluidCount = (short) (target.tickingFluidCount + fluids);
            }
        } finally {
            for (LevelChunkSection section : secs) {
                section.release();
            }
        }
        marks(wg, chunk, r.marks());
        FillHeights.Ga2Heightmap o = (FillHeights.Ga2Heightmap) (Object) ocean;
        FillHeights.Ga2Heightmap s = (FillHeights.Ga2Heightmap) (Object) surface;
        int[] tops = r.tops();
        for (int col = 0; col < 256; col++) {
            int top = tops[col * 2];
            int floor = tops[col * 2 + 1];
            if (top != Integer.MIN_VALUE) {
                s.ga2$setHeight(col & 15, col >> 4, top + 1);
            }
            if (floor != Integer.MIN_VALUE) {
                o.ga2$setHeight(col & 15, col >> 4, floor + 1);
            }
        }
        return true;
    }

    /**
     * Writes the GPU's surface into a chunk the GPU filled. This is the chunk's SURFACE step, replacing GA2's surface builder. Each
     * changed section gets its palette as vanilla's writes leave it, plus its storage and counters. The fluid marks are written in the
     * surface's order (columns x then z, y down), then the two worldgen heightmaps. Returns false, with nothing written, when a section
     * cannot be written as the GPU packed it.
     */
    public static boolean surface(GpuWorldgen wg, ChunkAccess chunk, dev.denismasterherobrine.generatoraccelerator2.gpu.surface.SurfaceBatches.Result r) {
        if (!fits(wg, chunk)) {
            return false;
        }
        var spec = wg.surfaceSpec;
        LevelChunkSection[] secs = chunk.getSections();
        int nsec = wg.height / 16, base = base(wg, chunk);
        if (secs.length < nsec || base != 0) {
            // The surface builder walks the whole chunk. Only a chunk that starts where the program starts qualifies.
            return false;
        }
        for (int sec = nsec; sec < secs.length; sec++) {
            // A world taller than its noise (2032 World Height, Epic Terrain). Above the program, the chunk is still air at this step. The
            // surface builder starts each column at its top block, so it does nothing there. A block there (another mod's) leaves the
            // chunk to the CPU.
            if (!secs[sec].hasOnlyAir()) {
                return false;
            }
        }
        Heightmap world = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        // Build the sections' data as the GPU packed them before anything is written. Any mismatch leaves the chunk untouched.
        @SuppressWarnings("unchecked")
        PalettedContainer.Data<BlockState>[] built = new PalettedContainer.Data[nsec];
        for (int sec = 0; sec < nsec; sec++) {
            int[] pal = r.palettes()[sec];
            if (pal == null) {
                continue;
            }
            PalettedContainer<BlockState> container = secs[sec].getStates();
            @SuppressWarnings("unchecked")
            PalettedContainer.Data<BlockState> data = ((PalettedContainerInvoker) (Object) container).ga2$createOrReuseData(null, r.bits()[sec]);
            Palette<BlockState> palette = data.palette();
            for (int id : pal) {
                palette.idFor(spec.states.get(id));
            }
            if (palette.getSize() != pal.length || !(data.storage() instanceof SimpleBitStorage) || data.storage().getBits() != r.bits()[sec]
                || data.storage().getRaw().length != r.storage()[sec].length) {
                return false;
            }
            System.arraycopy(r.storage()[sec], 0, data.storage().getRaw(), 0, r.storage()[sec].length);
            built[sec] = data;
        }
        for (LevelChunkSection section : secs) {
            section.acquire();
        }
        try {
            for (int sec = 0; sec < nsec; sec++) {
                if (built[sec] == null) {
                    continue;
                }
                secs[sec].getStates().data = built[sec];
                int nonEmpty = 0, ticking = 0, fluids = 0;
                int[] pal = r.palettes()[sec], cnt = r.counts()[sec];
                for (int p = 0; p < pal.length; p++) {
                    int c = cnt[p];
                    if (c == 0) {
                        continue;
                    }
                    BlockState state = spec.states.get(pal[p]);
                    if (!PlatformHooks.isEmpty(state)) {
                        nonEmpty += c;
                        if (state.isRandomlyTicking()) {
                            ticking += c;
                        }
                    }
                    if (!state.getFluidState().isEmpty()) {
                        fluids += c;
                    }
                }
                secs[sec].nonEmptyBlockCount = (short) nonEmpty;
                secs[sec].tickingBlockCount = (short) ticking;
                secs[sec].tickingFluidCount = (short) fluids;
            }
        } finally {
            for (LevelChunkSection section : secs) {
                section.release();
            }
        }
        ChunkPos cp = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sec = 0; sec < nsec; sec++) {
            long[] marks = r.marks()[sec];
            if (marks == null) {
                continue;
            }
            int y0 = wg.minY + sec * 16;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = 15; y >= 0; y--) {
                        int idx = y << 8 | lz << 4 | lx;
                        if ((marks[idx >> 6] >>> (idx & 63) & 1L) != 0L) {
                            chunk.markPosForPostprocessing(pos.set(cp.getMinBlockX() + lx, y0 + y, cp.getMinBlockZ() + lz));
                        }
                    }
                }
            }
        }
        FillHeights.Ga2Heightmap w = (FillHeights.Ga2Heightmap) (Object) world;
        FillHeights.Ga2Heightmap o = (FillHeights.Ga2Heightmap) (Object) ocean;
        int[] h = r.heights();
        for (int col = 0; col < 256; col++) {
            // the GPU's columns are x * 16 + z
            w.ga2$setHeight(col >> 4, col & 15, h[col * 2]);
            o.ga2$setHeight(col >> 4, col & 15, h[col * 2 + 1]);
        }
        return true;
    }

    /** The fluid update marks, per section in {@code doFill}'s order: cell x, cell z, y descending, then x and z in the cell. */
    private static void marks(GpuWorldgen wg, ChunkAccess chunk, long[][] sections) {
        ChunkPos cp = chunk.getPos();
        int cw = wg.cellWidth;
        int per = 16 / cw;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sec = 0; sec < wg.height / 16; sec++) {
            long[] marks = sections[sec];
            if (marks == null) {
                continue;
            }
            int mk = 0;
            int y0 = wg.minY + sec * 16;
            for (int cellX = 0; cellX < per; cellX++) {
                for (int cellZ = 0; cellZ < per; cellZ++) {
                    for (int y = 15; y >= 0; y--) {
                        for (int ix = 0; ix < cw; ix++) {
                            for (int iz = 0; iz < cw; iz++) {
                                int lx = cellX * cw + ix, lz = cellZ * cw + iz;
                                int idx = y << 8 | lz << 4 | lx;
                                if ((marks[mk + (idx >> 6)] >>> (idx & 63) & 1L) != 0L) {
                                    chunk.markPosForPostprocessing(pos.set(cp.getMinBlockX() + lx, y0 + y, cp.getMinBlockZ() + lz));
                                }
                            }
                        }
                    }
                }
            }
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

    /**
     * The chunk's biomes from the GPU's answers, written in the fill's order. After each quart, the searching thread's last result is
     * left as vanilla's fill leaves it. Before the first quart it is the thread's own last result. After the fill it is the last
     * quart's leaf. A quart nearest to a single leaf gets that leaf, whatever the last result was. A tie means several leaves at the
     * same distance. Vanilla's answer is then the last result if it is one of them, else the first leaf its walk meets. A tie is
     * searched as vanilla searches it, from the previous quart's leaf, at the GPU's climate point. When the GPU could not list the
     * tie, it is searched through the chunk's own climate sampler ({@code tieSampler}, created only then). Returns false, with
     * nothing written, when the chunk's layout is not the program's.
     *
     * <p>With TerraBlender's regions, each tree keeps its own last result. A quart sets its region tree's last result. After a
     * placeholder it also sets region 0's. A tie goes through TerraBlender's own lookup from those last results.
     */
    public static boolean biomes(GpuWorldgen wg, ChunkAccess chunk, GpuWorldgen.BiomeResult r, Supplier<Climate.Sampler> tieSampler) {
        if (!fits(wg, chunk)) {
            return false;
        }
        ChunkPos cp = chunk.getPos();
        int qx0 = cp.x * 4, qz0 = cp.z * 4, qy0 = wg.minY >> 2, qyn = wg.height >> 2;
        int[] quarts = r.quarts();
        // A chunk taller than the program has quarts the GPU did not decide. The CPU's climate sampler answers those.
        boolean taller = chunk.getHeight() != wg.height;
        // Lithostitched's injectors run on each quart's base biome, with the GPU's climate point. A point the GPU could not give is
        // sampled on the CPU
        BiomeSource injector = wg.biomeInjector();
        boolean missingPoints = injector != null && missingTargets(r);
        Climate.Sampler sampler = taller || !r.complete() && r.ties() > 0 || missingPoints ? tieSampler.get() : null;
        // every tree's last result. A search sets the last result of the tree it searched. With TerraBlender's regions, a quart searches
        // its region's tree, then region 0's tree after a placeholder
        Climate.RTree.Leaf<Holder<Biome>>[] last = wg.lastResults();
        int[] fallbacks = r.fallbacks();
        BiomeResolver resolver = (x, y, z, s) -> {
            if (y < qy0 || y >= qy0 + qyn) {
                wg.setLastResults(last);
                Holder<Biome> biome = injector != null ? injector.getNoiseBiome(x, y, z, s) : wg.findBiome(x, y, z, s);
                wg.readLastResults(last);
                return biome;
            }
            int i = (y - qy0) * 16 + (x - qx0) * 4 + (z - qz0);
            int v = quarts[i];
            Holder<Biome> base;
            Climate.TargetPoint point = null;
            if (v >= 0) {
                Climate.RTree.Leaf<Holder<Biome>> leaf = wg.leaf(v);
                last[wg.treeOf(v)] = leaf;
                if (fallbacks != null && wg.deferred(v)) {
                    leaf = wg.leaf(fallbacks[i]);
                    last[wg.treeOf(fallbacks[i])] = leaf;
                }
                base = leaf.value;
                if (injector != null) {
                    point = wg.target(r, i);
                }
            } else {
                wg.setLastResults(last);
                long[] t = r.target(i);
                if (t != null) {
                    point = new Climate.TargetPoint(t[0], t[1], t[2], t[3], t[4], t[5]);
                    base = wg.findBiome(point, x, y, z);
                } else {
                    base = wg.findBiome(x, y, z, s);
                }
                wg.readLastResults(last);
            }
            if (injector == null) {
                return base;
            }
            return dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes.inject(injector, x, y, z, s,
                point != null ? point : s.sample(x, y, z), base);
        };
        chunk.fillBiomesFromNoise(resolver, sampler);
        wg.setLastResults(last);
        return true;
    }

    /** Whether some climate point of {@code r} did not fit an int, or some tie was not listed with its point. */
    private static boolean missingTargets(GpuWorldgen.BiomeResult r) {
        if (!r.complete() && r.ties() > 0) {
            return true;
        }
        for (int v : r.targets()) {
            if (v == Integer.MIN_VALUE) {
                return true;
            }
        }
        return false;
    }

    /** {@code Holder} of a quart's answer, for tests. For a tie, it is the leaf the GPU's walk found. */
    static Holder<Biome> biome(GpuWorldgen wg, int v) {
        return wg.leaf(v & 0x7fffffff).value;
    }
}
