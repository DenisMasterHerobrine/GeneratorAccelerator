package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.service.MixinService;

/**
 * Fingerprints of other mods' methods whose exact behaviour a fast path relies on. A fingerprint hashes the instructions with
 * their operands and the exception handlers. Jump targets count as instruction indices, so line numbers and frames do not
 * count. A fast path written against one version of a method compares the method's fingerprint with the known fingerprint. On
 * any other fingerprint the fast path stands down. A mod update that changes the method therefore keeps the mod's own code
 * until GA2 has been checked against the update. Minecraft's names in the instructions are hashed by their Mojang names
 * ({@link Names}), so a method hashes the same on both loaders.
 */
public final class CodeShape {
    private CodeShape() {}

    /** The class as the mixin service reads it (other mods' mixins not applied), or null. */
    public static ClassNode classNode(String className) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(className);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** The method {@code name} (with {@code desc}, or the only one of that name when desc is null), or null. */
    public static MethodNode method(ClassNode node, String name, String desc) {
        if (node == null) {
            return null;
        }
        MethodNode found = null;
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) {
                if (found != null) {
                    return null;
                }
                found = m;
            }
        }
        return found;
    }

    /** The only method whose name starts with {@code prefix} (a lambda's synthetic method), or null. */
    public static MethodNode methodStarting(ClassNode node, String prefix) {
        if (node == null) {
            return null;
        }
        MethodNode found = null;
        for (MethodNode m : node.methods) {
            if (m.name.startsWith(prefix)) {
                if (found != null) {
                    return null;
                }
                found = m;
            }
        }
        return found;
    }

    /** True when the class declares a method of that name (any descriptor). */
    public static boolean declares(ClassNode node, String name) {
        return node != null && node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    /** The method's fingerprint: 16 hex digits of a SHA-256 over its instructions and handlers. "missing" for null. */
    public static String fingerprint(MethodNode m) {
        if (m == null) {
            return "missing";
        }
        Map<LabelNode, Integer> at = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                at.put(label, index);
            } else if (insn.getOpcode() >= 0) {
                index++;
            }
        }
        StringBuilder b = new StringBuilder(Names.mojangDesc(m.desc)).append('|').append(m.access).append('|');
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }
            b.append(op);
            if (insn instanceof MethodInsnNode n) {
                b.append(' ').append(Names.mojangClass(n.owner)).append('.').append(Names.mojangMethod(n.name)).append(Names.mojangDesc(n.desc)).append(n.itf ? " i" : "");
            } else if (insn instanceof FieldInsnNode n) {
                b.append(' ').append(Names.mojangClass(n.owner)).append('.').append(Names.mojangField(n.name)).append(':').append(Names.mojangDesc(n.desc));
            } else if (insn instanceof TypeInsnNode n) {
                b.append(' ').append(n.desc.startsWith("[") ? Names.mojangDesc(n.desc) : Names.mojangClass(n.desc));
            } else if (insn instanceof VarInsnNode n) {
                b.append(' ').append(n.var);
            } else if (insn instanceof IntInsnNode n) {
                b.append(' ').append(n.operand);
            } else if (insn instanceof LdcInsnNode n) {
                b.append(' ').append(n.cst.getClass().getSimpleName()).append(' ').append(n.cst);
            } else if (insn instanceof IincInsnNode n) {
                b.append(' ').append(n.var).append(',').append(n.incr);
            } else if (insn instanceof JumpInsnNode n) {
                b.append(" @").append(at.get(n.label));
            } else if (insn instanceof InvokeDynamicInsnNode n) {
                b.append(' ').append(n.name).append(n.desc).append(' ').append(n.bsm).append(Arrays.deepToString(n.bsmArgs));
            } else if (insn instanceof TableSwitchInsnNode n) {
                b.append(' ').append(n.min).append(',').append(n.max).append(" @").append(at.get(n.dflt));
                n.labels.forEach(l -> b.append(',').append(at.get(l)));
            } else if (insn instanceof LookupSwitchInsnNode n) {
                b.append(" @").append(at.get(n.dflt));
                for (int i = 0; i < n.keys.size(); i++) {
                    b.append(',').append(n.keys.get(i)).append(':').append(at.get(n.labels.get(i)));
                }
            } else if (insn instanceof MultiANewArrayInsnNode n) {
                b.append(' ').append(Names.mojangDesc(n.desc)).append(' ').append(n.dims);
            }
            b.append(';');
        }
        if (m.tryCatchBlocks != null) {
            for (TryCatchBlockNode t : m.tryCatchBlocks) {
                b.append('T').append(at.get(t.start)).append(',').append(at.get(t.end)).append(',').append(at.get(t.handler)).append(',').append(t.type).append(';');
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", h[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
