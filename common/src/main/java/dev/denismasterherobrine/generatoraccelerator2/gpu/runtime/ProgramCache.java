package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Built programs kept on disk ({@code <game dir>/.ga2/gpu-cache/}), keyed by the device, its driver, the build options and the
 * source. A second start with the same world and driver loads the device binary instead of compiling. NVIDIA's own cache does
 * this too. AMD's and Intel's drivers mostly do not. A binary the driver rejects is built again from source and replaced.
 *
 * <p>A program built in parts ({@link #start}) caches each part on its own. A part not in the cache builds in a process of its own
 * ({@code gpu.cl.lwjgl.BuildWorker}), which writes the binary straight into the cache.
 */
public final class ProgramCache {
    private ProgramCache() {}

    private static final long MAX_BYTES = 256L << 20;

    public static long build(GpuDevice device, String source, String options, StringBuilder log) {
        Path dir = Gpu.dataDir().resolve("gpu-cache");
        String key = key(device, source, options);
        Path file = dir.resolve(key + ".bin");
        if (!Boolean.getBoolean("generatoraccelerator2.gpu.noBinaryCache") && Files.isRegularFile(file)) {
            try {
                byte[] bin = Files.readAllBytes(file);
                long p = device.cl.buildProgramFromBinary(device.context, device.info.device(), bin, options, log);
                touch(file);
                return p;
            } catch (IOException | RuntimeException e) {
                GpuDevice.LOGGER.info("cached program {} unusable ({}) -> rebuild from source", file.getFileName(), e.getMessage());
            }
        }
        long program = device.cl.buildProgram(device.context, device.info.device(), source, options, log);
        try {
            byte[] bin = device.cl.programBinary(program);
            if (bin.length > 0) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, key, ".tmp");
                Files.write(tmp, bin);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                trim(dir);
            }
        } catch (IOException | RuntimeException e) {
            GpuDevice.LOGGER.debug("program not cached", e);
        }
        return program;
    }

    /**
     * {@code -Dgeneratoraccelerator2.gpu.buildProcesses=false} builds every part in the game's process. The driver builds one program
     * at a time per process. Parts built in processes of their own build at the same time.
     */
    private static final boolean PROCESSES = Boolean.parseBoolean(System.getProperty("generatoraccelerator2.gpu.buildProcesses", "true"));
    /** A process that has not finished after this long is stopped. Its part is then built in the game's process. */
    private static final long PROCESS_TIMEOUT_S = 900;

    /**
     * Starts building the parts of one program. Parts in the cache load from it. Parts not in the cache build in processes of their
     * own, all at the same time. A single missing part builds in the game's process, where no process start is needed.
     */
    public static Parts start(GpuDevice device, List<String> sources, String options) {
        Path dir = Gpu.dataDir().resolve("gpu-cache");
        boolean cache = !Boolean.getBoolean("generatoraccelerator2.gpu.noBinaryCache");
        Parts parts = new Parts(device, dir, sources, options, cache);
        int missing = 0;
        for (int i = 0; i < sources.size(); i++) {
            missing += cache && Files.isRegularFile(parts.files[i]) ? 0 : 1;
        }
        if (!PROCESSES || missing < 2 || Runtime.getRuntime().availableProcessors() < 3) {
            return parts;
        }
        try {
            List<String> command = ClLoader.workerCommand(Gpu.dataDir().resolve("gpu-lib"));
            Files.createDirectories(dir);
            sweep(dir);
            Path optionsFile = Files.createTempFile(dir, "options", ".txt");
            Files.writeString(optionsFile, options, StandardCharsets.UTF_8);
            parts.temps.add(optionsFile);
            String[] id = deviceId(device);
            for (int i = 0; i < sources.size(); i++) {
                if (cache && Files.isRegularFile(parts.files[i])) {
                    continue;
                }
                Path src = Files.createTempFile(dir, parts.keys[i], ".cl");
                Files.writeString(src, sources.get(i), StandardCharsets.UTF_8);
                parts.temps.add(src);
                Path out = cache ? parts.files[i] : Files.createTempFile(dir, parts.keys[i], ".bin");
                if (!cache) {
                    parts.temps.add(out);
                }
                Path log = Files.createTempFile(dir, parts.keys[i], ".log");
                parts.temps.add(log);
                parts.outs[i] = out;
                parts.logs[i] = log;
                List<String> args = new ArrayList<>(command);
                args.addAll(List.of(id[0], id[1], id[2], optionsFile.toString(), src.toString(), out.toString()));
                parts.processes[i] = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            }
        } catch (IOException | RuntimeException e) {
            GpuDevice.LOGGER.info("build processes unavailable ({}) -> parts build here", e.toString());
            parts.stopProcesses();
        }
        return parts;
    }

    /** Deletes build processes' files older than a day. A game that stopped during a build leaves them behind. */
    private static void sweep(Path dir) throws IOException {
        long old = System.currentTimeMillis() - 24L * 3600 * 1000;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if ((n.endsWith(".cl") || n.endsWith(".log") || n.endsWith(".tmp") || n.startsWith("options")) && modified(p) < old) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** The platform name, the device name and the device's index among the platform's GPUs of that name (BuildWorker's arguments). */
    private static String[] deviceId(GpuDevice device) {
        var info = device.info;
        int index = 0;
        for (var d : device.cl.devices(false)) {
            if (d.device() == info.device()) {
                break;
            }
            index += d.platformName().trim().equals(info.platformName().trim()) && d.name().trim().equals(info.name().trim()) ? 1 : 0;
        }
        return new String[] { info.platformName().trim(), info.name().trim(), Integer.toString(index) };
    }

    /** The parts of one program being built ({@link #start}). */
    public static final class Parts {
        private final GpuDevice device;
        private final Path dir;
        private final List<String> sources;
        private final String options;
        private final boolean cache;
        final String[] keys;
        final Path[] files;
        private final Path[] outs;
        private final Path[] logs;
        private final Process[] processes;
        private final List<Path> temps = new ArrayList<>();
        private final long t0 = System.nanoTime();
        /** How each part was made ("cache", "process", "here"), and when it was ready (ms after {@link #start}). */
        public final String[] how;
        public final double[] readyMs;

        Parts(GpuDevice device, Path dir, List<String> sources, String options, boolean cache) {
            this.device = device;
            this.dir = dir;
            this.sources = sources;
            this.options = options;
            this.cache = cache;
            int n = sources.size();
            keys = new String[n];
            files = new Path[n];
            outs = new Path[n];
            logs = new Path[n];
            processes = new Process[n];
            how = new String[n];
            readyMs = new double[n];
            for (int i = 0; i < n; i++) {
                keys[i] = key(device, sources.get(i), options);
                files[i] = dir.resolve(keys[i] + ".bin");
            }
        }

        public int size() {
            return sources.size();
        }

        /** Part {@code i}'s program. Waits for its process. A part whose process failed is built here. */
        public long take(int i, StringBuilder log) {
            Process p = processes[i];
            if (p != null) {
                processes[i] = null;
                String failed = waitFor(p, i);
                if (failed == null) {
                    try {
                        long program = device.cl.buildProgramFromBinary(device.context, device.info.device(), Files.readAllBytes(outs[i]), options, log);
                        done(i, "process");
                        if (cache) {
                            trimQuietly();
                        }
                        return program;
                    } catch (IOException | RuntimeException e) {
                        failed = e.getMessage();
                    }
                }
                GpuDevice.LOGGER.info("build process for part {} failed ({}) -> built here", i, failed);
            }
            boolean cached = cache && Files.isRegularFile(files[i]);
            long program = build(device, sources.get(i), options, log);
            done(i, cached ? "cache" : "here");
            return program;
        }

        /** Null when the process wrote its binary, else why not. */
        private String waitFor(Process p, int i) {
            try {
                if (!p.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "no answer after " + PROCESS_TIMEOUT_S + " s";
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            if (p.exitValue() == 0 && Files.isRegularFile(outs[i])) {
                return null;
            }
            String out;
            try {
                out = Files.readString(logs[i], StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                out = "";
            }
            return "exit " + p.exitValue() + (out.isEmpty() ? "" : ": " + out.lines().limit(3).reduce((a, b) -> a + " / " + b).orElse(""));
        }

        private void done(int i, String how) {
            this.how[i] = how;
            readyMs[i] = (System.nanoTime() - t0) / 1e6;
            if (Arrays.stream(processes).allMatch(Objects::isNull)) {
                deleteTemps();
            }
        }

        /** Stops the processes of parts not taken. */
        public void stopProcesses() {
            for (int i = 0; i < processes.length; i++) {
                if (processes[i] != null) {
                    processes[i].destroyForcibly();
                    processes[i] = null;
                }
            }
            deleteTemps();
        }

        private void deleteTemps() {
            for (Path t : temps) {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException ignored) {
                    // a temporary file the next start does not read
                }
            }
            temps.clear();
        }

        private void trimQuietly() {
            try {
                trim(dir);
            } catch (IOException ignored) {
                // the cache keeps working without it
            }
        }
    }

    private static String key(GpuDevice device, String source, String options) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String head = device.info.name() + '\n' + device.info.vendor() + '\n' + device.info.version() + '\n' + device.info.driverVersion() + '\n'
                + device.info.platformName() + '\n' + options + '\n';
            md.update(head.getBytes(StandardCharsets.UTF_8));
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(), 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // the cache keeps working without it
        }
    }

    /** Deletes the least recently used binaries beyond {@link #MAX_BYTES}. */
    private static void trim(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted(Comparator.comparingLong(ProgramCache::modified).reversed()).toList();
        }
        long total = 0;
        for (Path p : files) {
            total += Files.size(p);
            if (total > MAX_BYTES) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
