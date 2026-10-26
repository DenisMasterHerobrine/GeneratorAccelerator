package dev.denismasterherobrine.generatoraccelerator2.dfc.verify;

import com.mojang.brigadier.CommandDispatcher;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadCommand;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatCommand;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.NoiseBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.SkyBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.HiddenClassLoader;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCommand;
import dev.denismasterherobrine.generatoraccelerator2.gpu.light.GpuLight;
import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.LightLayers;
import dev.denismasterherobrine.generatoraccelerator2.light.LightSnapshot;
import dev.denismasterherobrine.generatoraccelerator2.light.LightTasks;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.PregenBench;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.StepsCommand;
import dev.denismasterherobrine.generatoraccelerator2.structure.FossilProbe;
import dev.denismasterherobrine.generatoraccelerator2.structure.RingBounds;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;

/** {@code /ga2 dfc stats} and {@code /ga2 dfc report}. */
public final class DfcCommand {
    private DfcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ga2")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("dfc")
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("report").executes(ctx -> report(ctx.getSource()))))
            .then(FeaturesCommand.build())
            .then(StepsCommand.build())
            .then(PregenBench.build())
            .then(CompatCommand.build())
            .then(ChunkLoadCommand.build())
            .then(Commands.literal("light").executes(ctx -> light(ctx.getSource()))));
    }

    /** {@code /ga2 light}: whether the light engine hooks and the parallel propagation are on, and what the latter did. */
    private static int light(CommandSourceStack source) {
        FastLight.refresh();
        String fast = "fast light engine: " + (FastLight.on ? "on" : "off");
        source.sendSuccess(() -> Component.literal(fast), false);
        for (String line : LightTasks.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        for (String line : LightLayers.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        for (String line : LightSnapshot.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        for (String line : ParallelLight.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        for (String line : GpuLight.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int stats(CommandSourceStack source) {
        int n = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            RandomState state = level.getChunkSource().randomState();
            ProgramSet set = ((Ga2RandomState) (Object) state).ga2$programs();
            if (set == null) {
                source.sendSuccess(() -> Component.literal(level.dimension().location() + ": no compiled programs"), false);
                continue;
            }
            String msg = level.dimension().location() + ": " + set.routerRoots().size() + " router roots, " + set.samplerRoots().size()
                + " sampler roots, " + set.internedNodes() + " unique nodes, " + set.specs().size() + " noise specs; column program "
                + (set.column() == null ? "none" : set.column().rootCount() + " roots") + "; cell programs " + set.cells().size()
                + "; climate sampler " + (set.chunkSampler() == null ? "no" : "yes") + "; global climate sampler "
                + (set.globalSampler() == null ? "no" : globalSamples(state)) + "; border cache "
                + (set.borderCache() == null ? "off" : set.borderCache().entries() + " entries, " + set.borderCache().hits.sum() + " hits / "
                + set.borderCache().misses.sum() + " misses") + "; compiled in " + set.compileNanos() / 1_000_000L + " ms";
            source.sendSuccess(() -> Component.literal(msg), false);
            n++;
        }
        var fill = FastFill.class;
        String whole = "noise fill in place: " + FastFill.FILLS.sum() + " chunks; cells handled whole: "
            + FastFill.CELLS_SKY.sum() + " air, "
            + FastFill.CELLS_SOLID.sum() + " solid, "
            + FastFill.CELLS_SOLID_VEINS.sum() + " solid with the vein rule; nether fossil probes skipped "
            + FossilProbe.SKIPPED.sum() + " of "
            + FossilProbe.PROBES.sum() + "; ring placement checks answered before the positions were known "
            + RingBounds.ANSWERED_EARLY.sum() + " (" + fill.getSimpleName() + ")";
        source.sendSuccess(() -> Component.literal(whole), false);
        String tight = "noise leaves bounded over the cell: " + CellBounds.TIGHT_TRIED.sum()
            + " mixed cells tried, " + CellBounds.TIGHT_WON.sum() + " decided whole, "
            + CellBounds.TIGHT_PAUSED.sum() + " skipped while paused; octaves bounded "
            + NoiseBounds.OCTAVES.sum() + ", too fine for the cell "
            + NoiseBounds.OCTAVES_WIDE.sum()
            + (CellBounds.TIGHT ? "" : " (off: -Dgeneratoraccelerator2.dfc.noiseBounds=false)");
        source.sendSuccess(() -> Component.literal(tight), false);
        String sky = "sky corners: " + ChunkBinding.SKY_PLANS.sum() + " chunks planned, "
            + ChunkBinding.SKY_CORNERS_SKIPPED.sum() + " corners left out, "
            + ChunkBinding.SKY_CORNERS_COMPLETED.sum() + " filled later for the aquifer"
            + (SkyBounds.ENABLED ? "" : " (off: -Dgeneratoraccelerator2.dfc.skyCorners=false)");
        source.sendSuccess(() -> Component.literal(sky), false);
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        String classes = "generated classes defined: " + HiddenClassLoader.DEFINED.sum()
            + " (surface rules per biome shared " + SurfaceProgram.BIOME_PROGRAMS_SHARED.sum()
            + " times); JIT compilation so far " + (jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() / 1000 + " s" : "unknown");
        source.sendSuccess(() -> Component.literal(classes), false);
        return n;
    }

    private static String globalSamples(RandomState state) {
        BoundSampler bound = BoundSampler.lookup(state.sampler());
        return bound == null || bound.sharedSamples == null ? "not bound" : bound.sharedSamples.sum() + " samples";
    }

    private static int report(CommandSourceStack source) {
        for (String line : CoverageReport.global().lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
