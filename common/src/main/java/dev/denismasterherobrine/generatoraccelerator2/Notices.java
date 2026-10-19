package dev.denismasterherobrine.generatoraccelerator2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import org.slf4j.Logger;

/**
 * Notices shown to the player once, through the loader's own screen. NeoForge shows them on its loading warnings screen. On
 * Fabric they open over the title screen.
 */
public final class Notices {
    private Notices() {}

    private static final Logger LOGGER = Ga2Log.get("Notices");
    private static final String FILE = "seen-notices.txt";
    private static final List<String> PENDING = new ArrayList<>();

    /** Queues {@code text} unless it was shown before. */
    public static synchronized void post(String text) {
        String hash = hash(text);
        if (!PENDING.contains(text) && !seen().contains(hash)) {
            PENDING.add(text);
        }
    }

    /** Takes the queued notices for the loader to show and remembers them as shown. */
    public static synchronized List<String> take() {
        List<String> notices = List.copyOf(PENDING);
        PENDING.clear();
        if (!notices.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            for (String text : notices) {
                lines.append(hash(text)).append('\n');
            }
            try {
                Path file = file();
                Files.createDirectories(file.getParent());
                Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignored) {}
        }
        return notices;
    }

    private static List<String> seen() {
        try {
            Path file = file();
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static Path file() {
        return Platform.configDir().resolve(ConfigFiles.FOLDER).resolve(FILE);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
