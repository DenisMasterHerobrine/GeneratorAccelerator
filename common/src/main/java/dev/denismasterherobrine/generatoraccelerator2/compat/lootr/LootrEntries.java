package dev.denismasterherobrine.generatoraccelerator2.compat.lootr;

import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Lootr's list of loot containers still to convert, filed by chunk. Lootr 1.10.34 and older add an entry for every container in a
 * chunk that becomes FULL. Each server tick the converter copies the whole list, walks the copy and then removes the finished
 * entries from the list. An entry whose chunk is below FULL is left alone until it expires (15 minutes by default). A
 * pregeneration makes each chunk FULL for a moment only. The list then holds the containers of every chunk made in the last 15
 * minutes. In a 1.20.1 ATM9 profile the same converter of Lootr 0.7 took 43% of the server thread.
 *
 * <p>Lootr's converter reads this set in place of its own field ({@code mixin.BlockEntityTickerMixin}). The set shows Lootr only the
 * entries of chunks at FULL or above. The other entries wait by chunk. Lootr's walk would skip them anyway: it converts an
 * entry only once the entry's chunk and its neighbours are FULL. A waiting chunk's entries come back when the chunk becomes FULL
 * again. They are dropped when the chunk unloads. A chunk that loads again adds its containers anew, so its entries get a new
 * lifetime instead of expiring.
 *
 * <p>{@code add}, {@code remove} and {@code contains} see the waiting entries too, so an entry is never in the set twice. Lootr
 * uses the set under its own list lock, on the server thread or on any thread that makes a chunk FULL. Every method holds this
 * set's monitor, and the iterator walks a snapshot.
 */
public final class LootrEntries extends AbstractSet<Object> {
    /** The one set. A game has one Lootr converter. */
    public static final LootrEntries ENTRIES = new LootrEntries();

    private static final MethodType GETTER = MethodType.methodType(Object.class, Object.class);
    /** Lootr's chunks with a load event per dimension ({@code HandleChunk.LOADED_CHUNKS}), or null. */
    @Nullable
    private static final Map<?, ?> LOADED_CHUNKS = loadedChunks();

    private final Reference2ObjectOpenHashMap<ResourceKey<Level>, Long2ObjectOpenHashMap<Chunk>> chunks = new Reference2ObjectOpenHashMap<>();
    /** The entries Lootr sees, in the order they came. */
    private final ObjectLinkedOpenHashSet<Object> shown = new ObjectLinkedOpenHashSet<>();
    private final ReferenceOpenHashSet<Chunk> shownChunks = new ReferenceOpenHashSet<>();
    private Class<?> entryType;
    private MethodHandle dimension;
    private MethodHandle position;

    private LootrEntries() {}

    /** One chunk's entries. */
    private static final class Chunk {
        final ResourceKey<Level> dimension;
        final long pos;
        final ObjectArrayList<Object> entries = new ObjectArrayList<>(2);
        /** Below FULL when last seen. The entries are not shown. */
        boolean waiting;

        Chunk(ResourceKey<Level> dimension, long pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    @Override
    public synchronized boolean add(Object entry) {
        Chunk chunk = chunkOf(entry, true);
        if (chunk.entries.contains(entry)) {
            return false;
        }
        chunk.entries.add(entry);
        if (!chunk.waiting) {
            this.shown.add(entry);
        }
        return true;
    }

    @Override
    public synchronized boolean addAll(Collection<?> entries) {
        boolean changed = false;
        for (Object entry : entries) {
            changed |= add(entry);
        }
        return changed;
    }

    @Override
    public synchronized boolean remove(Object o) {
        Chunk chunk = chunkOf(o, false);
        if (chunk == null || !chunk.entries.remove(o)) {
            return false;
        }
        if (!chunk.waiting) {
            this.shown.remove(o);
        }
        if (chunk.entries.isEmpty()) {
            forget(chunk);
        }
        return true;
    }

    /** Lootr removes the few finished entries of a tick. Asking each of them is cheaper than walking the set. */
    @Override
    public synchronized boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            changed |= remove(o);
        }
        return changed;
    }

    @Override
    public synchronized boolean contains(Object o) {
        Chunk chunk = chunkOf(o, false);
        return chunk != null && chunk.entries.contains(o);
    }

    @Override
    public synchronized int size() {
        return this.shown.size();
    }

    @Override
    public synchronized void clear() {
        this.chunks.clear();
        this.shown.clear();
        this.shownChunks.clear();
    }

    /**
     * The shown entries as they were at the call. Lootr copies the set through this iterator once a tick. The snapshot is taken
     * under this set's monitor, so a chunk event on another thread cannot change it midway. C2ME's chunk system reports
     * status changes from its own threads.
     */
    @Override
    public Iterator<Object> iterator() {
        Object[] snapshot;
        synchronized (this) {
            snapshot = this.shown.toArray();
        }
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return this.next < snapshot.length;
            }

            @Override
            public Object next() {
                if (this.next >= snapshot.length) {
                    throw new NoSuchElementException();
                }
                return snapshot[this.next++];
            }

            @Override
            public void remove() {
                if (this.next == 0) {
                    throw new IllegalStateException();
                }
                LootrEntries.this.remove(snapshot[this.next - 1]);
            }
        };
    }

    /**
     * Hides the entries of shown chunks below FULL. Runs right before Lootr copies the set. This covers entries added while
     * their chunk was already below FULL.
     */
    public synchronized void hideUnloaded() {
        MinecraftServer server = GeneratorAccelerator.server();
        if (server == null || this.shownChunks.isEmpty()) {
            return;
        }
        ResourceKey<Level> levelKey = null;
        ServerLevel level = null;
        ObjectIterator<Chunk> it = this.shownChunks.iterator();
        while (it.hasNext()) {
            Chunk chunk = it.next();
            if (chunk.dimension != levelKey) {
                levelKey = chunk.dimension;
                level = server.getLevel(levelKey);
            }
            // without the level Lootr drops the entries itself
            if (level != null && !level.getChunkSource().hasChunk(ChunkPos.getX(chunk.pos), ChunkPos.getZ(chunk.pos))) {
                it.remove();
                hide(chunk);
            }
        }
    }

    /** A chunk's full status changed ({@code ChunkMap.onFullChunkStatusChange}). Shows or hides its entries. */
    public synchronized void statusChanged(ResourceKey<Level> dimension, long pos, boolean full) {
        Long2ObjectOpenHashMap<Chunk> map = this.chunks.get(dimension);
        Chunk chunk = map == null ? null : map.get(pos);
        if (chunk == null || chunk.waiting != full) {
            return;
        }
        if (full) {
            chunk.waiting = false;
            this.shownChunks.add(chunk);
            this.shown.addAll(chunk.entries);
        } else {
            this.shownChunks.remove(chunk);
            hide(chunk);
        }
    }

    /**
     * A chunk unloaded. Its entries go. Loading the chunk again adds its containers anew. Lootr 1.10.34 never removes a chunk from
     * its loaded chunks, so a pregeneration grows that set by every chunk it makes. The chunk leaves it here, as in Lootr 1.11.
     */
    public static void unloaded(ResourceKey<Level> dimension, ChunkPos pos) {
        ENTRIES.drop(dimension, pos.toLong());
        Map<?, ?> loaded = LOADED_CHUNKS;
        if (loaded != null) {
            synchronized (loaded) {
                if (loaded.get(dimension) instanceof Set<?> set) {
                    set.remove(pos);
                }
            }
        }
    }

    private synchronized void drop(ResourceKey<Level> dimension, long pos) {
        Long2ObjectOpenHashMap<Chunk> map = this.chunks.get(dimension);
        Chunk chunk = map == null ? null : map.remove(pos);
        if (chunk != null && !chunk.waiting) {
            this.shownChunks.remove(chunk);
            for (Object entry : chunk.entries) {
                this.shown.remove(entry);
            }
        }
    }

    private void hide(Chunk chunk) {
        chunk.waiting = true;
        for (Object entry : chunk.entries) {
            this.shown.remove(entry);
        }
    }

    private void forget(Chunk chunk) {
        Long2ObjectOpenHashMap<Chunk> map = this.chunks.get(chunk.dimension);
        if (map != null) {
            map.remove(chunk.pos);
        }
        this.shownChunks.remove(chunk);
    }

    /** The chunk filed under {@code entry}'s dimension and position. Null for an object that is no entry, unless {@code create}. */
    @Nullable
    @SuppressWarnings("unchecked")
    private Chunk chunkOf(Object entry, boolean create) {
        if (this.entryType == null) {
            if (!create) {
                return null;
            }
            resolve(entry.getClass());
        }
        if (!this.entryType.isInstance(entry)) {
            if (create) {
                throw new IllegalArgumentException("not a Lootr entry: " + entry);
            }
            return null;
        }
        ResourceKey<Level> key;
        BlockPos at;
        try {
            key = (ResourceKey<Level>) (Object) this.dimension.invokeExact(entry);
            at = (BlockPos) (Object) this.position.invokeExact(entry);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
        long pos = ChunkPos.asLong(SectionPos.blockToSectionCoord(at.getX()), SectionPos.blockToSectionCoord(at.getZ()));
        Long2ObjectOpenHashMap<Chunk> map = this.chunks.get(key);
        if (map == null) {
            if (!create) {
                return null;
            }
            map = new Long2ObjectOpenHashMap<>();
            this.chunks.put(key, map);
        }
        Chunk chunk = map.get(pos);
        if (chunk == null && create) {
            chunk = new Chunk(key, pos);
            map.put(pos, chunk);
            this.shownChunks.add(chunk);
        }
        return chunk;
    }

    /** LootrMixins checked that the entry class has these getters. */
    private void resolve(Class<?> type) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            this.dimension = lookup.findVirtual(type, "getDimension", MethodType.methodType(ResourceKey.class)).asType(GETTER);
            this.position = lookup.findVirtual(type, "getPosition", MethodType.methodType(BlockPos.class)).asType(GETTER);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lootr entry " + type.getName() + " has no dimension or position", e);
        }
        this.entryType = type;
    }

    @Nullable
    private static Map<?, ?> loadedChunks() {
        for (String holder : LootrMixins.loadedChunksHolders()) {
            Class<?> type = Reflect.type(holder);
            if (type == null) {
                continue;
            }
            try {
                Field f = type.getField("LOADED_CHUNKS");
                return Modifier.isStatic(f.getModifiers()) && f.get(null) instanceof Map<?, ?> m ? m : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }
}
