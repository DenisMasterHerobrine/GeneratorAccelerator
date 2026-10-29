package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.verify.FeatureCompare;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * Checks a step's fast path against vanilla in a running game. With {@code -Dgeneratoraccelerator2.verify.<step>=N},
 * every N-th chunk the step's fast path builds is first copied ({@link ChunkCopy}). The copy is built by vanilla's code,
 * including every other mod's changes to that code. Both results are then compared block by block
 * ({@link FeatureCompare}). Differences are logged, and {@code /ga2 compat report} shows the counts. Off by default,
 * because it builds the sampled chunks twice.
 */
public final class StepVerify {
    private static final Logger LOGGER = Ga2Log.get("Verify");

    private final String step;
    private final int every;
    private final AtomicLong seen = new AtomicLong();
    public final AtomicLong checked = new AtomicLong();
    public final AtomicLong differing = new AtomicLong();

    public StepVerify(String step) {
        this.step = step;
        this.every = Integer.getInteger("generatoraccelerator2.verify." + step, 0);
    }

    public boolean enabled() {
        return every > 0;
    }

    /** True for the chunks to check. */
    public boolean sample(ChunkAccess chunk) {
        return every > 0 && chunk.getClass() == ProtoChunk.class && seen.incrementAndGet() % every == 0;
    }

    /** Compares vanilla's result ({@code expected}) with the fast path's. Returns true when they differ. */
    public boolean compare(ProtoChunk expected, ChunkAccess actual) {
        return compare(null, expected, actual);
    }

    /**
     * Compares vanilla's result ({@code expected}) with the fast path's. {@code before} is the chunk as it was before the
     * step, and may be null. The first differing column is also logged as before, vanilla, and fast path.
     */
    public boolean compare(ProtoChunk before, ProtoChunk expected, ChunkAccess actual) {
        checked.incrementAndGet();
        List<String> diffs = FeatureCompare.compare(expected, (ProtoChunk) actual, 6);
        if (diffs.isEmpty()) {
            return false;
        }
        {
            long n = differing.incrementAndGet();
            LOGGER.warn("{}: chunk {} mismatch vs vanilla: {}", step, actual.getPos(), diffs);
            if (n <= 3) {
                LOGGER.warn("{}: first mismatched column in chunk {}: {}", step, actual.getPos(), column(before, expected, actual));
            }
        }
        return true;
    }

    private static String column(ProtoChunk before, ProtoChunk expected, ChunkAccess actual) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = actual.getPos().getMinBlockX();
        int minZ = actual.getPos().getMinBlockZ();
        for (int y = actual.getMaxY(); y >= actual.getMinY(); y--) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    pos.set(minX + lx, y, minZ + lz);
                    if (expected.getBlockState(pos) != actual.getBlockState(pos)) {
                        Heightmap.Types ws = Heightmap.Types.WORLD_SURFACE_WG;
                        StringBuilder sb = new StringBuilder("x=" + (minX + lx) + " z=" + (minZ + lz) + " world surface before "
                            + (before == null ? "?" : before.getHeight(ws, lx, lz)) + ", vanilla " + expected.getHeight(ws, lx, lz) + ", fast "
                            + actual.getHeight(ws, lx, lz) + "; status " + (before == null ? "?" : before.getPersistedStatus()) + " (y: before / vanilla / fast)");
                        for (int yy = Math.min(actual.getMaxY(), y + 6); yy >= Math.max(actual.getMinY(), y - 40); yy--) {
                            pos.setY(yy);
                            sb.append("\n  ").append(yy).append(": ").append(before == null ? "?" : name(before.getBlockState(pos))).append(" / ")
                                .append(name(expected.getBlockState(pos))).append(" / ").append(name(actual.getBlockState(pos)));
                        }
                        return sb.toString();
                    }
                }
            }
        }
        return "none";
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    public String summary() {
        return step + ": " + checked.get() + " chunks checked against vanilla, " + differing.get() + " differ";
    }
}
