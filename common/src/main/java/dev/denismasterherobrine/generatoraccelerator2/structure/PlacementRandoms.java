package dev.denismasterherobrine.generatoraccelerator2.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * Per-thread random sources for vanilla code that creates a fresh source per call, seeds it at once and drops it when the call
 * returns. The callers are the structure placement checks ({@code RandomSpreadStructurePlacement.getPotentialStructureChunk}, the
 * frequency reducers of {@code StructurePlacement}) and the rule processor's per-block source. The placement checks run for every
 * structure set at every chunk, and many more times through exclusion zones. A {@code WorldgenRandom} over a
 * {@code LegacyRandomSource} is six objects. Seeding replaces all the state a draw reads: the 48-bit seed and the gaussian's
 * pending value. A reused source seeded the same way therefore gives the same draws as a new one. The source's draw counter
 * ({@code getCount}) keeps counting, but none of those callers reads it.
 */
public final class PlacementRandoms {
    private PlacementRandoms() {}

    private static final ThreadLocal<WorldgenRandom> WORLDGEN = ThreadLocal.withInitial(() -> new WorldgenRandom(new LegacyRandomSource(0L)));
    private static final ThreadLocal<LegacyRandomSource> LEGACY = ThreadLocal.withInitial(() -> new LegacyRandomSource(0L));

    /** The thread's {@code new WorldgenRandom(new LegacyRandomSource(0L))}, for a caller that seeds it before its first draw. */
    public static WorldgenRandom worldgen() {
        return WORLDGEN.get();
    }

    /** The thread's {@code RandomSource.create(seed)} ({@code new LegacyRandomSource(seed)}), seeded. */
    public static RandomSource legacy(long seed) {
        LegacyRandomSource random = LEGACY.get();
        random.setSeed(seed);
        return random;
    }
}
