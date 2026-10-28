package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Looks through a modded feature's code for state shared between placements. Two patterns count: a static field written
 * outside class initialization, and a static collection or array changed in place. A change in place is the receiver of
 * a JDK collection's mutating call, or the array of an element store, traced back through locals to a static field. A
 * feature is one object for every placement, so the same counts for the fields of its own class and its modded
 * superclasses outside their constructors (Blueprint's trees keep their log positions in such fields).
 * Decorations run on several threads at once, each in its own part of the world. So a feature that keeps such state can
 * see another placement's half-done changes. Vanilla ran all decorations on one thread. The scan reads only the bytecode.
 * It covers the feature's own methods and those of its modded superclasses. It then follows the calls (and lambdas) into
 * classes that are not vanilla's, the JDK's or a library's, at most {@link #MAX_METHODS} methods. A feature whose class
 * cannot be read counts as sharing state. It then places one at a time, which is always correct.
 */
public final class FeatureSafetyScan {
    private FeatureSafetyScan() {}

    static final int MAX_METHODS = 400;

    /**
     * The bytecode read by the running scan on this thread, dropped when the scan ends. A scan reads up to thousands of classes
     * (a structure's). The caller keeps the scan's answer, so the trees would only take memory afterwards.
     */
    private static final ThreadLocal<Map<String, ClassNode>> CLASSES = new ThreadLocal<>();
    private static final ClassNode MISSING = new ClassNode();

    private static final Set<String> MUTATORS = Set.of("add", "addAll", "put", "putAll", "putIfAbsent", "remove", "removeAll", "removeIf", "retainAll",
        "clear", "set", "replace", "replaceAll", "compute", "computeIfAbsent", "computeIfPresent", "merge", "offer", "poll", "push", "pop",
        "addFirst", "addLast", "removeFirst", "removeLast", "sort");

    /** Why the feature's placements would share state, or null when the scan found none. */
    @Nullable
    public static String sharedState(Class<?> feature) {
        return sharedState(feature, false, MAX_METHODS, false);
    }

    /**
     * @param followInstantiated also look through every method of the modded classes the code instantiates (vanilla code calls
     *                           their overrides through vanilla types, e.g. a structure piece's {@code addChildren})
     * @param maxMethods         how many methods to look through at most
     * @param capIsShared        true to answer "shared" when the code is larger than that (always correct), false to answer null
     */
    @Nullable
    public static String sharedState(Class<?> type, boolean followInstantiated, int maxMethods, boolean capIsShared) {
        return sharedState(type, followInstantiated, maxMethods, capIsShared, Set.of());
    }

    /** @param ignored static fields ({@code owner.name}, internal owner names) not to count: state reviewed elsewhere ({@link SeededStatics}) */
    @Nullable
    public static String sharedState(Class<?> type, boolean followInstantiated, int maxMethods, boolean capIsShared, Set<String> ignored) {
        if (CLASSES.get() != null) {
            return scan(type, followInstantiated, maxMethods, capIsShared, ignored, null);
        }
        CLASSES.set(new HashMap<>());
        try {
            return scan(type, followInstantiated, maxMethods, capIsShared, ignored, null);
        } finally {
            CLASSES.remove();
        }
    }

    /** The shared object's own fields a scan met outside constructors: the ones written, and the collections and arrays read. */
    private static final class OwnFields {
        final Set<String> written = new HashSet<>();
        final Set<String> containersRead = new HashSet<>();
    }

    private static final String PLACE = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z";

    /**
     * True when a feature's placements share state only through the feature's own fields, and its {@code place} assigns every one
     * of those fields anew before anything reads them (Blueprint's trees: their log and foliage sets). Each placement then starts
     * from fresh values, so a placement on a copy of the feature object does what a placement on the object itself does, and
     * copies on several threads share nothing ({@link FeatureCopies}). Static state, or a field read before it is assigned, rules
     * that out.
     */
    public static boolean perPlacementState(Class<?> type) {
        boolean outer = CLASSES.get() == null;
        if (outer) {
            CLASSES.set(new HashMap<>());
        }
        try {
            OwnFields ownFields = new OwnFields();
            if (scan(type, false, MAX_METHODS, true, Set.of(), ownFields) != null) {
                return false;
            }
            Set<String> needed = new HashSet<>(ownFields.written);
            needed.addAll(ownFields.containersRead);
            Set<String> assigned = assignedFirst(type);
            return !needed.isEmpty() && assigned != null && assigned.containsAll(needed);
        } finally {
            if (outer) {
                CLASSES.remove();
            }
        }
    }

    /**
     * The own fields the feature's {@code place} assigns before anything else can read them: the stores at its start, where only
     * locals, constants, new JDK or library objects and calls into vanilla or library classes come first. Null without such a method.
     */
    @Nullable
    private static Set<String> assignedFirst(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        Set<String> own = new HashSet<>();
        MethodNode place = null;
        for (Class<?> c = type; c != null && !isVanillaOrLibrary(c.getName()); c = c.getSuperclass()) {
            ClassNode node = node(c.getName().replace('.', '/'), loader);
            if (node == null) {
                return null;
            }
            own.add(node.name);
            for (MethodNode m : node.methods) {
                if (place == null && (m.access & Opcodes.ACC_STATIC) == 0 && Names.mojangMethodKey(m.name, m.desc).equals(PLACE)) {
                    place = m;
                }
            }
        }
        if (place == null || place.instructions == null) {
            return null;
        }
        Set<String> assigned = new HashSet<>();
        for (AbstractInsnNode insn = place.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                // labels, line numbers, frames
                continue;
            }
            if (insn instanceof FieldInsnNode f && op == Opcodes.PUTFIELD && own.contains(f.owner)) {
                assigned.add(f.name);
            } else if (insn instanceof MethodInsnNode call) {
                // a private method or a super call may read a field
                boolean special = op == Opcodes.INVOKESPECIAL && !call.name.equals("<init>");
                if (special || own.contains(call.owner) || !isVanillaOrLibrary(call.owner.replace('/', '.'))) {
                    break;
                }
            } else if (insn instanceof TypeInsnNode t) {
                if (op == Opcodes.NEW && !isVanillaOrLibrary(t.desc.replace('/', '.'))) {
                    break;
                }
            } else if (!(op >= Opcodes.ACONST_NULL && op <= Opcodes.ALOAD || op >= Opcodes.ISTORE && op <= Opcodes.ASTORE || op == Opcodes.DUP
                || op == Opcodes.POP)) {
                // a branch, a field read, an array access, a lambda: anything that could read a field comes after
                break;
            }
        }
        return assigned;
    }

    /**
     * @param ownFields where the writes of the shared object's own fields and the reads of its own collections go, instead of
     *                  counting as shared state; null to count them
     */
    @Nullable
    private static String scan(Class<?> type, boolean followInstantiated, int maxMethods, boolean capIsShared, Set<String> ignored,
                               @Nullable OwnFields ownFields) {
        ClassLoader loader = type.getClassLoader();
        ArrayDeque<String[]> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        // the classes of the one object every placement shares: the type and its modded superclasses
        Set<String> own = new HashSet<>();
        Set<String> instantiated = new HashSet<>();
        for (Class<?> c = type; c != null && !isVanillaOrLibrary(c.getName()); c = c.getSuperclass()) {
            ClassNode node = node(c.getName().replace('.', '/'), loader);
            if (node == null) {
                return "its bytecode could not be read";
            }
            own.add(node.name);
            for (MethodNode m : node.methods) {
                work.add(new String[] { node.name, m.name, m.desc });
            }
        }
        int scanned = 0;
        while (!work.isEmpty()) {
            String[] key = work.poll();
            if (!seen.add(key[0] + '.' + key[1] + key[2])) {
                continue;
            }
            if (++scanned > maxMethods) {
                return capIsShared ? "has more code than is looked through (" + maxMethods + " methods)" : null;
            }
            ClassNode owner = node(key[0], loader);
            ClassNode[] declaring = new ClassNode[1];
            MethodNode method = owner == null ? null : find(owner, key[1], key[2], loader, declaring);
            if (method == null || method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            boolean init = method.name.equals("<clinit>");
            boolean readsContainer = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode f) {
                    if (f.getOpcode() == Opcodes.PUTSTATIC && !init && !isJdk(f.owner) && !ignored.contains(f.owner + "." + f.name)) {
                        return "writes the static field " + simple(f.owner) + "." + f.name + " (in " + simple(declaring[0].name) + "." + method.name + ")";
                    }
                    if (f.getOpcode() == Opcodes.PUTFIELD && own.contains(f.owner) && !method.name.equals("<init>") && !ignored.contains(f.owner + "." + f.name)) {
                        if (ownFields != null) {
                            ownFields.written.add(f.name);
                            continue;
                        }
                        return "writes its own field " + simple(f.owner) + "." + f.name + " (in " + simple(declaring[0].name) + "." + method.name + ")";
                    }
                    if (f.getOpcode() == Opcodes.GETSTATIC && sharedContainer(f)) {
                        readsContainer = true;
                    } else if (f.getOpcode() == Opcodes.GETFIELD && own.contains(f.owner) && container(f.desc) && !method.name.equals("<init>")) {
                        if (ownFields != null) {
                            ownFields.containersRead.add(f.name);
                        } else {
                            readsContainer = true;
                        }
                    }
                } else if (insn instanceof MethodInsnNode call) {
                    if (!isVanillaOrLibrary(call.owner.replace('/', '.'))) {
                        work.add(new String[] { call.owner, call.name, call.desc });
                    }
                } else if (followInstantiated && insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode t
                    && !isVanillaOrLibrary(t.desc.replace('/', '.')) && instantiated.add(t.desc)) {
                    // an object of a modded class: vanilla code may call any of its methods through a vanilla type
                    for (String c = t.desc; c != null && !isVanillaOrLibrary(c.replace('/', '.')); ) {
                        ClassNode n = node(c, loader);
                        if (n == null) {
                            break;
                        }
                        for (MethodNode m : n.methods) {
                            work.add(new String[] { n.name, m.name, m.desc });
                        }
                        c = n.superName;
                    }
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && !isVanillaOrLibrary(h.getOwner().replace('/', '.'))) {
                            work.add(new String[] { h.getOwner(), h.getName(), h.getDesc() });
                        }
                    }
                }
            }
            if (readsContainer && !init) {
                String changed = changesContainer(declaring[0].name, method, ignored, ownFields != null ? Set.of() : own);
                if (changed != null) {
                    return "changes the " + changed + " (in " + simple(declaring[0].name) + "." + method.name + ")";
                }
            }
        }
        return null;
    }

    /**
     * True when the static field holds a thread-safe collection although it is declared through an interface ({@code Map},
     * {@code Set}, ...). That is the case when the field's class initializer stores in it a new {@code java.util.concurrent}
     * collection, a {@code Collections.synchronized*} wrapper or {@code ConcurrentHashMap.newKeySet()}, and nothing else. A
     * change through the field is then a change of a concurrent collection. The scan does not count such a change, as for a
     * field declared with such a type.
     */
    private static boolean concurrentStatic(FieldInsnNode field) {
        ClassNode owner = CLASSES.get().get(field.owner);
        if (owner == null || owner == MISSING) {
            return false;
        }
        boolean concurrent = false;
        for (MethodNode m : owner.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC || !put.owner.equals(field.owner)
                    || !put.name.equals(field.name)) {
                    continue;
                }
                if (!m.name.equals("<clinit>")) {
                    return false; // stored again later: not one fixed collection
                }
                // the value stored: the closest call that made it (skipping casts and duplicates)
                AbstractInsnNode prev = put.getPrevious();
                while (prev != null && (prev.getOpcode() == Opcodes.CHECKCAST || prev.getOpcode() == Opcodes.DUP || prev.getOpcode() == -1)) {
                    prev = prev.getPrevious();
                }
                if (!(prev instanceof MethodInsnNode made)) {
                    return false;
                }
                boolean ok = made.getOpcode() == Opcodes.INVOKESPECIAL && made.name.equals("<init>") && made.owner.startsWith("java/util/concurrent/")
                    || made.getOpcode() == Opcodes.INVOKESTATIC && made.owner.equals("java/util/Collections") && made.name.startsWith("synchronized")
                    || made.getOpcode() == Opcodes.INVOKESTATIC && made.owner.equals("java/util/concurrent/ConcurrentHashMap") && made.name.equals("newKeySet");
                if (!ok) {
                    return false;
                }
                concurrent = true;
            }
        }
        return concurrent;
    }

    /**
     * The static field, or the field of the shared object's own classes ({@code own}), whose collection or array this method
     * changes in place, or null. A change is a mutating JDK collection call on the field's value, or an element store into it.
     * The value is traced back through locals and casts.
     */
    @Nullable
    private static String changesContainer(String owner, MethodNode method, Set<String> ignored, Set<String> own) {
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (Exception e) {
            // cannot follow the values: count any change next to a static container read as shared
            return "collection or array of " + simple(owner);
        }
        AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            Frame<SourceValue> frame = frames[i];
            if (frame == null) {
                continue;
            }
            AbstractInsnNode insn = insns[i];
            int op = insn.getOpcode();
            SourceValue target = null;
            if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
                target = frame.getStack(frame.getStackSize() - 3);
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC && call.owner.startsWith("java/util/")
                && !call.owner.startsWith("java/util/concurrent/") && MUTATORS.contains(call.name)) {
                target = frame.getStack(frame.getStackSize() - 1 - Type.getArgumentTypes(call.desc).length);
            }
            if (target != null) {
                FieldInsnNode field = containerSource(target, frames, method, 0, own);
                boolean isStatic = field != null && field.getOpcode() == Opcodes.GETSTATIC;
                if (field != null && !ignored.contains(field.owner + "." + field.name) && (field.desc.startsWith("[") || !isStatic || !concurrentStatic(field))) {
                    return (isStatic ? "static " : "own ") + (field.desc.startsWith("[") ? "array " : "collection ") + simple(field.owner) + "." + field.name;
                }
            }
        }
        return null;
    }

    /** The read of a shared container a value comes from (a static field, or a field of {@code own}), through local variables, casts and duplicates. */
    @Nullable
    private static FieldInsnNode containerSource(SourceValue value, Frame<SourceValue>[] frames, MethodNode method, int depth, Set<String> own) {
        if (depth > 8) {
            return null;
        }
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof FieldInsnNode f && (f.getOpcode() == Opcodes.GETSTATIC && sharedContainer(f)
                || f.getOpcode() == Opcodes.GETFIELD && own.contains(f.owner) && container(f.desc))) {
                return f;
            }
            int index = method.instructions.indexOf(source);
            Frame<SourceValue> before = index >= 0 ? frames[index] : null;
            if (before == null) {
                continue;
            }
            SourceValue next = null;
            if (source instanceof VarInsnNode load && source.getOpcode() == Opcodes.ALOAD) {
                next = before.getLocal(load.var);
            } else if (source.getOpcode() == Opcodes.ASTORE || source.getOpcode() == Opcodes.CHECKCAST || source.getOpcode() == Opcodes.DUP) {
                next = before.getStack(before.getStackSize() - 1);
            }
            if (next != null) {
                FieldInsnNode f = containerSource(next, frames, method, depth + 1, own);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** A static field of a modded class holding changeable contents: an array or one of the JDK's non-concurrent collections. */
    private static boolean sharedContainer(FieldInsnNode f) {
        return !isVanillaOrLibrary(f.owner.replace('/', '.')) && container(f.desc);
    }

    /** A field type holding changeable contents: an array or one of the JDK's non-concurrent collections. */
    private static boolean container(String desc) {
        if (desc.startsWith("[")) {
            return true;
        }
        if (!desc.startsWith("Ljava/util/") || desc.startsWith("Ljava/util/concurrent/")) {
            return false;
        }
        String name = Type.getType(desc).getInternalName();
        return name.endsWith("Map") || name.endsWith("List") || name.endsWith("Set") || name.endsWith("Collection") || name.endsWith("Deque")
            || name.endsWith("Queue") || name.equals("java/util/BitSet");
    }

    /** The method, declared on the class or inherited from a modded superclass. {@code declaring[0]} receives the declaring class. */
    @Nullable
    private static MethodNode find(ClassNode owner, String name, String desc, ClassLoader loader, ClassNode[] declaring) {
        for (ClassNode c = owner; c != null; ) {
            for (MethodNode m : c.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    declaring[0] = c;
                    return m;
                }
            }
            if (c.superName == null || isVanillaOrLibrary(c.superName.replace('/', '.'))) {
                return null;
            }
            c = node(c.superName, loader);
        }
        return null;
    }

    private static boolean isJdk(String internal) {
        return internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/") || internal.startsWith("sun/");
    }

    /** Vanilla's, the JDK's and the libraries Minecraft and Forge ship. Not followed: their static state is not the feature's. */
    static boolean isVanillaOrLibrary(String name) {
        return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("java.") || name.startsWith("javax.")
            || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("it.unimi.dsi.") || name.startsWith("com.google.")
            || name.startsWith("org.slf4j.") || name.startsWith("org.apache.") || name.startsWith("net.minecraftforge.") || name.startsWith("cpw.mods.") || name.startsWith("net.fabricmc.") || name.startsWith("org.joml.");
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    @Nullable
    private static ClassNode node(String internal, ClassLoader loader) {
        ClassNode node = CLASSES.get().computeIfAbsent(internal, k -> {
            ClassLoader l = loader != null ? loader : ClassLoader.getSystemClassLoader();
            try (InputStream in = l.getResourceAsStream(k + ".class")) {
                if (in == null) {
                    return MISSING;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return cn;
            } catch (Exception e) {
                return MISSING;
            }
        });
        return node == MISSING ? null : node;
    }
}
