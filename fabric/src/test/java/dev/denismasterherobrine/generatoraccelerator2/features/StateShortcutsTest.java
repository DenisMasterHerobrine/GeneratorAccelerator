package dev.denismasterherobrine.generatoraccelerator2.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.state.FluidInvoker;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import dev.denismasterherobrine.generatoraccelerator2.state.StateFast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

/**
 * The state-level shortcuts against vanilla's own code: a section's counts and blocks after random writes (its palette growing
 * through every kind), every fluid state's emptiness, every block shape's full-block answer.
 */
public class StateShortcutsTest {
    /**
     * On Fabric, Fabric API (which every Fabric game has) changes the air test in {@code LevelChunkSection.setBlockState}. So
     * the section write hook stands down there, as it does for any other mod's change. This is the platform's own change, not a
     * broken shortcut.
     */
    private static final String FABRIC_API_SECTION = "net.fabricmc.fabric.mixin.block.ChunkSectionMixin";

    @Test
    void shortcutsAreOn() {
        HarnessWorld.registries();
        assertTrue(StateFast.ENABLED, "run with the shortcuts on");
        for (String key : new String[] { HookTargets.TAG_BITS, HookTargets.SECTION_WRITE, HookTargets.PALETTE, HookTargets.FLUID_EMPTY, HookTargets.FULL_BLOCK_SHAPE }) {
            if (key.equals(HookTargets.SECTION_WRITE) && HookTargets.foreign(key).equals(List.of(FABRIC_API_SECTION))) {
                continue;
            }
            assertTrue(HookTargets.hook(key).allowed(), key + " is not in use (another mod's change, or not inspected)");
        }
    }

    @Test
    void sectionWritesCountAsVanilla() {
        HarnessWorld.Registries2 r = HarnessWorld.registries();
        List<BlockState> pool = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            pool.addAll(b.getStateDefinition().getPossibleStates());
        }
        Random random = new Random(20261010L);
        for (int round = 0; round < 4; round++) {
            LevelChunkSection section = new LevelChunkSection(r.biomes);
            BlockState[] expected = new BlockState[4096];
            Arrays.fill(expected, Blocks.AIR.defaultBlockState());
            int nonEmpty = 0, ticking = 0, fluids = 0;
            // a few states at first (single, linear palette), then more (hash map), then many (global)
            int kinds = round == 0 ? 6 : round == 1 ? 40 : round == 2 ? 300 : 4000;
            List<BlockState> states = new ArrayList<>();
            states.add(Blocks.AIR.defaultBlockState());
            states.add(Blocks.WATER.defaultBlockState());
            states.add(Blocks.KELP.defaultBlockState());
            for (int i = states.size(); i < kinds; i++) {
                states.add(pool.get(random.nextInt(pool.size())));
            }
            for (int w = 0; w < 20000; w++) {
                int i = random.nextInt(4096);
                BlockState state = states.get(random.nextInt(states.size()));
                BlockState old = section.setBlockState(i & 15, i >> 8, (i >> 4) & 15, state, (w & 1) == 0);
                assertTrue(old == expected[i], "write " + w + " returned " + old + ", expected " + expected[i]);
                // the game's per-write counting (LevelChunkSection.setBlockState), asked of the states themselves
                if (!PlatformHooks.isEmpty(old)) {
                    nonEmpty--;
                    if (old.isRandomlyTicking()) {
                        ticking--;
                    }
                }
                if (!fluidEmpty(old)) {
                    fluids--;
                }
                if (!PlatformHooks.isEmpty(state)) {
                    nonEmpty++;
                    if (state.isRandomlyTicking()) {
                        ticking++;
                    }
                }
                if (!fluidEmpty(state)) {
                    fluids++;
                }
                expected[i] = state;
                assertEquals((short) nonEmpty, section.nonEmptyBlockCount, "non-empty count after write " + w);
                assertEquals((short) ticking, section.tickingBlockCount, "ticking count after write " + w);
                assertEquals((short) fluids, section.tickingFluidCount, "fluid count after write " + w);
            }
            for (int i = 0; i < 4096; i++) {
                assertTrue(section.getBlockState(i & 15, i >> 8, (i >> 4) & 15) == expected[i], "block " + i + " of round " + round);
            }
        }
    }

    private static boolean fluidEmpty(BlockState state) {
        return ((FluidInvoker) state.getFluidState().getType()).ga2$isEmpty();
    }

    @Test
    void fluidEmptinessAsVanilla() {
        HarnessWorld.registries();
        int n = 0;
        for (Fluid f : BuiltInRegistries.FLUID) {
            for (FluidState s : f.getStateDefinition().getPossibleStates()) {
                boolean truth = ((FluidInvoker) f).ga2$isEmpty();
                assertEquals(truth, s.isEmpty(), s + " (first)");
                assertEquals(truth, s.isEmpty(), s + " (remembered)");
                n++;
            }
        }
        assertTrue(n > 20, "too few fluid states: " + n);
    }

    @Test
    void fullBlockShapesAsVanilla() {
        HarnessWorld.registries();
        int n = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                List<VoxelShape> shapes = new ArrayList<>();
                shapes.add(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                shapes.add(s.getBlockSupportShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                for (Direction d : Direction.values()) {
                    shapes.add(s.getBlockSupportShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(d));
                }
                for (VoxelShape shape : shapes) {
                    boolean truth = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
                    assertEquals(truth, Block.isShapeFullBlock(shape), s + " " + shape + " (first)");
                    assertEquals(truth, Block.isShapeFullBlock(shape), s + " " + shape + " (remembered)");
                    n++;
                }
            }
        }
        assertTrue(n > 10000, "too few shapes: " + n);
    }
}
