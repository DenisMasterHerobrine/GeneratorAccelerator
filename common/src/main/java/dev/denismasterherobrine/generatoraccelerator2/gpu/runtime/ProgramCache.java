package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Built programs kept on disk ({@code <game dir>/.ga2/gpu-cache/}), keyed by the device, its driver, the build options and the
 * source. A second start with the same world and driver loads the device binary instead of compiling. NVIDIA's own cache does
 * this too. AMD's and Intel's drivers mostly do not. A binary the driver rejects is built again from source and replaced.
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
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
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
