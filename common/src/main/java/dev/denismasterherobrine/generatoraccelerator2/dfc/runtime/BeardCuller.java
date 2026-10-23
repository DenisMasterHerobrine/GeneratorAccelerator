package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import dev.denismasterherobrine.generatoraccelerator2.compat.MixinTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.BeardifierAccessor;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardBounds;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardExtras;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Conservative boxes outside of which every {@code Beardifier} contribution is exactly {@code 0.0},
 * per terrain adjustment type (see {@link BeardBounds}). A cell outside all of them skips the
 * beardifier entirely.
 */
public final class BeardCuller {
    /**
     * True when cells away from every vanilla piece may skip this beardifier. This requires that no mod changes the
     * beardifier's formulas or method in a way GA2 cannot look at. Other mods may add steps after vanilla's sum or wrap
     * the method ({@link BeardExtras}: YUNG's API, Integrated API, Moog's Structures, Twilight Forest's structure density).
     * When they do, it also requires that this chunk has none of their pieces.
     */
    public static boolean mayCull(Beardifier beardifier) {
        return dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard.testChunks == null
            && MixinTargets.beardCullingAllowed() && (!BeardExtras.present() || BeardExtras.cullable(beardifier));
    }

    private final int[] ranges;
    private final int count;

    private BeardCuller(int[] ranges, int count) {
        this.ranges = ranges;
        this.count = count;
    }

    public int count() {
        return count;
    }

    public static BeardCuller of(Beardifier beardifier) {
        BeardifierAccessor acc = (BeardifierAccessor) beardifier;
        ObjectListIterator<Beardifier.Rigid> pieces = acc.ga2$pieceIterator();
        ObjectListIterator<JigsawJunction> junctions = acc.ga2$junctionIterator();
        int n = 0;
        while (pieces.hasNext()) {
            pieces.next();
            n++;
        }
        pieces.back(Integer.MAX_VALUE);
        while (junctions.hasNext()) {
            junctions.next();
            n++;
        }
        junctions.back(Integer.MAX_VALUE);
        int[] ranges = new int[n * 6];
        int i = 0;
        while (pieces.hasNext()) {
            Beardifier.Rigid rigid = pieces.next();
            int code = BeardBounds.code(rigid.terrainAdjustment());
            if (code == BeardBounds.NONE) {
                continue;
            }
            if (code == BeardBounds.UNKNOWN) {
                // Unknown adjustment: never cull.
                int b = i * 6;
                ranges[b] = ranges[b + 2] = ranges[b + 4] = Integer.MIN_VALUE;
                ranges[b + 1] = ranges[b + 3] = ranges[b + 5] = Integer.MAX_VALUE;
            } else {
                BeardBounds.piece(rigid.box(), code, rigid.groundLevelDelta(), ranges, i * 6);
            }
            i++;
        }
        pieces.back(Integer.MAX_VALUE);
        while (junctions.hasNext()) {
            BeardBounds.junction(junctions.next(), ranges, i * 6);
            i++;
        }
        junctions.back(Integer.MAX_VALUE);
        return new BeardCuller(ranges, i);
    }

    /** True when any piece or junction can contribute a non-zero value somewhere inside the block range. */
    public boolean intersects(int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] r = ranges;
        for (int i = 0; i < count; i++) {
            int b = i * 6;
            if (x1 >= r[b] && x0 <= r[b + 1] && y1 >= r[b + 2] && y0 <= r[b + 3] && z1 >= r[b + 4] && z0 <= r[b + 5]) {
                return true;
            }
        }
        return false;
    }
}
