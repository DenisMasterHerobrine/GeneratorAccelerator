package dev.denismasterherobrine.generatoraccelerator2.bench.optional.biome.flatclimateindex;

import dev.denismasterherobrine.generatoraccelerator2.bench.optional.biome.BiomeBenchmark;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.*;

/**
 * Measures the cost of building a flat tree from a vanilla climate tree.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx2G"})
@State(Scope.Thread)
public class BiomeTreeBuildBenchmark {
    /**
     * Vanilla biome preset: OVERWORLD or NETHER.
     */
    @Param({"OVERWORLD", "NETHER"})
    public String preset;
    /**
     * Fabric-loaded tree builder.
     */
    private Supplier<?> workload;

    /**
     * Prepares the vanilla tree before timing starts.
     *
     * @throws ReflectiveOperationException if Fabric cannot load or create the workload
     */
    @Setup(Level.Trial)
    public void setup() throws ReflectiveOperationException {
        workload = (Supplier<?>) BiomeBenchmark.gameLoader().loadClass(
                        "dev.denismasterherobrine.generatoraccelerator2.biome.BiomeWorkload")
                .getConstructor(String.class, String.class, String.class).newInstance(preset, "columns", "build");
    }

    /**
     * Builds a flat tree, including its arrays and temporary storage.
     *
     * @return the new tree, so JMH keeps the allocation from being optimized away
     */
    @Benchmark
    public Object build() {
        return workload.get();
    }
}
