package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.gpu.codegen.ClEmitter;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.GeneratorPickerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.junit.jupiter.api.Test;

/** The global fluid picker as the GPU program writes it: vanilla's stays the program's own, a picker of y alone becomes intervals. */
public class PickerShapeTest {
    private static BlockState[] ids(BlockState fluid) {
        return new BlockState[] { Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), fluid, Blocks.LAVA.defaultBlockState() };
    }

    @Test
    void vanillaPickersAreTheProgramsOwn() {
        TerrainHarness.World w = TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        Aquifer.FluidPicker overworld = ((GeneratorPickerAccessor) (Object) w.generator).ga2$globalFluidPicker().get();
        assertNull(PickerShape.of(overworld, -64, 384, 63, ids(Blocks.WATER.defaultBlockState()), true));
        TerrainHarness.World nether = TerrainHarness.world(TerrainHarness.Preset.NETHER, 1234567L);
        Aquifer.FluidPicker lava = ((GeneratorPickerAccessor) (Object) nether.generator).ga2$globalFluidPicker().get();
        assertNull(PickerShape.of(lava, 0, 128, 32, ids(Blocks.LAVA.defaultBlockState()), false));
    }

    @Test
    void aPickerOfYAloneBecomesIntervals() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        BlockState water = Blocks.WATER.defaultBlockState();
        Aquifer.FluidStatus air = new Aquifer.FluidStatus(0, Blocks.AIR.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(32, water);
        // The Undergarden's: air below y 1, its fluid at the sea level above
        List<int[]> iv = PickerShape.of((x, y, z) -> y <= 0 ? air : sea, -64, 192, 32, ids(water), false);
        assertEquals(2, iv.size());
        assertArrayEquals(new int[] { Integer.MIN_VALUE, 0, ClEmitter.ID_AIR }, iv.get(0));
        assertArrayEquals(new int[] { 1, 32, ClEmitter.ID_WATER }, iv.get(1));
    }

    @Test
    void otherPickersAreRefused() {
        TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, 1234567L);
        BlockState water = Blocks.WATER.defaultBlockState();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(63, water);
        ClEmitter.Unsupported xz = assertThrows(ClEmitter.Unsupported.class, () -> PickerShape.of((x, y, z) -> x > 100 && y < 0 ? lava : sea, -64, 384, 63, ids(water), true));
        assertTrue(xz.getMessage().contains("x or z"), xz.getMessage());
        Aquifer.FluidStatus honey = new Aquifer.FluidStatus(63, Blocks.HONEY_BLOCK.defaultBlockState());
        assertThrows(ClEmitter.Unsupported.class, () -> PickerShape.of((x, y, z) -> honey, -64, 384, 63, ids(water), true));
        // a change far below the world (only beyond the probed range of heights)
        assertThrows(ClEmitter.Unsupported.class, () -> PickerShape.of((x, y, z) -> y < -50_000 ? lava : sea, -64, 384, 63, ids(water), true));
    }
}
