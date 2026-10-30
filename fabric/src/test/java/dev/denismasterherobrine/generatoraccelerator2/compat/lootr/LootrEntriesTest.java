package dev.denismasterherobrine.generatoraccelerator2.compat.lootr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import noobanidus.mods.lootr.event.HandleChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@link LootrEntries} shows Lootr each entry of a FULL chunk once, keeps the entries of other chunks aside until the chunk is FULL
 * again, and forgets a chunk that unloads. {@link LootrMixins#differs} accepts Lootr 0.7's converter shape and nothing else.
 */
class LootrEntriesTest {
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, new ResourceLocation("overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, new ResourceLocation("the_nether"));

    private final LootrEntries entries = LootrEntries.ENTRIES;

    @BeforeEach
    void empty() {
        this.entries.clear();
        HandleChunk.LOADED_CHUNKS.clear();
    }

    private static Ticker.Entry entry(ResourceKey<Level> dimension, int x, int z) {
        return new Ticker.Entry(dimension, new BlockPos(x, 64, z));
    }

    private static long chunk(int x, int z) {
        return ChunkPos.asLong(x >> 4, z >> 4);
    }

    /** The set as Lootr copies it each tick. */
    private Set<Object> copy() {
        return new ObjectOpenHashSet<>(this.entries);
    }

    @Test
    void showsEachEntryOnce() {
        Ticker.Entry a = entry(OVERWORLD, 1, 1);
        Ticker.Entry b = entry(OVERWORLD, 5, 9);
        Ticker.Entry c = entry(OVERWORLD, 40, 1);
        Ticker.Entry d = entry(NETHER, 1, 1);
        for (Ticker.Entry e : List.of(a, b, c, d)) {
            assertTrue(this.entries.add(e));
        }
        assertFalse(this.entries.add(entry(OVERWORLD, 1, 1)), "an equal entry is not added again");
        assertEquals(4, this.entries.size());
        assertEquals(Set.of(a, b, c, d), copy());
    }

    @Test
    void keepsChunksBelowFullAside() {
        Ticker.Entry a = entry(OVERWORLD, 1, 1);
        Ticker.Entry b = entry(OVERWORLD, 5, 9);
        Ticker.Entry c = entry(OVERWORLD, 40, 1);
        Ticker.Entry other = entry(NETHER, 1, 1);
        this.entries.addAll(List.of(a, b, c, other));
        this.entries.statusChanged(OVERWORLD, chunk(1, 1), false);
        assertEquals(Set.of(c, other), copy(), "the same chunk in another dimension stays shown");
        assertTrue(this.entries.contains(a), "a waiting entry is still in the set");
        assertFalse(this.entries.add(entry(OVERWORLD, 1, 1)), "so it is not added twice");
        Ticker.Entry late = entry(OVERWORLD, 2, 2);
        assertTrue(this.entries.add(late));
        assertEquals(Set.of(c, other), copy(), "an entry of a waiting chunk waits too");
        this.entries.statusChanged(OVERWORLD, chunk(1, 1), true);
        assertEquals(Set.of(a, b, c, late, other), copy());
        this.entries.statusChanged(OVERWORLD, chunk(1, 1), true);
        assertEquals(5, this.entries.size(), "a second FULL call changes nothing");
    }

    @Test
    void removesFinishedEntries() {
        Ticker.Entry a = entry(OVERWORLD, 1, 1);
        Ticker.Entry b = entry(OVERWORLD, 5, 9);
        Ticker.Entry c = entry(OVERWORLD, 40, 1);
        this.entries.addAll(List.of(a, b, c));
        assertTrue(this.entries.removeAll(Set.of(a, c)));
        assertFalse(this.entries.removeAll(Set.of(a)));
        assertEquals(Set.of(b), copy());
        Iterator<Object> it = this.entries.iterator();
        it.next();
        it.remove();
        assertEquals(0, this.entries.size());
        assertFalse(this.entries.contains(b));
        // the chunk is forgotten with its last entry, so a later status call finds nothing to hide
        this.entries.statusChanged(OVERWORLD, chunk(1, 1), false);
        assertTrue(this.entries.add(a));
        assertEquals(Set.of(a), copy());
    }

    @Test
    void unloadForgetsTheChunk() {
        Ticker.Entry a = entry(OVERWORLD, 1, 1);
        Ticker.Entry c = entry(OVERWORLD, 40, 1);
        this.entries.addAll(List.of(a, c));
        this.entries.statusChanged(OVERWORLD, chunk(1, 1), false);
        Set<ChunkPos> loaded = Collections.synchronizedSet(new ObjectLinkedOpenHashSet<>());
        loaded.addAll(List.of(new ChunkPos(0, 0), new ChunkPos(2, 0), new ChunkPos(9, 9)));
        HandleChunk.LOADED_CHUNKS.put(OVERWORLD, loaded);
        LootrEntries.unloaded(OVERWORLD, new ChunkPos(0, 0));
        LootrEntries.unloaded(OVERWORLD, new ChunkPos(2, 0));
        assertEquals(0, this.entries.size());
        assertFalse(this.entries.contains(a), "a waiting chunk goes too");
        assertEquals(Set.of(new ChunkPos(9, 9)), loaded, "Lootr's loaded chunks forget the unloaded ones");
        assertTrue(this.entries.add(a), "the chunk's next load adds its containers anew");
        assertEquals(Set.of(a), copy());
    }

    private static ClassNode read(Class<?> c) throws IOException {
        String file = c.getName().substring(c.getName().lastIndexOf('.') + 1) + ".class";
        try (InputStream in = c.getResourceAsStream(file)) {
            ClassNode node = new ClassNode();
            new ClassReader(Objects.requireNonNull(in, file)).accept(node, 0);
            return node;
        }
    }

    @Test
    void knowsTheConverterShape() throws IOException {
        ClassNode entry = read(Ticker.Entry.class);
        assertNull(LootrMixins.differs(read(Ticker.class), entry, "tileEntries", "addEntry", "serverTick"));
        assertEquals("tileEntries used in count", LootrMixins.differs(read(CountingTicker.class), entry, "tileEntries", "addEntry", "serverTick"));
        assertEquals("no hasChunk test", LootrMixins.differs(read(BlindTicker.class), entry, "tileEntries", "addEntry", "serverTick"));
        assertEquals("entry without dimension and position",
            LootrMixins.differs(read(Ticker.class), read(Ticker.class), "tileEntries", "addEntry", "serverTick"));
        assertEquals("no private static Set entries", LootrMixins.differs(read(Ticker.class), entry, "entries", "addEntry", "serverTick"));
    }

    /** Lootr 0.7's converter, reduced to what LootrMixins checks. */
    static final class Ticker {
        private static final Set<Entry> tileEntries = new ObjectOpenHashSet<>();

        static void addEntry(Entry entry) {
            tileEntries.add(entry);
        }

        static void serverTick(ServerLevel level) {
            Set<Entry> done = new ObjectOpenHashSet<>();
            for (Entry e : new ObjectOpenHashSet<>(tileEntries)) {
                if (level.getChunkSource().hasChunk(e.getPosition().getX() >> 4, e.getPosition().getZ() >> 4)) {
                    done.add(e);
                }
            }
            tileEntries.removeAll(done);
        }

        public static final class Entry {
            private final ResourceKey<Level> dimension;
            private final BlockPos position;

            Entry(ResourceKey<Level> dimension, BlockPos position) {
                this.dimension = dimension;
                this.position = position;
            }

            public ResourceKey<Level> getDimension() {
                return this.dimension;
            }

            public BlockPos getPosition() {
                return this.position;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Entry e && e.dimension.equals(this.dimension) && e.position.equals(this.position);
            }

            @Override
            public int hashCode() {
                return 31 * this.dimension.hashCode() + this.position.hashCode();
            }
        }
    }

    /** A converter that also reads the set elsewhere. */
    static final class CountingTicker {
        private static final Set<Object> tileEntries = new ObjectOpenHashSet<>();

        static void addEntry(Object entry) {
            tileEntries.add(entry);
        }

        static void serverTick(ServerLevel level) {
            if (level.getChunkSource().hasChunk(0, 0)) {
                tileEntries.clear();
            }
        }

        static int count() {
            return tileEntries.size();
        }
    }

    /** A converter whose tick never tests the chunk. */
    static final class BlindTicker {
        private static final Set<Object> tileEntries = new ObjectOpenHashSet<>();

        static void addEntry(Object entry) {
            tileEntries.add(entry);
        }

        static void serverTick() {
            tileEntries.clear();
        }
    }
}
