package dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;

/**
 * Vanilla Backport mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it.
 */
public final class VanillaBackportMixins {
    private VanillaBackportMixins() {}

    /** Renames old leaf litter properties in the chunk tag at the head of {@code ChunkSerializer.read} ({@code remapProperties}). */
    public static final String SERIALIZER = "com.blackgear.vanillabackport.core.mixin.common.level.ChunkSerializerMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(VanillaBackportRules.MOD_ID, HookTargets.STORAGE_LOAD_PREP_UNCHANGED, SERIALIZER, Set.of("remapProperties"),
            "Vanilla Backport still renames old leaf litter properties. GA2 reads only those chunks the careful way."));
}
