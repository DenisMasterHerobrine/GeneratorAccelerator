package dev.denismasterherobrine.generatoraccelerator2.compat.regionsunexplored;

import dev.denismasterherobrine.generatoraccelerator2.carver.CarveBlocks;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;

/**
 * Regions Unexplored's carver change ({@code WorldCarverMixin}) in GA2's carver runner. Regions Unexplored wraps
 * {@code carveBlock}'s grass test to also match the mod's peat and silt grass blocks. It wraps the dirt test to also
 * match the mod's peat and silt dirt. GA2's runner tests the same blocks ({@link CarveBlocks}). The blocks are the ones
 * that Regions Unexplored's mixin reads ({@code RUBlocks}' suppliers). They are resolved on first use, when the
 * registries are complete.
 */
public final class RegionsUnexploredCarvers {
    private RegionsUnexploredCarvers() {}

    private static final String BLOCKS = "net.regions_unexplored.registry.RUBlocks";

    public static void register() {
        CompatAdapters.register(HookTargets.CARVER_REPLAY, RegionsUnexploredMixins.CARVER, RegionsUnexploredCarvers::setUp);
    }

    private static boolean setUp() {
        try {
            Block peatGrass = block("PEAT_GRASS_BLOCK");
            Block siltGrass = block("SILT_GRASS_BLOCK");
            Block peatDirt = block("PEAT_DIRT");
            Block siltDirt = block("SILT_DIRT");
            CarveBlocks.addSurface(peatGrass, siltGrass);
            CarveBlocks.addDirt(peatDirt, siltDirt);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Block block(String field) throws ReflectiveOperationException {
        Object supplier = Class.forName(BLOCKS, true, RegionsUnexploredCarvers.class.getClassLoader()).getField(field).get(null);
        if (supplier instanceof Supplier<?> s && s.get() instanceof Block b) {
            return b;
        }
        throw new NoSuchFieldException(BLOCKS + "." + field);
    }
}
