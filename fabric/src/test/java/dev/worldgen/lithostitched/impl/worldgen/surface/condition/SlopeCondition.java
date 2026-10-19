package dev.worldgen.lithostitched.impl.worldgen.surface.condition;

import java.lang.reflect.Field;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Test copy of Lithostitched 1.8's {@code SlopeCondition} (same name, shape and semantics, but no codec). Lithostitched's
 * condition extends {@code SurfaceRules.LazyXZCondition} (made public by its access transformer) and reads the context
 * through a mixin accessor. The copy keeps the same per-column laziness ({@code LazyCondition.test}) and reads the context
 * reflectively.
 */
public record SlopeCondition(InclusiveRange<Integer> threshold) implements SurfaceRules.ConditionSource {
    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        throw new UnsupportedOperationException("test copy");
    }

    @Override
    public SurfaceRules.Condition apply(SurfaceRules.Context context) {
        return new Condition(context, this.threshold);
    }

    static final class Condition implements SurfaceRules.Condition {
        private final SurfaceRules.Context context;
        private final InclusiveRange<Integer> threshold;
        private long lastUpdate;
        private Boolean result;

        Condition(SurfaceRules.Context context, InclusiveRange<Integer> threshold) {
            this.context = context;
            this.threshold = threshold;
            this.lastUpdate = get(context, "lastUpdateXZ", Long.class) - 1L;
        }

        @Override
        public boolean test() {
            long i = get(context, "lastUpdateXZ", Long.class);
            if (i == this.lastUpdate) {
                return this.result;
            }
            this.lastUpdate = i;
            this.result = compute();
            return this.result;
        }

        private boolean compute() {
            ChunkAccess chunk = get(context, "chunk", ChunkAccess.class);
            int x = get(context, "blockX", Integer.class) & 15;
            int z = get(context, "blockZ", Integer.class) & 15;
            int north = Math.max(z - 1, 0);
            int south = Math.min(z + 1, 15);
            int west = Math.max(x - 1, 0);
            int east = Math.min(x + 1, 15);
            int northHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, north);
            int southHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, south);
            int westHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, west, z);
            int eastHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, east, z);
            int max = Math.max(Math.max(northHeight, southHeight), Math.max(eastHeight, westHeight));
            int min = Math.min(Math.min(northHeight, southHeight), Math.min(eastHeight, westHeight));
            return this.threshold.isValueInRange(max - min);
        }
    }

    private static <T> T get(SurfaceRules.Context context, String name, Class<T> type) {
        try {
            Field f = SurfaceRules.Context.class.getDeclaredField(name);
            f.setAccessible(true);
            return type.cast(f.get(context));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
