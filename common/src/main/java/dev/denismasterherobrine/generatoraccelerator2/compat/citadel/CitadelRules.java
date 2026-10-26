package dev.denismasterherobrine.generatoraccelerator2.compat.citadel;

import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.invoke.MethodHandle;
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
        MethodHandle handler = Reflect.mergedMethod(NoiseBasedChunkGenerator.class, "$citadel$mergeSurfaceRules", false,
            SurfaceRules.RuleSource.class, SurfaceRules.RuleSource.class);
        if (handler == null) {
            return false;
        }
        CarverRunner.surfaceRuleModifier = (generator, rule) -> {
            try {
                return (SurfaceRules.RuleSource) handler.invoke(generator, rule);
            } catch (Throwable t) {
                throw Reflect.unchecked(t);
            }
        };
        return true;
    }
}
