package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferFastPath;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.StateDependence;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.SurfaceLevels;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.Test;

/** Which router shapes let the aquifer classify ahead of vanilla and let chunks share surface levels. */
public class StateDependenceTest {
    @Test
    void vanillaPresetsAreIndependent() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        for (ResourceKey<NoiseGeneratorSettings> preset : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED,
            NoiseGeneratorSettings.LARGE_BIOMES, NoiseGeneratorSettings.CAVES, NoiseGeneratorSettings.FLOATING_ISLANDS, NoiseGeneratorSettings.NETHER,
            NoiseGeneratorSettings.END)) {
            NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(preset).value();
            RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 1L);
            Map<String, Node> roots = ((Ga2RandomState) (Object) state).ga2$programs().routerRoots();
            assertEquals(true, StateDependence.orderIndependent(roots, AquiferFastPath.statusFields(roots)), preset.identifier() + " order");
            assertEquals(true, StateDependence.chunkIndependent(roots, SurfaceLevels.fields(roots)), preset.identifier() + " chunk");
        }
    }

    @Test
    void detectsStatefulShapes() {
        DensityFunction gradient = DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0);
        DensityFunction flat = DensityFunctions.constant(0.25);
        check(DensityFunctions.add(DensityFunctions.flatCache(flat), gradient), true, true);
        check(DensityFunctions.flatCache(gradient), true, false);
        check(DensityFunctions.cache2d(gradient), false, false);
        check(DensityFunctions.cache2d(flat), true, true);
        check(DensityFunctions.add(gradient, DensityFunctions.BeardifierMarker.INSTANCE), true, false);
        check(DensityFunctions.interpolated(DensityFunctions.cacheOnce(gradient)), true, true);
    }

    private static void check(DensityFunction fn, boolean orderIndependent, boolean chunkIndependent) {
        Node root = new IrBuilder(new CoverageReport()).build("f", fn);
        Map<String, Node> roots = Map.of("f", root);
        assertEquals(orderIndependent, StateDependence.orderIndependent(roots, "f"), "order: " + fn);
        assertEquals(chunkIndependent, StateDependence.chunkIndependent(roots, "f"), "chunk: " + fn);
    }
}
