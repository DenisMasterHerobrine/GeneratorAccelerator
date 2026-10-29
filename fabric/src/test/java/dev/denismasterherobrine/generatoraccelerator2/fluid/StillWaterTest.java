package dev.denismasterherobrine.generatoraccelerator2.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.VanillaTags;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * The still water skip leaves out only work without effect. The test runs vanilla's fluid tick on random neighbourhoods. Every
 * tick the skip would leave out must write, schedule and notify nothing.
 */
class StillWaterTest {
    private static final int TRIALS = 200_000;
    private static final BlockPos CENTRE = new BlockPos(8, 64, 8);

    /**
     * States around the ticked water: still and flowing water, solids, waterloggable and water-holding blocks, lava. No block that
     * water breaks: the stub is a server level without a server, and a broken block drops its loot.
     */
    private static List<BlockState> palette() {
        List<BlockState> p = new ArrayList<>();
        p.add(Blocks.WATER.defaultBlockState());
        for (int level = 1; level <= 8; level++) {
            p.add(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level));
        }
        p.add(Blocks.AIR.defaultBlockState());
        p.add(Blocks.STONE.defaultBlockState());
        p.add(Blocks.GLASS.defaultBlockState());
        p.add(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        p.add(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP).setValue(BlockStateProperties.WATERLOGGED, true));
        p.add(Blocks.OAK_SLAB.defaultBlockState());
        p.add(Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        p.add(Blocks.KELP.defaultBlockState());
        p.add(Blocks.KELP_PLANT.defaultBlockState());
        p.add(Blocks.SEAGRASS.defaultBlockState());
        p.add(Blocks.BUBBLE_COLUMN.defaultBlockState());
        p.add(Blocks.MAGMA_BLOCK.defaultBlockState());
        p.add(Blocks.SOUL_SAND.defaultBlockState());
        p.add(Blocks.LAVA.defaultBlockState());
        p.add(Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        p.add(Blocks.ICE.defaultBlockState());
        return p;
    }

    @Test
    void leftOutTicksChangeNothing() throws ReflectiveOperationException {
        VanillaTags.bind();
        List<BlockState> palette = palette();
        BlockState water = Blocks.WATER.defaultBlockState();
        Random random = new Random(42);
        StubLevel level = StubLevel.create();
        int stillCount = 0;
        int changedCount = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            level.blocks.clear();
            level.changes.clear();
            // mostly water near the ticked block, so both answers come up often
            double waterShare = random.nextDouble();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -3; dy <= 2; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockState s = random.nextDouble() < waterShare ? water : palette.get(random.nextInt(palette.size()));
                        level.blocks.put(CENTRE.offset(dx, dy, dz), s);
                    }
                }
            }
            if (random.nextInt(8) != 0) {
                level.blocks.put(CENTRE, water);
            }
            BlockState state = level.getBlockState(CENTRE);
            FluidState fluid = state.getFluidState();
            // water only. The tick of lava reads the dimension, which the stub lacks.
            if (!fluid.getType().isSame(Fluids.WATER)) {
                continue;
            }
            // the chunk boundary passes next to the ticked block in some trials
            ChunkPos chunk = random.nextBoolean() ? ChunkPos.containing(CENTRE) : ChunkPos.containing(CENTRE.offset(1, 0, 0));
            boolean still = StillWater.stillWater(level, chunk, level, CENTRE, fluid);
            fluid.tick(level, CENTRE, state);
            if (still) {
                stillCount++;
                assertEquals(List.of(), level.changes, "a left-out tick changed the world (trial " + trial + ")");
            } else if (!level.changes.isEmpty()) {
                changedCount++;
            }
        }
        assertTrue(stillCount > TRIALS / 20, "still water came up " + stillCount + " times");
        assertTrue(changedCount > TRIALS / 20, "the stub records changes (" + changedCount + " ticks changed something)");
    }

    /**
     * A server level over a map of blocks, made without its constructor. Blocks outside the map are air. Writes, scheduled ticks,
     * neighbour updates and events are recorded in {@link #changes}. A write also changes the map, as in a real level.
     */
    static final class StubLevel extends ServerLevel {
        Map<BlockPos, BlockState> blocks;
        List<String> changes;
        private GameRules rules;

        @SuppressWarnings("unused")
        private StubLevel(MinecraftServer server, Executor executor, LevelStorageSource.LevelStorageAccess storage, ServerLevelData data,
                          ResourceKey<Level> key, LevelStem stem, boolean debug, long seed, List<CustomSpawner> spawners, boolean tickTime) {
            super(server, executor, storage, data, key, stem, debug, seed, spawners, tickTime);
        }

        static StubLevel create() throws ReflectiveOperationException {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            StubLevel level = (StubLevel) ((Unsafe) f.get(null)).allocateInstance(StubLevel.class);
            // fields of a constructor that never ran
            level.blocks = new HashMap<>();
            level.changes = new ArrayList<>();
            level.rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
            return level;
        }

        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public GameRules getGameRules() { return rules; }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            changes.add("set " + pos + " " + state);
            blocks.put(pos.immutable(), state);
            return true;
        }

        @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay) { changes.add("tick " + pos + " " + fluid); }
        @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) { changes.add("tick " + pos + " " + fluid); }
        @Override public void scheduleTick(BlockPos pos, Block block, int delay) { changes.add("tick " + pos + " " + block); }
        @Override public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) { changes.add("tick " + pos + " " + block); }
        @Override public void updateNeighborsAt(BlockPos pos, Block block) { changes.add("neighbours " + pos); }
        @Override public void updateNeighborsAt(BlockPos pos, Block block, @Nullable Orientation orientation) { changes.add("neighbours " + pos); }
        @Override public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) { changes.add("event " + type + " " + pos); }
        @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) { changes.add("game event " + pos); }
        @Override public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) { changes.add("sent " + pos); }
        @Override public void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { changes.add("sound"); }
        @Override public void playSeededSound(@Nullable Entity except, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { changes.add("sound"); }
    }
}
