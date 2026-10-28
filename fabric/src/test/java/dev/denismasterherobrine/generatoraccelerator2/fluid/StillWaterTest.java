package dev.denismasterherobrine.generatoraccelerator2.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.denismasterherobrine.generatoraccelerator2.testutil.VanillaTags;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * The still water skips leave out only work without effect. The post-processing test runs vanilla's fluid tick on random
 * neighbourhoods. Every tick the skip would leave out must write, schedule and notify nothing. The mesh test checks that vanilla's
 * renderer shows no face of the water the skip leaves out.
 */
class StillWaterTest {
    private static final int TRIALS = 200_000;
    private static final BlockPos CENTRE = new BlockPos(8, 64, 8);

    /** States around the ticked water: still and flowing water, solids, waterloggable and water-holding blocks, lava. */
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
        p.add(Blocks.GRASS.defaultBlockState());
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
            FluidState fluid = level.getBlockState(CENTRE).getFluidState();
            // water only. The tick of lava reads the dimension, which the stub lacks.
            if (!fluid.getType().isSame(Fluids.WATER)) {
                continue;
            }
            // the chunk boundary passes next to the ticked block in some trials
            ChunkPos chunk = random.nextBoolean() ? new ChunkPos(CENTRE) : new ChunkPos(CENTRE.offset(1, 0, 0));
            boolean still = StillWater.stillWater(level, chunk, level, CENTRE, fluid);
            fluid.tick(level, CENTRE);
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

    @Test
    void leftOutWaterShowsNoFace() throws ReflectiveOperationException {
        VanillaTags.bind();
        Class<?> renderer;
        try {
            renderer = Class.forName("net.minecraft.client.renderer.block.LiquidBlockRenderer", true, StillWaterTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            renderer = null;
        }
        assumeTrue(renderer != null, "no client classes in this environment");
        Method sameFluid = renderer.getDeclaredMethod("isNeighborSameFluid", FluidState.class, FluidState.class);
        Method renderFace = renderer.getDeclaredMethod("shouldRenderFace", BlockAndTintGetter.class, BlockPos.class, FluidState.class, BlockState.class,
            Direction.class, FluidState.class);
        Method occluded = renderer.getDeclaredMethod("isFaceOccludedByNeighbor", BlockGetter.class, BlockPos.class, Direction.class,
            float.class, BlockState.class);
        sameFluid.setAccessible(true);
        renderFace.setAccessible(true);
        occluded.setAccessible(true);
        List<BlockState> palette = palette();
        Random random = new Random(7);
        StubLevel level = StubLevel.create();
        int hiddenCount = 0;
        for (int trial = 0; trial < TRIALS / 4; trial++) {
            level.blocks.clear();
            double waterShare = random.nextDouble();
            for (Direction d : Direction.values()) {
                level.blocks.put(CENTRE.relative(d), random.nextDouble() < waterShare
                    ? Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, random.nextInt(3) == 0 ? random.nextInt(16) : 0)
                    : palette.get(random.nextInt(palette.size())));
            }
            BlockState self = palette.get(random.nextInt(palette.size()));
            FluidState fluid = self.getFluidState();
            if (!fluid.getType().isSame(Fluids.WATER) || !StillWater.surrounded(level, CENTRE)) {
                continue;
            }
            hiddenCount++;
            // the face flags tesselate computes before it returns without drawing
            Tint tint = new Tint(level);
            assertTrue((boolean) sameFluid.invoke(null, fluid, level.getFluidState(CENTRE.above())), "top face shows");
            boolean down = (boolean) renderFace.invoke(null, tint, CENTRE, fluid, self, Direction.DOWN, level.getFluidState(CENTRE.below()))
                && !(boolean) occluded.invoke(null, level, CENTRE, Direction.DOWN, 0.8888889F, level.getBlockState(CENTRE.below()));
            assertFalse(down, "bottom face shows");
            for (Direction d : Direction.Plane.HORIZONTAL) {
                assertFalse((boolean) renderFace.invoke(null, tint, CENTRE, fluid, self, d, level.getFluidState(CENTRE.relative(d))), d + " face shows");
            }
        }
        assertTrue(hiddenCount > 100, "hidden water came up " + hiddenCount + " times");
    }

    /** The stub level as a BlockAndTintGetter, for the renderer's face checks. */
    private record Tint(StubLevel level) implements BlockAndTintGetter {
        @Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
        @Override public LevelLightEngine getLightEngine() { throw new UnsupportedOperationException(); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0; }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return level.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return level.getFluidState(pos); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }

    /**
     * A level over a map of blocks, made without its constructor. Blocks outside the map are air. Writes, scheduled ticks,
     * neighbour updates and events are recorded in {@link #changes}. A write also changes the map, as in a real level.
     */
    static final class StubLevel extends Level {
        Map<BlockPos, BlockState> blocks;
        List<String> changes;
        private GameRules rules;

        @SuppressWarnings("unused")
        private StubLevel(WritableLevelData data, ResourceKey<Level> key, RegistryAccess access, Holder<DimensionType> type, Supplier<ProfilerFiller> profiler) {
            super(data, key, access, type, profiler, false, false, 0L, 0);
        }

        static StubLevel create() throws ReflectiveOperationException {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            StubLevel level = (StubLevel) ((Unsafe) f.get(null)).allocateInstance(StubLevel.class);
            // fields of a constructor that never ran
            level.blocks = new HashMap<>();
            level.changes = new ArrayList<>();
            level.rules = new GameRules();
            return level;
        }

        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public GameRules getGameRules() { return rules; }
        @Override public int getMinBuildHeight() { return -64; }
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
        @Override public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) { changes.add("event " + type + " " + pos); }
        @Override public void gameEvent(GameEvent event, Vec3 pos, GameEvent.Context context) { changes.add("game event " + pos); }

        @Override public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) { changes.add("sent " + pos); }
        @Override public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { changes.add("sound"); }
        @Override public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { changes.add("sound"); }
        @Override public String gatherChunkSourceStats() { return ""; }
        @Nullable @Override public Entity getEntity(int id) { return null; }
        @Nullable @Override public MapItemSavedData getMapData(String id) { return null; }
        @Override public void setMapData(String id, MapItemSavedData data) {}
        @Override public int getFreeMapId() { throw new UnsupportedOperationException("getFreeMapId"); }
        @Override public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {}
        @Override public Scoreboard getScoreboard() { throw new UnsupportedOperationException("getScoreboard"); }
        @Override public RecipeManager getRecipeManager() { throw new UnsupportedOperationException("getRecipeManager"); }
        @Override protected LevelEntityGetter<Entity> getEntities() { throw new UnsupportedOperationException("getEntities"); }
        @Override public LevelTickAccess<Block> getBlockTicks() { throw new UnsupportedOperationException("getBlockTicks"); }
        @Override public LevelTickAccess<Fluid> getFluidTicks() { throw new UnsupportedOperationException("getFluidTicks"); }
        @Override public ChunkSource getChunkSource() { throw new UnsupportedOperationException("getChunkSource"); }
        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
        @Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
        @Override public List<? extends Player> players() { return List.of(); }
        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { throw new UnsupportedOperationException("getUncachedNoiseBiome"); }
    }
}
