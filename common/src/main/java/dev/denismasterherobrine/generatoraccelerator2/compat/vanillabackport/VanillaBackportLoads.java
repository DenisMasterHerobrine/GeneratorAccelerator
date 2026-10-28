package dev.denismasterherobrine.generatoraccelerator2.compat.vanillabackport;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadTagEdits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Vanilla Backport's rename at the head of {@code ChunkSerializer.read} ({@link VanillaBackportMixins#SERIALIZER}). It edits a palette
 * entry of {@code minecraft:leaf_litter} whose properties hold {@code flower_amount}, in place. Such a chunk is read the checked way
 * ({@link LoadTagEdits}). Every other chunk is read as prepared.
 */
public final class VanillaBackportLoads {
    private VanillaBackportLoads() {}

    public static void register() {
        LoadTagEdits.register(VanillaBackportLoads::renames);
        CompatAdapters.register(HookTargets.STORAGE_LOAD_PREP_UNCHANGED, VanillaBackportMixins.SERIALIZER, () -> true);
    }

    /** Whether the rename would change {@code chunk}: the mod's own test, read without adding any key. */
    static boolean renames(CompoundTag chunk) {
        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            ListTag palette = sections.getCompound(i).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            for (int j = 0; j < palette.size(); j++) {
                CompoundTag entry = palette.getCompound(j);
                if ("minecraft:leaf_litter".equals(entry.getString("Name")) && entry.getCompound("Properties").contains("flower_amount")) {
                    return true;
                }
            }
        }
        return false;
    }
}
