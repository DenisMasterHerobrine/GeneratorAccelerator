package dev.denismasterherobrine.generatoraccelerator2.dfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Whole-cell bounds of a density that reads a block coordinate as an int ({@code Node.IntToDouble}). */
public class CellBoundsIntTest {
    private static final double[] NO_CORNERS = new double[0];

    /** {@code 100 - y}, the way the lifter writes a coordinate read: an int expression turned into a double. */
    private static Node hundredMinusY() {
        INode y = new INode.IntCoord(1);
        return new Node.Ap2(Node.Ap2Type.ADD, new Node.IntToDouble(new INode.IntNeg(y)), new Node.Const(100.0), Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY);
    }

    @Test
    void coordinateReadsAreBounded() {
        CellBounds bounds = CellBounds.of(hundredMinusY(), Map.of(), new NoiseSpec[0]);
        assertNotNull(bounds, "an int coordinate is covered");
        CellBounds b = bounds.fresh();
        assertEquals(CellBounds.SOLID, b.classify(NO_CORNERS, 0, 0, 0, 4, 8, true));
        assertEquals(CellBounds.SKY, b.classify(NO_CORNERS, 0, 200, 0, 4, 8, true));
        // 100 - y is 0 at y = 100: not solid, and the cell holds solid blocks below it
        assertEquals(CellBounds.MIXED, b.classify(NO_CORNERS, 0, 96, 0, 4, 8, true));
        assertEquals(CellBounds.SKY, b.classify(NO_CORNERS, 0, 100, 0, 4, 8, true));
    }

    @Test
    void sumsProductsAndShiftsAreBounded() {
        // (x * 3 - z) >> 2 over x in [16, 19], z in [32, 35]: (48 - 35) >> 2 = 3 up to (57 - 32) >> 2 = 6
        INode x = new INode.IntCoord(0);
        INode z = new INode.IntCoord(2);
        INode e = new INode.IntBin(INode.IntOp.SHR, new INode.IntBin(INode.IntOp.SUB, new INode.IntBin(INode.IntOp.MUL, x, new INode.IntConst(3)), z),
            new INode.IntConst(2));
        Node root = new Node.Ap2(Node.Ap2Type.ADD, new Node.IntToDouble(e), new Node.Const(-2.5), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        CellBounds b = CellBounds.of(root, Map.of(), new NoiseSpec[0]).fresh();
        assertEquals(CellBounds.SOLID, b.classify(NO_CORNERS, 16, 0, 32, 4, 8, true));
        assertEquals(0.5, b.lastLo);
        assertEquals(3.5, b.lastHi);
    }

    @Test
    void wrappingProductsHaveNoBounds() {
        INode x = new INode.IntCoord(0);
        Node root = new Node.IntToDouble(new INode.IntBin(INode.IntOp.MUL, x, new INode.IntConst(1 << 20)));
        CellBounds b = CellBounds.of(root, Map.of(), new NoiseSpec[0]).fresh();
        // x * 2^20 wraps past x = 2048: the int arithmetic is not monotone there
        assertEquals(CellBounds.MIXED, b.classify(NO_CORNERS, 4096, 0, 0, 4, 8, true));
        assertEquals(CellBounds.SOLID, b.classify(NO_CORNERS, 16, 0, 0, 4, 8, true));
    }

    @Test
    void otherIntOperationsStayUncovered() {
        Node root = new Node.IntToDouble(new INode.IntBin(INode.IntOp.XOR, new INode.IntCoord(0), new INode.IntConst(5)));
        assertNull(CellBounds.of(root, Map.of(), new NoiseSpec[0]));
    }
}
