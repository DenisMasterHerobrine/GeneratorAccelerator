package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.Ga2Aquifer;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkBlocks;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.material.Fluids;

/**
 * The block work of one carver run on one target chunk: {@code WorldCarver.carveEllipsoid},
 * {@code carveBlock} (and {@code NetherWorldCarver.carveBlock}), {@code getCarveState}, the cave and
 * canyon skip tests and {@code canReach}, transcribed onto {@link ChunkBlocks}. Iteration order (x,
 * then z, then y downward), the carving mask's first-visitor rule, the per-column grass flag and the
 * top-material fix-up below grass are vanilla's, since the result depends on them.
 */
final class Carving {
    private static final BlockState LAVA = Fluids.LAVA.defaultFluidState().createLegacyBlock();
    private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();

    private final CarvingContext context;
    private final ChunkAccess chunk;
    private final ChunkBlocks blocks;
    private final Function<BlockPos, Holder<Biome>> biomes;
    private final Aquifer aquifer;
    /** The aquifer, when it answers by coordinates ({@link Ga2Aquifer#ga2$substanceAt}), else null. */
    private final Ga2Aquifer coordAquifer;
    private final CarvingMask mask;
    private final ChunkPos pos;
    private final int minX;
    private final int minZ;
    private final double midX;
    private final double midZ;
    private final int minGenY;
    private final int topClamp;
    private final BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
    /**
     * {@code SurfaceSystem.topMaterial}'s rule context and the surface rules built against it, made on the first top material of this
     * chunk ({@link #topMaterial}). Null until then.
     */
    private net.minecraft.world.level.levelgen.SurfaceRules.Context topContext;
    private net.minecraft.world.level.levelgen.SurfaceRules.SurfaceRule topRule;
    private static final dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.Hook TOP_MATERIAL =
        dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.hook(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.CARVER_TOP_MATERIAL);
    /** Top materials answered by the chunk's rules built once, and by vanilla's call (which builds them per call). */
    static final java.util.concurrent.atomic.LongAdder TOP_REUSED = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder TOP_VANILLA = new java.util.concurrent.atomic.LongAdder();

    // per carver
    private CarverConfiguration config;
    private boolean nether;
    private int lavaLevel;
    /** Last {@code state.is(config.replaceable)} answer: carving mostly meets runs of the same state. */
    private BlockState lastState;
    private boolean lastReplaceable;
    /**
     * The answers met with this carver, by the low bits of the state's registry id. A state taking another state's slot is
     * asked again.
     */
    private final BlockState[] knownStates = new BlockState[64];
    private final boolean[] knownReplaceable = new boolean[64];

    Carving(CarvingContext context, ChunkAccess chunk, ChunkBlocks blocks, Function<BlockPos, Holder<Biome>> biomes, Aquifer aquifer, CarvingMask mask) {
        this.context = context;
        this.chunk = chunk;
        this.blocks = blocks;
        this.biomes = biomes;
        this.aquifer = aquifer;
        this.coordAquifer = aquifer instanceof Ga2Aquifer f ? f : null;
        this.mask = mask;
        this.pos = chunk.getPos();
        this.minX = pos.getMinBlockX();
        this.minZ = pos.getMinBlockZ();
        this.midX = (double) pos.getMiddleBlockX();
        this.midZ = (double) pos.getMiddleBlockZ();
        this.minGenY = context.getMinGenY();
        this.topClamp = context.getMinGenY() + context.getGenDepth() - 1 - (chunk.isUpgrading() ? 0 : 7);
    }

    double midX() {
        return midX;
    }

    double midZ() {
        return midZ;
    }

    void carver(CarverConfiguration config, boolean nether) {
        if (this.config != config) {
            this.config = config;
            this.lastState = null;
            java.util.Arrays.fill(this.knownStates, null);
        }
        this.nether = nether;
        this.lavaLevel = config.lavaLevel.resolveY(context);
    }

    /** {@code state.is(config.replaceable)}. Block tags do not change while a chunk is carved. */
    private boolean replaceable(BlockState state) {
        if (state == lastState) {
            return lastReplaceable;
        }
        int slot = dev.denismasterherobrine.generatoraccelerator2.state.StateIndex.id(state) & 63;
        boolean r;
        if (knownStates[slot] == state) {
            r = knownReplaceable[slot];
        } else {
            r = state.is(config.replaceable);
            knownStates[slot] = state;
            knownReplaceable[slot] = r;
        }
        lastState = state;
        lastReplaceable = r;
        return r;
    }

    /** Replays a path: rooms carve unconditionally, tunnels stop at their first unreachable carving step. */
    void replay(CarverPath path) {
        for (CarverPath.Op op : path.ops) {
            if (op.misses(midX, midZ)) {
                continue;
            }
            if (op instanceof CarverPath.Room room) {
                ellipsoid(room.x, room.y, room.z, room.hr, room.vr, room.skip);
            } else {
                tunnel((CarverPath.Tunnel) op);
            }
        }
    }

    private void tunnel(CarverPath.Tunnel t) {
        for (int i = 0; i < t.n; i++) {
            if (!canReach(t.x[i], t.z[i], t.j[i], t.end, t.thickness)) {
                return;
            }
            ellipsoid(t.x[i], t.y[i], t.z[i], t.hr[i], t.vr[i], t.skip);
        }
        if (t.branchA != null) {
            if (!t.branchA.misses(midX, midZ)) {
                tunnel(t.branchA);
            }
            if (!t.branchB.misses(midX, midZ)) {
                tunnel(t.branchB);
            }
        }
    }

    /** {@code WorldCarver.canReach} for this chunk. */
    private boolean canReach(double x, double z, int branchIndex, int branchCount, float width) {
        double d2 = x - midX;
        double d3 = z - midZ;
        double d4 = (double) (branchCount - branchIndex);
        double d5 = (double) (width + 2.0F + 16.0F);
        return d2 * d2 + d3 * d3 - d4 * d4 <= d5 * d5;
    }

    /** {@code WorldCarver.carveEllipsoid}. */
    private void ellipsoid(double x, double y, double z, double hr, double vr, CarverPath.Skip skip) {
        double d2 = 16.0 + hr * 2.0;
        if (Math.abs(x - midX) > d2 || Math.abs(z - midZ) > d2) {
            return;
        }
        int k = Math.max(Mth.floor(x - hr) - minX - 1, 0);
        int l = Math.min(Mth.floor(x + hr) - minX, 15);
        int i1 = Math.max(Mth.floor(y - vr) - 1, minGenY + 1);
        int k1 = Math.min(Mth.floor(y + vr) + 1, topClamp);
        int l1 = Math.max(Mth.floor(z - hr) - minZ - 1, 0);
        int i2 = Math.min(Mth.floor(z + hr) - minZ, 15);
        for (int j2 = k; j2 <= l; j2++) {
            int k2 = minX + j2;
            double d3 = ((double) k2 + 0.5 - x) / hr;
            for (int l2 = l1; l2 <= i2; l2++) {
                int i3 = minZ + l2;
                double d4 = ((double) i3 + 0.5 - z) / hr;
                if (!(d3 * d3 + d4 * d4 >= 1.0)) {
                    boolean grass = false;
                    for (int j3 = k1; j3 > i1; j3--) {
                        double d5 = ((double) j3 - 0.5 - y) / vr;
                        if (!skip(skip, d3, d5, d4, j3) && !mask.get(j2, j3, l2)) {
                            mask.set(j2, j3, l2);
                            if (nether) {
                                netherBlock(j2, j3, l2);
                            } else {
                                grass = carveBlock(j2, k2, j3, l2, i3, grass);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean skip(CarverPath.Skip skip, double rx, double ry, double rz, int y) {
        if (skip.canyon) {
            // CanyonWorldCarver.shouldSkip
            int i = y - minGenY;
            return (rx * rx + rz * rz) * (double) skip.widths[i - 1] + ry * ry / 6.0 >= 1.0;
        }
        // CaveWorldCarver.shouldSkip
        return ry <= skip.floor ? true : rx * rx + ry * ry + rz * rz >= 1.0;
    }

    /**
     * {@code WorldCarver.carveBlock}. Returns the column's grass flag after this block. The flag is vanilla's
     * {@code MutableBoolean}. It stays set for the rest of the column within this ellipsoid.
     */
    private boolean carveBlock(int lx, int x, int y, int lz, int z, boolean grass) {
        BlockState state = blocks.get(lx, y, lz);
        if (CarveBlocks.surface(state)) {
            grass = true;
        }
        if (!replaceable(state)) {
            return grass;
        }
        BlockState carve = carveState(x, y, z);
        if (carve == null) {
            return grass;
        }
        blocks.set(lx, y, lz, carve);
        if (aquifer.shouldScheduleFluidUpdate() && !carve.getFluidState().isEmpty()) {
            blocks.markForPostprocessing(x, y, z);
        }
        if (grass) {
            if (CarveBlocks.dirt(blocks.get(lx, y - 1, lz))) {
                below.set(x, y - 1, z);
                Optional<BlockState> top = topMaterial(x, y - 1, z, !carve.getFluidState().isEmpty());
                if (top.isPresent()) {
                    BlockState s = top.get();
                    blocks.set(lx, y - 1, lz, s);
                    if (!s.getFluidState().isEmpty()) {
                        blocks.markForPostprocessing(x, y - 1, z);
                    }
                }
            }
        }
        return grass;
    }

    /**
     * {@code context.topMaterial(biomes, chunk, pos, fluid)}. Vanilla builds the whole surface rule tree for every call, other
     * mods' rule sources included. One of Oh The Biomes We've Gone's sources builds 256 sub-rules. Here the tree is built once per
     * chunk against one context. Every call updates that context the way vanilla updates its fresh context before asking the rule.
     * The surface step itself asks one built tree for every block of a chunk. So rules keep nothing between positions beyond what
     * the context's updates renew, and rule sources build the same tree whenever they are asked against a fresh context. Another
     * mod's change to either {@code topMaterial} keeps vanilla's call.
     */
    private Optional<BlockState> topMaterial(int x, int y, int z, boolean fluid) {
        if (!TOP_MATERIAL.allowed() || !(context instanceof dev.denismasterherobrine.generatoraccelerator2.mixin.carver.CarvingContextAccessor access)
            || context.getClass() != CarvingContext.class) {
            TOP_VANILLA.increment();
            return context.topMaterial(biomes, chunk, below.set(x, y, z), fluid);
        }
        if (topRule == null) {
            net.minecraft.world.level.levelgen.RandomState randomState = context.randomState();
            topContext = new net.minecraft.world.level.levelgen.SurfaceRules.Context(randomState.surfaceSystem(), randomState, chunk, access.ga2$noiseChunk(),
                biomes, context.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), context);
            topRule = access.ga2$surfaceRule().apply(topContext);
        }
        TOP_REUSED.increment();
        topContext.updateXZ(x, z);
        topContext.updateY(1, 1, fluid ? y + 1 : Integer.MIN_VALUE, x, y, z);
        return Optional.ofNullable(topRule.tryApply(x, y, z));
    }

    /** {@code WorldCarver.getCarveState} (debug mode is never simulated). */
    private BlockState carveState(int x, int y, int z) {
        if (y <= lavaLevel) {
            return LAVA;
        }
        Ga2Aquifer coords = coordAquifer;
        return coords != null ? coords.ga2$substanceAt(x, y, z, 0.0) : aquifer.computeSubstance(new DensityFunction.SinglePointContext(x, y, z), 0.0);
    }

    /** {@code NetherWorldCarver.carveBlock}. */
    private boolean netherBlock(int lx, int y, int lz) {
        if (replaceable(blocks.get(lx, y, lz))) {
            blocks.set(lx, y, lz, y <= minGenY + 31 ? LAVA : CAVE_AIR);
            return true;
        }
        return false;
    }
}
