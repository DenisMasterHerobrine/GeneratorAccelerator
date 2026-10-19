package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import java.util.Arrays;

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

    private static volatile Sampler[] samplers = new Sampler[0];
    private static volatile Object[] keys = new Object[0];

    /** The index of the sampler registered for {@code key} (by identity), registering {@code sampler} if there is none. */
    public static synchronized int register(Object key, Sampler sampler) {
        Object[] k = keys;
        for (int i = 0; i < k.length; i++) {
            if (k[i] == key) {
                return i;
            }
        }
        int index = k.length;
        Object[] nk = Arrays.copyOf(k, index + 1);
        nk[index] = key;
        Sampler[] ns = Arrays.copyOf(samplers, index + 1);
        ns[index] = sampler;
        samplers = ns;
        keys = nk;
        return index;
    }

    public static double sample(int index, double x, double y, double z) {
        return samplers[index].sample(x, y, z);
    }

    /**
     * How the GPU computes a sampler bit for bit (GA2's GPU generator, {@code gpu.codegen.ClEmitter}). It names an OpenCL function
     * the program carries ({@code kind}), and holds the function's int and float parameters and a float table shared with other
     * samplers (by identity). Registered only after the description was checked against the sampler itself.
     */
    public record GpuForm(int kind, int[] ints, float[] floats, float[] table) {
        /** FastNoiseLite's cellular noise in 3D ({@code fnl_cellular}). */
        public static final int FNL_CELLULAR = 1;
    }

    private static final java.util.Map<Integer, GpuForm> GPU = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> GPU_SOURCES = new java.util.LinkedHashSet<>();

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
