package dev.denismasterherobrine.generatoraccelerator2.compat.yungscavebiomes;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.reflect.Field;

/**
 * YUNG's Cave Biomes' final density rewire ({@link YungsCaveBiomesMixins#MARBLE_CAVES}). The rewire only changes the final density
 * while the mod's marble caves are on. The switch is read when a hook first asks. A missing switch counts as on, and the hooks
 * then keep the mod's final density.
 */
public final class YungsCaveBiomesNoise {
    private YungsCaveBiomesNoise() {}

    private static final String COMMON = "com.yungnickyoung.minecraft.yungscavebiomes.YungsCaveBiomesCommon";

    public static void register() {
        CompatAdapters.register(HookTargets.NOISE_FINAL_DENSITY, YungsCaveBiomesMixins.MARBLE_CAVES, YungsCaveBiomesNoise::marbleCavesOff);
        CompatAdapters.register(HookTargets.GPU_NOISE, YungsCaveBiomesMixins.MARBLE_CAVES, YungsCaveBiomesNoise::marbleCavesOff);
    }

    private static boolean marbleCavesOff() {
        Class<?> common = Reflect.type(COMMON);
        if (common == null) {
            return false;
        }
        try {
            Field enabled = common.getField("MARBLE_CAVES_ENABLED");
            return enabled.getType() == boolean.class && !enabled.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
