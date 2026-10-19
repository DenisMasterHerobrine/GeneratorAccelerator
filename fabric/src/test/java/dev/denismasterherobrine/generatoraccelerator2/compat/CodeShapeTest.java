package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/** {@link CodeShape}: the same code gives the same fingerprint however it is read; a changed instruction gives another. */
class CodeShapeTest {
    private static ClassNode read(Class<?> c, int flags) throws IOException {
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, flags);
            return node;
        }
    }

    /** A method with a loop, a branch, a switch and an exception handler. */
    static int sample(int[] values, String key) {
        int sum = 0;
        for (int v : values) {
            switch (v & 3) {
                case 0 -> sum += v;
                case 1 -> sum -= v;
                default -> sum ^= v;
            }
        }
        try {
            sum += Integer.parseInt(key);
        } catch (NumberFormatException e) {
            sum = -sum;
        }
        return sum > 100 ? sum : sum * 2;
    }

    @Test
    void debugInfoAndFramesDoNotCount() throws IOException {
        MethodNode full = CodeShape.method(read(CodeShapeTest.class, 0), "sample", null);
        MethodNode bare = CodeShape.method(read(CodeShapeTest.class, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES), "sample", null);
        MethodNode expanded = CodeShape.method(read(CodeShapeTest.class, ClassReader.EXPAND_FRAMES), "sample", null);
        assertEquals(CodeShape.fingerprint(full), CodeShape.fingerprint(bare));
        assertEquals(CodeShape.fingerprint(full), CodeShape.fingerprint(expanded));
    }

    @Test
    void aChangedInstructionCounts() throws IOException {
        ClassNode node = read(CodeShapeTest.class, 0);
        MethodNode m = CodeShape.method(node, "sample", null);
        String before = CodeShape.fingerprint(m);
        m.instructions.insert(new InsnNode(Opcodes.NOP));
        assertNotEquals(before, CodeShape.fingerprint(m));
    }

    @Test
    void lookups() throws IOException {
        ClassNode node = read(CodeShapeTest.class, 0);
        assertNull(CodeShape.method(node, "missing", null));
        assertEquals("missing", CodeShape.fingerprint(null));
        assertEquals("sample", CodeShape.methodStarting(node, "samp").name);
    }
}
