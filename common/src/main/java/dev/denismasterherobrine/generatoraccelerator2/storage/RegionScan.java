package dev.denismasterherobrine.generatoraccelerator2.storage;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.concurrent.atomic.LongAdder;

/**
 * Before generating near a region for the first time, blending asks whether any chunk of that region was saved by an
 * older version. The call is {@code IOWorker.isOldChunkAround}, made from the worldgen thread, which waits for the
 * answer. Vanilla computes it on a worker thread. That worker hands each of the region's 1024 chunks to the IO thread
 * one at a time and waits for each. The IO thread writes a pending chunk after every task. On a slow disk the worker,
 * and the worldgen thread behind it, therefore wait for up to 1024 chunk writes. This class runs the same per-chunk
 * check in 16 IO tasks of 64 chunks. The check reads the pending write first, else the region file. Loads queued
 * meanwhile are not held behind the whole region. The answer is the same, because a chunk this version saves meanwhile
 * is not old either way.
 *
 * <p>{@code -Dgeneratoraccelerator2.regionScan=false} turns it off.
 */
public final class RegionScan {
    private RegionScan() {}

    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.regionScan"));
    public static final LongAdder SCANS = new LongAdder();
    public static final int BATCH = 64;

    public static boolean enabled() {
        return ENABLED && StorageConfig.regionScan() && HookTargets.allowed(HookTargets.STORAGE_OLD_CHUNK_SCAN);
    }
}
