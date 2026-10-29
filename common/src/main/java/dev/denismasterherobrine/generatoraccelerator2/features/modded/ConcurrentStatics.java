package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static collections of other mods that GA2 itself makes thread-safe. A compat mixin replaces the mod's map with a concurrent
 * copy where the class initializer stores it. The scan then counts a change to it as a change of a concurrent collection
 * ({@link FeatureSafetyScan}). Only memos qualify: the mod fills a key with the same value whichever placement asks first, and
 * nothing removes keys while the world generates.
 */
public final class ConcurrentStatics {
    private ConcurrentStatics() {}

    private static final Set<String> MADE = ConcurrentHashMap.newKeySet();

    /** Records that {@code field} ({@code owner.name}, internal owner name) holds a concurrent collection from now on. */
    public static void made(String field) {
        MADE.add(field);
    }

    static boolean isMade(String owner, String name) {
        return MADE.contains(owner + "." + name);
    }
}
