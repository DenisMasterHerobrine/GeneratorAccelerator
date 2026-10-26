package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.FillHeights;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code Heightmap.primeHeightmaps} for the four final heightmaps at the start of FEATURES, from the two worldgen
 * heightmaps the chunk has kept exact since its noise fill.
 *
 * <p>Vanilla scans every column down from the highest non-empty section until each of the four has found its block.
 * {@code WORLD_SURFACE} tests the same predicate as {@code WORLD_SURFACE_WG} (not air). {@code OCEAN_FLOOR} tests the same
 * predicate as {@code OCEAN_FLOOR_WG} (blocks motion). Every block write since the fill has updated both worldgen maps,
 * because they are the persisted status's maps up to CARVERS. Their contents are therefore copied. The two
 * motion-blocking maps are scanned as vanilla does, but from the column's highest non-air block. Every block above that
 * one is air, and vanilla's scan passes over air without effect. Anything other than a plain proto chunk with both
 * worldgen maps goes to vanilla's scan.
 */
public final class HeightmapPriming {
    private HeightmapPriming() {}

    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fastPrime"));
    public static final LongAdder FAST = new LongAdder();
    /** Primings where every final heightmap already existed and was kept. Such maps were primed at the first decoration write. */
    public static final LongAdder KEPT = new LongAdder();
    public static final LongAdder VANILLA = new LongAdder();

    /**
     * {@code Heightmap.primeHeightmaps(chunk, types)}. {@code vanilla} is that call, possibly wrapped by other mods.
     *
     * <p>From CARVERS on, a block write updates the final heightmaps. Vanilla primes them with a scan at the first such
     * write. For most chunks that write is a neighbour's decoration, before the chunk's own FEATURES step. A final
     * heightmap that exists has been exact since then, while those writes no longer update the worldgen heightmaps. A type
     * that exists is therefore kept, because vanilla's scan would only find the same heights. Only the missing types come
     * from the worldgen maps. No write has bypassed the worldgen maps while no final map exists.
     */
    public static void prime(ChunkAccess chunk, Set<Heightmap.Types> types, BiConsumer<ChunkAccess, Set<Heightmap.Types>> vanilla) {
        prime(chunk, types, vanilla, true);
    }

    /**
     * The priming triggered by a write into a chunk without final heightmaps. The block is already in the section, and
     * the maps are updated with it right after. The verify flag does not apply here. Vanilla's scan would see the new
     * block before the update that both sides make.
     */
    public static void primeForWrite(ChunkAccess chunk, Set<Heightmap.Types> types, BiConsumer<ChunkAccess, Set<Heightmap.Types>> vanilla) {
        prime(chunk, types, vanilla, false);
    }

    private static void prime(ChunkAccess chunk, Set<Heightmap.Types> types, BiConsumer<ChunkAccess, Set<Heightmap.Types>> vanilla, boolean verify) {
        if (!ENABLED || chunk.getClass() != ProtoChunk.class || chunk.isUpgrading() || chunk.getBelowZeroRetrogen() != null) {
            VANILLA.increment();
            vanilla.accept(chunk, types);
            return;
        }
        boolean allPresent = true;
        for (Heightmap.Types type : types) {
            if (!chunk.hasPrimedHeightmap(type)) {
                allPresent = false;
                break;
            }
        }
        if (allPresent) {
            KEPT.increment();
        } else if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE_WG) || !chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)) {
            VANILLA.increment();
            vanilla.accept(chunk, types);
            return;
        } else {
            // A write primes every final map at once. With one missing, no write has happened, so the worldgen maps are exact.
            // A final map that exists on its own (from a height read before any write) is derived again like the others.
            FAST.increment();
            Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            List<Heightmap> scan = new ArrayList<>(2);
            for (Heightmap.Types type : types) {
                switch (type) {
                    case WORLD_SURFACE -> copy(chunk, worldSurface, type);
                    case OCEAN_FLOOR -> copy(chunk, chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG), type);
                    default -> scan.add(chunk.getOrCreateHeightmapUnprimed(type));
                }
            }
            if (!scan.isEmpty()) {
                scan(chunk, worldSurface, scan);
            }
        }
        if (verify && VERIFY_EVERY > 0 && VERIFY_SEEN.incrementAndGet() % VERIFY_EVERY == 0) {
            verify(chunk, types, vanilla);
        }
    }

    /** {@code -Dgeneratoraccelerator2.verify.prime=N} redoes every N-th priming with vanilla's scan and compares. Vanilla's result is kept. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.prime", 0);
    private static final AtomicLong VERIFY_SEEN = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();

    private static void verify(ChunkAccess chunk, Set<Heightmap.Types> types, BiConsumer<ChunkAccess, Set<Heightmap.Types>> vanilla) {
        Heightmap.Types[] order = types.toArray(new Heightmap.Types[0]);
        long[][] mine = new long[order.length][];
        for (int i = 0; i < order.length; i++) {
            mine[i] = chunk.getOrCreateHeightmapUnprimed(order[i]).getRawData().clone();
        }
        vanilla.accept(chunk, types);
        VERIFIED.incrementAndGet();
        for (int i = 0; i < order.length; i++) {
            Heightmap theirs = chunk.getOrCreateHeightmapUnprimed(order[i]);
            if (!Arrays.equals(mine[i], theirs.getRawData()) && DIFFERING.incrementAndGet() <= 10) {
                // Log the first differing columns, with both heights and the blocks at them.
                StringBuilder sb = new StringBuilder();
                Heightmap view = chunk.getOrCreateHeightmapUnprimed(order[i]);
                long[] vanillaRaw = view.getRawData().clone();
                view.setRawData(chunk, order[i], mine[i]);
                int shown = 0;
                for (int x = 0; x < 16 && shown < 4; x++) {
                    for (int z = 0; z < 16 && shown < 4; z++) {
                        int h1 = view.getFirstAvailable(x, z);
                        view.setRawData(chunk, order[i], vanillaRaw);
                        int h2 = view.getFirstAvailable(x, z);
                        view.setRawData(chunk, order[i], mine[i]);
                        if (h1 != h2) {
                            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                            sb.append(String.format(" col %d,%d mine %d (below it %s) vanilla %d (below it %s)", x, z, h1, chunk.getBlockState(p.set(x, h1 - 1, z)),
                                h2, chunk.getBlockState(p.set(x, h2 - 1, z))));
                            shown++;
                        }
                    }
                }
                view.setRawData(chunk, order[i], vanillaRaw);
                Ga2Log.get("Verify").warn("heightmap prime mismatch: chunk {} ({}) heightmap {} vs vanilla scan:{}",
                    chunk.getPos(), chunk.getPersistedStatus().getName(), order[i], sb);
            }
        }
    }

    private static void copy(ChunkAccess chunk, Heightmap from, Heightmap.Types type) {
        chunk.getOrCreateHeightmapUnprimed(type).setRawData(chunk, type, from.getRawData());
    }

    /** Vanilla's scan, started at each column's highest non-air block. */
    private static void scan(ChunkAccess chunk, Heightmap top, List<Heightmap> maps) {
        int n = maps.size();
        Heightmap[] pending = new Heightmap[n];
        int minY = chunk.getMinBuildHeight();
        int filled = chunk.getHighestFilledSectionIndex();
        int highest = filled == -1 ? minY - 1 : SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(filled)) + 15;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int i = 0; i < n; i++) {
                    pending[i] = maps.get(i);
                }
                int left = n;
                int start = Math.min(highest, top.getFirstAvailable(x, z) - 1);
                for (int y = start; y >= minY; y--) {
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    if (!state.is(Blocks.AIR)) {
                        for (int i = 0; i < n; i++) {
                            Heightmap map = pending[i];
                            if (map != null && ((FillHeights.Ga2Heightmap) (Object) map).ga2$isOpaque().test(state)) {
                                ((FillHeights.Ga2Heightmap) (Object) map).ga2$setHeight(x, z, y + 1);
                                pending[i] = null;
                                left--;
                            }
                        }
                        if (left == 0) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
