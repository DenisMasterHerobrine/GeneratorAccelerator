package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Exact speedups of vanilla's light engine (1.20+ {@code LightEngine}), which runs on one thread per dimension.
 *
 * <p>Propagation spends most of its time on lookups. It looks up the light data of the block's section, and vanilla
 * caches the last two sections. It looks up the chunk of a block, and vanilla caches the last two chunks. A flood fill
 * along a chunk corner touches four. It reads the block state through the level's height bounds on every read. It also
 * fetches the occlusion shapes of two blocks that have none. With this on, the section and chunk caches are
 * direct-mapped, with room for every neighbour of a section or chunk. They are emptied exactly where vanilla empties its
 * own. Block states are read from the cached chunk's section array, with the chunk class's own rules. Two blocks without
 * occlusion shapes skip straight to vanilla's answer, which is no occlusion. Repeated additions of the same section to
 * the two "changed" sets are skipped. The sets only grow until vanilla clears them.
 * Every read returns what vanilla's would, and every write happens in the same order. Light is therefore identical.
 *
 * <p>{@code -Dgeneratoraccelerator2.fastLight=false} turns it off.
 */
public final class FastLight {
    private FastLight() {}

    /** Not final so the exactness test can compare both ways. Call {@link #refresh()} after a change. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastLight"));

    /** Read by the hooks. Set by {@link #refresh()} when a light engine is created and when the config changes. */
    public static boolean on;

    public static final int CHUNK_SLOTS = 16;
    public static final int LAYER_SLOTS = 32;

    /** Chunk kinds for {@link #blockState}. A kind says how the chunk class answers {@code getBlockState}. */
    public static final byte KIND_OTHER = 0;
    public static final byte KIND_PROTO = 1;
    public static final byte KIND_LEVEL = 2;

    public static void refresh() {
        on = ENABLED && LightConfig.fastEngine() && HookTargets.allowed(HookTargets.LIGHT_ENGINE);
    }

    /** Slot of a section key in a {@link #LAYER_SLOTS} cache, from x and z mod 4 and y mod 2. A section and its six neighbours never share a slot. */
    public static int layerSlot(long sectionKey) {
        return (int) (sectionKey >>> 42) & 3 | ((int) (sectionKey >>> 20) & 3) << 2 | ((int) sectionKey & 1) << 4;
    }

    public static int chunkSlot(int chunkX, int chunkZ) {
        return chunkX & 3 | (chunkZ & 3) << 2;
    }

    /**
     * {@code SectionPos.blockToSection(BlockPos.offset(pos, direction))} given {@code section = SectionPos.blockToSection(pos)}.
     * Returns the same section, unless the step leaves it through a face. In that case, returns the adjacent section.
     */
    public static long neighbourSection(long pos, long section, net.minecraft.core.Direction direction) {
        int d = direction.ordinal();
        int local = (int) (pos >> FACE_SHIFT[d]) & 15;
        return local == FACE_LOCAL[d] ? net.minecraft.core.SectionPos.offset(section, direction) : section;
    }

    /** Per direction ordinal: the shift, in a packed {@code BlockPos}, of the coordinate the direction moves along. Also the
     * local coordinate (0 or 15) from which a step leaves the section. */
    private static final int[] FACE_SHIFT = new int[6];
    private static final int[] FACE_LOCAL = new int[6];

    static {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            int d = direction.ordinal();
            // BlockPos packs x at bit 38, z at bit 12, y at bit 0. The low 4 bits of each are the in-section coordinate.
            FACE_SHIFT[d] = switch (direction.getAxis()) {
                case X -> 38;
                case Y -> 0;
                case Z -> 12;
            };
            FACE_LOCAL[d] = direction.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE ? 15 : 0;
        }
    }

    /** How {@code chunk.getBlockState} works for this chunk's class, or {@link #KIND_OTHER} to call it. */
    public static byte kindOf(LightChunk chunk) {
        Class<?> c = chunk.getClass();
        if (c == ProtoChunk.class) {
            return KIND_PROTO;
        } else if (c == LevelChunk.class) {
            return ((LevelChunk) chunk).getLevel().isDebug() ? KIND_OTHER : KIND_LEVEL;
        } else if (c == ImposterProtoChunk.class) {
            LevelChunk wrapped = ((ImposterProtoChunk) chunk).getWrapped();
            return wrapped.getClass() == LevelChunk.class && !wrapped.getLevel().isDebug() ? KIND_LEVEL : KIND_OTHER;
        }
        return KIND_OTHER;
    }

    /** The sections {@code getBlockState} reads for a chunk of kind PROTO or LEVEL. */
    public static LevelChunkSection[] sectionsOf(LightChunk chunk) {
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped().getSections() : ((net.minecraft.world.level.chunk.ChunkAccess) chunk).getSections();
    }

    /**
     * {@code ProtoChunk.getBlockState} or {@code LevelChunk.getBlockState}, read from the chunk's section array. Outside the
     * build height, a proto chunk gives void air. Outside the sections, a level chunk gives air.
     */
    public static BlockState blockState(byte kind, LevelChunkSection[] sections, int minY, int x, int y, int z) {
        int index = (y >> 4) - (minY >> 4);
        if (kind == KIND_PROTO) {
            if (y < minY || index >= sections.length) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            LevelChunkSection section = sections[index];
            return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(section, x & 15, y & 15, z & 15);
        }
        if (index >= 0 && index < sections.length) {
            LevelChunkSection section = sections[index];
            if (!section.hasOnlyAir()) {
                return dev.denismasterherobrine.generatoraccelerator2.chunkio.StateReads.get(section, x & 15, y & 15, z & 15);
            }
        }
        return Blocks.AIR.defaultBlockState();
    }
}
