package dev.denismasterherobrine.generatoraccelerator2.bench.biome.flatclimateindex;

import dev.denismasterherobrine.generatoraccelerator2.bench.optional.biome.BiomeBenchmark;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.openjdk.jmh.annotations.*;

/**
 * Measures FlatClimateTree operations on sampled world data and synthetic stress cases.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Timeout(time = 30)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx2G"})
@State(Scope.Thread)
public class FlatClimateTreeBenchmark {
    /**
     * Input set: sampled world data or synthetic stress cases.
     */
    @Param({"realistic", "absurd"})
    public String scenario;
    /**
     * Operation to measure, with a separate record for each scenario.
     */
    @Param({"search", "columns", "outsideFill", "walkColumns", "fittest", "fittestColumns",
            "walkFittestColumns", "reusedFittest", "alone", "aloneByValue", "secondAlone",
            "distance", "build", "coldCache", "adaptiveCache", "lifecycle"})
    public String system;
    /**
     * Workload loaded through Fabric so game mixins are applied.
     */
    private LongSupplier workload;

    /**
     * Prepares and checks the inputs before timing starts.
     *
     * @throws ReflectiveOperationException if Fabric cannot load or create the workload
     */
    @Setup(Level.Trial)
    public void setup() throws ReflectiveOperationException {
        workload = (LongSupplier) BiomeBenchmark.gameLoader().loadClass(
                        "dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateWorkload")
                .getConstructor(String.class, String.class).newInstance(scenario, system);
    }

    /**
     * Runs 64 operations; JMH reports time and allocations per operation.
     *
     * @return checksum of the results, so the JVM cannot discard the work
     */
    @Benchmark
    @OperationsPerInvocation(64)
    public long subsystem() {
        return workload.getAsLong();
    }
}
