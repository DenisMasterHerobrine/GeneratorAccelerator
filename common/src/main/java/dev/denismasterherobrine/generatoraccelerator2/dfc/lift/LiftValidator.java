package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.program.Interpreter;
import java.util.Random;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Accepts a lifted node only if it reproduces the original object's {@code compute} bit for bit on thousands of positions. */
public final class LiftValidator {
    private LiftValidator() {}

    public static final int POINTS = 3000;

    /** @return null when identical, otherwise a description of the first mismatch */
    public static String validate(DensityFunction fn, Node lifted, Interpreter interpreter) {
        Random rng = new Random(0x11F7L ^ fn.getClass().getName().hashCode());
        for (int i = 0; i < POINTS; i++) {
            int x, z;
            switch (rng.nextInt(4)) {
                case 0 -> { x = rng.nextInt(60_000_000) - 30_000_000; z = rng.nextInt(60_000_000) - 30_000_000; }
                case 1 -> { x = (rng.nextInt(2000) - 1000) * 4; z = (rng.nextInt(2000) - 1000) * 4; }
                default -> { x = rng.nextInt(8192) - 4096; z = rng.nextInt(8192) - 4096; }
            }
            int y = rng.nextInt(3) == 0 ? -64 + rng.nextInt(49) * 8 : rng.nextInt(400) - 70;
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            double expected;
            try {
                expected = fn.compute(ctx);
            } catch (RuntimeException e) {
                return "original compute threw " + e + " at (" + x + "," + y + "," + z + ")";
            }
            double actual;
            try {
                actual = interpreter.eval(lifted, ctx);
            } catch (RuntimeException e) {
                return "lifted evaluation threw " + e + " at (" + x + "," + y + "," + z + ")";
            }
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                return "differential mismatch at (" + x + "," + y + "," + z + "): original=" + expected + " lifted=" + actual;
            }
        }
        return null;
    }
}
