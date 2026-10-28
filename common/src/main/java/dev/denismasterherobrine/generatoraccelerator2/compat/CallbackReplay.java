package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;

/**
 * Other mods' callbacks that GA2's fast paths call themselves, where vanilla's code calls them.
 *
 * <p>A callback is an {@code @Inject} handler at the head of a method or right before one of its returns, which takes exactly the
 * method's arguments and its {@code CallbackInfo}: no locals and no other values from the method's body. Such a handler sees what
 * the fast path sees at the same point. A fast path that does a method's work in place, or leaves the method out because it would do
 * nothing, then calls those handlers at the method's head and end ({@link #SITES}) instead of standing down. A callback that cancels
 * at the head ends the method there, as in vanilla. Every other change of the same mixin to the hook keeps the whole mixin foreign.
 *
 * <p>Read by the mixin plugin, so it must not reference Minecraft types. The handlers are looked up when a fast path first asks.
 */
public final class CallbackReplay {
    private CallbackReplay() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    public enum Point { HEAD, RETURN }

    /** A place where a fast path calls the callbacks: the hook, the method's Mojang name and the points it calls. */
    record Site(String hook, String method, Set<Point> points) {}

    /** Hook keys of the sites: the names their inspection records under. */
    public static final String POI_SKIP = HookTargets.STATE_POI_SKIP;
    public static final String TREES = "trees";

    private static final List<Site> SITES = List.of(
        // the POI skip leaves the whole method out where it would do nothing (state.PoiStates)
        new Site(POI_SKIP, "onBlockStateChange", Set.of(Point.HEAD, Point.RETURN)),
        // the tree transcription does doPlace's work in place (features.tree.TreeFast)
        new Site(TREES, "doPlace", Set.of(Point.HEAD)),
        // the GPU's biome fill does doCreateBiomes' work (gpu.GpuSteps): YUNG's Cave Biomes notes the chunk's biome source at its return
        new Site(HookTargets.GPU_BIOMES, "doCreateBiomes", Set.of(Point.RETURN)));

    /** A handler the fast path calls: the class it was merged into, its name and descriptor as the game runs them, and its point. */
    public record Callback(String mixin, String owner, String name, String desc, boolean isStatic, Point point, int at) {}

    private static final Map<String, List<Callback>> RECORDED = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle[]> HANDLES = new ConcurrentHashMap<>();

    private static String key(String hook, String method, Point point) {
        return hook + "|" + method + "|" + point;
    }

    /**
     * Takes the callbacks a fast path calls out of {@code changes}, for a hook with replay sites. The other changes stay, and so do all
     * changes of a mixin that also changes the hook in another way.
     */
    public static List<MergedMethods.Change> take(String hook, ClassNode target, List<MergedMethods.Change> changes) {
        List<Site> sites = SITES.stream().filter(s -> s.hook.equals(hook)).toList();
        if (sites.isEmpty() || changes.isEmpty()) {
            return changes;
        }
        Map<String, List<MergedMethods.Change>> byMixin = new LinkedHashMap<>();
        for (MergedMethods.Change c : changes) {
            byMixin.computeIfAbsent(c.mixin(), k -> new ArrayList<>()).add(c);
        }
        List<MergedMethods.Change> rest = new ArrayList<>();
        for (Map.Entry<String, List<MergedMethods.Change>> e : byMixin.entrySet()) {
            List<Callback> callbacks = new ArrayList<>();
            List<String> sitesOf = new ArrayList<>();
            for (MergedMethods.Change c : e.getValue()) {
                Callback cb = null;
                for (Site s : sites) {
                    cb = callback(target, c, s);
                    if (cb != null) {
                        sitesOf.add(s.method);
                        break;
                    }
                }
                if (cb == null) {
                    callbacks = null;
                    break;
                }
                callbacks.add(cb);
            }
            if (callbacks == null) {
                rest.addAll(e.getValue());
                continue;
            }
            for (int i = 0; i < callbacks.size(); i++) {
                Callback cb = callbacks.get(i);
                RECORDED.merge(key(hook, sitesOf.get(i), cb.point()), List.of(cb), (a, b) -> {
                    List<Callback> all = new ArrayList<>(a);
                    b.stream().filter(x -> !all.contains(x)).forEach(all::add);
                    all.sort(Comparator.comparingInt(Callback::at));
                    return List.copyOf(all);
                });
            }
            LOGGER.info("hook '{}' shared with {} -> kept, its callbacks run where vanilla runs them", hook, e.getKey());
        }
        return rest;
    }

    /** {@code c} as a callback of {@code site}, or null when it is not one. */
    static Callback callback(ClassNode target, MergedMethods.Change c, Site site) {
        // the change's method names are Mojang's
        if (c.at() < 0 || !c.inspected().startsWith(site.method + "(")) {
            return null;
        }
        MethodNode inspected = method(target, c.inspected());
        MethodNode handler = method(target, c.method());
        if (inspected == null || handler == null || !handler.name.startsWith("handler$")) {
            return null;
        }
        boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        if (isStatic != ((inspected.access & Opcodes.ACC_STATIC) != 0) || Type.getReturnType(handler.desc) != Type.VOID_TYPE) {
            return null;
        }
        Type[] args = Type.getArgumentTypes(inspected.desc);
        Type[] params = Type.getArgumentTypes(handler.desc);
        if (params.length != args.length + 1 || !isCallbackInfo(params[args.length].getInternalName())) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals(params[i])) {
                return null;
            }
        }
        Point point = point(inspected, c.at());
        if (point == null || !site.points.contains(point)) {
            return null;
        }
        return new Callback(c.mixin(), target.name, handler.name, handler.desc, isStatic, point, c.at());
    }

    private static MethodNode method(ClassNode target, String mojangKey) {
        for (MethodNode m : target.methods) {
            if (Names.mojangMethodKey(m.name, m.desc).equals(mojangKey)) {
                return m;
            }
        }
        return null;
    }

    private static boolean isCallbackInfo(String internalName) {
        return internalName.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
            || internalName.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable");
    }

    /**
     * Where the call at {@code index} sits: at the head when only callback code (other callbacks, their info objects and cancellation
     * checks) comes before it, before a return when only such code comes between it and a return. Null anywhere else.
     */
    private static Point point(MethodNode m, int index) {
        AbstractInsnNode call = m.instructions.get(index);
        boolean head = true;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != call; insn = insn.getNext()) {
            if (!callbackCode(insn)) {
                head = false;
                break;
            }
        }
        if (head) {
            return Point.HEAD;
        }
        for (AbstractInsnNode insn = call.getNext(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                return Point.RETURN;
            }
            if (!callbackCode(insn)) {
                return null;
            }
        }
        return null;
    }

    /** Instructions Mixin writes around a callback: its info object, the loads of the arguments, the cancellation check and return. */
    private static boolean callbackCode(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op < 0) {
            // labels, line numbers, frames
            return true;
        }
        if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD || op >= Opcodes.ISTORE && op <= Opcodes.ASTORE
            || op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5 || op == Opcodes.LDC || op == Opcodes.DUP || op == Opcodes.POP
            || op == Opcodes.ACONST_NULL || op >= Opcodes.IRETURN && op <= Opcodes.RETURN
            || op == Opcodes.IFEQ || op == Opcodes.IFNE || op == Opcodes.GOTO) {
            return true;
        }
        if (insn instanceof TypeInsnNode t) {
            return (op == Opcodes.NEW && isCallbackInfo(t.desc)) || op == Opcodes.CHECKCAST;
        }
        if (insn instanceof MethodInsnNode mi) {
            // the info object's own methods, the boxing of the return value, and other mods' callbacks
            return isCallbackInfo(mi.owner) || mi.owner.startsWith("java/lang/") && (mi.name.endsWith("Value") || mi.name.equals("valueOf"))
                || mi.name.startsWith("handler$");
        }
        return false;
    }

    /** Whether a fast path has callbacks to call at {@code hook}'s {@code method}. */
    public static boolean any(String hook, String method) {
        return RECORDED.containsKey(key(hook, method, Point.HEAD)) || RECORDED.containsKey(key(hook, method, Point.RETURN));
    }

    /**
     * The callbacks at {@code hook}'s {@code method} and {@code point}, in vanilla's order, as handles of {@code type} (the receiver
     * first unless the method is static, then the method's arguments and the info object). Empty when there are none. A handler that
     * cannot be opened makes the whole list empty and is logged; the fast path must then not be used (see {@link #complete}).
     */
    public static MethodHandle[] handles(String hook, String method, Point point, MethodType type) {
        String k = key(hook, method, point) + "|" + type.toMethodDescriptorString();
        MethodHandle[] h = HANDLES.get(k);
        if (h == null) {
            h = resolve(RECORDED.getOrDefault(key(hook, method, point), List.of()), type);
            HANDLES.put(k, h);
        }
        return h;
    }

    /** True when every callback of {@code hook}'s {@code method} could be opened as {@code head} and {@code ret} types. */
    public static boolean complete(String hook, String method, MethodType head, MethodType ret) {
        return handles(hook, method, Point.HEAD, head).length == RECORDED.getOrDefault(key(hook, method, Point.HEAD), List.of()).size()
            && (ret == null || handles(hook, method, Point.RETURN, ret).length == RECORDED.getOrDefault(key(hook, method, Point.RETURN), List.of()).size());
    }

    private static MethodHandle[] resolve(List<Callback> callbacks, MethodType type) {
        List<MethodHandle> out = new ArrayList<>();
        for (Callback cb : callbacks) {
            try {
                Class<?> owner = Class.forName(cb.owner().replace('/', '.'), false, CallbackReplay.class.getClassLoader());
                MethodType desc = MethodType.fromMethodDescriptorString(cb.desc(), owner.getClassLoader());
                Method m = owner.getDeclaredMethod(cb.name(), desc.parameterArray());
                m.setAccessible(true);
                out.add(MethodHandles.lookup().unreflect(m).asType(type));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("callback {} of {} -> cannot be called, fast path off there ({})", cb.name(), cb.mixin(), e.toString());
                return new MethodHandle[0];
            }
        }
        return out.toArray(new MethodHandle[0]);
    }
}
