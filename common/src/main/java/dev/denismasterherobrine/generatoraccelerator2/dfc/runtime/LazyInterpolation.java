package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

/**
 * Lazy z step of NoiseChunk interpolation.
 *
 * <p>Vanilla's {@code NoiseChunk.updateForZ} runs for every block of a noise fill. It updates every
 * interpolator ({@code value = lerp(dz, valueZ0, valueZ1)}), although most interpolators are not read for
 * most blocks. The final density is read from the cell cache. The ore vein interpolators are read only for
 * solid blocks. With this on, {@code updateForZ} only records {@code dz}. An interpolator read outside cell
 * filling computes the same {@code lerp} from the same three numbers. Vanilla reads {@code value} in one
 * place. Both callers of {@code updateForZ} (the noise fill and the column probe) read only after it, and
 * before the next {@code updateForX} changes {@code valueZ0}/{@code valueZ1}. So every read returns the
 * same double.
 *
 * <p>{@code -Dgeneratoraccelerator2.lazyInterpolation=false} turns it off.
 */
public final class LazyInterpolation {
    private LazyInterpolation() {}

    /** Not final so the exactness test can compare both ways; the noise fill reads it per block. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lazyInterpolation"));
}
