package terrablender.worldgen.surface;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import java.lang.reflect.Field;

/**
 * Test copy of TerraBlender 4.1's {@code NamespacedSurfaceRuleSource} (same name, shape and rule semantics, no codec).
 * It lets GA2's lowering of the source be compared with what the original rule objects do.
 */
public record NamespacedSurfaceRuleSource(SurfaceRules.RuleSource base, Map<String, SurfaceRules.RuleSource> sources) implements SurfaceRules.RuleSource {
    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
        throw new UnsupportedOperationException("test copy");
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        ImmutableMap.Builder<String, SurfaceRules.SurfaceRule> rules = new ImmutableMap.Builder<>();
        this.sources.entrySet().forEach(entry -> rules.put(entry.getKey(), entry.getValue().apply(context)));
        return new NamespacedRule(context, this.base.apply(context), rules.build());
    }

    /** TerraBlender widens {@code Context.biome} with an access transformer. The test copy reads it reflectively. */
    @SuppressWarnings("unchecked")
    static Holder<Biome> biome(SurfaceRules.Context context) {
        try {
            if (BIOME == null) {
                Field f = SurfaceRules.Context.class.getDeclaredField("biome");
                f.setAccessible(true);
                BIOME = f;
            }
            return ((Supplier<Holder<Biome>>) BIOME.get(context)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field BIOME;

    record NamespacedRule(SurfaceRules.Context context, SurfaceRules.SurfaceRule baseRule, Map<String, SurfaceRules.SurfaceRule> rules) implements SurfaceRules.SurfaceRule {
        @Override
        public BlockState tryApply(int x, int y, int z) {
            Holder<Biome> biome = biome(this.context);
            BlockState state = null;
            if (biome.is(key -> this.rules.containsKey(key.identifier().getNamespace()))) {
                state = this.rules.get(biome.unwrapKey().get().identifier().getNamespace()).tryApply(x, y, z);
            }
            if (state == null) {
                state = this.baseRule.tryApply(x, y, z);
            }
            return state;
        }
    }
}
