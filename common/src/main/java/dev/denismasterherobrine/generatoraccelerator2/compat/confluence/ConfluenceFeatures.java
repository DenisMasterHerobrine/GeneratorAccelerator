package dev.denismasterherobrine.generatoraccelerator2.compat.confluence;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.apache.commons.lang3.mutable.MutableBoolean;

/**
 * Confluence's placement wrapper in GA2's placement pipeline. Confluence's {@code PlacedFeatureMixin} is a
 * {@code @WrapOperation} of the {@code forEach} that places a placed feature at each of its positions. At each position,
 * GA2's pipeline calls Confluence's own handler. It passes a one-position stream, vanilla's consumer, and vanilla's
 * {@code forEach} as the wrapped call. The handler wraps the consumer per position. It offers a tree feature's position
 * to Confluence's pine replacement first. Feeding the handler one position at a time, in vanilla's order, is therefore the
 * same as feeding it the whole lazy stream.
 */
public final class ConfluenceFeatures {
    private ConfluenceFeatures() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey(FeatureTargets.PLACEMENT), ConfluenceMixins.PLACED_FEATURE, ConfluenceFeatures::setUp);
        // Nothing to set up. GA2's section tags only encode, and Confluence's decode fixes leave encoding unchanged.
        CompatAdapters.register(HookTargets.STORAGE_SECTION_CODEC, ConfluenceMixins.CHUNK_SERIALIZER, () -> true);
    }

    private static final Operation<Void> FOR_EACH = args -> {
        @SuppressWarnings("unchecked")
        Stream<BlockPos> stream = (Stream<BlockPos>) args[0];
        @SuppressWarnings("unchecked")
        Consumer<BlockPos> consumer = (Consumer<BlockPos>) args[1];
        stream.forEach(consumer);
        return null;
    };

    private static boolean setUp() {
        Class<?>[] params = {Stream.class, Consumer.class, Operation.class, PlacementContext.class, RandomSource.class, MutableBoolean.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(PlacedFeature.class)) {
            if (m.getName().endsWith("$wrap") && m.getName().contains("confluence") && !Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == void.class && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    PlacementPipeline.position = (placed, pos, place, ctx, random, success) -> {
                        try {
                            handler.invoke(placed, Stream.of(pos), place, FOR_EACH, ctx, random, success);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IllegalStateException(t);
                        }
                    };
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        }
        return false;
    }
}
