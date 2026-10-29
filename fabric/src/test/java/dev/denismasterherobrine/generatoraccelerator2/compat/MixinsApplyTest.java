package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Every target of GA2's mixins loads. Each config has {@code defaultRequire: 1}, so an injector that finds nothing in the game's
 * code fails its target's load. Targets of other mods' classes are skipped when the mod is absent, as their configs apply only
 * with their mod. And every field initialiser of a mixin reaches its target's constructors.
 */
class MixinsApplyTest {
    @Test
    void everyTargetLoads() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        Set<String> targets = new TreeSet<>();
        for (ClassNode mixin : mixins(loader)) {
            targets.addAll(targetsOf(mixin));
        }
        List<String> failures = new ArrayList<>();
        int loaded = 0;
        for (String target : targets) {
            try {
                Class.forName(target, false, loader);
                loaded++;
            } catch (ClassNotFoundException e) {
                // another mod's class, absent here
                if (target.startsWith("net.minecraft.") || target.startsWith("com.mojang.")) {
                    failures.add(target + ": not found");
                }
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                failures.add(target + ": " + root);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + targets.size() + " targets failed:\n" + String.join("\n", failures));
        assertTrue(loaded > 100, "targets loaded: " + loaded);
    }

    /**
     * Mixin copies a mixin's field initialisers from the lines of its constructor outside the constructor's own range: from the
     * super call's line to the return's line. javac 25 gives an implicit constructor's return the line of the class's last static
     * initialiser. An instance initialiser above that line would be left out, and the field would keep its default.
     */
    @Test
    void everyFieldInitialiserIsCopied() throws IOException {
        List<String> dropped = new ArrayList<>();
        for (ClassNode mixin : mixins(getClass().getClassLoader())) {
            for (MethodNode m : mixin.methods) {
                if (m.name.equals("<init>")) {
                    for (String field : droppedInitialisers(m)) {
                        dropped.add(mixin.name + "." + field);
                    }
                }
            }
        }
        assertTrue(dropped.isEmpty(), "initialisers Mixin leaves out: " + dropped);
    }

    /** The fields whose initialiser lies inside the constructor's line range, as Mixin's {@code Constructor.getRange} computes it. */
    private static List<String> droppedInitialisers(MethodNode ctor) {
        int line = 0, start = 0, end = 0;
        boolean valid = false, afterSuper = false;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof LineNumberNode ln) {
                line = ln.line;
                valid = true;
            } else if (insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && !afterSuper) {
                afterSuper = true;
                start = line;
            } else if (insn.getOpcode() == Opcodes.PUTFIELD) {
                valid = false;
            } else if (insn.getOpcode() == Opcodes.RETURN) {
                end = valid ? line : start;
            }
        }
        List<String> out = new ArrayList<>();
        boolean kept = false;
        afterSuper = false;
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof LineNumberNode ln) {
                AbstractInsnNode next = ln.getNext();
                kept = ln.line < start || ln.line > end || ln.line == end && next != null && next.getOpcode() != Opcodes.RETURN;
            } else if (insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>")) {
                afterSuper = true;
            } else if (afterSuper && insn.getOpcode() == Opcodes.PUTFIELD && !kept) {
                out.add(((FieldInsnNode) insn).name);
            }
        }
        return out;
    }

    /** GA2's mixin classes, from the configs its {@code fabric.mod.json} lists, read from their bytes without loading them. */
    private static List<ClassNode> mixins(ClassLoader loader) throws IOException {
        // every mod on the classpath has one. GA2's is picked by its id.
        JsonObject mod = null;
        for (URL url : Collections.list(loader.getResources("fabric.mod.json"))) {
            try (InputStream in = url.openStream()) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if ("generatoraccelerator2".equals(json.get("id").getAsString())) {
                    mod = json;
                }
            }
        }
        assertNotNull(mod, "GA2's fabric.mod.json");
        List<ClassNode> out = new ArrayList<>();
        for (JsonElement config : mod.getAsJsonArray("mixins")) {
            JsonObject json;
            try (InputStream in = loader.getResourceAsStream(config.getAsString())) {
                json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            String pkg = json.get("package").getAsString();
            for (String side : List.of("mixins", "client", "server")) {
                JsonArray names = json.getAsJsonArray(side);
                if (names == null) {
                    continue;
                }
                for (JsonElement name : names) {
                    String mixin = pkg + "." + name.getAsString();
                    try (InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
                        if (in == null) {
                            throw new IOException("no class file for mixin " + mixin);
                        }
                        ClassNode node = new ClassNode();
                        new ClassReader(in).accept(node, 0);
                        out.add(node);
                    }
                }
            }
        }
        return out;
    }

    /** The {@code @Mixin} targets of one mixin class. */
    private static List<String> targetsOf(ClassNode node) {
        List<String> out = new ArrayList<>();
        List<AnnotationNode> annotations = node.invisibleAnnotations != null ? node.invisibleAnnotations : List.of();
        for (AnnotationNode a : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) {
                continue;
            }
            for (int i = 0; i < a.values.size(); i += 2) {
                Object value = a.values.get(i + 1);
                if (!(value instanceof List<?> list)) {
                    continue;
                }
                for (Object v : list) {
                    if (v instanceof Type type) {
                        out.add(type.getClassName());
                    } else if (v instanceof String name) {
                        out.add(name.replace('/', '.'));
                    }
                }
            }
        }
        return out;
    }
}
