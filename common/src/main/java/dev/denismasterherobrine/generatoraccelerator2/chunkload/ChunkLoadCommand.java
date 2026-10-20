package dev.denismasterherobrine.generatoraccelerator2.chunkload;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.DistanceManagerTicketsAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.TaskQueueAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload.TaskSorterAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /ga2 chunks}: how chunks load and are sent ({@link ChunkLoadStats}). {@code /ga2 chunks reset} starts over. */
public final class ChunkLoadCommand {
    private ChunkLoadCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunks")
            .executes(ctx -> stats(ctx.getSource()))
            .then(Commands.literal("reset").executes(ctx -> {
                ChunkLoadStats.reset();
                ctx.getSource().sendSuccess(() -> Component.literal("chunk loading stats reset"), false);
                return 1;
            }));
    }

    private static int stats(CommandSourceStack source) {
        int window = -1;
        int inUse = -1;
        try {
            var throttler = ((DistanceManagerTicketsAccessor) source.getLevel().getChunkSource().chunkMap.getDistanceManager()).ga2$ticketThrottler();
            for (var queue : ((TaskSorterAccessor) throttler).ga2$queues().values()) {
                inUse = ((TaskQueueAccessor) queue).ga2$acquired().size();
                window = ((TaskQueueAccessor) queue).ga2$maxTasks();
            }
        } catch (RuntimeException | LinkageError e) {
            // another chunk system replaced the distance manager's throttler
        }
        for (String line : ChunkLoadStats.lines(window, inUse)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
