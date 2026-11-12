package dev.denismasterherobrine.generatoraccelerator2.memory;

import dev.denismasterherobrine.generatoraccelerator2.ConfigFiles;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Chunks left idle between level 35 and the last loaded level are saved and let go, as ModernFix's {@code perf.release_protochunks}
 * does. Such a holder keeps the chunk of an unfinished generation step, usually at the edge of the loaded area. GA2 takes ModernFix's
 * two mixins over ({@code compat.Takeover}) and does the same itself:
 *
 * <ul>
 * <li>A holder that reaches such a level goes to the end of an ordered map with the tick. ModernFix queued a task on the server
 * thread for every such level change. That bookkeeping took 37% of the server thread in one pack and 6% in another. Here a tick
 * looks only at the entries that are due.</li>
 * <li>A holder idle for 100 ticks, with no generation using it, unloads through vanilla's own unload task. The task runs at once,
 * as with ModernFix, and the holder's futures are cleared. A later request reads the chunk from disk.</li>
 * <li>The chunks GA2 keeps for Chunky ({@code compat.chunky.ChunkyHolds}) stay. Chunky needs them again soon, and ModernFix's
 * drop made GA2 read them back.</li>
 * </ul>
 * Only while ModernFix's option is on and the vanilla chunk system runs. {@code -Dgeneratoraccelerator2.protoDrops=false} keeps
 * ModernFix's mixins.
 *
 * <p>Read by the takeover while mixins apply, so it must not reference Minecraft types.
 */
public final class ProtoDrops {
    private ProtoDrops() {}

    private static final Logger LOGGER = Ga2Log.get("Memory");

    /** ModernFix's idle time. */
    public static final int IDLE_TICKS = 100;
    /** ModernFix's budget per tick and level: chunks let go, and entries looked at. */
    public static final int MAX_DROPS = 50;
    public static final int MAX_VISITS = 500;
    /** Past this many entries, the budget is spent even when the tick has no time left (ModernFix's rule). */
    public static final int FORCE_SIZE = 1000;

    private static final boolean SWITCH = !"false".equals(System.getProperty("generatoraccelerator2.protoDrops"));
    private static volatile Boolean takesOver;
    private static volatile boolean tookOver;
    private static volatile boolean failed;

    public static final LongAdder DROPPED = new LongAdder();
    public static final LongAdder KEPT_FOR_CHUNKY = new LongAdder();

    /** The server's tick at the last {@code processUnloads}. Written on the server thread, read anywhere. */
    public static volatile int tick;
    /** Set on the server thread while a drop runs vanilla's unload task at once ({@code ChunkMapSavePrepMixin}). */
    public static boolean runNow;

    /** The chunk map's side ({@code mixin.chunkload.ChunkMapProtoDropMixin}). */
    public interface Owner {
        /** A holder came to a level whose chunk may be let go (server thread). */
        void ga2$markIdle(long pos);
    }

    /** The holder's side ({@code mixin.chunkload.ChunkHolderProtoDropMixin}). */
    public interface Holder {
        /** The tick the holder was last given work that its save waits for. */
        int ga2$lastUse();

        /** A generation task of its own is scheduled. */
        boolean ga2$busy();

        /** Forgets the chunk: every status future, the started work and the save dependencies. */
        void ga2$resetFutures();
    }

    /** Whether GA2 takes ModernFix's option over. Asked while mixins apply. */
    public static boolean takesOver() {
        Boolean t = takesOver;
        if (t == null) {
            t = SWITCH && !ChunkLoadConfig.otherChunkSystem() && modernFixOptionOn();
            takesOver = t;
        }
        return t;
    }

    /** ModernFix's mixins were cancelled. */
    public static void tookOver() {
        tookOver = true;
    }

    public static boolean active() {
        return tookOver && !failed;
    }

    /** Vanilla's unload task did not run at once: another mod changes {@code scheduleUnload}. Proto chunks then stay. */
    public static void fail() {
        if (!failed) {
            failed = true;
            LOGGER.warn("proto chunk drops: unload task not run at once (scheduleUnload changed by another mod) -> idle proto chunks kept");
        }
    }

    /** For {@code /ga2 chunks}, or null when the drops are off. */
    public static String describe() {
        if (!tookOver) {
            return null;
        }
        return String.format(Locale.ROOT, "idle proto chunks let go (ModernFix's release_protochunks, run by GA2): %,d; kept for Chunky %,d%s",
            DROPPED.sum(), KEPT_FOR_CHUNKY.sum(), failed ? "; off (scheduleUnload changed)" : "");
    }

    public static void resetCounts() {
        DROPPED.reset();
        KEPT_FOR_CHUNKY.reset();
    }

    /** ModernFix's option file. The option is on unless the file turns it off. */
    private static boolean modernFixOptionOn() {
        Path file = ConfigFiles.inConfigDir("modernfix-mixins.properties");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String l = line.trim();
                    int eq = l.indexOf('=');
                    if (!l.startsWith("#") && eq > 0 && l.substring(0, eq).trim().equals("mixin.perf.release_protochunks")) {
                        return !l.substring(eq + 1).trim().equalsIgnoreCase("false");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("ModernFix options: read failed -> release_protochunks taken as on", e);
        }
        return true;
    }
}
