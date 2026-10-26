package dev.denismasterherobrine.generatoraccelerator2.compat.pastel;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
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
 * Pastel's gemstone ores ({@code GeodesGenerateWithGemstoneOresMixin}, a handler at the tail of {@code GeodeFeature.place}) in GA2's
 * geode. GA2's transcription calls Pastel's merged handler before the transcription's final {@code return true}, as vanilla's method
 * does ({@link FeatureWraps#geodeTail}). The handler reads only the context: the context's config, origin, random and level.
 * Spectrum, of which Pastel is the port, has the same handler ({@code compat.spectrum.SpectrumMixins}, {@link #bindTail}).
 */
public final class PastelGeodes {
    private PastelGeodes() {}

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("geode"), PastelMixins.GEODE_ORES, PastelGeodes::bindTail);
        // Pastel's block change event acts on the server thread only. There, the POI skip then keeps the call.
        CompatAdapters.register(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.STATE_POI_SKIP, PastelMixins.SERVER_LEVEL, () -> {
            dev.denismasterherobrine.generatoraccelerator2.state.PoiStates.keepOnServerThread();
            return true;
        });
    }

    /**
     * Binds the merged gemstone ore handler (Pastel's, or Spectrum's of which Pastel is the port) as the geode's tail. There must be
     * exactly one such handler: with both mods, which runs first would be the merged method's order.
     */
    public static boolean bindTail() {
        Class<?>[] params = {FeaturePlaceContext.class, CallbackInfoReturnable.class};
        Method found = null;
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(GeodeFeature.class)) {
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
