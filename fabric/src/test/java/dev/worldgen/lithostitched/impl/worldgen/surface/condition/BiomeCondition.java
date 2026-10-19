package dev.worldgen.lithostitched.impl.worldgen.surface.condition;

import java.lang.reflect.Field;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Test copy of Lithostitched 1.8's {@code BiomeCondition} (same name, shape and semantics, but no codec). Lithostitched
 * reads {@code Context.biome} through a mixin accessor ({@code biomes.contains(this.biome.get())}). The copy reads the field
 * reflectively.
 */
public record BiomeCondition(HolderSet<Biome> biomes) implements SurfaceRules.ConditionSource {
    private static Field biomeField;

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        throw new UnsupportedOperationException("test copy");
    }

    @Override
    public SurfaceRules.Condition apply(SurfaceRules.Context context) {
        return () -> this.biomes.contains(biome(context));
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome> biome(SurfaceRules.Context context) {
        try {
            if (biomeField == null) {
                Field f = SurfaceRules.Context.class.getDeclaredField("biome");
                f.setAccessible(true);
                biomeField = f;
            }
            return ((Supplier<Holder<Biome>>) biomeField.get(context)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
