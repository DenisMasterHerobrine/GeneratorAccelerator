package dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal;

import com.mojang.serialization.MapCodec;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedGpuBiomes;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Stand-in for Lithostitched's injector source (same class name, delegate accessors and lookup order), for GA2's tests without
 * the mod. Its {@code getNoiseBiome} samples the climate point, asks the base resolver, then replaces a target biome where the
 * point is warm and deep enough, as a partial replacement does. Its two lookups go through {@link LithostitchedGpuBiomes}, as GA2's
 * mixin routes Lithostitched's.
 */
public class InjectorBiomeSource extends BiomeSource {
    private final BiomeSource directDelegate;
    public BiomeResolver baseResolver;
    private final Set<Holder<Biome>> targets;
    private final Holder<Biome> replacement;

    public InjectorBiomeSource(BiomeSource directDelegate, Set<Holder<Biome>> targets, Holder<Biome> replacement) {
        this.directDelegate = directDelegate;
        this.baseResolver = directDelegate;
        this.targets = targets;
        this.replacement = replacement;
    }

    public BiomeSource rootDelegate() {
        return directDelegate;
    }

    public BiomeSource directDelegate() {
        return directDelegate;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return null;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(directDelegate.possibleBiomes().stream(), Stream.of(replacement));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        Climate.TargetPoint point = LithostitchedGpuBiomes.point(sampler, x, y, z, a -> ((Climate.Sampler) a[0]).sample((int) a[1], (int) a[2], (int) a[3]));
        Holder<Biome> biome = LithostitchedGpuBiomes.base(baseResolver, x, y, z, sampler,
            a -> ((BiomeResolver) a[0]).getNoiseBiome((int) a[1], (int) a[2], (int) a[3], (Climate.Sampler) a[4]));
        if (targets.contains(biome) && point.temperature() > -1500L && point.depth() < 3000L) {
            return replacement;
        }
        return biome;
    }
}
