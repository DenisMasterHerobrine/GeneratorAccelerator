package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/** Defines generated classes as hidden nestmates of this package. */
public final class HiddenClassLoader {
    private HiddenClassLoader() {}

    /** Internal name prefix every generated class must use (same package as the lookup class). */
    public static final String GENERATED_PREFIX = "dev/denismasterherobrine/generatoraccelerator2/dfc/codegen/Generated";

    /**
     * HotSpot never JIT-compiles a method with more bytecode than this ({@code -XX:HugeMethodLimit}, with
     * {@code DontCompileHugeMethods} on by default). Such a program would run interpreted for good, far slower than vanilla.
     */
    public static final int JIT_METHOD_LIMIT = 8000;

    /**
     * Writes and defines a generated class. A class the JVM cannot load (a method over 64 KB) is refused with a
     * {@link DfcCompileException}. So is a class with a method the JIT would never compile. The program then falls back to
     * vanilla.
     */
    public static MethodHandles.Lookup define(ClassWriter cw, String debugName) {
        byte[] bytes;
        try {
            bytes = cw.toByteArray();
        } catch (RuntimeException e) {
            // ASM's MethodTooLargeException / ClassTooLargeException
            throw new DfcCompileException("generated class " + debugName + " is too large for the JVM", e, true);
        }
        String huge = largestMethodOver(bytes, JIT_METHOD_LIMIT);
        if (huge != null) {
            throw new DfcCompileException("generated method " + huge + " of " + debugName + " is over the JIT's " + JIT_METHOD_LIMIT
                + "-byte limit, so it would never be compiled", null, true);
        }
        return define(bytes, debugName);
    }

    public static MethodHandles.Lookup define(byte[] bytes, String debugName) {
        try {
            return MethodHandles.lookup().defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
        } catch (Throwable t) {
            Path dump = dump(bytes, debugName);
            throw new DfcCompileException("defineHiddenClass failed for " + debugName + (dump != null ? " (class bytes dumped to " + dump + ")" : ""), t);
        }
    }

    /** {@code "name (n bytes)"} of the largest method whose bytecode exceeds {@code limit}, or null. */
    public static String largestMethodOver(byte[] bytes, int limit) {
        ClassReader cr = new ClassReader(bytes);
        char[] buf = new char[cr.getMaxStringLength()];
        int u = cr.header + 6;
        u += 2 + 2 * cr.readUnsignedShort(u);
        u = skipMembers(cr, u);
        int methods = cr.readUnsignedShort(u);
        u += 2;
        String worst = null;
        int worstSize = limit;
        for (int i = 0; i < methods; i++) {
            String name = cr.readUTF8(u + 2, buf);
            int attributes = cr.readUnsignedShort(u + 6);
            u += 8;
            for (int a = 0; a < attributes; a++) {
                String attribute = cr.readUTF8(u, buf);
                int length = cr.readInt(u + 2);
                if ("Code".equals(attribute)) {
                    // max_stack (2), max_locals (2), code_length (4)
                    int codeLength = cr.readInt(u + 10);
                    if (codeLength > worstSize) {
                        worstSize = codeLength;
                        worst = name + " (" + codeLength + " bytes)";
                    }
                }
                u += 6 + length;
            }
        }
        return worst;
    }

    /** Skips the fields table starting at {@code u}. Returns the offset of the methods count. */
    private static int skipMembers(ClassReader cr, int u) {
        int count = cr.readUnsignedShort(u);
        u += 2;
        for (int i = 0; i < count; i++) {
            int attributes = cr.readUnsignedShort(u + 6);
            u += 8;
            for (int a = 0; a < attributes; a++) {
                u += 6 + cr.readInt(u + 2);
            }
        }
        return u;
    }

    public static Path dump(byte[] bytes, String debugName) {
        try {
            Path dir = Path.of("ga2-dfc-dump");
            Files.createDirectories(dir);
            Path file = dir.resolve(debugName + ".class");
            Files.write(file, bytes);
            return file;
        } catch (Exception e) {
            return null;
        }
    }
}
