package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkBlocks;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuTerrain;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceSystemAccessor;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.DeepPlan;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.slf4j.Logger;

/**
 * {@code SurfaceSystem.buildSurface} with the same column loop, the rules evaluated by a compiled
 * {@link SurfaceProgram}, biome lookups through {@link BiomeZoom} and blocks through
 * {@link ChunkBlocks}.
 *
 * <p>Per column, as vanilla: the biome at the top block, the eroded badlands extension, the walk from
 * the top down keeping the stone-depth and water counters, the rules applied to every default block,
 * and the frozen ocean extension. Below the column's preliminary surface the walk switches to the
 * {@link DeepPlan} once only folded results remain. Rules of other mods run through vanilla rule
 * objects built against a vanilla {@code Context} that is updated exactly like vanilla's.
 */
public final class SurfaceBuilder {
    private SurfaceBuilder() {}

    private static final ConcurrentMap<SurfaceRules.RuleSource, SurfaceProgram> PROGRAMS = new MapMaker().weakKeys().makeMap();
    private static final Logger LOGGER = Ga2Log.get("Surface");
    private static final Set<String> LOGGED_OPAQUE = ConcurrentHashMap.newKeySet();

    /**
     * Programs by the rule's shape ({@link Shape}), for a rule asked as a new object each time. Citadel (Alex's Caves, Alex's
     * Mobs) merges its rules into the surface rule on every call, so each chunk came with a new sequence of the same rules. Each
     * chunk then lowered and compiled them again, into classes the JIT never compiled: 3,086 such classes ran in one 49 s
     * square of a 575-mod pack, and the surface step took half of the workers' time. Cleared when the server stops.
     */
    private static final ConcurrentMap<Shape, SurfaceProgram> BY_SHAPE = new ConcurrentHashMap<>();
    /** Programs found by their shape after the rule object itself missed. */
    public static final LongAdder SHAPE_HITS = new LongAdder();

    /**
     * A sequence rule by its parts: a nested sequence by its own shape, any other rule by identity. Two sequences of the same
     * rule objects in the same order are the same rule, so they share one program.
     */
    static final class Shape {
        private final Object[] parts;
        private final int hash;

        private Shape(Object[] parts) {
            this.parts = parts;
            int h = 1;
            for (Object p : parts) {
                h = 31 * h + (p instanceof Shape s ? s.hash : System.identityHashCode(p));
            }
            this.hash = h;
        }

        /** The shape of a sequence rule, or null for any other rule (whose identity is its key). */
        static Shape of(SurfaceRules.RuleSource source) {
            if (!(source instanceof SurfaceRules.SequenceRuleSource seq)) {
                return null;
            }
            List<SurfaceRules.RuleSource> rules = seq.sequence();
            Object[] parts = new Object[rules.size()];
            for (int i = 0; i < parts.length; i++) {
                SurfaceRules.RuleSource r = rules.get(i);
                Shape inner = of(r);
                parts[i] = inner != null ? inner : r;
            }
            return new Shape(parts);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Shape other) || other.hash != hash || other.parts.length != parts.length) {
                return false;
            }
            for (int i = 0; i < parts.length; i++) {
                Object a = parts[i], b = other.parts[i];
                if (a instanceof Shape s ? !s.equals(b) : a != b) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** The server stopped: the shapes hold the rules of its settings. */
    public static void forgetShapes() {
        BY_SHAPE.clear();
    }

    public static SurfaceProgram program(SurfaceRules.RuleSource source) {
        SurfaceProgram program = PROGRAMS.get(source);
        if (program == null) {
            Shape shape = Shape.of(source);
            if (shape != null) {
                program = BY_SHAPE.get(shape);
                if (program != null) {
                    // a new sequence of known rules: not put under its own object, which is gone after this chunk
                    SHAPE_HITS.increment();
                    return program;
                }
            }
            program = SurfaceProgram.lower(source);
            if (program.hasOpaque) {
                Set<String> kinds = new TreeSet<>();
                for (Object o : program.opaques) {
                    kinds.add(o.getClass().getName());
                }
                // once per kind of source and set of types (mods may build a few equal sources while worldgen starts)
                if (LOGGED_OPAQUE.add(source.getClass().getName() + kinds)) {
                    LOGGER.info("rules {}: uncompiled rule types run as objects: {}",
                        source.getClass().getSimpleName(), kinds);
                }
            }
            if (program.root instanceof Ir.OpaqueRule) {
                // nothing to compile (and the program would hold its own weak key, so the entry could never go)
                return program;
            }
            SurfaceProgram raced = PROGRAMS.putIfAbsent(source, program);
            if (raced != null) {
                program = raced;
            }
            if (shape != null) {
                raced = BY_SHAPE.putIfAbsent(shape, program);
                if (raced != null) {
                    program = raced;
                }
            }
        }
        return program;
    }

    /** @return false when vanilla must build this surface (unsupported chunk, hooks off) */
    public static boolean build(SurfaceSystem system, RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomeRegistry, boolean legacy,
                                WorldGenerationContext genContext, ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource) {
        if (!SurfaceHooks.enabled() || system.getClass() != SurfaceSystem.class || chunk.getClass() != ProtoChunk.class || chunk.isUpgrading()
            || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE_WG)) {
            SurfaceHooks.CHUNKS_VANILLA.increment();
            return false;
        }
        ChunkBlocks blocks = ChunkBlocks.of(chunk);
        if (!blocks.fast()) {
            SurfaceHooks.CHUNKS_VANILLA.increment();
            return false;
        }
        SurfaceProgram program = program(ruleSource);
        if (program.root instanceof Ir.OpaqueRule || !program.guardsHold()) {
            // another mod's rule type at the top (vanilla runs it with less overhead than GA2's shadow context), or a
            // mod's config switch the lowering assumed has changed since
            SurfaceHooks.CHUNKS_VANILLA.increment();
            return false;
        }
        // other mods' code in vanilla's column loop (their mixins on buildSurface, run through compat/<modid>)
        ColumnHooks.Resolved hooks = ColumnHooks.resolved();
        if (hooks != null && !hooks.valid()) {
            SurfaceHooks.CHUNKS_VANILLA.increment();
            return false;
        }
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BiomeZoom zoom = BiomeZoom.forChunk(biomeManager, minX, minZ, chunk.getMinBuildHeight(), chunk.getMaxBuildHeight());
        try {
            SurfaceEval eval = new SurfaceEval(program, system, randomState, genContext, worldSurface, zoom);
            SurfaceRules.Context shadow = null;
            if (program.hasOpaque) {
                shadow = new SurfaceRules.Context(system, randomState, chunk, noiseChunk, biomeManager::getBiome, biomeRegistry, genContext);
                instantiateOpaques(program, eval, shadow);
                SurfaceHooks.CHUNKS_OPAQUE.increment();
            } else if (hooks == null || hooks.columnLocal()) {
                blocks.lock();
            }
            ColumnHooks.Frame frame = null;
            if (hooks != null) {
                frame = new ColumnHooks.Frame();
                frame.system = system;
                frame.randomState = randomState;
                frame.biomeManager = biomeManager;
                frame.biomes = biomeRegistry;
                frame.legacy = legacy;
                frame.context = genContext;
                frame.chunk = chunk;
                frame.noiseChunk = noiseChunk;
                frame.rule = ruleSource;
                if (hooks.needsRuleContext()) {
                    frame.ruleContext = shadow != null ? shadow
                        : new SurfaceRules.Context(system, randomState, chunk, noiseChunk, biomeManager::getBiome, biomeRegistry, genContext);
                }
                SurfaceHooks.CHUNKS_HOOKED.increment();
            }
            DeepPlan deep = SurfaceConfig.deepBands() ? program.deepPlan(chunk.getMinBuildHeight(), chunk.getMaxBuildHeight(), eval.gradLo, eval.gradHi, eval.anchorY)
                : null;
            run((SurfaceSystemAccessor) system, program, eval, shadow, hooks, frame, deep, blocks, chunk, noiseChunk, worldSurface, zoom, legacy, minX, minZ);
        } finally {
            blocks.unlock();
            zoom.release();
        }
        SurfaceHooks.CHUNKS_FAST.increment();
        return true;
    }

    /** Applies the opaque sources to the vanilla context in the program's (= vanilla's) apply order. */
    private static void instantiateOpaques(SurfaceProgram program, SurfaceEval eval, SurfaceRules.Context shadow) {
        int n = program.opaques.size();
        eval.opaqueRules = new SurfaceRules.SurfaceRule[n];
        eval.opaqueConds = new SurfaceRules.Condition[n];
        for (int i = 0; i < n; i++) {
            Object source = program.opaques.get(i);
            if (source instanceof SurfaceRules.RuleSource rule) {
                eval.opaqueRules[i] = rule.apply(shadow);
            } else {
                eval.opaqueConds[i] = ((SurfaceRules.ConditionSource) source).apply(shadow);
            }
        }
    }

    private static void run(SurfaceSystemAccessor system, SurfaceProgram program, SurfaceEval eval, SurfaceRules.Context shadow, ColumnHooks.Resolved hooks,
                            ColumnHooks.Frame frame, DeepPlan deep, ChunkBlocks blocks, ChunkAccess chunk, NoiseChunk noiseChunk, Heightmap worldSurface,
                            BiomeZoom zoom, boolean legacy, int minX, int minZ) {
        // vanilla's rule context for the hooks that read it, when it is not the shadow context (which is updated anyway)
        SurfaceRules.Context hookContext = frame != null && frame.ruleContext != shadow ? frame.ruleContext : null;
        BlockState defaultBlock = system.ga2$defaultBlock();
        boolean deepOk = deep != null && !defaultBlock.isAir() && defaultBlock.getFluidState().isEmpty();
        Column column = new Column(blocks, chunk.getHeightAccessorForGeneration().getMinBuildHeight(), chunk.getHeightAccessorForGeneration().getMaxBuildHeight());
        BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
        int minBuild = chunk.getMinBuildHeight();
        // Context.getMinSurfaceLevel: the four corners of the 16x16 surface cell, which is this chunk
        int cellX = minX >> 4;
        int cellZ = minZ >> 4;
        // without a NoiseChunk (a chunk the GPU filled), the GPU's levels, computed as the NoiseChunk computes them
        GpuTerrain terrain = noiseChunk == null ? GpuTerrain.of(chunk) : null;
        int p00 = terrain != null ? terrain.preliminarySurfaceLevel(cellX << 4, cellZ << 4) : noiseChunk.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
        int p10 = terrain != null ? terrain.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4) : noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
        int p01 = terrain != null ? terrain.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4) : noiseChunk.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
        int p11 = terrain != null ? terrain.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4) : noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
        SurfaceProgram.Evaluator evaluator = SurfaceConfig.compile() ? program.compiled() : program.interpreter;
        ProgramPick pick = SurfaceConfig.compile() && SurfaceConfig.biomePrograms() && SurfaceHooks.zoomAllowed() ? new ProgramPick(program) : null;
        Deferred deferred = deepOk && blocks.locked() && SurfaceConfig.sectionBulk() ? new Deferred(deep, blocks, defaultBlock) : null;
        int deepColumns = 0;
        if (hooks != null) {
            frame.biomePos = biomePos;
            hooks.start(frame);
        }
        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                int x = minX + k;
                int z = minZ + l;
                int k1 = worldSurface.getFirstAvailable(k, l);
                column.at(k, l, x, z);
                Holder<Biome> holder = zoom.biome(x, legacy ? 0 : k1, z);
                biomePos.set(x, legacy ? 0 : k1, z);
                boolean erodedBadlands;
                if (hooks != null) {
                    frame.column = column;
                    frame.biome = holder;
                    frame.localX = k;
                    frame.localZ = l;
                    frame.x = x;
                    frame.z = z;
                    frame.top = k1;
                    erodedBadlands = hooks.top(frame, Biomes.ERODED_BADLANDS);
                } else {
                    erodedBadlands = holder.is(Biomes.ERODED_BADLANDS);
                }
                if (erodedBadlands) {
                    system.ga2$erodedBadlandsExtension(column, x, z, k1, chunk);
                }
                if (hooks != null) {
                    hooks.beforeRules(frame);
                }
                // vanilla reads the heightmap again: the eroded badlands extension (or another mod's hook) may have raised the column
                int l1 = erodedBadlands || hooks != null ? worldSurface.getFirstAvailable(k, l) : k1;
                eval.updateXZ(x, z);
                if (shadow != null) {
                    shadow.updateXZ(x, z);
                }
                if (hookContext != null) {
                    hookContext.updateXZ(x, z);
                }
                int surfaceLevel = Mth.floor(Mth.lerp2((double) ((float) (x & 15) / 16.0F), (double) ((float) (z & 15) / 16.0F), (double) p00, (double) p10,
                    (double) p01, (double) p11));
                eval.minSurface = surfaceLevel + eval.surfaceDepth - 8;
                int i2 = 0;
                int j2 = Integer.MIN_VALUE;
                int k2 = Integer.MAX_VALUE;
                BlockState lastState = null;
                int lastKind = 0;
                eval.column(blocks, k, l, minBuild);
                for (int i3 = l1; i3 >= minBuild; i3--) {
                    if (deepOk && i3 < eval.minSurface && deep.okAtOrBelow(i3)) {
                        deepColumn(deep, eval, evaluator, pick, blocks, column, defaultBlock, deferred, k, l, i3, minBuild);
                        deepColumns++;
                        break;
                    }
                    BlockState state = blocks.get(k, i3, l);
                    // air / fluid / solid is a property of the state. Runs of one state are the rule
                    if (state != lastState) {
                        lastState = state;
                        lastKind = state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : 2;
                    }
                    if (lastKind == 0) {
                        i2 = 0;
                        j2 = Integer.MIN_VALUE;
                    } else if (lastKind == 1) {
                        if (j2 == Integer.MIN_VALUE) {
                            j2 = i3 + 1;
                        }
                    } else {
                        i2++;
                        if (shadow == null) {
                            // stone depth below is scanned for lazily (SurfaceEval.stoneDepthBelow)
                            eval.updateY(i2, j2, i3);
                        } else {
                            if (k2 >= i3) {
                                k2 = DimensionType.WAY_BELOW_MIN_Y;
                                for (int j3 = i3 - 1; j3 >= minBuild - 1; j3--) {
                                    BlockState below = blocks.get(k, j3, l);
                                    if (below.isAir() || !below.getFluidState().isEmpty()) {
                                        k2 = j3 + 1;
                                        break;
                                    }
                                }
                            }
                            int k3 = i3 - k2 + 1;
                            eval.updateY(i2, k3, j2, i3);
                            shadow.updateY(i2, k3, j2, x, i3, z);
                        }
                        if (state == defaultBlock) {
                            BlockState result = pick != null ? pick.forBlock(eval).apply(eval) : evaluator.apply(eval);
                            if (result != null) {
                                column.set(i3, result, result == state);
                            }
                        }
                    }
                }
                boolean frozenOcean = hooks != null ? hooks.frozen(frame, Biomes.FROZEN_OCEAN) : holder.is(Biomes.FROZEN_OCEAN);
                if (frozenOcean || holder.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    system.ga2$frozenOceanExtension(eval.minSurface, holder.value(), column, biomePos, x, z, k1);
                }
            }
        }
        if (deferred != null) {
            deferred.finish(column, minX, minZ);
        }
        if (hooks != null) {
            hooks.end(frame);
        }
        SurfaceHooks.COLUMNS_DEEP.add(deepColumns);
    }

    /**
     * The rest of a column below its preliminary surface. Only default blocks change, by the folded
     * plan. Whole sections whose plan is a counter-neutral constant may be deferred (see {@link Deferred}).
     */
    private static void deepColumn(DeepPlan deep, SurfaceEval eval, SurfaceProgram.Evaluator evaluator, ProgramPick pick, ChunkBlocks blocks, Column column,
                                   BlockState defaultBlock, Deferred deferred, int k, int l, int from, int minBuild) {
        int y = from;
        while (y >= minBuild) {
            int next = deep.activeAtOrBelow(y);
            if (next != y) {
                // nothing between next and y can change: skip to the next block the plan may write
                y = next;
                continue;
            }
            if (deferred != null && (y & 15) == 15 && deferred.defer(k, l, y - 15)) {
                y -= 16;
                continue;
            }
            if (blocks.get(k, y, l) == defaultBlock) {
                BlockState result;
                if (deep.kind(y) == DeepPlan.CONST) {
                    result = deep.state(y);
                } else {
                    eval.moveY(y);
                    result = pick != null ? pick.forBlock(eval).apply(eval) : evaluator.apply(eval);
                }
                if (result != null) {
                    column.set(y, result, result == defaultBlock);
                }
            }
            y--;
        }
    }

    /**
     * The program for the block's biome ({@code SurfaceProgram.forBiome}). Blocks next to each other mostly share a biome, so
     * the last pick is kept. Asking the biome here is what the rules' first biome condition would do. The zoom keeps the answer.
     */
    static final class ProgramPick {
        private final SurfaceProgram program;
        private Holder<Biome> biome;
        private SurfaceProgram.Evaluator evaluator;

        ProgramPick(SurfaceProgram program) {
            this.program = program;
        }

        SurfaceProgram.Evaluator forBlock(SurfaceEval eval) {
            Holder<Biome> b = eval.biome();
            if (b != biome) {
                evaluator = program.forBiome(b);
                biome = b;
            }
            return evaluator;
        }
    }

    /**
     * Deep sections postponed until every column is done. A column may postpone its part of a section
     * when the section's plan is a counter-neutral constant without fluids and every block of the part
     * lies at least two below the column's heightmap tops. Writing those blocks then changes nothing that
     * any other part of the surface build reads. Other columns read only their own blocks and the
     * heightmaps, and later writes to this column only raise its heightmap tops. A section postponed by
     * all 256 columns is rewritten in one pass over its storage. Otherwise the postponed column parts are
     * written block by block.
     */
    static final class Deferred {
        private final DeepPlan deep;
        private final ChunkBlocks blocks;
        private final BlockState defaultBlock;
        private final boolean[] eligible;
        private final int[] count;
        private final long[][] columns;

        Deferred(DeepPlan deep, ChunkBlocks blocks, BlockState defaultBlock) {
            this.deep = deep;
            this.blocks = blocks;
            this.defaultBlock = defaultBlock;
            int n = blocks.sectionCount();
            this.eligible = new boolean[n];
            this.count = new int[n];
            this.columns = new long[n][4];
            for (int s = 0; s < n; s++) {
                eligible[s] = deep.bulkSection((blocks.minSection() + s) << 4, defaultBlock);
            }
        }

        boolean defer(int k, int l, int sectionMinY) {
            int s = (sectionMinY >> 4) - blocks.minSection();
            if (s < 0 || !eligible[s] || !blocks.heightmapsIgnore(k, l, sectionMinY + 15)) {
                return false;
            }
            int bit = k * 16 + l;
            columns[s][bit >> 6] |= 1L << (bit & 63);
            count[s]++;
            return true;
        }

        void finish(Column column, int minX, int minZ) {
            for (int s = 0; s < count.length; s++) {
                if (count[s] == 0) {
                    continue;
                }
                int sectionMinY = (blocks.minSection() + s) << 4;
                if (count[s] == 256) {
                    BlockState[] targets = new BlockState[16];
                    for (int ly = 0; ly < 16; ly++) {
                        targets[ly] = deep.state(sectionMinY + ly);
                    }
                    blocks.rewriteSection(sectionMinY, defaultBlock, targets);
                    SurfaceHooks.SECTIONS_BULK.increment();
                    continue;
                }
                for (int bit = 0; bit < 256; bit++) {
                    if ((columns[s][bit >> 6] & (1L << (bit & 63))) == 0) {
                        continue;
                    }
                    int k = bit >> 4;
                    int l = bit & 15;
                    column.at(k, l, minX + k, minZ + l);
                    for (int y = sectionMinY + 15; y >= sectionMinY; y--) {
                        BlockState result = deep.state(y);
                        if (result != null && blocks.get(k, y, l) == defaultBlock) {
                            column.set(y, result, result == defaultBlock);
                        }
                    }
                }
            }
        }
    }

    /** {@code SurfaceSystem.buildSurface}'s block column over {@link ChunkBlocks}. */
    static final class Column implements BlockColumn {
        private final ChunkBlocks blocks;
        private final int minHeight;
        private final int maxHeight;
        private int lx;
        private int lz;
        private int x;
        private int z;

        Column(ChunkBlocks blocks, int minHeight, int maxHeight) {
            this.blocks = blocks;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        void at(int lx, int lz, int x, int z) {
            this.lx = lx;
            this.lz = lz;
            this.x = x;
            this.z = z;
        }

        @Override
        public BlockState getBlock(int y) {
            return blocks.get(lx, y, lz);
        }

        @Override
        public void setBlock(int y, BlockState state) {
            set(y, state, false);
        }

        void set(int y, BlockState state, boolean same) {
            if (y >= minHeight && y < maxHeight) {
                if (same) {
                    blocks.setSame(lx, y, lz, state);
                } else {
                    blocks.set(lx, y, lz, state);
                }
                if (!state.getFluidState().isEmpty()) {
                    blocks.markForPostprocessing(x, y, z);
                }
            }
        }

        @Override
        public String toString() {
            return "Ga2ChunkBlockColumn " + x + "," + z;
        }
    }
}
