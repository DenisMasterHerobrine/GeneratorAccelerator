package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkCopy;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuSteps;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuVerify;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.NoiseChunkSamplerInvoker;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Climate;
import java.util.Locale;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Checks run before the GPU is used. The first checks that the device computes Java's float and double expressions bit for bit,
 * for the patterns the generated code relies on. The second checks that the whole GPU path gives a dimension's chunks exactly what
 * GA2's CPU path gives them.
 */
public final class SelfTest {
    private SelfTest() {}

    private static final String ARITHMETIC = """
        #pragma OPENCL EXTENSION cl_khr_fp64 : enable
        #pragma OPENCL FP_CONTRACT OFF
        float lerpf(float t, float a, float b) { return a + t * (b - a); }
        double lerpd(double t, double a, double b) { return a + t * (b - a); }
        __kernel void k(__global const float* f, __global const double* d, __global float* of, __global double* od, int n) {
          int i = get_global_id(0); if (i >= n) return;
          float t = f[4 * i], a = f[4 * i + 1], b = f[4 * i + 2], e = f[4 * i + 3];
          of[4 * i] = lerpf(t, a, b);
          of[4 * i + 1] = lerpf(t, a, b) + t * (1.0f - t) * lerpf(t, e, b);
          of[4 * i + 2] = (float) ((double) a / (double) b);
          of[4 * i + 3] = (float) d[4 * i];
          double x = d[4 * i], y = d[4 * i + 1], z = d[4 * i + 2], w = d[4 * i + 3];
          od[4 * i] = lerpd(x, y, z);
          od[4 * i + 1] = x * y + z;
          od[4 * i + 2] = x * y * z * (x * (x * 6.0 - 15.0) + 10.0);
          od[4 * i + 3] = y / w;
        }
        """;

    /** Empty when every pattern matches Java on {@code device}. Otherwise, lists the patterns that differ. */
    public static String arithmetic(GpuDevice device, String options) {
        ClApi cl = device.cl;
        int n = 1 << 16;
        SplittableRandom rnd = new SplittableRandom(42);
        float[] f = new float[4 * n];
        double[] d = new double[4 * n];
        for (int i = 0; i < f.length; i++) {
            f[i] = (float) (rnd.nextDouble() * 2.0 - 0.5);
            d[i] = rnd.nextDouble() * 3.0 - 1.0;
        }
        long program = cl.buildProgram(device.context, device.info.device(), ARITHMETIC, options, null);
        long k = cl.createKernel(program, "k");
        long mf = cl.createBuffer(device.context, ClApi.MEM_READ_ONLY, GpuWorldgen.floats(f));
        long md = cl.createBuffer(device.context, ClApi.MEM_READ_ONLY, GpuWorldgen.doubles(d));
        long of = cl.createBuffer(device.context, ClApi.MEM_WRITE_ONLY, 4L * f.length);
        long od = cl.createBuffer(device.context, ClApi.MEM_WRITE_ONLY, 8L * d.length);
        try {
            cl.argMem(k, 0, mf);
            cl.argMem(k, 1, md);
            cl.argMem(k, 2, of);
            cl.argMem(k, 3, od);
            cl.argInt(k, 4, n);
            cl.releaseEvent(cl.launch(device.queue, k, n, 64));
            ByteBuffer rf = GpuWorldgen.direct(4 * f.length);
            ByteBuffer rd = GpuWorldgen.direct(8 * d.length);
            cl.read(device.queue, of, true, 0, rf);
            cl.read(device.queue, od, true, 0, rd);
            FloatBuffer gf = rf.asFloatBuffer();
            DoubleBuffer gd = rd.asDoubleBuffer();
            long[] bad = new long[8];
            for (int i = 0; i < n; i++) {
                float t = f[4 * i], a = f[4 * i + 1], b = f[4 * i + 2], e = f[4 * i + 3];
                float[] jf = { a + t * (b - a), (a + t * (b - a)) + t * (1.0F - t) * (e + t * (b - e)), (float) ((double) a / (double) b), (float) d[4 * i] };
                double x = d[4 * i], y = d[4 * i + 1], z = d[4 * i + 2], w = d[4 * i + 3];
                double[] jd = { y + x * (z - y), x * y + z, x * y * z * (x * (x * 6.0 - 15.0) + 10.0), y / w };
                for (int p = 0; p < 4; p++) {
                    if (Float.floatToRawIntBits(jf[p]) != Float.floatToRawIntBits(gf.get(4 * i + p))) {
                        bad[p]++;
                    }
                    if (Double.doubleToRawLongBits(jd[p]) != Double.doubleToRawLongBits(gd.get(4 * i + p))) {
                        bad[4 + p]++;
                    }
                }
            }
            String[] names = { "float lerp", "float spline tail", "float divide via double", "double to float", "double lerp", "double x*y+z",
                "double smoothstep", "double divide" };
            StringBuilder sb = new StringBuilder();
            for (int p = 0; p < 8; p++) {
                if (bad[p] > 0) {
                    sb.append(String.format(Locale.ROOT, "%s: %d of %d differ; ", names[p], bad[p], n));
                }
            }
            return sb.toString();
        } finally {
            cl.releaseMem(mf);
            cl.releaseMem(md);
            cl.releaseMem(of);
            cl.releaseMem(od);
            cl.releaseKernel(k);
            cl.releaseProgram(program);
        }
    }

    /** Chunks the dimension test generates both ways: near the origin, across a region border, far out. */
    private static final int[][] CHUNKS = { { 0, 0 }, { 7, 8 }, { -1953, 771 }, { 117187, -117187 } };
    public static final int CHUNK_COUNT = CHUNKS.length;

    /**
     * Runs each test chunk through the GPU path and through GA2's CPU path, without structures. The GPU path does biomes (when the GPU
     * decides them), then blocks, written as the steps write them. Returns null when the results are identical, else the first
     * difference.
     */
    public static String chunks(GpuWorldgen wg, NoiseBasedChunkGenerator generator, Holder<NoiseGeneratorSettings> settings, Supplier<Aquifer.FluidPicker> picker,
                                RandomState randomState, LevelHeightAccessor heights, Registry<Biome> biomes) throws Exception {
        // The level's own structure manager. Other mods' changes to the biome step and the fill read it (Confluence reads its registries).
        // The chunks' NoiseChunk is created here first, so vanilla's code asks the manager for no structures.
        StructureManager structures = heights instanceof ServerLevel level ? level.structureManager() : null;
        Beardifier none = Beardifier.EMPTY;
        int compared = 0;
        for (int[] c : CHUNKS) {
            ChunkPos pos = new ChunkPos(c[0], c[1]);
            ProtoChunk gpu = new ProtoChunk(pos, UpgradeData.EMPTY, heights, ChunkCopy.factory(biomes), null);
            GpuWorldgen.BiomeResult b = null;
            Object[] start = null;
            if (wg.decidesBiomes()) {
                b = wg.biomes(pos).get(60, TimeUnit.SECONDS);
                if (b == null) {
                    return "no GPU biomes for chunk " + pos;
                }
                Supplier<Climate.Sampler> tieSampler = () -> ((NoiseChunkSamplerInvoker) gpu.getOrCreateNoiseChunk(
                    ch -> NoiseChunk.forChunk(ch, randomState, none, settings.value(), picker.get(), Blender.empty())))
                    .ga2$cachedClimateSampler(randomState.router(), settings.value().spawnTarget());
                if (!Apply.biomes(wg, gpu, b, tieSampler)) {
                    return "GPU biomes not applicable to chunk " + pos;
                }
            } else {
                // No structure manager here. The NoiseChunk the CPU's biome step asks for already exists, without structures.
                gpu.getOrCreateNoiseChunk(ch -> NoiseChunk.forChunk(ch, randomState, none, settings.value(), picker.get(), Blender.empty()));
                GpuWorldgen.BiomeResult handed = wg.handsBiomes() ? wg.biomes(pos).get(60, TimeUnit.SECONDS) : null;
                if (handed != null && handed.hand() != null) {
                    // the CPU's own lookups with the GPU's points and searches, from the same last search results as the CPU's fill below
                    start = GpuVerify.handState(wg);
                    BiomeHand.fill(wg, gpu, handed, () -> GpuSteps.cpuOnly(() -> generator.createBiomes(randomState, Blender.empty(), structures, gpu))).join();
                } else {
                    GpuSteps.cpuOnly(() -> generator.createBiomes(randomState, Blender.empty(), structures, gpu)).join();
                }
            }
            // no structures, but the chunk's Better Caves liquid regions, which the CPU's aquifer applies too
            int[] liquids = GpuSteps.liquids(wg, randomState, pos);
            if (liquids == GpuSteps.LIQUID_WITHOUT_ID) {
                continue;
            }
            GpuWorldgen.Beard beard = liquids == null ? GpuWorldgen.Beard.NONE : new GpuWorldgen.Beard(new int[0], new int[0], liquids);
            GpuWorldgen.NoiseResult n = wg.noise(pos, beard).get(60, TimeUnit.SECONDS);
            if (n == null) {
                continue; // Handed to the CPU, as in game: a recheck list overflow, or aquifer cells whose order decides a value.
            }
            if (!Apply.noise(wg, gpu, n)) {
                return "GPU blocks not applicable to chunk " + pos;
            }
            ProtoChunk cpu = new ProtoChunk(pos, UpgradeData.EMPTY, heights, ChunkCopy.factory(biomes), null);
            if (start != null) {
                GpuVerify.restoreHandState(wg, start);
            }
            cpu.getOrCreateNoiseChunk(ch -> NoiseChunk.forChunk(ch, randomState, none, settings.value(), picker.get(), Blender.empty()));
            GpuSteps.cpuOnly(() -> generator.createBiomes(randomState, Blender.empty(), structures, cpu)).join();
            GpuSteps.cpuOnly(() -> generator.fillFromNoise(Blender.empty(), randomState, structures, cpu)).join();
            String diff = ChunkCompare.blocks(cpu, gpu);
            if (diff == null && b != null) {
                diff = ChunkCompare.biomes(cpu, gpu, b::tie);
            } else if (diff == null && start != null) {
                // the handed lookups started from the same last search results as the CPU's, so even ties must agree
                diff = ChunkCompare.biomes(cpu, gpu, i -> false);
            }
            if (diff != null) {
                return "chunk " + pos + ": " + diff + " (CPU vs GPU)";
            }
            compared++;
        }
        return compared > 0 ? null : "every test chunk was handed to the CPU";
    }
}
