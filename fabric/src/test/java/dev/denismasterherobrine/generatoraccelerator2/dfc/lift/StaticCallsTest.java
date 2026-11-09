package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The interpreter's static calls: arguments arrive as the method's own types, and results come back unchanged. */
public class StaticCallsTest {
    private static final String OWNER = StaticCallsTest.class.getName().replace('.', '/');

    /** Five arguments of mixed sorts, each kept apart in the result. */
    public static double mixed(double a, int b, float c, double d, int e) {
        return a * 1e8 + b * 1e6 + c * 1e4 + d * 1e2 + e;
    }

    @Test
    void doubleCalls() {
        assertEquals(Math.fma(0.1, 0.2, 0.3), StaticCalls.invoke("java/lang/Math", "fma", "(DDD)D", 0.1, 0.2, 0.3, 0, 0));
        assertEquals(Math.max(-0.0, 0.0), StaticCalls.invoke("java/lang/Math", "max", "(DD)D", -0.0, 0.0, 0, 0, 0));
    }

    @Test
    void intCalls() {
        assertEquals(Math.floorMod(-7, 3), (int) StaticCalls.invoke("java/lang/Math", "floorMod", "(II)I", -7, 3, 0, 0, 0));
        // overloads of one name resolve apart
        assertEquals(Integer.MIN_VALUE, (int) StaticCalls.invoke("java/lang/Math", "abs", "(I)I", Integer.MIN_VALUE, 0, 0, 0, 0));
        assertEquals(2.5, StaticCalls.invoke("java/lang/Math", "abs", "(D)D", -2.5, 0, 0, 0, 0));
    }

    @Test
    void floatArgument() {
        float f = 2.5f;
        assertEquals(Math.round(f), (int) StaticCalls.invoke("java/lang/Math", "round", "(F)I", f, 0, 0, 0, 0));
    }

    @Test
    void fiveArguments() {
        assertEquals(mixed(1, 2, 3f, 4, 5), StaticCalls.invoke(OWNER, "mixed", "(DIFDI)D", 1, 2, 3f, 4, 5));
    }
}
