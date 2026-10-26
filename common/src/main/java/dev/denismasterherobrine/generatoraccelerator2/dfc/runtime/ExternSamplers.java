package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Other mods' noise samplers that compiled density code calls by index. A lowering in {@code compat/<modid>} registers
 * the sampler that a density function samples (a pure function of three coordinates). The lowering then turns the
 * function into a static call of {@link #sample} with that index and the function's coordinate expressions. So the
 * expressions around the sampler compile with the rest of the program, and only the sampler itself stays the mod's code.
 */
public final class ExternSamplers {
    private ExternSamplers() {}

    public static final String OWNER = "dev/denismasterherobrine/generatoraccelerator2/dfc/runtime/ExternSamplers";
    public static final String NAME = "sample";
    public static final String DESC = "(IDDD)D";

    /** A pure function of three coordinates (the same inputs give the same value, on any thread). */
    @FunctionalInterface
    public interface Sampler {
        double sample(double x, double y, double z);
    }

    /**
     * The samplers by index, held weakly. A compile keeps the samplers its programs call ({@link #own}), in the RandomState's
     * {@link ProgramSet}. A slot therefore lives while a program that calls it can run. A key is a world's object (a density
     * function or a datapack config) and leads to that world's registries. Strong slots kept every world ever opened.
     */
    @SuppressWarnings("unchecked")
    private static volatile WeakReference<Sampler>[] samplers = new WeakReference[0];
    @SuppressWarnings("unchecked")
    private static volatile WeakReference<Object>[] keys = new WeakReference[0];
    /** Samplers registered outside a compile (tests that build IR directly). Nothing owns them, so they are held here. */
    private static final List<Sampler> UNOWNED = new ArrayList<>();
    /** The samplers of the compile running on this thread, or null. */
    private static final ThreadLocal<List<Sampler>> COMPILING = new ThreadLocal<>();

    /**
     * Runs a compile and returns the samplers it registered. Its programs call them by index, so the caller keeps the list for as
     * long as the programs can run.
     */
    public static <T> T own(Supplier<T> compile, List<Sampler> owned) {
        List<Sampler> outer = COMPILING.get();
        COMPILING.set(owned);
        try {
            return compile.get();
        } finally {
            COMPILING.set(outer);
        }
    }

    /**
     * The index of the sampler registered for {@code key} (by identity), registering {@code sampler} if there is none. A slot whose
     * sampler was collected is used again. No program that could call it is left.
     */
    @SuppressWarnings("unchecked")
    public static synchronized int register(Object key, Sampler sampler) {
        WeakReference<Object>[] k = keys;
        WeakReference<Sampler>[] s = samplers;
        int free = -1;
        for (int i = 0; i < k.length; i++) {
            Sampler live = s[i].get();
            if (k[i].get() == key) {
                if (live == null) {
                    // the key outlived the programs that used this slot
                    live = sampler;
                    s[i] = new WeakReference<>(sampler);
                }
                keep(live);
                return i;
            }
            if (live == null && free < 0) {
                free = i;
            }
        }
        int index = free;
        if (index < 0) {
            index = k.length;
            k = Arrays.copyOf(k, index + 1);
            s = Arrays.copyOf(s, index + 1);
        } else {
            GPU.remove(index);
        }
        k[index] = new WeakReference<>(key);
        s[index] = new WeakReference<>(sampler);
        samplers = s;
        keys = k;
        keep(sampler);
        return index;
    }

    private static void keep(Sampler sampler) {
        List<Sampler> owned = COMPILING.get();
        if (owned != null) {
            owned.add(sampler);
        } else if (!UNOWNED.contains(sampler)) {
            UNOWNED.add(sampler);
        }
    }

    public static double sample(int index, double x, double y, double z) {
        return samplers[index].get().sample(x, y, z);
    }

    /**
     * How the GPU computes a sampler bit for bit (GA2's GPU generator, {@code gpu.codegen.ClEmitter}). It names an OpenCL function
     * the program carries ({@code kind}), and holds the function's int and float parameters and a float table shared with other
     * samplers (by identity). Registered only after the description was checked against the sampler itself.
     */
    public record GpuForm(int kind, int[] ints, float[] floats, float[] table) {
        /** FastNoiseLite's cellular noise in 3D ({@code fnl_cellular}). */
        public static final int FNL_CELLULAR = 1;
        /** Lithostitched's {@code cellular} density function without a condition ({@code litho_cellular}). */
        public static final int LITHO_CELLULAR = 2;
    }

    private static final Map<Integer, GpuForm> GPU = new ConcurrentHashMap<>();
    private static final Set<String> GPU_SOURCES = new LinkedHashSet<>();

    /** Adds OpenCL functions GPU forms call (a compat registers its own when its mod is present). */
    public static synchronized void addGpuSource(String source) {
        GPU_SOURCES.add(source);
    }

    /** Every registered OpenCL function, for the GPU program's prelude. */
    public static synchronized String gpuSource() {
        return String.join("", GPU_SOURCES);
    }

    /** Sets (or, with null, clears) the GPU form of sampler {@code index}. */
    public static void registerGpu(int index, GpuForm form) {
        if (form == null) {
            GPU.remove(index);
        } else {
            GPU.put(index, form);
        }
    }

    /** The GPU's form of sampler {@code index}, or null when only the CPU computes it. */
    public static GpuForm gpu(int index) {
        return GPU.get(index);
    }
}
