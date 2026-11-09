package dev.denismasterherobrine.generatoraccelerator2.light;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import org.slf4j.Logger;

/**
 * A new chunk's sections with blocks, registered with the light engine again right before its block light sources are queued.
 *
 * <p>INITIALIZE_LIGHT registers every section that holds blocks ({@code ThreadedLevelLightEngine.initializeLight}). Each one
 * then stores light, and so does each section next to it. A block written into the chunk later is reported to the light
 * engine only once the chunk's status is INITIALIZE_LIGHT ({@code ProtoChunk.setBlockState}). The status is set when the
 * step's future completes, after the light thread ran the scan. A neighbour's decoration that writes into the chunk in
 * between fills a section nobody registers. At LIGHT, {@code BlockLightEngine.propagateLightSources} queues every light source
 * of the chunk without asking whether its section stores light, and the propagation then reads a section that has none: the
 * NullPointerException in {@code propagateIncreases} that stopped the light of {@code macabre:the_pit} (tall terrain and
 * spires, light-emitting blocks high above any other section). At LIGHT no decoration writes into the chunk any more: every
 * neighbour is past FEATURES. So registering here catches every write the scan missed. The calls are vanilla's own
 * {@code updateSectionStatus(pos, false)}, which change nothing for a section already registered.
 *
 * <p>{@code -Dgeneratoraccelerator2.lightSectionResync=false} turns it off. The propagation still skips an entry whose section
 * stores no light ({@link #skipped}) instead of failing the update.
 */
public final class LightSectionSync {
    private LightSectionSync() {}

    private static final Logger LOGGER = Ga2Log.get("Light");

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.lightSectionResync"));

    private static final AtomicLong REGISTERED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();

    /**
     * Registers every section of {@code chunk} that holds blocks and is not registered yet, as {@code initializeLight} does.
     * {@code register} is the base {@code LevelLightEngine.updateSectionStatus(pos, false)}, not the threaded engine's, which
     * would only queue a task. {@code reference} is the storage whose state is current on this thread (the sky light's while the
     * block light's calls are recorded). Without one, every section with blocks is registered (a no-op for registered ones).
     */
    public static void resync(LevelHeightAccessor heights, @Nullable LightChunk lightChunk, ChunkPos pos, @Nullable Ga2LightStorage reference,
                              Consumer<SectionPos> register) {
        if (!ENABLED || !(lightChunk instanceof ChunkAccess chunk)) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int count = Math.min(chunk.getSectionsCount(), sections.length);
        for (int i = 0; i < count; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            SectionPos sectionPos = SectionPos.of(pos, heights.getSectionYFromSectionIndex(i));
            if (reference != null) {
                if (reference.ga2$hasData(sectionPos.asLong())) {
                    // registered. Both engines get the same updateSectionStatus calls in the same order, so the other one is too
                    continue;
                }
                long n = REGISTERED.incrementAndGet();
                if (n <= 16) {
                    LOGGER.warn("light: section {} in {} holds blocks but was never registered with the light engine (written after the chunk's light"
                        + " init scanned it, status {}); registered before its light sources are queued", sectionPos, name(heights), chunk.getPersistedStatus());
                } else if (n % 1000 == 0) {
                    LOGGER.warn("light: {} sections with blocks registered late so far", n);
                }
            }
            register.accept(sectionPos);
        }
    }

    /** The propagation took an entry whose section stores no light. Vanilla's {@code getStoredLevel} would throw. */
    public static void skipped(boolean sky, long pos) {
        long n = SKIPPED.incrementAndGet();
        if (n <= 16) {
            LOGGER.error("light: {} light increase at {} skipped: its section {} stores no light", sky ? "sky" : "block", BlockPos.of(pos),
                SectionPos.of(BlockPos.of(pos)));
        } else if (n % 1000 == 0) {
            LOGGER.error("light: {} light increases skipped so far in sections that store no light", n);
        }
    }

    private static Object name(LevelHeightAccessor heights) {
        return heights instanceof Level level ? level.dimension().location() : heights;
    }

    /** Status lines for {@code /ga2 light}. */
    public static List<String> statusLines() {
        return List.of("light sections: " + (ENABLED ? "re-registered at LIGHT" : "not re-registered (-Dgeneratoraccelerator2.lightSectionResync=false)")
            + ", " + REGISTERED.get() + " registered late, " + SKIPPED.get() + " increases skipped in sections without light");
    }
}
