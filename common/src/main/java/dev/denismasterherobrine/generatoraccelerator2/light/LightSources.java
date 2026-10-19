package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;

/**
 * {@code ChunkAccess.findBlockLightSources}, which lighting a new chunk runs once. For every section whose palette may hold
 * a light source, the game reads all 4096 block states and asks each for its light emission. On NeoForge the query is made
 * at the block's position. This class reads the section's storage in the same order (y, z, x) and decides per palette entry
 * instead. Some blocks have an emission that does not depend on position ({@link PlatformHooks#positionalLightEmission}).
 * On NeoForge these are the blocks that keep the default {@code getLightEmission(state, level, pos)}. On Fabric it is every
 * block. Such a block emits {@code state.getLightEmission()} wherever it is. Only blocks that override the method are asked
 * per position. The consumer gets the same positions and states in the same order.
 */
public final class LightSources {
    private LightSources() {}

    /** The game's coarse filter, as in its {@code findBlockLightSources}. */
    private static final Predicate<BlockState> MAY_EMIT = PlatformHooks::mayEmitLight;

    private static final byte NONE = 1;
    private static final byte EMITS = 2;
    private static final byte ASK = 3;

    /** Per block state id: unknown (0), NONE, EMITS or ASK. Racy fills write the same value. */
    private static volatile byte[] codes = new byte[0];

    private static final ClassValue<Boolean> DEFAULT_EMISSION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return !PlatformHooks.positionalLightEmission(type);
        }
    };

    @SuppressWarnings("unchecked")
    private static final ClassValue<Boolean> PLAIN_FIND_BLOCKS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return PlatformHooks.plainBlockSearch((Class<? extends ChunkAccess>) type);
        }
    };

    /** True when {@code findBlockLightSources} of this chunk is vanilla's section scan. Its class must not change {@code findBlocks}. */
    public static boolean handles(ChunkAccess chunk) {
        return PLAIN_FIND_BLOCKS.get(chunk.getClass());
    }

    public static void find(ChunkAccess chunk, BiConsumer<BlockPos, BlockState> consumer) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] local = new int[256];
        for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.maybeHas(MAY_EMIT)) {
                continue;
            }
            BlockPos origin = SectionPos.of(chunk.getPos(), sectionY).origin();
            PalettedContainer.Data<BlockState> data = section.getStates().data;
            Palette<BlockState> palette = data.palette();
            BitStorage storage = data.storage();
            int bits = storage.getBits();
            boolean small = bits <= 8;
            if (small) {
                java.util.Arrays.fill(local, 0, 1 << bits, 0);
            }
            if (storage instanceof SimpleBitStorage simple) {
                long[] raw = simple.getRaw();
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1L;
                int index = 0;
                for (int w = 0; w < raw.length && index < 4096; w++) {
                    long word = raw[w];
                    int end = Math.min(4096, index + perLong);
                    for (; index < end; index++) {
                        int id = (int) (word & mask);
                        word >>= bits;
                        visit(chunk, consumer, pos, origin, palette, local, small, id, index);
                    }
                }
            } else {
                for (int index = 0; index < 4096; index++) {
                    visit(chunk, consumer, pos, origin, palette, local, small, storage.get(index), index);
                }
            }
        }
    }

    private static void visit(ChunkAccess chunk, BiConsumer<BlockPos, BlockState> consumer, BlockPos.MutableBlockPos pos, BlockPos origin,
                              Palette<BlockState> palette, int[] local, boolean small, int id, int index) {
        int code = small ? local[id] : 0;
        BlockState state = null;
        if (code == 0) {
            state = palette.valueFor(id);
            code = code(state);
            if (small) {
                local[id] = code;
            }
        }
        if (code == NONE) {
            return;
        }
        if (state == null) {
            state = palette.valueFor(id);
        }
        pos.setWithOffset(origin, index & 15, index >> 8, (index >> 4) & 15);
        if (code == EMITS || PlatformHooks.lightEmission(state, chunk, pos) != 0) {
            consumer.accept(pos, state);
        }
    }

    private static byte code(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        byte[] table = codes;
        if (id >= 0 && id < table.length && table[id] != 0) {
            return table[id];
        }
        byte code;
        if (state.getClass() == BlockState.class && DEFAULT_EMISSION.get(state.getBlock().getClass())) {
            code = state.getLightEmission() != 0 ? EMITS : NONE;
        } else {
            code = ASK;
        }
        if (id >= 0) {
            if (id >= table.length) {
                byte[] grown = java.util.Arrays.copyOf(table, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
                codes = table = grown;
            }
            table[id] = code;
        }
        return code;
    }
}
