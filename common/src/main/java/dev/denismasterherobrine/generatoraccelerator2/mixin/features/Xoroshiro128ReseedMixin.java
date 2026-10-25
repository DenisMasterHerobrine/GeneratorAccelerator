package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.fast.Reseed;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The state {@code new Xoroshiro128PlusPlus(seedLo, seedHi)} would start from, set on this generator ({@code XoroshiroReseedMixin}). */
@Mixin(Xoroshiro128PlusPlus.class)
public abstract class Xoroshiro128ReseedMixin implements Reseed {
    @Shadow
    private long seedLo;
    @Shadow
    private long seedHi;

    @Override
    public void ga2$reseed(long lo, long hi) {
        this.seedLo = lo;
        this.seedHi = hi;
        if ((lo | hi) == 0L) {
            this.seedLo = -7046029254386353131L;
            this.seedHi = 7640891576956012809L;
        }
    }
}
