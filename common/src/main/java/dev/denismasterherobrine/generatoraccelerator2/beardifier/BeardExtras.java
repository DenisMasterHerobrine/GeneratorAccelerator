package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.BeardifierChain;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The steps other mods add to {@code Beardifier.compute} after vanilla's sum ({@link BeardifierChain}). GA2's piece index
 * runs them in the method's order. First comes a known coremod's step on the sum (Twilight Forest's structure densities).
 * Then come the merged return callbacks. Each callback is handed the value, and the first one to cancel decides it.
 * GA2 knows three enhanced beardifiers: YUNG's API, Integrated API and Moog's Structures (the same code under three
 * names). Each adds its own pieces' density and, as a callback, always cancels. With no pieces in the chunk, such a step
 * adds nothing. The callback then returns the value it was given, so GA2 returns that value without the call.
 * Villages &amp; Pillages wraps the method for its witch villages' terrain the same way. With no such pieces near the
 * chunk, the Villages &amp; Pillages wrapper returns the method's value. A chunk with no extra pieces at all computes
 * vanilla's sum. "No extra pieces" means none of theirs, no Twilight Forest structure density, and no callback GA2 does
 * not know. Cells of such a chunk away from every vanilla piece may skip the beardifier ({@link #cullable}).
 */
public final class BeardExtras {
    private BeardExtras() {}

    /**
     * An enhanced beardifier: its mixin and the interface methods of its two lists. A null or exhausted list means no
     * pieces. When {@code data} is null, {@code pieces} instead names the provider's own no-argument method on the
     * beardifier that reports it has no pieces.
     */
    private record Provider(String mixin, String data, String pieces, String junctions) {}

    private static final List<Provider> PROVIDERS = List.of(
        new Provider("com.yungnickyoung.minecraft.yungsapi.mixin.BeardifierMixin",
            "com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier.EnhancedBeardifierData",
            "yungsapi_getEnhancedPieceIterator", "yungsapi_getEnhancedJunctionIterator"),
        new Provider("com.craisinlord.integrated_api.mixins.structures.BeardifierMixin",
            "com.craisinlord.integrated_api.world.terrainadaptation.beardifier.EnhancedBeardifierData",
            "getEnhancedRigidIterator", "getEnhancedJunctionIterator"),
        new Provider("com.finndog.moogs_structures.mixins.terrainadaptation.BeardifierMixin",
            "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.EnhancedBeardifierData",
            "moogs_structures_getEnhancedPieceIterator", "moogs_structures_getEnhancedJunctionIterator"),
        // a @WrapMethod: the method's value plus its witch villages' density, or the value alone when it has no such pieces
        new Provider("com.faboslav.villagesandpillages.common.mixin.BeardifierMixin", null,
            "villagesandpillages$hasNoTerrainAdaptationData", null));

    /** A known provider's resolved lists. For a provider's own "no pieces" method, {@code pieces} holds it and {@code junctions} is null. */
    private record Lists(MethodHandle pieces, MethodHandle junctions) {
        boolean none(Beardifier b) throws Throwable {
            if (junctions == null) {
                return (boolean) pieces.invoke(b);
            }
            return empty(pieces.invoke(b)) && empty(junctions.invoke(b));
        }
    }

    private record Callback(MethodHandle handle, @Nullable Lists known) {}

    /** The chain, resolved once. */
    private static final class Chain {
        final MethodHandle tfField;
        final MethodHandle tfDensity;
        final Callback[] callbacks;
        /** Known outer wrappers' lists. Null when an outer wrapper is unknown (cells are then never skipped). */
        final Lists[] outer;

        Chain(MethodHandle tfField, MethodHandle tfDensity, Callback[] callbacks, Lists[] outer) {
            this.tfField = tfField;
            this.tfDensity = tfDensity;
            this.callbacks = callbacks;
            this.outer = outer;
        }
    }

    private static volatile Chain chain;
    private static volatile boolean resolved;

    /** True when the steps after vanilla's sum are known and every one of them can be run (handles found). */
    public static boolean ready() {
        return chain() != null;
    }

    /** True when other mods add steps after vanilla's sum or wrap the method (so a beardifier's extras must be looked at). */
    public static boolean present() {
        BeardifierChain.Recorded r = BeardifierChain.recorded();
        return r != null && (!r.steps().isEmpty() || !r.outer().isEmpty());
    }

    @Nullable
    private static Chain chain() {
        if (!resolved) {
            synchronized (BeardExtras.class) {
                if (!resolved) {
                    chain = resolve();
                    resolved = true;
                }
            }
        }
        return chain;
    }

    @Nullable
    private static Chain resolve() {
        BeardifierChain.Recorded r = BeardifierChain.recorded();
        if (r == null || !r.understood()) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle tfField = null;
            MethodHandle tfDensity = null;
            List<Callback> callbacks = new ArrayList<>();
            for (BeardifierChain.Step s : r.steps()) {
                if (s.kind() == BeardifierChain.Kind.COREMOD) {
                    if (tfDensity != null || !s.name().startsWith("getCustomDensity(")) {
                        return null; // only Twilight Forest's step, once, is known
                    }
                    Field f = Beardifier.class.getDeclaredField("twilightforest_customStructureDensities");
                    f.setAccessible(true);
                    tfField = lookup.unreflectGetter(f);
                    Class<?> hooks = Class.forName(s.owner().replace('/', '.'), true, BeardExtras.class.getClassLoader());
                    tfDensity = MethodHandles.publicLookup().findStatic(hooks, "getCustomDensity",
                        MethodType.methodType(double.class, double.class, DensityFunction.FunctionContext.class, it.unimi.dsi.fastutil.objects.ObjectListIterator.class));
                } else {
                    Method m = Beardifier.class.getDeclaredMethod(s.name(), DensityFunction.FunctionContext.class, CallbackInfoReturnable.class);
                    m.setAccessible(true);
                    callbacks.add(new Callback(lookup.unreflect(m), lists(s.owner())));
                }
            }
            Lists[] outer = new Lists[r.outer().size()];
            for (int i = 0; i < outer.length; i++) {
                outer[i] = lists(r.outer().get(i));
                if (outer[i] == null) {
                    outer = null;
                    break;
                }
            }
            return new Chain(tfField, tfDensity, callbacks.toArray(new Callback[0]), outer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Ga2Log.get("Beardifier").warn(
                "foreign compute steps not runnable by the piece index ({}) -> off", e.toString());
            return null;
        }
    }

    @Nullable
    private static Lists lists(String mixin) throws ReflectiveOperationException {
        for (Provider p : PROVIDERS) {
            if (p.mixin().equals(mixin) && p.data() == null) {
                // the mixin's own method, merged into the beardifier. It is private, so it is found by name. Mixin may prefix the name.
                for (Method m : dev.denismasterherobrine.generatoraccelerator2.compat.Reflect.declaredMethods(Beardifier.class)) {
                    if (m.getName().endsWith(p.pieces()) && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                        m.setAccessible(true);
                        return new Lists(MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(boolean.class, Beardifier.class)), null);
                    }
                }
                return null;
            }
            if (p.mixin().equals(mixin)) {
                Class<?> data = Class.forName(p.data(), false, BeardExtras.class.getClassLoader());
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle pieces = lookup.unreflect(data.getMethod(p.pieces()));
                MethodHandle junctions = lookup.unreflect(data.getMethod(p.junctions()));
                return new Lists(pieces.asType(MethodType.methodType(Object.class, Beardifier.class)),
                    junctions.asType(MethodType.methodType(Object.class, Beardifier.class)));
            }
        }
        return null;
    }

    private static boolean empty(Object iterator) {
        return iterator == null || iterator instanceof Iterator<?> it && !it.hasNext();
    }

    /**
     * Per-beardifier plan: which callbacks the index must call, and whether Twilight Forest's step must be called. That
     * holds whenever Twilight Forest's density list is present, even when it is empty. The
     * skipped callbacks are known providers without pieces. Such a provider ends the chain with the value it is given.
     * The plan is built on first use, once the mods have filled their lists ({@code forStructuresInChunk} and the
     * NoiseChunk's creation).
     */
    public static final class Plan {
        final Chain chain;
        /** Per callback: true to call it, false when it ends the chain with the value (a known provider without pieces). */
        final boolean[] call;
        final boolean tf;
        /** Every extra source is empty: the method computes vanilla's sum. */
        public final boolean vanilla;
        /** Cells away from every vanilla piece may skip the beardifier. */
        public final boolean cullable;

        private Plan(Chain chain, boolean[] call, boolean tf, boolean vanilla, boolean cullable) {
            this.chain = chain;
            this.call = call;
            this.tf = tf;
            this.vanilla = vanilla;
            this.cullable = cullable;
        }

        /** {@code sum} (vanilla's part) after the extra steps, as the method returns it. */
        public double apply(Beardifier beardifier, DensityFunction.FunctionContext context, double sum) {
            try {
                double v = sum;
                if (tf) {
                    v = (double) chain.tfDensity.invoke(v, context, (it.unimi.dsi.fastutil.objects.ObjectListIterator<?>) chain.tfField.invoke(beardifier));
                }
                Callback[] callbacks = chain.callbacks;
                for (int i = 0; i < callbacks.length; i++) {
                    if (!call[i]) {
                        return v;
                    }
                    CallbackInfoReturnable<Double> cir = new CallbackInfoReturnable<>("compute", true, v);
                    callbacks[i].handle().invoke(beardifier, context, cir);
                    if (cir.isCancelled()) {
                        return cir.getReturnValueD();
                    }
                }
                return v;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    /** The plan for {@code beardifier}, or null when the index cannot serve it (the chain is not known or cannot run). */
    @Nullable
    public static Plan plan(Beardifier beardifier) {
        Chain c = chain();
        if (c == null) {
            return null;
        }
        try {
            boolean tf = false;
            boolean tfHasDensities = false;
            if (c.tfDensity != null) {
                Object list = c.tfField.invoke(beardifier);
                tf = list != null; // getCustomDensity returns its input unchanged for a null list
                tfHasDensities = !empty(list);
            }
            boolean[] call = new boolean[c.callbacks.length];
            boolean extras = tfHasDensities;
            boolean ended = false;
            for (int i = 0; i < call.length; i++) {
                Lists known = c.callbacks[i].known();
                boolean none = known != null && known.none(beardifier);
                call[i] = !none;
                if (!ended) {
                    if (none) {
                        ended = true; // this callback returns the value it is given. Nothing after it runs.
                    } else {
                        extras = true;
                    }
                }
            }
            boolean outerNone = c.outer != null;
            if (c.outer != null) {
                for (Lists l : c.outer) {
                    if (!l.none(beardifier)) {
                        outerNone = false;
                    }
                }
            }
            boolean vanilla = !extras;
            return new Plan(c, call, tf, vanilla, vanilla && outerNone);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static volatile MethodHandle inner;

    /**
     * The value returned by the method body that holds GA2's callback, as vanilla runs it. That body is {@code compute}
     * itself, or the copy of it a {@code @WrapMethod} made. The wrappers around the copy add their part after GA2's value,
     * so a check of that value calls the copy. For {@code -Dgeneratoraccelerator2.verify.beardifier}.
     */
    public static double body(Beardifier beardifier, DensityFunction.FunctionContext context) {
        BeardifierChain.Recorded r = BeardifierChain.recorded();
        if (r == null || r.body().equals(BeardifierChain.computeName())) {
            return beardifier.compute(context);
        }
        try {
            MethodHandle h = inner;
            if (h == null) {
                Method m = Beardifier.class.getDeclaredMethod(r.body(), DensityFunction.FunctionContext.class);
                m.setAccessible(true);
                h = MethodHandles.lookup().unreflect(m);
                inner = h;
            }
            return (double) h.invoke(beardifier, context);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** True when cells away from every vanilla piece may skip this beardifier although other mods change its density. */
    public static boolean cullable(Beardifier beardifier) {
        if (!present()) {
            return false;
        }
        Plan p = plan(beardifier);
        return p != null && p.cullable;
    }
}
