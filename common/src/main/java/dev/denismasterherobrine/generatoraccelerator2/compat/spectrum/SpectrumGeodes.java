package dev.denismasterherobrine.generatoraccelerator2.compat.spectrum;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spectrum's gemstone ores ({@code GeodesGenerateWithGemstoneOresMixin}, a handler at the tail of {@code GeodeFeature.place}) in GA2's
 * geode. GA2's transcription calls Spectrum's merged handler before the transcription's final {@code return true}, as vanilla's method
 * does ({@link FeatureWraps#geodeTail}). The handler reads only the context: the context's config, origin, random and level.
 */
public final class SpectrumGeodes {
    private SpectrumGeodes() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("geode"), SpectrumMixins.GEODE_ORES, SpectrumGeodes::bindTail);
    }

    /** Binds the merged gemstone ore handler as the geode's tail. There must be exactly one such handler. */
    static boolean bindTail() {
        Class<?>[] params = {FeaturePlaceContext.class, CallbackInfoReturnable.class};
        Method found = null;
        for (Method m : Reflect.declaredMethods(GeodeFeature.class)) {
            if (m.getName().endsWith("$generate") && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), params)) {
                if (found != null) {
                    return false;
                }
                found = m;
            }
        }
        if (found == null) {
            return false;
        }
        try {
            found.setAccessible(true);
            MethodHandle handler = MethodHandles.lookup().unreflect(found);
            FeatureWraps.geodeTail = (feature, ctx) -> {
                try {
                    handler.invoke(feature, ctx, new CallbackInfoReturnable<Boolean>("place", false, Boolean.TRUE));
                } catch (Throwable t) {
                    throw Reflect.unchecked(t);
                }
            };
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
