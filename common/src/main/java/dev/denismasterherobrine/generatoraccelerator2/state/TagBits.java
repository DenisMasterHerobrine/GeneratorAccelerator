package dev.denismasterherobrine.generatoraccelerator2.state;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Set;
import net.minecraft.tags.TagKey;

/**
 * Tag membership as bits ({@code HolderTagsMixin}). Vanilla answers {@code Holder.is(TagKey)} from the holder's tag set. Every
 * call hashes the tag key and probes the set. The key is a record, so the hash covers its registry key, its location and their
 * strings. Decoration makes this call for most blocks it looks at: sculk's replaceable checks, leaves' log checks, structure
 * processors. Here each tag key that a holder is bound with gets an index, kept on the key ({@code TagKeyIndexMixin}). Each
 * holder keeps the bits of its tags, set when its tags are bound.
 *
 * <p>Indices go by the key's value (a record). A key instance without an index looks the index up by equality once and keeps
 * it. A key that no holder was bound with has no index, and no holder's set holds it. So every answer matches the set's.
 */
public final class TagBits {
    private TagBits() {}

    public static final long[] NONE = new long[0];

    /**
     * Implemented by every tag key. Holds the index + 1, or minus the {@link #generation} in which this instance found it has no
     * index, or 0 before it looked.
     */
    public interface Indexed {
        int ga2$tagIndex();

        void ga2$setTagIndex(int index);
    }

    /** By value. Copied on every new key. Keys are added while tags are bound, a few thousand at most. Read without locks. */
    private static volatile Object2IntOpenHashMap<TagKey<?>> index = empty();
    private static int next;
    /**
     * Raised after keys were added ({@link #assign}). A key without an index remembers the generation it looked in. It looks
     * again in a later one. A mod's tag that no block has (an empty tag) was looked up in the map at every test before.
     */
    private static volatile int generation = 1;

    private static Object2IntOpenHashMap<TagKey<?>> empty() {
        Object2IntOpenHashMap<TagKey<?>> m = new Object2IntOpenHashMap<>();
        m.defaultReturnValue(-1);
        return m;
    }

    /** The key's index, or -1 when no holder was bound with this key. */
    public static int index(TagKey<?> key) {
        Indexed k = (Indexed) (Object) key;
        int v = k.ga2$tagIndex();
        if (v > 0) {
            return v - 1;
        }
        // the generation first: a map read after it holds every key added before it was raised
        int g = generation;
        if (v == -g) {
            return -1;
        }
        int i = index.getInt(key);
        k.ga2$setTagIndex(i >= 0 ? i + 1 : -g);
        return i;
    }

    /** The bits of a holder bound with {@code tags}. Keys not seen before get indices. */
    public static long[] bits(Set<? extends TagKey<?>> tags) {
        if (tags.isEmpty()) {
            return NONE;
        }
        boolean missing = false;
        for (TagKey<?> t : tags) {
            if (index(t) < 0) {
                missing = true;
                break;
            }
        }
        if (missing) {
            assign(tags);
        }
        int max = 0;
        for (TagKey<?> t : tags) {
            max = Math.max(max, index(t));
        }
        long[] bits = new long[(max >>> 6) + 1];
        for (TagKey<?> t : tags) {
            int i = index(t);
            bits[i >>> 6] |= 1L << i;
        }
        return bits;
    }

    private static synchronized void assign(Set<? extends TagKey<?>> tags) {
        Object2IntOpenHashMap<TagKey<?>> m = new Object2IntOpenHashMap<>(index);
        m.defaultReturnValue(-1);
        for (TagKey<?> t : tags) {
            if (m.getInt(t) < 0) {
                m.put(t, next++);
            }
        }
        index = m;
        generation++;
    }
}
