package dev.denismasterherobrine.generatoraccelerator2.aquifer;

import com.mojang.brigadier.CommandDispatcher;
import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /ga2 aquifer stats}: whether the aquifer, surface level and beardifier fast paths are allowed, and shared surface level hits. */
public final class AquiferCommand {
    private AquiferCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ga2")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("aquifer")
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))));
    }

    private static int stats(CommandSourceStack source) {
        long hits = SurfaceLevelCache.HITS.sum();
        long misses = SurfaceLevelCache.MISSES.sum();
        String allowed = "aquifer fast path " + onOff(MixinTargets.aquiferAllowed()) + ", shared surface levels " + onOff(MixinTargets.surfaceLevelAllowed())
            + ", beardifier index " + onOff(MixinTargets.beardifierAllowed()) + " (other mods' mixins permitting; config may still turn them off)";
        String shared = "shared surface levels: " + hits + " hits, " + misses + " misses"
            + (hits + misses > 0 ? String.format(" (%.1f%% hit rate)", 100.0 * hits / (hits + misses)) : "");
        source.sendSuccess(() -> Component.literal(allowed), false);
        source.sendSuccess(() -> Component.literal(shared), false);
        if (AquiferFastPath.STATS) {
            source.sendSuccess(() -> Component.literal("aquifer paths: " + AquiferFastPath.statsLine()), false);
        }
        return 1;
    }

    private static String onOff(boolean on) {
        return on ? "allowed" : "off";
    }
}
