package dev.denismasterherobrine.generatoraccelerator2.compat.citadel;

import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Citadel's merged surface rules in GA2's carver runner. Citadel's {@code NoiseBasedChunkGeneratorMixin} modifies the
 * {@code surfaceRule()} value in {@code applyCarvers}. The runner passes the generator's rule to Citadel's merged handler before
 * it builds the carving context, as vanilla's method does ({@link CarverRunner#surfaceRule}).
 */
public final class CitadelRules {
    private CitadelRules() {}

    public static void register() {
        CompatAdapters.register(HookTargets.CARVER_ENTRY, CitadelMixins.GENERATOR, CitadelRules::setUp);
    }

    private static boolean setUp() {
        Class<?>[] params = {SurfaceRules.RuleSource.class};
        for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(NoiseBasedChunkGenerator.class)) {
            if (m.getName().endsWith("$citadel$mergeSurfaceRules") && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == SurfaceRules.RuleSource.class
                && Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    MethodHandle handler = MethodHandles.lookup().unreflect(m);
                    CarverRunner.surfaceRuleModifier = (generator, rule) -> {
                        try {
                            return (SurfaceRules.RuleSource) handler.invoke(generator, rule);
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
