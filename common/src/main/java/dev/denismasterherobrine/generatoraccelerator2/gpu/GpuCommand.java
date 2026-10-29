package dev.denismasterherobrine.generatoraccelerator2.gpu;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /ga2 gpu}: the device, each dimension's GPU program and why a part runs on the CPU, chunk counts. */
public final class GpuCommand {
    private GpuCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ga2")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("gpu").executes(ctx -> status(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        for (String line : Gpu.statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
