package dev.worldgen.lithostitched.impl.worldgen.biomeinjector;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;

/**
 * Stand-in for Lithostitched's partial replacement (same class name, codec field and {@code targets()} accessor) so GA2's
 * {@code LithostitchedInjectors} can be tested without the mod: {@code matches} answers no right away for a biome its
 * targets do not hold, then asks its parameters, as the mod's does.
 */
public record ReplacePartially(HolderSet<Biome> targets, Holder<Biome> replacement, Predicate<Holder<Biome>> parameters) {
    public static final Object CODEC = new Object();

    public boolean matches(Holder<Biome> biome) {
        if (!targets().contains(biome)) {
            return false;
        }
        return parameters.test(biome);
    }
}
