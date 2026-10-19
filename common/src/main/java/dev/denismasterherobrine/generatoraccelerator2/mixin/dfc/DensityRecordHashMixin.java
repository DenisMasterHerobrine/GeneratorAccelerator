package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches the hash code of the density function records. A record computes its hash code from its components on every
 * call, and the components of a density function are density functions. Hashing the root of a modded terrain router
 * therefore walks the whole tree. Vanilla's NoiseChunk looks up every router node in a hash map while it wires the
 * functions of a chunk. Lithostitched keys a map by its region functions on every noise biome lookup. As a result, the
 * same trees were hashed again and again (about 5% of worker time with Terralith, Tectonic and Lithostitched).
 * The components are immutable (density functions, holders, noises, numbers), so the first result is always the result.
 * The result is stored in an int that stays 0 until known, as {@code String} does. A hash code of 0 is recomputed on
 * every call. Another thread reads the int complete or not at all, and no object is allocated per function.
 */
@Mixin({
    DensityFunctions.Ap2.class, DensityFunctions.MulOrAdd.class, DensityFunctions.Mapped.class, DensityFunctions.Clamp.class,
    DensityFunctions.RangeChoice.class, DensityFunctions.Marker.class, DensityFunctions.HolderHolder.class, DensityFunctions.Noise.class,
    DensityFunctions.ShiftedNoise.class, DensityFunctions.ShiftA.class, DensityFunctions.ShiftB.class, DensityFunctions.Shift.class,
    DensityFunctions.WeirdScaledSampler.class, DensityFunctions.BlendDensity.class, DensityFunctions.Spline.class,
    DensityFunctions.Spline.Coordinate.class, DensityFunction.NoiseHolder.class})
public abstract class DensityRecordHashMixin {
    @Unique
    private int ga2$hash;

    @WrapMethod(method = "hashCode")
    private int ga2$cachedHash(Operation<Integer> original) {
        int h = this.ga2$hash;
        if (h == 0) {
            h = original.call();
            this.ga2$hash = h;
        }
        return h;
    }
}
