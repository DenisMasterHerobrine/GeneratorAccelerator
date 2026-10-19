package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Beardifier.compute} with the piece index must return the same bits as vanilla for random
 * piece and junction sets of every adjustment type, queried both cell by cell (like the noise fill)
 * and in random order, plus the edge cases that keep vanilla's code.
 */
public class BeardifierExactnessTest {
    private static final TerrainAdjustment[] ADJUSTMENTS = TerrainAdjustment.values();

    @AfterEach
    void reset() {
        BeardifierFastPath.override = null;
    }

    @Test
    void randomPiecesMatchVanilla() throws Exception {
        Random r = new Random(20261002L);
        long compared = 0;
        int indexed = 0;
        for (int trial = 0; trial < 80; trial++) {
            List<Beardifier.Rigid> pieces = new ObjectArrayList<>();
            List<JigsawJunction> junctions = new ObjectArrayList<>();
            int pieceCount = trial % 10 == 0 ? 0 : r.nextInt(30);
            int junctionCount = trial % 7 == 0 ? 0 : r.nextInt(40);
            for (int i = 0; i < pieceCount; i++) {
                int x0 = r.nextInt(80) - 40;
                int y0 = r.nextInt(60) - 30;
                int z0 = r.nextInt(80) - 40;
                BoundingBox box = new BoundingBox(x0, y0, z0, x0 + r.nextInt(20), y0 + r.nextInt(20), z0 + r.nextInt(20));
                pieces.add(new Beardifier.Rigid(box, ADJUSTMENTS[r.nextInt(ADJUSTMENTS.length)], r.nextInt(11) - 5));
            }
            for (int i = 0; i < junctionCount; i++) {
                junctions.add(new JigsawJunction(r.nextInt(80) - 40, r.nextInt(60) - 30, r.nextInt(80) - 40, r.nextInt(5) - 2,
                    r.nextBoolean() ? StructureTemplatePool.Projection.RIGID : StructureTemplatePool.Projection.TERRAIN_MATCHING));
            }
            Beardifier vanilla = beardifier(pieces, junctions, false);
            Beardifier fast = beardifier(pieces, junctions, true);
            // Cell-by-cell sweep (every other cell column) in fill order.
            for (int cx = -56; cx < 56; cx += 8) {
                for (int cz = -52; cz < 56; cz += 8) {
                    for (int cy = 48; cy >= -56; cy -= 8) {
                        for (int iy = 7; iy >= 0; iy--) {
                            for (int ix = 0; ix < 4; ix++) {
                                for (int iz = 0; iz < 4; iz++) {
                                    compared += compare(vanilla, fast, cx + ix, cy + iy, cz + iz, trial);
                                }
                            }
                        }
                    }
                }
            }
            // Random order, so the bucket changes on almost every call.
            for (int q = 0; q < 20000; q++) {
                compared += compare(vanilla, fast, r.nextInt(130) - 65, r.nextInt(130) - 65, r.nextInt(130) - 65, trial);
            }
            if (state(fast) == 2) {
                indexed++;
            }
        }
        assertTrue(indexed > 60, "piece index was not used: " + indexed);
        System.out.println("BeardifierExactnessTest: " + compared + " values bit-identical to vanilla");
    }

    @Test
    void edgeCasesKeepVanillaBehaviour() throws Exception {
        List<Beardifier.Rigid> pieces = new ObjectArrayList<>();
        pieces.add(new Beardifier.Rigid(new BoundingBox(0, 0, 0, 8, 8, 8), TerrainAdjustment.BEARD_THIN, 0));
        pieces.add(new Beardifier.Rigid(new BoundingBox(10, -4, 3, 14, 6, 9), TerrainAdjustment.BURY, 3));
        List<JigsawJunction> junctions = new ObjectArrayList<>();
        junctions.add(new JigsawJunction(4, 2, 4, 0, StructureTemplatePool.Projection.RIGID));

        // Iterators handed over mid-list: vanilla's first compute sees a suffix, later ones everything.
        Supplier<Beardifier> midList = () -> {
            ObjectListIterator<Beardifier.Rigid> it = new ObjectArrayList<>(pieces).iterator();
            it.next();
            return new Beardifier(it, new ObjectArrayList<>(junctions).iterator());
        };
        BeardifierFastPath.override = false;
        Beardifier vanilla = midList.get();
        BeardifierFastPath.override = true;
        Beardifier fast = midList.get();
        for (int i = 0; i < 3; i++) {
            compare(vanilla, fast, 5, 3, 5, -1);
        }

        // Coordinates beyond the overflow-safe range keep vanilla's arithmetic.
        List<Beardifier.Rigid> far = new ObjectArrayList<>(pieces);
        far.add(new Beardifier.Rigid(new BoundingBox(1 << 30, 0, 0, (1 << 30) + 4, 4, 4), TerrainAdjustment.BEARD_BOX, 0));
        vanilla = beardifier(far, junctions, false);
        fast = beardifier(far, junctions, true);
        compare(vanilla, fast, 5, 3, 5, -1);
        compare(vanilla, fast, (1 << 30) + 2, 3, 2, -1);
        assertTrue(state(fast) == 0, "far piece must keep the vanilla path");

        vanilla = beardifier(pieces, junctions, false);
        fast = beardifier(pieces, junctions, true);
        int[] probes = { Integer.MAX_VALUE, Integer.MIN_VALUE, (1 << 29) + 1, -(1 << 29) - 1, 2_000_000_000 };
        for (int p : probes) {
            compare(vanilla, fast, p, 3, 5, -1);
            compare(vanilla, fast, 5, p, 5, -1);
            compare(vanilla, fast, 5, 3, p, -1);
        }
        compare(vanilla, fast, 5, 3, 5, -1);
    }

    private static Beardifier beardifier(List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions, boolean index) {
        BeardifierFastPath.override = index;
        try {
            return new Beardifier(new ObjectArrayList<>(pieces).iterator(), new ObjectArrayList<>(junctions).iterator());
        } finally {
            BeardifierFastPath.override = null;
        }
    }

    private static int compare(Beardifier vanilla, Beardifier fast, int x, int y, int z, int trial) {
        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
        double expected = vanilla.compute(ctx);
        double actual = fast.compute(ctx);
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            throw new AssertionError("trial " + trial + " at " + x + "," + y + "," + z + ": vanilla " + expected + ", index " + actual);
        }
        return 1;
    }

    private static int state(Beardifier b) throws ReflectiveOperationException {
        Field f = Beardifier.class.getDeclaredField("ga2$state");
        f.setAccessible(true);
        return f.getInt(b);
    }
}
