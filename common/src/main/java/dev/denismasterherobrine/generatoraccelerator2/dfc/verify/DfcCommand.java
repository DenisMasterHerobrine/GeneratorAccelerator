package dev.denismasterherobrine.generatoraccelerator2.dfc.verify;

import com.mojang.brigadier.CommandDispatcher;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.CoverageReport;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;

/** {@code /ga2 dfc stats} and {@code /ga2 dfc report}. */
public final class DfcCommand {
    private DfcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ga2")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("dfc")
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("report").executes(ctx -> report(ctx.getSource()))))
            .then(dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCommand.build())
            .then(dev.denismasterherobrine.generatoraccelerator2.scheduling.StepsCommand.build())
            .then(dev.denismasterherobrine.generatoraccelerator2.scheduling.PregenBench.build())
            .then(dev.denismasterherobrine.generatoraccelerator2.compat.CompatCommand.build())
            .then(Commands.literal("light").executes(ctx -> light(ctx.getSource()))));
    }

    /** {@code /ga2 light}: whether the light engine hooks and the parallel propagation are on, and what the latter did. */
    private static int light(CommandSourceStack source) {
        dev.denismasterherobrine.generatoraccelerator2.light.FastLight.refresh();
        String fast = "fast light engine: " + (dev.denismasterherobrine.generatoraccelerator2.light.FastLight.on ? "on" : "off");
        source.sendSuccess(() -> Component.literal(fast), false);
        for (String line : dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight.statusLines()) {
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
        var fill = dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.class;
        String whole = "noise fill in place: " + dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.FILLS.sum() + " chunks; cells handled whole: "
            + dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.CELLS_SKY.sum() + " air, "
            + dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.CELLS_SOLID.sum() + " solid, "
            + dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill.CELLS_SOLID_VEINS.sum() + " solid with the vein rule; nether fossil probes skipped "
            + dev.denismasterherobrine.generatoraccelerator2.structure.FossilProbe.SKIPPED.sum() + " of "
            + dev.denismasterherobrine.generatoraccelerator2.structure.FossilProbe.PROBES.sum() + "; ring placement checks answered before the positions were known "
            + dev.denismasterherobrine.generatoraccelerator2.structure.RingBounds.ANSWERED_EARLY.sum() + " (" + fill.getSimpleName() + ")";
        source.sendSuccess(() -> Component.literal(whole), false);
        return n;
    }

    private static String globalSamples(RandomState state) {
        dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler bound = dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler.lookup(state.sampler());
        return bound == null || bound.sharedSamples == null ? "not bound" : bound.sharedSamples.sum() + " samples";
    }

    private static int report(CommandSourceStack source) {
        for (String line : CoverageReport.global().lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
