package dev.denismasterherobrine.generatoraccelerator2.compat.environmental;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Environmental's pine barrens stone, run by GA2's surface builder where vanilla's loop runs it. Environmental's own handlers are
 * called with the values vanilla's loop holds there.
 *
 * <p>The column handler looks up the biome at the column's ocean floor with its own lookup, so it gets a position of its own instead
 * of vanilla's biome position. Before vanilla's lookup or after it is the same: it reads only the column's ocean floor height, which
 * nothing has changed yet. The end handler places the stone through vanilla's block column, the one that writes at vanilla's first
 * mutable position. It writes into any column, so the builder leaves the sections unlocked. The handlers keep the build's raises
 * in the surface system's fields, so each thread builds on its own system. Where the GPU builds the surface, the head and column
 * handlers run on the CPU before the GPU's blocks are written and the end handler right after: the stone goes on top of the surface
 * as after vanilla's loop, and it reads none of the surface's blocks.
 */
public final class EnvironmentalSurface {
    private EnvironmentalSurface() {}

    public static void register() {
        ColumnHooks.register(EnvironmentalMixins.SURFACE, EnvironmentalSurface::hooks);
        // where the GPU builds the surface, the raises are noted on the CPU before its blocks are written and the stone placed after
        CompatAdapters.register(HookTargets.GPU_SURFACE, EnvironmentalMixins.SURFACE, () -> ColumnHooks.ready(EnvironmentalMixins.SURFACE));
    }

    private static List<ColumnHooks.Hook> hooks() throws ReflectiveOperationException {
        // (buildSurface's arguments, CallbackInfo)
        MethodHandle head = ColumnHooks.handler(EnvironmentalMixins.HEAD, void.class, ColumnHooks.injectionParams());
        // (..., CallbackInfo, every local: MutableBlockPos, ChunkPos, int i, int j, BlockColumn, Context, SurfaceRule, MutableBlockPos, int k, l, i1, j1)
        MethodHandle column = ColumnHooks.handler(EnvironmentalMixins.COLUMN, void.class, ColumnHooks.injectionParams(BlockPos.MutableBlockPos.class,
            ChunkPos.class, int.class, int.class, BlockColumn.class, SurfaceRules.Context.class, SurfaceRules.SurfaceRule.class, BlockPos.MutableBlockPos.class,
            int.class, int.class, int.class, int.class));
        // (..., CallbackInfo, the locals up to the block column: MutableBlockPos, ChunkPos, int i, int j, BlockColumn)
        MethodHandle end = ColumnHooks.handler(EnvironmentalMixins.END, void.class,
            ColumnHooks.injectionParams(BlockPos.MutableBlockPos.class, ChunkPos.class, int.class, int.class, BlockColumn.class));
        return List.of(
            new Hook(EnvironmentalMixins.HEAD, ColumnHooks.Place.START, false) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    head.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci);
                }

                // its call sits before GA2's callback, so vanilla's head runs it already. It also clears the flag where the GPU builds
                // the surface and no head runs. A second clear changes nothing
                @Override
                public boolean setsUpShared() {
                    return true;
                }
            },
            new Hook(EnvironmentalMixins.COLUMN, ColumnHooks.Place.TOP, false) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    Locals l = locals(f);
                    // the handler reads neither the rule context nor the rule
                    column.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci, l.pos, l.chunkPos,
                        l.chunkPos.getMinBlockX(), l.chunkPos.getMinBlockZ(), l.column, (SurfaceRules.Context) null, (SurfaceRules.SurfaceRule) null, l.lookup,
                        f.localX, f.localZ, f.x, f.z);
                }
            },
            new Hook(EnvironmentalMixins.END, ColumnHooks.Place.END, true) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    Locals l = locals(f);
                    end.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci, l.pos, l.chunkPos,
                        l.chunkPos.getMinBlockX(), l.chunkPos.getMinBlockZ(), l.column);
                }
            });
    }

    /** The key of the build's locals in {@link ColumnHooks.Frame#shared}. */
    private static final Object LOCALS = new Object();

    /** Vanilla's first mutable position and the block column writing there, the chunk's position, and the column handler's lookup position. */
    private record Locals(BlockPos.MutableBlockPos pos, ChunkPos chunkPos, BlockColumn column, BlockPos.MutableBlockPos lookup) {}

    private static Locals locals(ColumnHooks.Frame f) {
        Object o = f.shared.get(LOCALS);
        if (o == null) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            o = new Locals(pos, f.chunk.getPos(), new PosColumn(f.chunk, pos), new BlockPos.MutableBlockPos());
            f.shared.put(LOCALS, o);
        }
        return (Locals) o;
    }

    /** Vanilla's block column of {@code buildSurface}: the chunk's blocks at {@code pos}'s x and z. */
    private record PosColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos) implements BlockColumn {
        @Override
        public BlockState getBlock(int y) {
            return chunk.getBlockState(pos.setY(y));
        }

        @Override
        public void setBlock(int y, BlockState state) {
            LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
            if (y >= height.getMinBuildHeight() && y < height.getMaxBuildHeight()) {
                chunk.setBlockState(pos.setY(y), state, false);
                if (!state.getFluidState().isEmpty()) {
                    chunk.markPosForPostprocessing(pos);
                }
            }
        }
    }

    private abstract static class Hook implements ColumnHooks.Hook {
        private final String handler;
        private final ColumnHooks.Place place;
        // the end handler writes the stone. The others only note what they are handed
        private final boolean writes;

        Hook(String handler, ColumnHooks.Place place, boolean writes) {
            this.handler = handler;
            this.place = place;
            this.writes = writes;
        }

        @Override
        public String handler() {
            return handler;
        }

        @Override
        public ColumnHooks.Place place() {
            return place;
        }

        @Override
        public boolean readsNoiseChunk() {
            return false;
        }

        @Override
        public boolean keepsSystemState() {
            return true;
        }

        @Override
        public boolean record() {
            return !writes;
        }

        @Override
        public boolean afterBlocks() {
            return writes;
        }
    }
}
