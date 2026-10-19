package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads the {@code @MixinMerged} marks that Mixin leaves on every method it adds to or overwrites in a
 * target class. The marks tell which mixins, other than this mod's, have changed given methods so far.
 * Used by the mixin plugin while the target class is being transformed. Never loads game classes. Lives
 * outside the mixin package because Mixin refuses to load other classes from there.
 *
 * <p>Only changes that a fast path would skip count. Mixin applies mixins in priority order. Mixin inserts
 * each injector at its point in the method as found before any injector ran. The callbacks of mixins applied
 * before this mod's late mixins therefore run first. A cancelling check that another mod adds at the head of
 * a method still runs, and still decides, before GA2's callback replaces the rest of the body.
 */
public final class MergedMethods {
    private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private MergedMethods() {}

    /** How a hook changes the methods it is recorded for. */
    public enum Mode {
        /**
         * GA2's callback takes over the method, or the hook skips the method entirely. Other mods' code placed before
         * GA2's callback still runs. An overwrite, or anything placed after the callback or in a callee, would be skipped.
         */
        REPLACE,
        /**
         * The fast path does not run the method at all. It does the method's work in place. Any change to the method, or
         * to a method it calls, counts, wherever the change sits.
         */
        BYPASS,
        /**
         * GA2 wraps particular calls inside the method, and the rest of the vanilla body runs as usual. Callbacks
         * ({@code @Inject}, {@code @ModifyReturnValue}) keep working. An overwrite, or another mod's redirect, wrapper or
         * modifier inside the method, may touch the wrapped calls and counts.
         */
        WRAP
    }

    /**
     * @param methods name + descriptor of the methods to inspect, e.g. {@code "foo(J)I"}, or a bare name for every method of
     *                that name
     * @return names of foreign mixins whose changes to the methods (or to methods they call) the fast path would skip
     */
    public static List<String> foreignChanges(ClassNode target, List<String> methods, String ownPackage) {
        return foreignChanges(target, methods, List.of(), Mode.REPLACE, ownPackage);
    }

    /**
     * @param passThrough callees (name + descriptor) the fast path still calls through the class. A change to them is
     *                    not a change the fast path would skip. They count only when listed in {@code methods} themselves
     */
    public static List<String> foreignChanges(ClassNode target, List<String> methods, List<String> passThrough, Mode mode, String ownPackage) {
        return foreignChanges(target, methods, passThrough, mode, List.of(), ownPackage);
    }

    /**
     * @param wrapTypes in {@link Mode#WRAP}: another mod's redirect or wrapper counts only when the handler's descriptor names
     *                  one of these types (the types of the calls GA2 wraps). Empty means every redirect or wrapper counts
     */
    public static List<String> foreignChanges(ClassNode target, List<String> methods, List<String> passThrough, Mode mode, List<String> wrapTypes,
                                              String ownPackage) {
        return mixins(foreignCalls(target, methods, passThrough, mode, wrapTypes, ownPackage));
    }

    /**
     * One change by another mod's mixin that a fast path would skip. Either a call from an inspected method to a method that
     * the mixin merged into the class (an injector's handler, or a MixinExtras bridge to a handler), or the inspected method
     * itself when the mixin overwrote it. In the overwrite case, {@code at} is -1.
     *
     * @param method the merged method's name and descriptor
     * @param at     where the call sits: the call's instruction index in the inspected method. Calls inside a MixinExtras
     *               bridge count at the place where the method references the bridge. Changes in one method therefore sort
     *               in execution order
     */
    public record Change(String mixin, String inspected, String method, int at) {}

    /** The distinct mixins of {@code changes}, in order. */
    public static List<String> mixins(List<Change> changes) {
        List<String> out = new ArrayList<>();
        for (Change c : changes) {
            addOnce(out, c.mixin());
        }
        return out;
    }

    /** {@link #foreignChanges} with every call that counts. */
    public static List<Change> foreignCalls(ClassNode target, List<String> methods, List<String> passThrough, Mode mode, List<String> wrapTypes,
                                            String ownPackage) {
        return foreignCalls(target, methods, passThrough, mode, wrapTypes, ownPackage, null);
    }

    /**
     * @param boundary in {@link Mode#REPLACE}: the own handler whose call takes the method over, given by a part of the
     *                 handler's name. Alternatively, {@code "!"} followed by a part of the names of the own handlers that do
     *                 not count. A method with several of this mod's handlers has one hook per handler. Null selects the
     *                 first own call
     */
    public static List<Change> foreignCalls(ClassNode target, List<String> methods, List<String> passThrough, Mode mode, List<String> wrapTypes,
                                            String ownPackage, String boundary) {
        Map<String, String> mergedBy = new HashMap<>();
        Set<String> own = new HashSet<>();
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode m : target.methods) {
            byKey.put(m.name + m.desc, m);
            String mixin = mergingMixin(m);
            if (mixin == null) {
                continue;
            }
            if (mixin.startsWith(ownPackage)) {
                own.add(m.name + m.desc);
            } else {
                mergedBy.put(m.name + m.desc, mixin);
            }
        }
        List<Change> out = new ArrayList<>();
        if (mergedBy.isEmpty()) {
            return out;
        }
        // the lambdas that the inspected methods create are part of those methods' code. A fast path that replaces a method
        // replaces its lambdas too. The lambdas are inspected the same way, and every change in them counts as one after
        // GA2's callback
        Set<String> lambdas = new HashSet<>();
        for (MethodNode m : target.methods) {
            if (listed(methods, m)) {
                lambdasOf(target, m, byKey, lambdas);
            }
        }
        for (MethodNode m : target.methods) {
            String key = m.name + m.desc;
            boolean lambda = lambdas.contains(key);
            if (!listed(methods, m) && !lambda) {
                continue;
            }
            String inspected = Names.mojangMethodKey(m.name, m.desc);
            // overwritten (or added) by another mod
            String overwrite = mergedBy.get(key);
            if (overwrite != null) {
                out.add(new Change(overwrite, inspected, inspected, -1));
            }
            int ownCall = mode == Mode.REPLACE && !lambda ? firstCall(target, m, own, boundary) : -1;
            int index = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
                List<MethodInsnNode> calls;
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)) {
                    calls = List.of(call);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    // a MixinExtras operation: another wrapper of the same call may sit inside the operation's bridge
                    calls = new ArrayList<>();
                    bridgeCalls(target, indy, byKey, new HashSet<>(), calls);
                } else {
                    continue;
                }
                for (MethodInsnNode call : calls) {
                    String mixin = mergedBy.get(call.name + call.desc);
                    String callee = Names.mojangMethodKey(call.name, call.desc);
                    if (mixin == null || (passThrough.contains(callee) && !methods.contains(callee))) {
                        continue;
                    }
                    if (mode == Mode.REPLACE && ownCall >= 0 && index < ownCall) {
                        // another mod's callback that runs before GA2's: kept
                        continue;
                    }
                    if (mode == Mode.WRAP && (isCallback(call.name) || !wrapTypes.isEmpty() && wrapTypes.stream().noneMatch(Names.mojangDesc(call.desc)::contains))) {
                        continue;
                    }
                    out.add(new Change(mixin, inspected, callee, index));
                }
            }
        }
        return out;
    }

    /**
     * Whether {@code methods} names {@code m}, by name and descriptor or by bare name. The tables use Mojang names. The class has
     * the running game's names ({@link Names}).
     */
    private static boolean listed(List<String> methods, MethodNode m) {
        return methods.contains(Names.mojangMethodKey(m.name, m.desc)) || methods.contains(Names.mojangMethod(m.name));
    }

    /** The class's own synthetic lambdas {@code m} creates (and the ones those create), by name and descriptor. */
    private static void lambdasOf(ClassNode target, MethodNode m, Map<String, MethodNode> byKey, Set<String> out) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof InvokeDynamicInsnNode indy)) {
                continue;
            }
            for (Object arg : indy.bsmArgs) {
                if (arg instanceof Handle h && h.getOwner().equals(target.name) && h.getName().startsWith("lambda$")) {
                    String key = h.getName() + h.getDesc();
                    MethodNode lambda = byKey.get(key);
                    if (lambda != null && out.add(key)) {
                        lambdasOf(target, lambda, byKey, out);
                    }
                }
            }
        }
    }

    /** The calls to the class's own methods inside the MixinExtras bridges an invokedynamic refers to (recursively). */
    private static void bridgeCalls(ClassNode target, InvokeDynamicInsnNode indy, Map<String, MethodNode> byKey, Set<String> seen, List<MethodInsnNode> out) {
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle h) || !h.getOwner().equals(target.name) || !h.getName().contains("mixinextras$bridge")) {
                continue;
            }
            MethodNode bridge = byKey.get(h.getName() + h.getDesc());
            if (bridge == null || !seen.add(h.getName() + h.getDesc())) {
                continue;
            }
            for (AbstractInsnNode insn = bridge.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)) {
                    out.add(call);
                } else if (insn instanceof InvokeDynamicInsnNode inner) {
                    bridgeCalls(target, inner, byKey, seen, out);
                }
            }
        }
    }

    /** Index of the first instruction of {@code m} calling one of {@code own} that {@code boundary} selects, or -1. */
    private static int firstCall(ClassNode target, MethodNode m, Set<String> own, String boundary) {
        boolean exclude = boundary != null && boundary.startsWith("!");
        String part = exclude ? boundary.substring(1) : boundary;
        int index = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(target.name) && own.contains(call.name + call.desc)
                && (part == null || call.name.contains(part) != exclude)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Mixin and MixinExtras name handler methods {@code <kind>$<id>$...}. Callbacks ({@code handler} for {@code @Inject},
     * {@code modifyReturnValue}) observe or replace the result but do not change the calls in between.
     */
    private static boolean isCallback(String handlerName) {
        int dollar = handlerName.indexOf('$');
        if (dollar <= 0) {
            return false;
        }
        String kind = handlerName.substring(0, dollar);
        return kind.equals("handler") || kind.equals("modifyReturnValue");
    }

    private static void addOnce(List<String> out, String mixin) {
        if (mixin != null && !out.contains(mixin)) {
            out.add(mixin);
        }
    }

    /** The mixin that merged {@code m} into its class (its {@code @MixinMerged} annotation), or null for the class's own method. */
    public static String mergedBy(MethodNode m) {
        return mergingMixin(m);
    }

    private static String mergingMixin(MethodNode m) {
        for (List<AnnotationNode> list : List.of(m.visibleAnnotations == null ? List.<AnnotationNode>of() : m.visibleAnnotations,
            m.invisibleAnnotations == null ? List.<AnnotationNode>of() : m.invisibleAnnotations)) {
            for (AnnotationNode a : list) {
                if (MIXIN_MERGED.equals(a.desc) && a.values != null) {
                    for (int i = 0; i + 1 < a.values.size(); i += 2) {
                        if ("mixin".equals(a.values.get(i))) {
                            return String.valueOf(a.values.get(i + 1));
                        }
                    }
                }
            }
        }
        return null;
    }
}
