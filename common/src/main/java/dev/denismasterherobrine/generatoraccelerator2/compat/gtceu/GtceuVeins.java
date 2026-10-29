package dev.denismasterherobrine.generatoraccelerator2.compat.gtceu;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import org.slf4j.Logger;

/**
 * GregTech CEu's {@code removeVanillaLargeOreVeins} ({@link GtceuMixins#ORE_VEINS}). With it on, the vein rule never places a vein. The
 * terrain then equals the terrain without veins, which GA2's fill and GPU program make directly. With it off, GregTech's handler does
 * nothing and vanilla's rule stays. GregTech reads the setting each time a chunk's vein rule is made, and so does GA2.
 */
public final class GtceuVeins {
    private GtceuVeins() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    /** {@code ConfigHolder.INSTANCE}, {@code .worldgen}, {@code .oreVeins}, {@code .removeVanillaLargeOreVeins}. Null without GregTech. */
    @Nullable
    private static volatile Field[] path;

    /** Finds GregTech's setting. The vein hook stays on for GregTech's mixin only when GA2 can read it. */
    public static void register() {
        Class<?> holder = Reflect.type("com.gregtechceu.gtceu.config.ConfigHolder");
        if (holder == null) {
            return;
        }
        try {
            Field instance = holder.getField("INSTANCE");
            Field worldgen = holder.getField("worldgen");
            Field oreVeins = worldgen.getType().getField("oreVeins");
            Field remove = oreVeins.getType().getField("removeVanillaLargeOreVeins");
            path = new Field[] { instance, worldgen, oreVeins, remove };
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("GregTech's ore vein setting not found -> vein hook stands down for {}", GtceuMixins.ORE_VEINS, e);
            return;
        }
        CompatAdapters.register(HookTargets.NOISE_VEIN_RULE, GtceuMixins.ORE_VEINS, () -> true);
    }

    /** Whether GregTech's vein rule places no vein right now. False without GregTech or before its config loads (vanilla's rule then). */
    public static boolean removesVeins() {
        Field[] p = path;
        if (p == null) {
            return false;
        }
        try {
            Object holder = p[0].get(null);
            Object worldgen = holder == null ? null : p[1].get(holder);
            Object veins = worldgen == null ? null : p[2].get(worldgen);
            return veins != null && p[3].getBoolean(veins);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("GregTech's ore vein setting became unreadable", e);
        }
    }
}
