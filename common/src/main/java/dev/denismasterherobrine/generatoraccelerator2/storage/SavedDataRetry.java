package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Writing a saved data that a generation thread adds to at the same time. Some mods keep their saved data in a plain hash map that their
 * features fill during generation. TerraFirmaGreg's natural gas features fill GregTech's fluid veins this way. The server thread's
 * autosave then walks the map while a worker adds to it. The walk throws {@code ConcurrentModificationException}, and the server
 * crashes. Vanilla's own generation has the same race. GA2's faster generation runs into it more often.
 *
 * <p>Here the encoding starts over, up to {@link #ATTEMPTS} times, so the save sees the map between two additions. The tag written is
 * one the mod's own codec made. A lock would not do: the feature may wait for the server thread while holding it.
 */
public final class SavedDataRetry {
    private SavedDataRetry() {}

    private static final Logger LOGGER = Ga2Log.get("Storage");
    static final int ATTEMPTS = 8;

    /** Encodings that started over after another thread changed the data. */
    public static final LongAdder RETRIED = new LongAdder();
    private static volatile boolean logged;

    /** {@code encode.get()}, started over while it throws {@code ConcurrentModificationException}. */
    public static <T> T encode(Object data, Supplier<T> encode) {
        for (int attempt = 1; ; attempt++) {
            try {
                return encode.get();
            } catch (ConcurrentModificationException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                RETRIED.increment();
                if (!logged) {
                    logged = true;
                    LOGGER.warn("{} changed by another thread while saved -> encoded again", data.getClass().getName());
                }
                Thread.yield();
            }
        }
    }
}
