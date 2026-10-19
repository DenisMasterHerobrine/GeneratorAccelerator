package dev.denismasterherobrine.generatoraccelerator2.mixin.biome;

import dev.denismasterherobrine.generatoraccelerator2.biome.FusedLookup;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code getNoiseBiome(x, y, z, sampler)}. When the sample and the climate search are both GA2's, they run in one step
 * ({@link FusedLookup}). Otherwise vanilla's two calls run. Copied over vanilla's body when no other mod changes it
 * ({@code compat.LateOverwrites}).
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 5000)
public abstract class BiomeSourceLookupMixin {
    @Shadow
    private Climate.ParameterList<Holder<Biome>> parameters() {
        throw new AssertionError();
    }

    @Shadow
    public abstract Holder<Biome> getNoiseBiome(Climate.TargetPoint target);

    @Unique
    private Holder<Biome> ga2$getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        if (FusedLookup.ready(((Object) this).getClass() == MultiNoiseBiomeSource.class)) {
            Holder<Biome> fused = FusedLookup.lookup(this.parameters(), x, y, z, sampler);
            if (fused != null) {
                return fused;
            }
        }
        return this.getNoiseBiome(sampler.sample(x, y, z));
    }
}
