package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.EnhancedTerrainAdaptation;
import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier.EnhancedBeardifierRigid;
import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier.EnhancedJigsawJunction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.Test;

/**
 * YUNG's API 4.0 (1.20.1) pieces, read for the GPU ({@link EnhancedBeard#of}) from stand-ins of the mod's classes: the 4.0 layout is
 * found (no {@code adaptations} package) and read as Integrated API's, never inverted. Each term's factor equals the stand-in's
 * {@code computeDensityFactor}, transcribed from the mod's bytecode, with the mod's arguments: a piece's distances to its box and the
 * height above its bottom, a junction's offsets from its source and its height twice.
 */
public class EnhancedBeardYung4Test {
    @Test
    void readsYung4PiecesAsIntegratedApis() {
        EnhancedTerrainAdaptation both = new EnhancedTerrainAdaptation(24, 16, true, true);
        EnhancedTerrainAdaptation carveOnly = new EnhancedTerrainAdaptation(12, 8, true, false);
        BoundingBox a = new BoundingBox(0, 60, 0, 20, 75, 14);
        BoundingBox b = new BoundingBox(30, 50, -10, 41, 58, 3);
        BoundingBox c = new BoundingBox(-20, 64, 20, -5, 70, 30);
        List<EnhancedBeardifierRigid> rigids = List.of(new EnhancedBeardifierRigid(a, both, 0), new EnhancedBeardifierRigid(b, carveOnly, 0),
            new EnhancedBeardifierRigid(c, EnhancedTerrainAdaptation.NONE, 0));
        List<EnhancedJigsawJunction> junctions = List.of(
            new EnhancedJigsawJunction(new JigsawJunction(10, 62, 5, 3, StructureTemplatePool.Projection.RIGID), both),
            new EnhancedJigsawJunction(new JigsawJunction(35, 52, 0, -2, StructureTemplatePool.Projection.RIGID), EnhancedTerrainAdaptation.NONE));

        EnhancedBeard.Chunk chunk = EnhancedBeard.of(EnhancedBeard.Kind.YUNG, new ObjectArrayList<>(rigids).iterator(), new ObjectArrayList<>(junctions).iterator());
        assertNotNull(chunk, "YUNG's API 4.0 pieces must be readable");
        assertEquals(EnhancedBeard.Kind.INTEGRATED, chunk.kind());
        assertEquals(3, chunk.pieces().size());
        assertEquals(2, chunk.junctions().size());
        assertNull(chunk.pieces().get(2).adaptation(), "NONE adds nothing");
        assertNull(chunk.override());

        int compared = 0;
        for (int x = -30; x <= 50; x += 3) {
            for (int y = 40; y <= 90; y += 2) {
                for (int z = -20; z <= 40; z += 3) {
                    for (int i = 0; i < 2; i++) {
                        BoundingBox box = rigids.get(i).pieceBoundingBox();
                        EnhancedBeard.Adaptation ad = chunk.pieces().get(i).adaptation();
                        assertNotNull(ad);
                        assertFalse(ad.inverted());
                        int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
                        int dy = Math.max(0, Math.max(box.minY() - y, y - box.maxY()));
                        int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
                        double mod = rigids.get(i).pieceTerrainAdaptation().computeDensityFactor(dx, dy, dz, y - box.minY());
                        assertEquals(Double.doubleToLongBits(mod), Double.doubleToLongBits(EnhancedBeard.factor(ad, dx, dy, dz, y - box.minY())),
                            "piece " + i + " at " + x + ", " + y + ", " + z);
                        compared++;
                    }
                    for (int i = 0; i < 2; i++) {
                        JigsawJunction j = junctions.get(i).jigsawJunction();
                        EnhancedBeard.Junction g = chunk.junctions().get(i);
                        assertEquals(j.getSourceGroundY(), g.groundY(), "no bottom offset in 4.0");
                        int dx = x - j.getSourceX(), dy = y - j.getSourceGroundY(), dz = z - j.getSourceZ();
                        double mod = junctions.get(i).pieceTerrainAdaptation().computeDensityFactor(dx, dy, dz, dy);
                        assertEquals(Double.doubleToLongBits(mod), Double.doubleToLongBits(EnhancedBeard.factor(g.adaptation(), dx, dy, dz, dy)),
                            "junction " + i + " at " + x + ", " + y + ", " + z);
                        compared++;
                    }
                }
            }
        }
        assertEquals(4 * 27 * 26 * 21, compared);
    }
}
