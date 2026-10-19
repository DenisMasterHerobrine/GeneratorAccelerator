package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.LegacyFactoryAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.XoroshiroFactoryAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The first draw of {@code factory.at(x, y, z)} without building the random source. The surface rules
 * only ever take one {@code nextFloat} (gradients) or one {@code nextDouble} (surface depth) from a
 * positional random. Transcribes the seeding and first step of {@code Xoroshiro128PlusPlus} and of
 * {@code LegacyRandomSource}, with the same int/long to float/double conversions. Any other factory
 * is asked for a source as vanilla does.
 */
public final class PositionalDraws {
    private final int kind;
    private final PositionalRandomFactory factory;
    private final long lo;
    private final long hi;

    private static final int XOROSHIRO = 0;
    private static final int LEGACY = 1;
    private static final int OTHER = 2;

    public PositionalDraws(PositionalRandomFactory factory) {
        this.factory = factory;
        if (factory.getClass() == XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class) {
            XoroshiroFactoryAccessor acc = (XoroshiroFactoryAccessor) factory;
            kind = XOROSHIRO;
            lo = acc.ga2$seedLo();
            hi = acc.ga2$seedHi();
        } else if (factory.getClass() == LegacyRandomSource.LegacyPositionalRandomFactory.class) {
            kind = LEGACY;
            lo = ((LegacyFactoryAccessor) factory).ga2$seed();
            hi = 0L;
        } else {
            kind = OTHER;
            lo = 0L;
            hi = 0L;
        }
    }

    /** {@code factory.at(x, y, z).nextFloat()}. */
    public float nextFloat(int x, int y, int z) {
        switch (kind) {
            case XOROSHIRO -> {
                return (float) (xoroshiroFirst(Mth.getSeed(x, y, z) ^ lo, hi) >>> 40) * 5.9604645E-8F;
            }
            case LEGACY -> {
                long s = (Mth.getSeed(x, y, z) ^ lo ^ 25214903917L) & 281474976710655L;
                s = s * 25214903917L + 11L & 281474976710655L;
                return (float) (int) (s >> 24) * 5.9604645E-8F;
            }
            default -> {
                return factory.at(x, y, z).nextFloat();
            }
        }
    }

    /** {@code factory.at(x, y, z).nextDouble()}. */
    public double nextDouble(int x, int y, int z) {
        switch (kind) {
            case XOROSHIRO -> {
                return (double) (xoroshiroFirst(Mth.getSeed(x, y, z) ^ lo, hi) >>> 11) * 1.110223E-16F;
            }
            case LEGACY -> {
                long s = (Mth.getSeed(x, y, z) ^ lo ^ 25214903917L) & 281474976710655L;
                s = s * 25214903917L + 11L & 281474976710655L;
                int i = (int) (s >> 22);
                s = s * 25214903917L + 11L & 281474976710655L;
                int j = (int) (s >> 21);
                long k = ((long) i << 27) + (long) j;
                return (double) k * 1.110223E-16F;
            }
            default -> {
                return factory.at(x, y, z).nextDouble();
            }
        }
    }

    /** First {@code Xoroshiro128PlusPlus.nextLong()} of a generator seeded with (lo, hi). */
    private static long xoroshiroFirst(long seedLo, long seedHi) {
        if ((seedLo | seedHi) == 0L) {
            seedLo = -7046029254386353131L;
            seedHi = 7640891576956012809L;
        }
        return Long.rotateLeft(seedLo + seedHi, 17) + seedLo;
    }
}
