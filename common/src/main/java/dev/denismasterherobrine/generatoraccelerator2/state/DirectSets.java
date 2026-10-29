package dev.denismasterherobrine.generatoraccelerator2.state;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/**
 * Membership in a direct holder set ({@code HolderSet.Direct.contains}, {@code DirectSetMixin}) by bit. Vanilla copies the list
 * into a set at the first call and asks the set. That hashes the holder and compares it with the set's entries. A mod's block
 * list in its feature config is such a set. Supplementaries' barnacles ask one with hundreds of blocks for every face they try.
 *
 * <p>Each resource key of a set's entries gets an index, kept on the key ({@code ResourceKeyIndexMixin}). Each set keeps the bits
 * of its entries' keys. Only reference holders are answered this way, and only for sets whose entries are all reference holders
 * with keys. How two reference holders compare decides the rest:
 * <ul>
 *   <li>NeoForge's {@code Holder.Reference.equals}: the same holder, or another reference holder with the same key instance. Its
 *   {@code hashCode} is the key's. So a holder is in the set exactly when its key's bit is set.</li>
 *   <li>Vanilla's (no {@code equals} of its own): the same object. A holder whose key's bit is not set is not in the set. One whose
 *   bit is set is asked of the set.</li>
 * </ul>
 * Any other code in those methods leaves every set to vanilla.
 */
public final class DirectSets {
    private DirectSets() {}

    private static final Logger LOGGER = Ga2Log.get("State");

    /** Implemented by every resource key. Holds the index once given, 0 before. */
    public interface Indexed {
        int ga2$directIndex();

        void ga2$setDirectIndex(int index);
    }

    /** Implemented by every reference holder: its key field, null while unbound ({@code key()} throws then). */
    public interface Keyed {
        ResourceKey<?> ga2$keyOrNull();
    }

    /** Sets are left to vanilla. */
    public static final int OFF = 0;
    /** Reference holders compare by key (NeoForge). */
    public static final int KEY = 1;
    /** Reference holders compare by identity (vanilla). */
    public static final int IDENTITY = 2;

    /** Fingerprints ({@link CodeShape}) of NeoForge's {@code Holder.Reference} methods. */
    private static final String NEOFORGE_EQUALS = "7d62267ee3bcc958";
    private static final String NEOFORGE_HASH_CODE = "144768d8c639ceb3";
    private static final String NEOFORGE_GET_KEY = "5d553308fad1de30";

    public static final int MODE = mode();

    /** A set that its own set answers: an entry that is not a reference holder with a key. */
    public static final long[] VANILLA = new long[0];

    /** The next index. Indices start at 1. */
    private static int next = 1;

    private static int mode() {
        if (!StateFast.ENABLED) {
            return OFF;
        }
        boolean equals = declares("equals", Object.class), hashCode = declares("hashCode");
        if (!equals && !hashCode) {
            return IDENTITY;
        }
        String owner = Holder.Reference.class.getName();
        ClassNode node = CodeShape.classNode(owner);
        String e = CodeShape.fingerprint(CodeShape.method(node, "equals", "(Ljava/lang/Object;)Z"));
        String h = CodeShape.fingerprint(CodeShape.method(node, "hashCode", "()I"));
        String k = CodeShape.fingerprint(CodeShape.method(node, "getKey", "()Lnet/minecraft/resources/ResourceKey;"));
        if (e.equals(NEOFORGE_EQUALS) && h.equals(NEOFORGE_HASH_CODE) && k.equals(NEOFORGE_GET_KEY)) {
            return KEY;
        }
        LOGGER.info("Holder.Reference compares in an unknown way (equals {}, hashCode {}, getKey {}) -> direct holder sets by vanilla", e, h, k);
        return OFF;
    }

    private static boolean declares(String name, Class<?>... args) {
        try {
            Method m = Holder.Reference.class.getDeclaredMethod(name, args);
            return m != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The bits of the entries' keys, or {@link #VANILLA} when an entry is not a reference holder with a key. */
    public static long[] bits(List<? extends Holder<?>> contents) {
        for (Holder<?> h : contents) {
            if (h == null || h.getClass() != Holder.Reference.class || ((Keyed) h).ga2$keyOrNull() == null) {
                return VANILLA;
            }
        }
        int max = 0;
        synchronized (DirectSets.class) {
            for (Holder<?> h : contents) {
                Indexed k = (Indexed) (Object) ((Keyed) h).ga2$keyOrNull();
                int i = k.ga2$directIndex();
                if (i == 0) {
                    k.ga2$setDirectIndex(i = next++);
                }
                max = Math.max(max, i);
            }
        }
        long[] bits = new long[(max >>> 6) + 1];
        for (Holder<?> h : contents) {
            int i = ((Indexed) (Object) ((Keyed) h).ga2$keyOrNull()).ga2$directIndex();
            bits[i >>> 6] |= 1L << i;
        }
        return bits;
    }

    /** No. */
    public static final int NO = 0;
    /** Yes. */
    public static final int YES = 1;
    /** The set answers. */
    public static final int ASK = 2;

    /**
     * Whether a reference holder with {@code key} is in the set with these bits, under {@code mode}. The bits were read before
     * the key's index: a key given its index later belongs to no entry of this set.
     */
    public static int answer(int mode, long[] bits, ResourceKey<?> key) {
        int i = ((Indexed) (Object) key).ga2$directIndex();
        int w = i >>> 6;
        if (i == 0 || w >= bits.length || (bits[w] & (1L << i)) == 0) {
            return NO;
        }
        return mode == KEY ? YES : ASK;
    }
}
