package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ExternSamplers;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;

/**
 * Whitelist of pure static methods a lifted density function may call. Generated code invokes the
 * very same methods, so results are identical to the original bytecode by construction. The
 * interpreter calls them reflectively.
 */
public final class StaticCalls {
    private StaticCalls() {}

    public static final String MATH = "java/lang/Math";
    public static final String MTH = "net/minecraft/util/Mth";

    private static final Set<String> ALLOWED = Set.of(
        MATH + ".abs(D)D", MATH + ".max(DD)D", MATH + ".min(DD)D", MATH + ".sqrt(D)D", MATH + ".cbrt(D)D",
        MATH + ".floor(D)D", MATH + ".ceil(D)D", MATH + ".rint(D)D", MATH + ".pow(DD)D", MATH + ".exp(D)D",
        MATH + ".log(D)D", MATH + ".log10(D)D", MATH + ".sin(D)D", MATH + ".cos(D)D", MATH + ".tan(D)D",
        MATH + ".atan(D)D", MATH + ".atan2(DD)D", MATH + ".hypot(DD)D", MATH + ".signum(D)D", MATH + ".fma(DDD)D",
        MATH + ".abs(I)I", MATH + ".max(II)I", MATH + ".min(II)I", MATH + ".floorDiv(II)I", MATH + ".floorMod(II)I",
        MTH + ".clamp(DDD)D", MTH + ".clampedLerp(DDD)D", MTH + ".lerp(DDD)D", MTH + ".inverseLerp(DDD)D",
        MTH + ".clampedMap(DDDDD)D", MTH + ".map(DDDDD)D", MTH + ".square(D)D", MTH + ".smoothstep(D)D",
        MTH + ".smoothstepDerivative(D)D", MTH + ".absMax(DD)D", MTH + ".lengthSquared(DD)D", MTH + ".lengthSquared(DDD)D",
        MTH + ".length(DD)D", MTH + ".length(DDD)D", MTH + ".wrapDegrees(D)D", MTH + ".fastInvSqrt(D)D", MTH + ".abs(I)I",
        MTH + ".floor(D)I", MTH + ".ceil(D)I", MTH + ".clamp(III)I", MTH + ".floorDiv(II)I", MTH + ".positiveModulo(II)I",
        MTH + ".square(I)I", MTH + ".lerpInt(FII)I");

    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    public static boolean allowed(String owner, String name, String desc) {
        // The whitelist uses Mojang names. A lifted call has the running game's names ({@link Names}).
        return ALLOWED.contains(Names.mojangClass(owner) + "." + Names.mojangMethod(name) + Names.mojangDesc(desc))
            // other mods' samplers registered by compat lowerings (never in a mod's own bytecode)
            || owner.equals(ExternSamplers.OWNER)
                && name.equals(ExternSamplers.NAME)
                && desc.equals(ExternSamplers.DESC);
    }

    public static Object invoke(String owner, String name, String desc, Object[] args) {
        try {
            MethodHandle mh = HANDLES.computeIfAbsent(owner + "." + name + desc, k -> resolve(owner, name, desc));
            return mh.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException("static call failed: " + owner + "." + name + desc, t);
        }
    }

    private static MethodHandle resolve(String owner, String name, String desc) {
        try {
            Class<?> cls = Class.forName(owner.replace('/', '.'), false, StaticCalls.class.getClassLoader());
            MethodType type = MethodType.fromMethodDescriptorString(desc, StaticCalls.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(cls, name, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot resolve " + owner + "." + name + desc, e);
        }
    }

    /** Argument sorts of a descriptor: 'D' or 'I' per argument (other sorts are not whitelisted). */
    public static char[] argSorts(String desc) {
        Type[] t = Type.getArgumentTypes(desc);
        char[] out = new char[t.length];
        for (int i = 0; i < t.length; i++) {
            out[i] = t[i].getSort() == Type.DOUBLE ? 'D' : t[i].getSort() == Type.INT ? 'I' : t[i].getSort() == Type.FLOAT ? 'F' : '?';
        }
        return out;
    }

    public static char returnSort(String desc) {
        Type r = Type.getReturnType(desc);
        return r.getSort() == Type.DOUBLE ? 'D' : r.getSort() == Type.INT ? 'I' : '?';
    }
}
