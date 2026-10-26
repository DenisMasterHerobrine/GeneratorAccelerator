package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Shared transcriptions of the {@code Feature} helpers the fast paths use. */
final class Fast {
    private Fast() {}

    /** Returned by a fast path when vanilla must run this call. */
    static final Object FALLBACK = new Object();

    /** Exact {@code Feature.safeSetBlock}: read, test, write with flags the region ignores. */
    static void safeSetBlock(SectionCursor cursor, BlockPos pos, BlockState state, Predicate<BlockState> predicate) {
        if (predicate.test(cursor.read(pos))) {
            RegionWriter.setBlock(cursor.region(), pos, state);
        }
    }

    /** Exact {@code Feature.markAboveForPostProcessing}. */
    static void markAboveForPostProcessing(SectionCursor cursor, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int i = 0; i < 2; i++) {
            m.move(Direction.UP);
            if (cursor.read(m).isAir()) {
                return;
            }
            cursor.chunk(m.getX() >> 4, m.getZ() >> 4).markPosForPostprocessing(m);
        }
    }
}
