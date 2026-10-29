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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What runs around GA2's piece index in {@code Beardifier.compute}, read from the finished class. The index is a head
 * callback that returns vanilla's sum. The method no longer reaches whatever it does after vanilla's loops, so GA2 has to
 * do that work itself, in the same order:
 * <ul>
 * <li>a coremod's step on the sum before the method returns. For example, Twilight Forest adds the density of its
 *     structures ({@code WorldgenHooks.getCustomDensity}). GA2 knows these steps one by one.</li>
 * <li>other mods' cancellable callbacks at the return ({@code (FunctionContext, CallbackInfoReturnable)} handlers of YUNG's
 *     API and Integrated API). Each callback gets the value the method would return, and the first callback to cancel
 *     decides the value. GA2 calls the merged handlers the same way.</li>
 * </ul>
 * MixinExtras' {@code @WrapMethod} handlers (Moog's Structures) wrap the whole method, GA2's callback included, and keep
 * working. The body they wrap moves to a {@code compute$mixinextras$wrapped$N} copy, and the steps are read from that copy.
 * Anything else after GA2's callback is {@link Recorded#unknown}, and the index stands down. Examples are a redirect or
 * wrapper inside the loops, a modified variable, or a coremod GA2 does not know.
 *
 * <p>Loaded by the mixin plugin, so it must not reference Minecraft types.
 */
public final class BeardifierChain {
    private BeardifierChain() {}

    public enum Kind { CALLBACK, COREMOD }

    /**
     * One step after vanilla's sum.
     *
     * @param owner CALLBACK: the mixin that merged the handler. COREMOD: the class the coremod calls (internal name)
     * @param name  CALLBACK: the merged handler's name. COREMOD: the called method's name and descriptor
     */
    public record Step(Kind kind, String owner, String name) {}

    /**
     * @param outer   mixins whose {@code @WrapMethod} handlers wrap the whole method (outermost first)
     * @param steps   the steps after vanilla's sum, in the order the method runs them
     * @param unknown changes GA2 cannot run itself (mixin or coremod names)
     * @param before  mixins whose code in the method runs before GA2's callback. The index keeps that code. A culled cell skips it
     * @param body    the method holding vanilla's body and GA2's callback: {@code compute}, or the copy that {@code @WrapMethod} made
     */
    public record Recorded(List<String> outer, List<Step> steps, List<String> unknown, List<String> before, String body) {
        public boolean understood() {
            return unknown.isEmpty();
        }
    }

    private static final String BEARDIFIER = "net/minecraft/world/level/levelgen/Beardifier";
    private static final String COMPUTE = "compute";
    private static final String COMPUTE_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";
    public static final String CALLBACK_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;"
        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
    /** Coremod steps GA2 runs itself, by called class and method. */
    public static final Set<String> KNOWN_COREMOD_STEPS = Set.of(
        "twilightforest/asmhooks/WorldgenHooks.getCustomDensity(DLnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;"
            + "Lit/unimi/dsi/fastutil/objects/ObjectListIterator;)D");
    /** Owners of calls made by the method's own code and by Mixin's callback plumbing. These calls are not another mod's step. */
    private static final String[] OWN_OWNERS = { "net/minecraft/", "java/", "it/unimi/dsi/", "com/mojang/", "org/spongepowered/", "net/neoforged/",
        "com/google/", "org/apache/", "com/llamalad7/mixinextras/", "net/fabricmc/" };

    private static volatile Recorded recorded;

    /** {@code Beardifier.compute}'s name in the running game ({@link Names}). It holds the body when no {@code @WrapMethod} moved it. */
    public static String computeName() {
        return Names.runtimeMethod(BEARDIFIER, COMPUTE, COMPUTE_DESC);
    }

    /** The last inspection's result, or null before the class was inspected. */
    public static Recorded recorded() {
        return recorded;
    }

    public static Recorded inspect(ClassNode beardifier, String ownPackage) {
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode m : beardifier.methods) {
            byKey.put(m.name + m.desc, m);
        }
        List<String> outer = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> before = new ArrayList<>();
        MethodNode body = byKey.get(computeName() + Names.runtimeDesc(COMPUTE_DESC));
        Set<String> seen = new HashSet<>();
        // follow @WrapMethod handlers to the wrapped copy of the body
        while (body != null && seen.add(body.name + body.desc)) {
            MethodNode wrapped = null;
            boolean wraps = false;
            for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(beardifier.name) && call.name.startsWith("wrapMethod$")) {
                    MethodNode handler = byKey.get(call.name + call.desc);
                    String mixin = handler == null ? null : MergedMethods.mergedBy(handler);
                    if (mixin != null && !mixin.startsWith(ownPackage)) {
                        outer.add(mixin);
                    }
                    wraps = true;
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    MethodNode copy = wrappedCopy(beardifier, indy, byKey);
                    if (copy != null) {
                        wrapped = copy;
                    }
                }
            }
            if (!wraps) {
                break;
            }
            if (wrapped == null) {
                unknown.add("(the method wrapped around compute could not be followed)");
                body = null;
                break;
            }
            body = wrapped;
        }
        if (body == null) {
            if (unknown.isEmpty()) {
                unknown.add("(compute not found)");
            }
        } else {
            int own = -1;
            int index = 0;
            for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(beardifier.name)) {
                    MethodNode callee = byKey.get(call.name + call.desc);
                    String mixin = callee == null ? null : MergedMethods.mergedBy(callee);
                    if (mixin != null && mixin.startsWith(ownPackage) && call.name.endsWith("$ga2$compute")) {
                        own = index;
                        break;
                    }
                }
            }
            if (own < 0) {
                unknown.add("(GA2's callback is not in compute)");
            } else {
                index = 0;
                for (AbstractInsnNode insn = body.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
                    if (index < own) {
                        // other mods' code before GA2's callback runs as before. The index keeps that code. A culled cell does not
                        if (insn instanceof MethodInsnNode call && call.owner.equals(beardifier.name)) {
                            MethodNode callee = byKey.get(call.name + call.desc);
                            String mixin = callee == null ? null : MergedMethods.mergedBy(callee);
                            if (mixin != null && !mixin.startsWith(ownPackage) && !before.contains(mixin)) {
                                before.add(mixin);
                            }
                        }
                        continue;
                    }
                    if (index == own) {
                        continue;
                    }
                    if (insn instanceof MethodInsnNode call) {
                        if (call.owner.equals(beardifier.name)) {
                            MethodNode callee = byKey.get(call.name + call.desc);
                            String mixin = callee == null ? null : MergedMethods.mergedBy(callee);
                            if (mixin == null || mixin.startsWith(ownPackage)) {
                                continue; // vanilla's own method, or GA2's
                            }
                            if (call.name.startsWith("handler$") && Names.mojangDesc(call.desc).equals(CALLBACK_DESC)) {
                                // a return callback is merged at each of the method's returns. One return runs, so it runs once.
                                Step step = new Step(Kind.CALLBACK, mixin, call.name);
                                if (!steps.contains(step)) {
                                    steps.add(step);
                                }
                            } else {
                                unknown.add(mixin);
                            }
                        } else if (!own(call.owner)) {
                            String key = call.owner + "." + call.name + Names.mojangDesc(call.desc);
                            if (KNOWN_COREMOD_STEPS.contains(key)) {
                                steps.add(new Step(Kind.COREMOD, call.owner, call.name + call.desc));
                            } else {
                                unknown.add("coremod " + call.owner);
                            }
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy && touchesMerged(beardifier, indy, byKey, ownPackage)) {
                        unknown.add("(a MixinExtras operation inside compute)");
                    }
                }
            }
        }
        Recorded r = new Recorded(List.copyOf(outer), List.copyOf(steps), List.copyOf(unknown), List.copyOf(before), body == null ? computeName() : body.name);
        recorded = r;
        return r;
    }

    private static boolean own(String owner) {
        for (String p : OWN_OWNERS) {
            if (owner.startsWith(p)) {
                return true;
            }
        }
        return owner.startsWith("dev/denismasterherobrine/generatoraccelerator2/");
    }

    /** The {@code compute$mixinextras$wrapped$N} copy an invokedynamic's bridge calls, or null. */
    private static MethodNode wrappedCopy(ClassNode owner, InvokeDynamicInsnNode indy, Map<String, MethodNode> byKey) {
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle h) || !h.getOwner().equals(owner.name)) {
                continue;
            }
            MethodNode bridge = byKey.get(h.getName() + h.getDesc());
            if (bridge == null) {
                continue;
            }
            if (bridge.name.startsWith(computeName() + "$mixinextras$wrapped$")) {
                return bridge;
            }
            for (AbstractInsnNode insn = bridge.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.startsWith(computeName() + "$mixinextras$wrapped$")) {
                    return byKey.get(call.name + call.desc);
                }
            }
        }
        return null;
    }

    /** True when an invokedynamic refers to a method another mod merged (a MixinExtras operation of that mod). */
    private static boolean touchesMerged(ClassNode owner, InvokeDynamicInsnNode indy, Map<String, MethodNode> byKey, String ownPackage) {
        for (Object arg : indy.bsmArgs) {
            if (arg instanceof Handle h && h.getOwner().equals(owner.name)) {
                MethodNode m = byKey.get(h.getName() + h.getDesc());
                String mixin = m == null ? null : MergedMethods.mergedBy(m);
                if (mixin != null && !mixin.startsWith(ownPackage) || h.getName().contains("mixinextras$bridge")) {
                    return true;
                }
            }
        }
        return false;
    }
}
