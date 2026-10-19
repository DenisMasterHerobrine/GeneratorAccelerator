package dev.denismasterherobrine.generatoraccelerator2.gpu;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Cold compile time of the generated program against its shape: unrolled noise or noise tables, inlining or noinline helpers,
 * NVIDIA's optimization level. Every configuration is built cold (a nonce defeats the driver's cache), checked for exact blocks and
 * biomes against GA2, and timed on a 16x16 region. {@code gradlew test --tests "*GpuCompileBench*" -Dga2.gpu.compile=true
 * --no-configuration-cache [-Dga2.gpu.configs=tables+noinline+O1,tables+noinline] [-Dga2.gpu.prec=F32]}
 */
@EnabledIfSystemProperty(named = "ga2.gpu.compile", matches = "true")
public class GpuCompileBench {
    private static void log(String fmt, Object... args) {
        System.out.println("[GpuCompile] " + String.format(Locale.ROOT, fmt, args));
    }

    private record Config(String name, boolean tables, boolean noinline, String options) {}

    private static final Config[] CONFIGS = {
        new Config("tables+noinline+O1", true, true, "-cl-nv-opt-level=1"),
        new Config("tables+noinline", true, true, ""),
        new Config("tables", true, false, ""),
        new Config("unrolled+noinline", false, true, ""),
        new Config("unrolled", false, false, ""),
    };

    @Test
    void compile() throws Exception {
        GpuProbe.Setup s = GpuDatapack.setup(Long.getLong("ga2.gpu.seed", 1234567L));
        ClGen.Prec prec = ClGen.Prec.valueOf(System.getProperty("ga2.gpu.prec", "F64"));
        String only = System.getProperty("ga2.gpu.configs", "");
        String nonce = Long.toString(System.nanoTime());
        try (Cl cl = Cl.open()) {
            log("device %s, %s", cl.describe(), prec);
            for (Config cfg : CONFIGS) {
                if (!only.isEmpty() && !Arrays.asList(only.split(",")).contains(cfg.name())) {
                    continue;
                }
                ClGen gen = s.gen(prec, cfg.tables(), cfg.noinline(), nonce + " " + cfg.name());
                GpuProbe.Variant v;
                try {
                    v = new GpuProbe.Variant(cl, s, "compile-" + cfg.name(), gen, "-D FULL " + cfg.options());
                } catch (RuntimeException e) {
                    log("%-20s build FAILED: %s", cfg.name(), e.getMessage().lines().limit(3).toList());
                    continue;
                }
                long[] diff;
                try (GpuProbe.Run run = new GpuProbe.Run(cl, v, -1953, 771, 2, 2, false)) {
                    diff = GpuProbe.compareChunks(s, run, run.readBlocks(null), run.readBiomes(null));
                }
                long[][] samples = new long[3][];
                try (GpuProbe.Run warm = new GpuProbe.Run(cl, v, 7000, 7000, 16, 16, false)) {
                    // warm-up
                }
                for (int i = 0; i < samples.length; i++) {
                    try (GpuProbe.Run run = new GpuProbe.Run(cl, v, 7100 + i * 20, -7000, 16, 16, false)) {
                        samples[i] = run.ns.clone();
                    }
                }
                long[] med = new long[samples[0].length];
                long total = 0;
                StringBuilder parts = new StringBuilder();
                for (int k = 0; k < med.length; k++) {
                    final int kk = k;
                    long[] col = Arrays.stream(samples).mapToLong(x -> x[kk]).sorted().toArray();
                    med[k] = col[col.length / 2];
                    total += med[k];
                    parts.append(String.format(Locale.ROOT, " %s %.1f", GpuProbe.Run.KERNELS[k], med[k] / 1e3 / 256));
                }
                log("%-20s %,7d chars, cold build %6.1f s; blocks differing %d, fluid marks differing %d, biomes differing %d (rebuilt chunks identical);"
                        + " 16x16: %.1f us/chunk on the device (%s)",
                    cfg.name(), v.sourceChars, v.buildMs / 1e3, diff[1], diff[3], diff[5] - diff[6], total / 1e3 / 256, parts.toString().trim());
                v.close();
            }
        }
    }
}
