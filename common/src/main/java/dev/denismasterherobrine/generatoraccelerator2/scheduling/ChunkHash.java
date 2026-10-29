package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * {@code /ga2 bench hash <chunkX> <chunkZ> <radius> [blocks]}. Logs a digest of every block state per chunk in the square,
 * by block state id, section after section. Also reports how many blocks of the listed kinds (comma separated ids) the
 * square holds. Two worlds generated from the same seed and mods, with and without GA2, are compared line by line.
 * Chunks are loaded (or generated) as needed.
 */
public final class ChunkHash {
    private ChunkHash() {}

    private static final Logger LOGGER = Ga2Log.get("Bench");

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("hash")
            .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> hash(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"), IntegerArgumentType.getInteger(ctx, "chunkZ"),
                            IntegerArgumentType.getInteger(ctx, "radius"), ""))
                        .then(Commands.argument("blocks", StringArgumentType.greedyString())
                            .executes(ctx -> hash(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunkX"), IntegerArgumentType.getInteger(ctx, "chunkZ"),
                                IntegerArgumentType.getInteger(ctx, "radius"), StringArgumentType.getString(ctx, "blocks")))))));
    }

    private static int hash(CommandSourceStack source, int cx, int cz, int radius, String blocks) {
        ServerLevel level = source.getLevel();
        List<Block> kinds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String id : blocks.split("[, ]+")) {
            if (!id.isBlank()) {
                kinds.add(BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)));
                names.add(id);
            }
        }
        long[] counts = new long[kinds.size()];
        long total = 1;
        int n = 0;
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                LevelChunk chunk = level.getChunk(x, z);
                long h = 0xcbf29ce484222325L;
                for (LevelChunkSection section : chunk.getSections()) {
                    for (int y = 0; y < 16; y++) {
                        for (int zz = 0; zz < 16; zz++) {
                            for (int xx = 0; xx < 16; xx++) {
                                BlockState state = section.getBlockState(xx, y, zz);
                                h = (h ^ Block.getId(state)) * 0x100000001b3L;
                                Block b = state.getBlock();
                                for (int k = 0; k < counts.length; k++) {
                                    if (b == kinds.get(k)) {
                                        counts[k]++;
                                    }
                                }
                            }
                        }
                    }
                }
                LOGGER.info("hash {} {} {} {}", level.dimension().identifier(), x, z, Long.toHexString(h));
                total = total * 31 + h;
                n++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < counts.length; k++) {
            sb.append(k == 0 ? "" : ", ").append(names.get(k)).append(' ').append(counts[k]);
        }
        String line = "hash " + level.dimension().identifier() + " " + n + " chunks around " + cx + ", " + cz + ": " + Long.toHexString(total)
            + (counts.length > 0 ? "; " + sb : "");
        LOGGER.info("{}", line);
        source.sendSuccess(() -> Component.literal(line), false);
        return n;
    }
}
