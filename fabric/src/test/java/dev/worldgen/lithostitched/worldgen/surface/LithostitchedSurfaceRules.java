package dev.worldgen.lithostitched.worldgen.surface;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** Test copy of Lithostitched 1.4 (1.20.1)'s merged surface rule, with the mod's names and apply. */
public final class LithostitchedSurfaceRules {
    private LithostitchedSurfaceRules() {}

    public record TransientMergedRuleSource(List<SurfaceRules.RuleSource> sequence, SurfaceRules.RuleSource original)
        implements SurfaceRules.RuleSource {
        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
            throw new UnsupportedOperationException("test only");
        }

        @Override
        public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
            if (sequence.size() == 1) {
                return sequence.get(0).apply(context);
            }
            ImmutableList.Builder<SurfaceRules.SurfaceRule> rules = ImmutableList.builder();
            for (SurfaceRules.RuleSource source : sequence) {
                rules.add(source.apply(context));
            }
            rules.add(original.apply(context));
            return new SequenceRule(rules.build());
        }
    }

    record SequenceRule(List<SurfaceRules.SurfaceRule> rules) implements SurfaceRules.SurfaceRule {
        @Override
        public BlockState tryApply(int x, int y, int z) {
            for (SurfaceRules.SurfaceRule rule : rules) {
                BlockState state = rule.tryApply(x, y, z);
                if (state != null) {
                    return state;
                }
            }
            return null;
        }
    }
}
