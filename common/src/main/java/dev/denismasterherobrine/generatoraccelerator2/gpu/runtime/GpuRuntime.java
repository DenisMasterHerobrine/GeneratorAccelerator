package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/** The runtime's lifecycle operations for {@code gpu.Gpu}. The objects' constructors and state changes stay package-private. */
public final class GpuRuntime {
    private GpuRuntime() {}

    public static GpuDevice open(ClLoader.Loaded loaded, ClDeviceInfo info) {
        return new GpuDevice(loaded.api(), info, loaded.description());
    }

    public static GpuWorldgen worldgen(GpuDevice device, String name, NoiseGeneratorSettings settings, RandomState randomState) {
        return new GpuWorldgen(device, name, settings, randomState);
    }

    public static void prepare(GpuWorldgen wg, BiomeSource source) {
        wg.reason = "building the program";
        wg.prepare(source);
        wg.reason = "self-test";
    }

    /** Marks {@code wg} ready, unless it failed meanwhile: its self-test failed, or its world closed while the program built. */
    public static void ready(GpuWorldgen wg) {
        if (wg.state == GpuWorldgen.State.FAILED || wg.closed) {
            return;
        }
        wg.reason = null;
        wg.state = GpuWorldgen.State.READY;
    }

    /** Marks the world of {@code wg} closed, from any thread. The close itself runs on the GPU thread ({@link #close}). */
    public static void markClosed(GpuWorldgen wg) {
        wg.closed = true;
    }

    public static void fail(GpuWorldgen wg, String why) {
        wg.fail(why);
    }

    /** On the GPU thread. Releases what a failed program build made. The pump of a world without a program never does. */
    public static void release(GpuWorldgen wg) {
        wg.closed = true;
        wg.close();
    }

    /** On the GPU thread. */
    public static void close(GpuWorldgen wg) {
        wg.closed = true;
        wg.fail("the world closed");
        wg.close();
    }
}
