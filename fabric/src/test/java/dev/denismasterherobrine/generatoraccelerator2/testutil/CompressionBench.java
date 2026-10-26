package dev.denismasterherobrine.generatoraccelerator2.testutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Time and size of a saved chunk's compression, per deflate level and with vanilla's LZ4, on the full chunks of a world's region
 * files ({@code -Dga2.bench.regions=<region dir>}, {@code -Dga2.bench.chunks=N}). Compression and decompression are timed apart.
 * {@code gradlew test --tests "*CompressionBench*" -Dga2.bench=compression -Dga2.bench.regions=<dir>}
 */
@EnabledIfSystemProperty(named = "ga2.bench", matches = "compression")
public class CompressionBench {
    @Test
    void levels() throws IOException {
        Path dir = Path.of(System.getProperty("ga2.bench.regions", "run/world/region"));
        int limit = Integer.getInteger("ga2.bench.chunks", 2000);
        List<byte[]> raw = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        for (Path file : files) {
            if (raw.size() >= limit) {
                break;
            }
            for (CompoundTag tag : SaveBench.readRegion(file, limit - raw.size())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
                NbtIo.write(tag, new DataOutputStream(out));
                raw.add(out.toByteArray());
            }
        }
        long rawBytes = raw.stream().mapToLong(b -> b.length).sum();
        System.out.println(String.format(Locale.ROOT, "compression bench: %s, %d full chunks, %.1f KB of NBT per chunk", dir, raw.size(),
            rawBytes / 1024.0 / raw.size()));
        int rounds = Integer.getInteger("ga2.bench.rounds", 6);
        int[] levels = { 6, 1, 2, 3, 4 };
        for (int round = 0; round < rounds; round++) {
            for (int level : levels) {
                Deflater deflater = new Deflater(level);
                Inflater inflater = new Inflater();
                byte[] out = new byte[1 << 20];
                byte[] back = new byte[1 << 22];
                List<byte[]> packed = new ArrayList<>(raw.size());
                long t0 = System.nanoTime();
                for (byte[] b : raw) {
                    deflater.reset();
                    deflater.setInput(b);
                    deflater.finish();
                    int n = 0;
                    while (!deflater.finished()) {
                        if (n == out.length) {
                            out = Arrays.copyOf(out, out.length * 2);
                        }
                        n += deflater.deflate(out, n, out.length - n);
                    }
                    packed.add(Arrays.copyOf(out, n));
                }
                long t1 = System.nanoTime();
                for (int i = 0; i < packed.size(); i++) {
                    inflater.reset();
                    inflater.setInput(packed.get(i));
                    try {
                        int n = 0;
                        while (!inflater.finished()) {
                            n += inflater.inflate(back, n, back.length - n);
                        }
                        if (n != raw.get(i).length) {
                            throw new IllegalStateException("round trip length");
                        }
                    } catch (DataFormatException e) {
                        throw new IOException(e);
                    }
                }
                long t2 = System.nanoTime();
                deflater.end();
                inflater.end();
                report(round, rounds, "deflate " + level, packed, raw, t0, t1, t2);
            }
            List<byte[]> packed = new ArrayList<>(raw.size());
            long t0 = System.nanoTime();
            for (byte[] b : raw) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(b.length / 2);
                try (OutputStream o = RegionFileVersion.VERSION_LZ4.wrap(bytes)) {
                    o.write(b);
                }
                packed.add(bytes.toByteArray());
            }
            long t1 = System.nanoTime();
            for (byte[] p : packed) {
                try (InputStream in = RegionFileVersion.VERSION_LZ4.wrap(new ByteArrayInputStream(p))) {
                    in.readAllBytes();
                }
            }
            long t2 = System.nanoTime();
            report(round, rounds, "lz4 (vanilla)", packed, raw, t0, t1, t2);
        }
    }

    private static void report(int round, int rounds, String name, List<byte[]> packed, List<byte[]> raw, long t0, long t1, long t2) {
        if (round < rounds / 2) {
            return;
        }
        long size = packed.stream().mapToLong(b -> b.length).sum();
        System.out.println(String.format(Locale.ROOT, "compression bench: %-14s compress %6.1f us/chunk, decompress %5.1f us/chunk, %6.2f KB/chunk", name,
            (t1 - t0) / 1e3 / raw.size(), (t2 - t1) / 1e3 / raw.size(), size / 1024.0 / raw.size()));
    }
}
