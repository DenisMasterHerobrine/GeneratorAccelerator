package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.util.Arrays;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The two worldgen heightmaps of a noise fill, set once per column after the fill instead of updated
 * for every block.
 *
 * <p>{@code doFill} writes each column strictly top-down, once per position, into heightmaps it has just
 * created. For such a heightmap, {@code Heightmap.update} only acts on the first block of a column that
 * passes the heightmap's test. The height becomes that block + 1. Every later block of the column is lower,
 * so it is at least two below the height and is ignored. So the result is the highest passing block + 1, or
 * the minimum height when none passes. That is what {@link #finish} writes. A heightmap that already held a
 * height when the fill reached it keeps vanilla's per-block updates.
 */
public final class FillHeights {
    private static final ThreadLocal<FillHeights> CURRENT = ThreadLocal.withInitial(FillHeights::new);
    /** Not final so the exactness test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.fillHeights"));

    private final Heightmap[] maps = new Heightmap[2];
    private final boolean[] fresh = new boolean[2];
    private final int[][] top = { new int[256], new int[256] };
    private int count;

    public static FillHeights begin() {
        FillHeights h = CURRENT.get();
        h.count = 0;
        h.maps[0] = null;
        h.maps[1] = null;
        return h;
    }

    public static FillHeights current() {
        return CURRENT.get();
    }

    /** Vanilla's {@code heightmap.update(x, y, z, state)} during the fill, or the recorded column top. */
    public void update(Heightmap heightmap, int x, int y, int z, BlockState state, Ga2Heightmap access) {
        int slot = slot(heightmap, access);
        if (slot < 0 || !this.fresh[slot]) {
            heightmap.update(x, y, z, state);
            return;
        }
        int[] tops = this.top[slot];
        int index = x + z * 16;
        if (y > tops[index] && access.ga2$isOpaque().test(state)) {
            tops[index] = y;
        }
    }

    private int slot(Heightmap heightmap, Ga2Heightmap access) {
        for (int i = 0; i < this.count; i++) {
            if (this.maps[i] == heightmap) {
                return i;
            }
        }
        if (this.count == 2) {
            return -1;
        }
        int i = this.count++;
        this.maps[i] = heightmap;
        this.fresh[i] = isUnset(heightmap);
        Arrays.fill(this.top[i], Integer.MIN_VALUE);
        return i;
    }

    private static boolean isUnset(Heightmap heightmap) {
        for (long word : heightmap.getRawData()) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    /** Writes the recorded column tops into the heightmaps that were fresh. */
    public void finish() {
        for (int i = 0; i < this.count; i++) {
            if (this.fresh[i]) {
                Ga2Heightmap access = (Ga2Heightmap) (Object) this.maps[i];
                int[] tops = this.top[i];
                for (int index = 0; index < 256; index++) {
                    if (tops[index] != Integer.MIN_VALUE) {
                        access.ga2$setHeight(index & 15, index >> 4, tops[index] + 1);
                    }
                }
            }
            // the thread keeps this object. It must not also keep the chunk (and through it the level)
            this.maps[i] = null;
        }
        this.count = 0;
    }

    /** Added to {@code Heightmap}. */
    public interface Ga2Heightmap {
        Predicate<BlockState> ga2$isOpaque();

        void ga2$setHeight(int x, int z, int height);
    }
}
