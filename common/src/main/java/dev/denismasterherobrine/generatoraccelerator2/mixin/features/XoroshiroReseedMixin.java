package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.fast.Reseed;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code setSeed} reseeds the source's generator in place. Vanilla makes two seed records and a new generator per call, and
 * decoration reseeds its random for every feature of every chunk. The state is the same: {@code upgradeSeedTo128bit} spelled out,
 * and the generator's zero-seed rule ({@code Xoroshiro128ReseedMixin}). The source keeps its generator to itself, so replacing its
 * state is the same as replacing it. The body takes vanilla's place only when no other mod changed {@code setSeed}
 * ({@code compat.LateOverwrites}).
 */
@Mixin(XoroshiroRandomSource.class)
public abstract class XoroshiroReseedMixin {
    @Shadow
    private Xoroshiro128PlusPlus randomNumberGenerator;
    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    /** The body of {@code setSeed(seed)}. */
    @Unique
    public void ga2$setSeed(long seed) {
        long lo = seed ^ RandomSupport.SILVER_RATIO_64;
        long hi = lo + RandomSupport.GOLDEN_RATIO_64;
        ((Reseed) (Object) this.randomNumberGenerator).ga2$reseed(RandomSupport.mixStafford13(lo), RandomSupport.mixStafford13(hi));
        this.gaussianSource.reset();
    }
}
