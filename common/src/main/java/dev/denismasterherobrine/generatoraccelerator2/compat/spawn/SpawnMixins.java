package dev.denismasterherobrine.generatoraccelerator2.compat.spawn;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/** Spawn mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class SpawnMixins {
    private SpawnMixins() {}

    public static final String MOD_ID = "spawn";
    /**
     * Modifies {@code MultiNoiseBiomeSource.parameters()}'s return value. Overworld biomes that Spawn adds and that another mod's
     * list dropped are put back. The merged list is kept in a field. It is reused while the list it was made from is returned
     * again.
     */
    public static final String PARAMETERS = "com.ninni.spawn.mixin.MultiNoiseBiomeSourceMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_PARAMETERS, PARAMETERS, Set.of("spawn$restoreDroppedBiomes"),
            "Biomes restored by Spawn still appear. GA2 adds them to its biome list the same way Spawn does."));
}
