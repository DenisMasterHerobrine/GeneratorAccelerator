package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.CellSkipper;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;

/**
 * Whole-cell air tests over an aquifer, for the noise fill's cell classification ({@code CellBounds}). Take a cell whose
 * density is non-positive everywhere. If the aquifer answers air for each of its blocks, the cell is all air, and the
 * fill writes nothing for it.
 *
 * <p>The global fluid picker is a lambda of {@code NoiseBasedChunkGenerator.createFluidPicker}. It gives lava below
 * {@code min(-54, seaLevel)}, the sea level's fluid below the sea level, and air above, whatever x and z. The lava and
 * the fluid are therefore each a {@code y < level} test, and one probe at the bottom of a cell answers for the whole
 * cell. A picker of another class is asked for every block instead. That is still far cheaper than the per-block fill
 * path.
 */
public final class CellAir {
    private CellAir() {}

    // Compared with the names of the running classes. Names maps them to intermediary on Fabric.
    private static final String VANILLA_PICKER = Names.runtimeBinaryClass("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator") + "$$Lambda";
    private static final String DISABLED_AQUIFER = Names.runtimeBinaryClass("net.minecraft.world.level.levelgen.Aquifer$1");

    private static final ClassValue<Boolean> VANILLA = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith(VANILLA_PICKER);
        }
    };
    private static final HookTargets.Hook FLUID_PICKER = HookTargets.hook(HookTargets.NOISE_FLUID_PICKER);

    /**
     * True for the lambda {@code NoiseBasedChunkGenerator.createFluidPicker} returns. That lambda's fluids depend on y alone.
     * The picker must be a lambda of that class, and other mods must not change the method or its lambda body
     * ({@code noise.fluidPicker}).
     */
    public static boolean vanillaPicker(Aquifer.FluidPicker picker) {
        return VANILLA.get(picker.getClass()) && FLUID_PICKER.allowed();
    }

    /** True when the picker gives no lava for any block of the cell. */
    public static boolean noLava(Aquifer.FluidPicker picker, int x0, int y0, int z0, int w, int h) {
        if (vanillaPicker(picker)) {
            return !picker.computeFluid(x0, y0, z0).at(y0).is(Blocks.LAVA);
        }
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                for (int z = z0; z < z0 + w; z++) {
                    if (picker.computeFluid(x, y, z).at(y).is(Blocks.LAVA)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** True when the picker gives air for every block of the cell. */
    public static boolean allAir(Aquifer.FluidPicker picker, int x0, int y0, int z0, int w, int h) {
        if (vanillaPicker(picker)) {
            return picker.computeFluid(x0, y0, z0).at(y0).isAir();
        }
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                for (int z = z0; z < z0 + w; z++) {
                    if (!picker.computeFluid(x, y, z).at(y).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The whole-cell test for an aquifer, or null when none applies (another mod's aquifer). Vanilla's noise-based aquifer
     * uses the fast path's test. The disabled aquifer of dimensions without aquifers (the Nether and the End) uses the
     * picker alone. In those dimensions every non-solid block is the picker's fluid at the block's height.
     */
    @Nullable
    public static CellSkipper of(Aquifer aquifer) {
        if (aquifer instanceof Ga2Aquifer fast) {
            return fast::ga2$cellAir;
        }
        Aquifer.FluidPicker picker = disabledPicker(aquifer);
        return picker == null ? null : (x0, y0, z0, w, h) -> allAir(picker, x0, y0, z0, w, h);
    }

    /**
     * The picker of the aquifer from {@code Aquifer.createDisabled}, or null for any other aquifer. The anonymous class keeps
     * the picker in its one captured field. That field's name differs between the development and the shipped game, so the
     * field is found by type.
     */
    @Nullable
    public static Aquifer.FluidPicker disabledPicker(Aquifer aquifer) {
        return Reflect.fieldOfType(aquifer, DISABLED_AQUIFER, Aquifer.FluidPicker.class) instanceof Aquifer.FluidPicker picker ? picker : null;
    }
}
