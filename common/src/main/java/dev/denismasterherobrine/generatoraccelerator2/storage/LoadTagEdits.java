package dev.denismasterherobrine.generatoraccelerator2.storage;

import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;

/**
 * Other mods' known edits of a chunk tag inside {@code ChunkSerializer.read} (Vanilla Backport's leaf litter rename, Block Swap's
 * missing block ids). The load preparation takes prepared palettes by tag without comparing them only while nothing edits the tag
 * after it was prepared ({@code storage.loadPrepUnchanged}). With such a known edit, each edit registers a test of the tags it
 * would change, and only a chunk one of them accepts is prepared the checked way: copies of its palette tags, compared when they
 * are taken.
 */
public final class LoadTagEdits {
    private LoadTagEdits() {}

    @SuppressWarnings("unchecked")
    private static volatile Predicate<CompoundTag>[] edits = new Predicate[0];

    /** Registers a test of the chunk tags another mod's read edits. Called at mod construction. */
    public static synchronized void register(Predicate<CompoundTag> edits) {
        Predicate<CompoundTag>[] next = Arrays.copyOf(LoadTagEdits.edits, LoadTagEdits.edits.length + 1);
        next[next.length - 1] = edits;
        LoadTagEdits.edits = next;
    }

    /** Whether some known edit would change {@code chunk}. */
    public static boolean any(CompoundTag chunk) {
        for (Predicate<CompoundTag> p : edits) {
            if (p.test(chunk)) {
                return true;
            }
        }
        return false;
    }
}
