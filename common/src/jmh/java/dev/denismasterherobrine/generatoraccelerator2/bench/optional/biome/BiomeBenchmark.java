package dev.denismasterherobrine.generatoraccelerator2.bench.optional.biome;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import net.fabricmc.loader.impl.junit.FabricLoaderLauncherSessionListener;
import org.openjdk.jmh.annotations.*;

/**
 * Compares biome searches and cache operations with game mixins applied.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx2G"})
@State(Scope.Thread)
public class BiomeBenchmark {
    /**
     * Vanilla biome preset: OVERWORLD or NETHER.
     */
    @Param({"OVERWORLD", "NETHER"})
    public String preset;

    /**
     * Query order: successive depths in a column or unrelated climate points.
     */
    @Param({"columns", "random"})
    public String distribution;

    /**
     * Search or cache operation to measure.
     */
    @Param({"vanilla", "flat", "columns", "climateSearch", "fittest", "fittestColumns",
            "cacheHit", "cacheMiss", "cacheTie", "cacheBypass", "watch", "sampleAndSearch", "fusedLookup"})
    public String operation;

    /**
     * Fabric-loaded workload, called through a JDK interface shared by both class loaders.
     */
    private LongSupplier workload;
    /**
     * Fabric's game class loader, shared within this fork.
     */
    private static ClassLoader game;

    /**
     * Prepares and checks the query set before timing starts.
     *
     * @throws ReflectiveOperationException if Fabric cannot load or create the workload
     */
    @Setup(Level.Trial)
    public void setup() throws ReflectiveOperationException {
        workload = (LongSupplier) gameLoader().loadClass(
                        "dev.denismasterherobrine.generatoraccelerator2.biome.BiomeWorkload")
                .getConstructor(String.class, String.class, String.class).newInstance(preset, distribution, operation);
    }

    /**
     * Starts Fabric before any Minecraft classes are loaded.
     *
     * @return Fabric's class loader, with GA2 mixins and access widening enabled
     */
    public static synchronized ClassLoader gameLoader() {
        if (game == null) {
            String group = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .filter(p -> {
                        String normalized = p.replace('\\', '/');
                        return (normalized.contains("/fabric/build/") || normalized.contains("/common/build/"))
                                && !normalized.contains("/benchmark");
                    }).collect(Collectors.joining(File.pathSeparator));
            System.setProperty("fabric.classPathGroups", group);
            System.setProperty("generatoraccelerator2.gpu", "false");
            FabricLoaderLauncherSessionListener listener = new FabricLoaderLauncherSessionListener();
            listener.launcherSessionOpened(null);
            game = Thread.currentThread().getContextClassLoader();
            listener.launcherSessionClosed(null);
        }
        return game;
    }

    /**
     * Runs 256 lookups; JMH reports time and allocations per lookup.
     *
     * @return checksum of the results, so the JVM cannot discard the lookups
     */
    @Benchmark
    @OperationsPerInvocation(256)
    public long lookup() {
        return workload.getAsLong();
    }
}
