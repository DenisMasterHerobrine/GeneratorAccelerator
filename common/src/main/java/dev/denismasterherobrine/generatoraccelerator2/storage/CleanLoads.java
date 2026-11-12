package dev.denismasterherobrine.generatoraccelerator2.storage;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * A full chunk read from disk is written again only when something changed it.
 *
 * <p>Vanilla marks every chunk it reads as unsaved, so every loaded chunk is encoded, compressed and written again at its unload.
 * Three calls of {@code ChunkSerializer.read} mark it ({@code setLightCorrect}, {@code setAllStarts}, {@code setAllReferences})
 * and the LIGHT step of the loading marks it twice more ({@code ThreadedLevelLightEngine.lightChunk}). None of them changes what
 * is saved. For a clean load these marks are left out. Every real change still marks the chunk: block and block entity changes,
 * light changes ({@code ChunkHolder.sectionLightChanged}), structure references.
 *
 * <p>Vanilla also relies on the marks for state that sets no flag. Those cases keep vanilla's save:
 * <ul>
 * <li>a chunk that starts block ticking ({@code postProcessGeneration}) is marked. Inhabited time, post-processing and upgrade
 * data only change in ticking chunks;</li>
 * <li>a chunk with scheduled ticks is saved, since a tick's saved delay counts down with the game time;</li>
 * <li>a tag the data fixer changed, a tag with mod data attachments, auxiliary light, missing heightmaps, light not marked
 * correct or {@code shouldSave} is not a clean load.</li>
 * </ul>
 * Off with {@code storage.toml skipUnchangedSaves = false}, or when another mod changes the calls involved.
 */
public final class CleanLoads {
    private CleanLoads() {}

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STORAGE_CLEAN_LOADS);

    /** Tags the data fixer changed on their way to {@code read}. Weak keys, compared by identity. */
    private static final Map<CompoundTag, Boolean> UPGRADED = new MapMaker().weakKeys().concurrencyLevel(4).makeMap();

    /** Whether the read on this thread is a clean load so far. */
    private static final ThreadLocal<boolean[]> READING = ThreadLocal.withInitial(() -> new boolean[1]);

    /** The clean flag of a level chunk ({@code mixin.storage.ChunkAccessCleanLoadMixin}). */
    public interface Flag {
        boolean ga2$cleanLoad();

        void ga2$setCleanLoad(boolean clean);
    }

    public static boolean enabled() {
        return StorageConfig.cleanLoads() && HOOK.allowed();
    }

    /** After {@code ChunkMap.upgradeChunkTag}: the data fixer returned another tag than it was given. */
    public static void upgraded(CompoundTag in, CompoundTag out) {
        if (out != in && out != null) {
            UPGRADED.put(out, Boolean.TRUE);
        }
    }

    /** At the head of {@code read}: whether the tag can give a clean load. */
    public static void begin(CompoundTag tag) {
        boolean[] state = READING.get();
        boolean upgraded = UPGRADED.remove(tag) != null;
        state[0] = !upgraded && enabled() && candidate(tag);
    }

    static boolean candidate(CompoundTag tag) {
        return ChunkSerializer.getChunkTypeFromTag(tag) == ChunkType.LEVELCHUNK
            && tag.getBoolean("isLightOn")
            && !tag.getBoolean("shouldSave")
            && !tag.contains("neoforge:attachments")
            && !tag.contains("fabric:attachments")
            && !tag.contains("neoforge:aux_lights")
            && tag.getList("block_ticks", Tag.TAG_COMPOUND).isEmpty()
            && tag.getList("fluid_ticks", Tag.TAG_COMPOUND).isEmpty();
    }

    /** Inside {@code read}: whether a mark of the chunk being read is to be left out. */
    public static boolean reading(ChunkAccess chunk) {
        return READING.get()[0] && chunk instanceof LevelChunk;
    }

    /** Inside {@code read}: the chunk is no clean load after all (a heightmap was primed). */
    public static void notClean(ChunkAccess chunk) {
        boolean[] state = READING.get();
        if (state[0]) {
            state[0] = false;
            chunk.setUnsaved(true);
        }
    }

    /** At the return of {@code read}. */
    public static void end(ChunkAccess result) {
        boolean[] state = READING.get();
        if (state[0] && result instanceof ImposterProtoChunk imposter) {
            ((Flag) imposter.getWrapped()).ga2$setCleanLoad(true);
        }
        state[0] = false;
    }

    /** Whether the chunk, or the level chunk an imposter wraps, is a clean load not changed since. */
    public static boolean isClean(ChunkAccess chunk) {
        ChunkAccess c = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
        return c instanceof LevelChunk && ((Flag) c).ga2$cleanLoad();
    }

    /** At the head of {@code ChunkMap.save}. A clean chunk with scheduled ticks is saved as vanilla saves it. */
    public static void beforeSave(ChunkAccess chunk) {
        if (!(chunk instanceof LevelChunk level) || !((Flag) level).ga2$cleanLoad()) {
            return;
        }
        if (level.isUnsaved()) {
            return;
        }
        if (level.getBlockTicks().count() > 0 || level.getFluidTicks().count() > 0) {
            level.setUnsaved(true);
            return;
        }
        ChunkLoadStats.CLEAN_UNLOADS.increment();
    }
}
