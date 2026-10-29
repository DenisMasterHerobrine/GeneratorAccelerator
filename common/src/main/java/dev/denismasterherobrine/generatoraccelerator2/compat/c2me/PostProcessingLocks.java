package dev.denismasterherobrine.generatoraccelerator2.compat.c2me;

import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.shorts.ShortListIterator;
import java.util.function.Supplier;

/**
 * Keeps C2ME's fluid filter and the chunk save from using a chunk's post-processing lists at the same time.
 *
 * <p>C2ME filters the post-processing lists of a chunk that becomes block-ticking ({@code ServerBlockTicking.filterFluidTicks},
 * option {@code chunkSystem.filterFluidPostProcessing}). The filter runs on a C2ME worker. It removes the fluids that cannot
 * flow from the lists. C2ME's autosave may save the same chunk on the server thread meanwhile. The chunk is full and nothing
 * generates around it, so the chunk counts as ready for saving. The save's walk over a list that got shorter then fails
 * (the copy of a list in {@code SerializableChunkData.copyOf} sees it change). The chunk is not saved.
 *
 * <p>Each step of the filter's walk holds the list's monitor. The save holds the monitors of all of the chunk's lists while it
 * copies them. The save therefore sees every list between two steps of the filter. The filter holds one monitor at a time and
 * takes no other lock while holding it. The two cannot deadlock. A proto chunk and its LevelChunk share the list objects, so
 * the lists themselves are the monitors.
 */
public final class PostProcessingLocks {
    private PostProcessingLocks() {}

    /** The filter's walk over {@code list}, with every call holding the list's monitor. */
    public static ShortListIterator iterator(ShortList list, ShortListIterator walk) {
        return new Locked(list, walk);
    }

    /** Runs {@code pack} while holding the monitors of all non-null {@code lists}, in index order. */
    public static <T> T pack(ShortList[] lists, Supplier<T> pack) {
        return pack(lists, 0, pack);
    }

    private static <T> T pack(ShortList[] lists, int from, Supplier<T> pack) {
        for (int i = from; i < lists.length; i++) {
            ShortList list = lists[i];
            if (list != null) {
                synchronized (list) {
                    return pack(lists, i + 1, pack);
                }
            }
        }
        return pack.get();
    }

    private static final class Locked implements ShortListIterator {
        private final ShortList list;
        private final ShortListIterator walk;

        Locked(ShortList list, ShortListIterator walk) {
            this.list = list;
            this.walk = walk;
        }

        @Override
        public boolean hasNext() {
            synchronized (list) {
                return walk.hasNext();
            }
        }

        @Override
        public short nextShort() {
            synchronized (list) {
                return walk.nextShort();
            }
        }

        @Override
        public boolean hasPrevious() {
            synchronized (list) {
                return walk.hasPrevious();
            }
        }

        @Override
        public short previousShort() {
            synchronized (list) {
                return walk.previousShort();
            }
        }

        @Override
        public int nextIndex() {
            synchronized (list) {
                return walk.nextIndex();
            }
        }

        @Override
        public int previousIndex() {
            synchronized (list) {
                return walk.previousIndex();
            }
        }

        @Override
        public void remove() {
            synchronized (list) {
                walk.remove();
            }
        }

        @Override
        public void set(short k) {
            synchronized (list) {
                walk.set(k);
            }
        }

        @Override
        public void add(short k) {
            synchronized (list) {
                walk.add(k);
            }
        }
    }
}
