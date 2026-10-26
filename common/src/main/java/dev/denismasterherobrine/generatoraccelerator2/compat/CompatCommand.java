package dev.denismasterherobrine.generatoraccelerator2.compat;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.Map;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardifierFastPath;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.HeightmapPriming;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FastFill;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /ga2 compat report}: what GA2 took over from other mods, and where it stands down for them. */
public final class CompatCommand {
    private CompatCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("compat")
            .then(Commands.literal("report").executes(ctx -> report(ctx.getSource())));
    }

    private static int report(CommandSourceStack source) {
        send(source, "takeover: " + (Takeover.enabled() ? "on" : "off (config takeOver = false)"));
        Map<String, String> cancelled = Takeover.cancelled();
        if (cancelled.isEmpty()) {
            send(source, "no other mod's worldgen mixins were taken over");
        } else {
            for (Map.Entry<String, String> e : cancelled.entrySet()) {
                send(source, "took over " + e.getKey() + " (" + e.getValue() + ")");
            }
        }
        report(source, "aquifer fast path", MixinTargets.aquiferAllowed(), MixinTargets.aquiferForeign());
        if (MixinTargets.aquiferAllowed() && MixinTargets.aquiferSearchOnly()) {
            send(source, "aquifer fast path: search only (statuses in vanilla's order) for " + MixinTargets.aquiferChanges());
        }
        report(source, "beardifier piece index", MixinTargets.beardifierAllowed(), MixinTargets.beardifierForeign());
        BeardifierChain.Recorded chain = BeardifierChain.recorded();
        if (chain != null && (!chain.steps().isEmpty() || !chain.outer().isEmpty())) {
            send(source, "beardifier: other mods wrap compute (" + chain.outer() + ") and add after vanilla's sum " + chain.steps()
                + (chain.understood() ? ". The piece index runs those steps itself." : ". The index cannot run all of them: " + chain.unknown()));
        }
        for (Map.Entry<String, List<String>> e : HookTargets.allForeign().entrySet()) {
            if (e.getKey().equals(HookTargets.NOISE_FILL_STATE) && HookTargets.fillStateKnown()) {
                send(source, "hook " + e.getKey() + ": GA2's fill runs the fluid steps of " + e.getValue() + " itself in the chunks with their structures");
                continue;
            }
            send(source, "hook " + e.getKey() + " keeps the code of " + e.getValue());
        }
        for (Map.Entry<String, List<HookTargets.Handled>> e : HookTargets.allHandled().entrySet()) {
            String hook = e.getKey();
            for (String mixin : HookTargets.handledMixins(hook)) {
                KnownMixins.Entry entry = e.getValue().stream().filter(h -> h.mixin().equals(mixin)).findFirst().orElseThrow().entry();
                send(source, "hook " + hook + " runs " + mixin + " itself (" + entry.modId() + ": " + entry.what() + ")"
                    + (CompatAdapters.ready(hook, mixin) ? "" : " - its compat code is not set up, so the hook is off"));
            }
        }
        List<String> undecided = HookTargets.undecided();
        if (!undecided.isEmpty()) {
            send(source, "hooks whose classes were not inspected (not loaded yet, so off until they are): " + undecided);
        }
        for (Map.Entry<String, List<String>> e : FeatureTargets.allForeign().entrySet()) {
            send(source, "feature hook " + e.getKey() + " keeps the code of " + e.getValue());
        }
        if (SurfaceHooks.VERIFY.enabled()) {
            send(source, "verify " + SurfaceHooks.VERIFY.summary());
        }
        if (CarverRunner.VERIFY.enabled()) {
            send(source, "verify " + CarverRunner.VERIFY.summary());
        }
        if (FastFill.VERIFIED.get() > 0) {
            send(source, "verify fill: " + FastFill.VERIFIED.get()
                + " block states checked against the NoiseChunk's own, " + FastFill.DIFFERING.get() + " differ");
        }
        if (ChunkBinding.CELLS_VERIFIED.get() > 0) {
            send(source, "verify cells: " + ChunkBinding.CELLS_VERIFIED.get()
                + " cells checked against vanilla's fill, " + ChunkBinding.CELLS_DIFFERING.get() + " differ");
        }
        if (HeightmapPriming.VERIFIED.get() > 0) {
            send(source, "verify prime: " + HeightmapPriming.VERIFIED.get()
                + " chunks' final heightmaps checked against vanilla's scan, " + HeightmapPriming.DIFFERING.get() + " differ");
        }
        if (BeardifierFastPath.VERIFIED.get() > 0) {
            send(source, "verify beardifier: " + BeardifierFastPath.VERIFIED.get()
                + " sums checked against vanilla's compute, " + BeardifierFastPath.DIFFERING.get() + " differ");
        }
        if (TerraBlenderMixins.VERIFIED.get() > 0) {
            send(source, "verify biomes: " + TerraBlenderMixins.VERIFIED.get()
                + " TerraBlender region searches checked against vanilla's walk, "
                + TerraBlenderMixins.DIFFERING.get() + " differ");
        }
        for (Map.Entry<String, List<String>> e : FeatureTargets.allHandled().entrySet()) {
            for (String mixin : e.getValue()) {
                send(source, "feature hook " + e.getKey() + " runs " + mixin + " itself"
                    + (CompatAdapters.ready(FeatureTargets.compatKey(e.getKey()), mixin) ? "" : " - its compat code is not set up, so the hook is off"));
            }
        }
        return 1;
    }

    private static void report(CommandSourceStack source, String hook, boolean on, List<String> foreign) {
        send(source, hook + ": " + (on ? "on" : "off") + (foreign.isEmpty() ? "" : " (method also changed by " + foreign + ")"));
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
