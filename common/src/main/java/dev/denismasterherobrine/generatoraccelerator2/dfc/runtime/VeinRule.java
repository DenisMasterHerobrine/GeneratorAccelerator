package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.XoroshiroFactoryAccessor;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Vanilla's ore vein rule ({@code OreVeinifier.create}'s filler), without the positional random source vanilla builds for every
 * block past the toggle threshold. {@code XoroshiroPositionalRandomFactory.at(x, y, z)} and its up to three {@code nextFloat}
 * draws are transcribed on locals: {@code Xoroshiro128PlusPlus}'s seeding and {@code nextLong}, and {@code nextBits(24)} with the
 * same long to float conversion. That source was three objects per block (the source, its generator and its gaussian), the
 * largest allocation of the CPU noise fill. The same density functions are asked in the same order with the same context, so
 * every cache they reach sees the calls vanilla's rule makes. A VeinRule is made only for a filler the NoiseChunk built from
 * these exact arguments ({@code NoiseChunkMixin}). It is used only while no other mod changes the rule
 * ({@code HookTargets.NOISE_VEIN_RULE}).
 */
public final class VeinRule implements NoiseChunk.BlockStateFiller {
    private final DensityFunction toggle;
    private final DensityFunction ridged;
    private final DensityFunction gap;
    private final long seedLo;
    private final long seedHi;

    private VeinRule(DensityFunction toggle, DensityFunction ridged, DensityFunction gap, long seedLo, long seedHi) {
        this.toggle = toggle;
        this.ridged = ridged;
        this.gap = gap;
        this.seedLo = seedLo;
        this.seedHi = seedHi;
    }

    /** The transcription of {@code OreVeinifier.create(toggle, ridged, gap, random)}, or null for another kind of random factory. */
    @Nullable
    public static VeinRule of(DensityFunction toggle, DensityFunction ridged, DensityFunction gap, PositionalRandomFactory random) {
        if (random == null || random.getClass() != XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class) {
            return null;
        }
        XoroshiroFactoryAccessor seeds = (XoroshiroFactoryAccessor) random;
        return new VeinRule(toggle, ridged, gap, seeds.ga2$seedLo(), seeds.ga2$seedHi());
    }

    @Nullable
    @Override
    public BlockState calculate(DensityFunction.FunctionContext ctx) {
        double d0 = this.toggle.compute(ctx);
        int i = ctx.blockY();
        OreVeinifier.VeinType type = d0 > 0.0 ? OreVeinifier.VeinType.COPPER : OreVeinifier.VeinType.IRON;
        double d1 = Math.abs(d0);
        int j = type.maxY - i;
        int k = i - type.minY;
        if (k < 0 || j < 0) {
            return null;
        }
        int l = Math.min(j, k);
        double d2 = Mth.clampedMap((double) l, 0.0, 20.0, -0.2, 0.0);
        if (d1 + d2 < 0.4F) {
            return null;
        }
        // random.at(x, y, z): new Xoroshiro128PlusPlus(getSeed(x, y, z) ^ seedLo, seedHi)
        long lo = Mth.getSeed(ctx.blockX(), i, ctx.blockZ()) ^ this.seedLo;
        long hi = this.seedHi;
        if ((lo | hi) == 0L) {
            lo = -7046029254386353131L;
            hi = 7640891576956012809L;
        }
        // nextFloat(): nextLong() >>> 40, as a float, times 2^-24
        long r = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        lo = Long.rotateLeft(lo, 49) ^ hi ^ hi << 21;
        hi = Long.rotateLeft(hi, 28);
        if ((float) (r >>> 40) * 5.9604645E-8F > 0.7F) {
            return null;
        }
        if (this.ridged.compute(ctx) >= 0.0) {
            return null;
        }
        double d3 = Mth.clampedMap(d1, 0.4F, 0.6F, 0.1F, 0.3F);
        r = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        lo = Long.rotateLeft(lo, 49) ^ hi ^ hi << 21;
        hi = Long.rotateLeft(hi, 28);
        if ((double) ((float) (r >>> 40) * 5.9604645E-8F) < d3 && this.gap.compute(ctx) > -0.3F) {
            r = Long.rotateLeft(lo + hi, 17) + lo;
            return (float) (r >>> 40) * 5.9604645E-8F < 0.02F ? type.rawOreBlock : type.ore;
        }
        return type.filler;
    }
}
