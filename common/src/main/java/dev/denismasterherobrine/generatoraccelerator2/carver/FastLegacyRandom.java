package dev.denismasterherobrine.generatoraccelerator2.carver;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * {@code LegacyRandomSource} with a plain {@code long} seed instead of an {@code AtomicLong} with a
 * compare-and-set per draw. Same seeding, same LCG step, same derived draws (they are
 * {@code BitRandomSource}'s defaults over {@link #next}), same Gaussian pairing. For use by one thread
 * on a source that no other code sees.
 */
public final class FastLegacyRandom implements BitRandomSource {
    private long seed;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public FastLegacyRandom(long seed) {
        setSeed(seed);
    }

    @Override
    public void setSeed(long seed) {
        this.seed = (seed ^ 25214903917L) & 281474976710655L;
        this.haveNextNextGaussian = false;
    }

    @Override
    public int next(int bits) {
        long j = this.seed * 25214903917L + 11L & 281474976710655L;
        this.seed = j;
        return (int) (j >> 48 - bits);
    }

    /** {@code WorldgenRandom.setLargeFeatureSeed} on a legacy source. */
    public void setLargeFeatureSeed(long baseSeed, int chunkX, int chunkZ) {
        setSeed(baseSeed);
        long i = nextLong();
        long j = nextLong();
        long k = (long) chunkX * i ^ (long) chunkZ * j ^ baseSeed;
        setSeed(k);
    }

    @Override
    public RandomSource fork() {
        return new LegacyRandomSource(this.nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return new LegacyRandomSource.LegacyPositionalRandomFactory(this.nextLong());
    }

    @Override
    public double nextGaussian() {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double d0;
        double d1;
        double d2;
        do {
            d0 = 2.0 * nextDouble() - 1.0;
            d1 = 2.0 * nextDouble() - 1.0;
            d2 = Mth.square(d0) + Mth.square(d1);
        } while (d2 >= 1.0 || d2 == 0.0);
        double d3 = Math.sqrt(-2.0 * Math.log(d2) / d2);
        nextNextGaussian = d1 * d3;
        haveNextNextGaussian = true;
        return d0 * d3;
    }
}
