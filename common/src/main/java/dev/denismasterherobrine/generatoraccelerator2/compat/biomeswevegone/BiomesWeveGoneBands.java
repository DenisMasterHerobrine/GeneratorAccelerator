package dev.denismasterherobrine.generatoraccelerator2.compat.biomeswevegone;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.slf4j.Logger;

/**
 * The band tables that Oh The Biomes We've Gone keeps on each surface system ({@code bandsLookup}). The mod's mixin adds
 * this field. The mod fills it through {@code computeIfAbsent} on the first use of each bands rule. The mod's map is not
 * safe to fill from several threads. A concurrent map gives the same tables. Each table is built from a fresh random at
 * the origin, so the thread that builds it does not matter.
 */
public final class BiomesWeveGoneBands {
    private BiomesWeveGoneBands() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");
    private static volatile Field field;
    private static volatile boolean resolved;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void makeConcurrent(SurfaceSystem system) {
        Field f = field();
        if (f == null) {
            return;
        }
        try {
            Object map = f.get(system);
            if (map instanceof Map m && !(map instanceof ConcurrentHashMap)) {
                ConcurrentHashMap concurrent = new ConcurrentHashMap<>();
                concurrent.putAll(m);
                f.set(system, concurrent);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("BWG band tables: parallel-surface patch failed: {}", e.toString());
        }
    }

    private static Field field() {
        if (!resolved) {
            synchronized (BiomesWeveGoneBands.class) {
                if (!resolved) {
                    try {
                        Field f = SurfaceSystem.class.getDeclaredField("bandsLookup");
                        if (Map.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                            f.setAccessible(true);
                            field = f;
                        }
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        LOGGER.warn("BWG band tables not found on surface system ({}) -> unpatched; "
                            + "if filled concurrently, set parallelSurface = false", e.toString());
                    }
                    resolved = true;
                }
            }
        }
        return field;
    }
}
