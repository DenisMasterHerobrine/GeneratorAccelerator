package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.BoundedFloatFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code CubicSpline.Multipoint.mapAll} without its stream. Every NoiseChunk maps its router, and with it every point of every
 * spline, through a stream pipeline per point. Each pipeline allocates a spliterator, the pipeline stages and a node builder.
 * That is the largest share of the garbage a NoiseChunk makes during construction. This version makes the same visits in the
 * same order: the coordinate first, then each value in order. The order matters because NoiseChunk's {@code wrap} registers
 * interpolators in the order it meets them. It calls the same {@code create} with the same arguments, and passes an unmodifiable
 * list of the mapped values, as {@code Stream.toList} returns. Copied over vanilla's body when no other mod changes it
 * ({@code compat.LateOverwrites}).
 */
@Mixin(value = CubicSpline.Multipoint.class, priority = 5000)
public abstract class SplineMultipointMapMixin<C, I extends BoundedFloatFunction<C>> {
    @Shadow @Final private I coordinate;
    @Shadow @Final private float[] locations;
    @Shadow @Final private List<CubicSpline<C, I>> values;
    @Shadow @Final private float[] derivatives;

    @Shadow
    static <C, I extends BoundedFloatFunction<C>> CubicSpline.Multipoint<C, I> create(I coordinate, float[] locations, List<CubicSpline<C, I>> values, float[] derivatives) {
        throw new AssertionError();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private CubicSpline<C, I> ga2$mapAll(CubicSpline.CoordinateVisitor<I> visitor) {
        I mappedCoordinate = visitor.visit(this.coordinate);
        List<CubicSpline<C, I>> points = this.values;
        Object[] mapped = new Object[points.size()];
        for (int i = 0; i < mapped.length; i++) {
            mapped[i] = points.get(i).mapAll(visitor);
        }
        return create(mappedCoordinate, this.locations, (List<CubicSpline<C, I>>) (List<?>) Collections.unmodifiableList(Arrays.asList(mapped)), this.derivatives);
    }
}
