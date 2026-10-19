package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.PointProgram;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.ProgramCompiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.IrBuilder;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;

/** Liftable modded density functions compile and match bit for bit; unliftable ones fall back with a reason. */
public class LifterTest {
    @Test
    void liftsSupportedShapesAndReportsTheRest() {
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState state = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 2024L);
        DensityFunction continents = state.router().continents();
        DensityFunction erosion = state.router().erosion();
        DensityFunction depth = state.router().depth();
        NormalNoise.NoiseParameters params = new NormalNoise.NoiseParameters(-4, 1.0, 1.0);
        DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(params), NormalNoise.create(new XoroshiroRandomSource(5L), params));

        List<DensityFunction> liftable = List.of(
            new ModdedFunctions.ScaledSum(continents, erosion, 1.75),
            new ModdedFunctions.Ternary(depth, 0.1),
            new ModdedFunctions.CoordMath(0.3),
            new ModdedFunctions.NoiseUser(noise, 0.05),
            new ModdedFunctions.AndChain(continents, erosion),
            new ModdedFunctions.FloatRound(depth),
            // nested: modded inside vanilla inside modded
            new ModdedFunctions.Ternary(DensityFunctions.add(new ModdedFunctions.ScaledSum(continents, DensityFunctions.constant(0.25), 2.0), erosion), -0.3));
        List<DensityFunction> unliftable = List.of(
            new ModdedFunctions.Loopy(continents),
            new ModdedFunctions.Alloc(continents),
            new ModdedFunctions.Stateful(continents));

        CoverageReport report = new CoverageReport();
        IrBuilder builder = new IrBuilder(report);
        List<Node> roots = new ArrayList<>();
        List<DensityFunction> all = new ArrayList<>(liftable);
        all.addAll(unliftable);
        for (int i = 0; i < all.size(); i++) {
            roots.add(builder.build("mod" + i, all.get(i)));
        }
        Interpreter interpreter = new Interpreter(builder.specs(), builder.blendedSpecs());
        PointProgram program = ProgramCompiler.compilePoint("lifted", roots, builder.specs(), builder.blendedSpecs());
        double[] scratch = new double[Math.max(1, program.scratchSize)];
        double[] out = new double[roots.size()];

        Random rng = new Random(7L);
        long compared = 0;
        for (int i = 0; i < 4000; i++) {
            int x = rng.nextInt(8192) - 4096, z = rng.nextInt(8192) - 4096, y = rng.nextInt(400) - 70;
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            program.computeAll(ctx, scratch, out);
            for (int r = 0; r < liftable.size(); r++) {
                double expected = all.get(r).compute(ctx);
                double viaInterpreter = interpreter.eval(roots.get(r), ctx);
                if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(viaInterpreter) || Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(out[r])) {
                    throw new AssertionError(all.get(r).getClass().getSimpleName() + " at (" + x + "," + y + "," + z + "): original=" + expected + " interpreter=" + viaInterpreter + " generated=" + out[r]);
                }
                compared += 2;
            }
        }
        int lifted = 0, fallback = 0;
        StringBuilder reasons = new StringBuilder();
        for (CoverageReport.Entry e : report.entries()) {
            if (e.kind == CoverageReport.Kind.LIFTED) {
                lifted++;
            } else if (e.kind == CoverageReport.Kind.FALLBACK) {
                fallback++;
                reasons.append(e.line()).append('\n');
            }
        }
        System.out.println("DFC lifter: " + compared + " comparisons bit-identical; lifted classes=" + lifted + " fallback classes=" + fallback + "\n" + reasons);
        assertEquals(6, lifted, "every liftable class should be lifted (ScaledSum, Ternary, CoordMath, NoiseUser, AndChain, FloatRound)");
        assertEquals(3, fallback, "Loopy, Alloc and Stateful must fall back");
        assertTrue(reasons.toString().contains("loop"), "Loopy reason should mention the loop");
        assertTrue(reasons.toString().contains("NEWARRAY"), "Alloc reason should mention the allocation");
        assertTrue(reasons.toString().contains("non-final field"), "Stateful reason should mention the mutable field");
    }
}
