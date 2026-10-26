package dev.denismasterherobrine.generatoraccelerator2.structure;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.FeatureSafetyScan;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;

/**
 * Lays out, one at a time, modded structures whose layout keeps state between starts in static fields. Vanilla runs
 * every structure start on its single worldgen thread. GA2 runs the STRUCTURE_STARTS step on several threads. Two
 * starts of such a structure in different chunks would then share that state half-done. Twilight Forest's knight
 * stronghold copies vanilla's stronghold layout, including the total piece weight and the last piece in static fields.
 * With two layouts at once, one layout resets the weight while the other draws from it ({@code Bound must be positive}).
 * Alternatively, one layout continues from the other layout's last piece. The class's code is scanned once
 * ({@code FeatureSafetyScan}), following the calls into modded classes. The scan looks for a static field written
 * outside class initialization, or a static collection or array changed in place. A flagged structure's starts are
 * generated under one lock shared by all flagged structures. This gives the layouts vanilla's single thread would
 * give, in whatever order the starts arrive, as in vanilla. Vanilla's own structures (strongholds, fortresses, end
 * cities) have their own locks ({@link StructureLocks}).
 */
public final class ModdedStructures {
    private ModdedStructures() {}

    private static final Logger LOGGER = Ga2Log.get("Structures");
    private static final Object SHARED_LOCK = new Object();
    /**
     * Static fields reviewed as no part of a layout, which the scan does not count. Alex's Caves 2.0.10: its rare biome map's
     * {@code init} writes the same values from the config each time (the biome step calls it on every worker thread too), and a
     * flag stops a repeated log in {@code replaceBiomes}. Mowzie's Mobs: the boss's constructor stores a config value.
     */
    private static final java.util.Set<String> REVIEWED = java.util.Set.of(
        "com/github/alexmodguy/alexscaves/server/level/biome/ACBiomeRarity.biomeSize",
        "com/github/alexmodguy/alexscaves/server/level/biome/ACBiomeRarity.seperationDistance",
        "com/github/alexmodguy/alexscaves/server/level/biome/ACBiomeRarity.initialized",
        "com/github/alexmodguy/alexscaves/server/level/structure/piece/AbstractCaveGenerationStructurePiece.replaceBiomesError",
        "com/bobmowzie/mowziesmobs/server/entity/umvuthana/EntityUmvuthi.HEALTH_LOST_BETWEEN_SUNBLOCKERS");
    private static final Object NONE = new Object();
    /** Classes already logged. Several threads may compute a class value at once. */
    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final ClassValue<Object> LOCKS = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> type) {
            if (type.getName().startsWith("net.minecraft.")) {
                return NONE;
            }
            String shared;
            try {
                shared = FeatureSafetyScan.sharedState(type, true, 4000, true, REVIEWED);
            } catch (Throwable t) {
                shared = "its code could not be checked (" + t + ")";
            }
            if (shared == null) {
                return NONE;
            }
            if (LOGGED.add(type.getName())) {
                LOGGER.info("structure {}: {} -> starts serialized", type.getName(), shared);
            }
            return SHARED_LOCK;
        }
    };

    /** The lock to generate the starts of {@code structure} under, or null when they may run concurrently. */
    @Nullable
    public static Object lock(Structure structure) {
        Object lock = LOCKS.get(structure.getClass());
        return lock == NONE ? null : lock;
    }
}
