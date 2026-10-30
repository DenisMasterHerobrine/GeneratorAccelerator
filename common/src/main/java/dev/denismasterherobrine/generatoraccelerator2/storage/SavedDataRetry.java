package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/**
 * Writing a saved data that a generation thread adds to at the same time. Some mods keep their saved data in a plain hash map that their
 * features fill during generation. TerraFirmaGreg's natural gas features fill GregTech's fluid veins this way. The server thread's
 * autosave then walks the map while a worker adds to it. The walk throws {@code ConcurrentModificationException}, and the server
 * crashes. Vanilla's own generation has the same race. GA2's faster generation runs into it more often.
 *
 * <p>Here the write starts over with a fresh tag, up to {@link #ATTEMPTS} times, so the save sees the map between two additions. The
 * tag written is one the mod's own code made. A lock would not do: the feature may wait for the server thread while holding it.
 */
public final class SavedDataRetry {
    private SavedDataRetry() {}

    private static final Logger LOGGER = Ga2Log.get("Storage");
    static final int ATTEMPTS = 8;

    /** Writes that started over after another thread changed the data. */
    public static final LongAdder RETRIED = new LongAdder();
    private static volatile boolean logged;

    /** {@code write(tag)}, started over with a fresh tag while it throws {@code ConcurrentModificationException}. */
    public static CompoundTag save(SavedData data, CompoundTag tag, UnaryOperator<CompoundTag> write) {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.apply(tag);
            } catch (ConcurrentModificationException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                RETRIED.increment();
                if (!logged) {
                    logged = true;
                    LOGGER.warn("{} changed by another thread while saved -> written again", data.getClass().getName());
                }
                tag = new CompoundTag();
                Thread.yield();
            }
        }
    }
}
