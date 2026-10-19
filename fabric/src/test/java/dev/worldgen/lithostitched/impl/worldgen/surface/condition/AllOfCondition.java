package dev.worldgen.lithostitched.impl.worldgen.surface.condition;

import java.util.List;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** Test copy of Lithostitched 1.8's {@code AllOfCondition} (same name, shape and semantics, no codec). */
public record AllOfCondition(List<SurfaceRules.ConditionSource> conditions) implements SurfaceRules.ConditionSource {
    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        throw new UnsupportedOperationException("test copy");
    }

    @Override
    public SurfaceRules.Condition apply(SurfaceRules.Context context) {
        return new Condition(this.conditions.stream().map(source -> (SurfaceRules.Condition) source.apply(context)).toList());
    }

    record Condition(List<SurfaceRules.Condition> conditions) implements SurfaceRules.Condition {
        @Override
        public boolean test() {
            for (SurfaceRules.Condition condition : this.conditions) {
                if (!condition.test()) {
                    return false;
                }
            }
            return true;
        }
    }
}
