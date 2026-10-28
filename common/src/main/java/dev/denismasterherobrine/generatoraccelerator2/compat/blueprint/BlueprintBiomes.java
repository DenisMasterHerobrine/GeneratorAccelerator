package dev.denismasterherobrine.generatoraccelerator2.compat.blueprint;

import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import javax.annotation.Nullable;
import net.minecraft.world.level.biome.BiomeSource;

/**
 * Blueprint's {@code ModdedBiomeSource} wraps a dimension's own biome source. Per position it picks a slice by its own seeded noise.
 * The original slice, and every slice whose provider answers with Blueprint's marker, asks the wrapped source with the chunk's own
 * sampler. So the GPU's climate points and the searches of the wrapped source's tree can be handed to those lookups
 * ({@code gpu.runtime.BiomeHand}). The slices' own providers sample through Blueprint's sampler and search their own lists, which
 * stay on the CPU as they are.
 */
public final class BlueprintBiomes {
    private BlueprintBiomes() {}

    private static final String MODDED_SOURCE = "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource";

    /** The source {@code source} wraps when it is Blueprint's, else null. */
    @Nullable
    public static BiomeSource wrapped(BiomeSource source) {
        return Reflect.field(source, MODDED_SOURCE, "originalSource") instanceof BiomeSource original ? original : null;
    }
}
