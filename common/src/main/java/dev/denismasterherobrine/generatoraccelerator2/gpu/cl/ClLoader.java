package dev.denismasterherobrine.generatoraccelerator2.gpu.cl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads the OpenCL binding ({@code gpu.cl.lwjgl.LwjglCl} over LWJGL's {@code lwjgl-opencl}) without putting it on GA2's class
 * path. The binding cannot live there. It needs the LWJGL core whose native library the game already loaded. A second core in
 * another class loader may fail to load the same library file. Dedicated servers have no LWJGL natives.
 *
 * <p>The jar carries the binding, an LWJGL core and the core's natives under {@code META-INF/ga2-gpu/} ({@code gpuBundle} in
 * build.gradle). They are copied out once to {@code <game dir>/.ga2/gpu-lib/} and read by a dedicated class loader. That loader
 * loads {@code org.lwjgl.opencl} itself. It loads every other {@code org.lwjgl} class from the game's core when one exists, else
 * from the bundled core and natives. It loads GA2's classes from GA2's loader. It defines the implementation class itself from
 * GA2's jar.
 */
public final class ClLoader {
    private ClLoader() {}

    /** The bundled LWJGL, as {@code lwjgl_version} in gradle.properties (the game's own LWJGL). */
    public static final String LWJGL_VERSION = "3.4.1";
    private static final String IMPL = "dev.denismasterherobrine.generatoraccelerator2.gpu.cl.lwjgl.LwjglCl";
    private static final String IMPL_PACKAGE = "dev.denismasterherobrine.generatoraccelerator2.gpu.cl.lwjgl.";
    private static final String GA2_PACKAGE = "dev.denismasterherobrine.generatoraccelerator2.";
    private static final String BUNDLE = "META-INF/ga2-gpu/";

    /** The loaded binding and how it was loaded. */
    public record Loaded(ClApi api, String description) {}

    /** Loads the binding. Throws with a reason a player can act on (no LWJGL core, no natives for this platform, ...). */
    public static Loaded load(Path libDir) throws Exception {
        ClassLoader ga2 = ClLoader.class.getClassLoader();
        ClassLoader core = gameCore(ga2);
        List<URL> urls = new ArrayList<>();
        urls.add(extract(libDir, "lwjgl-opencl-" + LWJGL_VERSION + ".jar").toUri().toURL());
        String how;
        if (core != null && !Boolean.getBoolean("generatoraccelerator2.gpu.bundledLwjgl")) {
            how = "the game's LWJGL " + coreVersion(core);
        } else {
            core = null;
            String natives = nativesClassifier();
            if (natives == null) {
                throw new IllegalStateException("no LWJGL natives bundled for " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            }
            urls.add(extract(libDir, "lwjgl-" + LWJGL_VERSION + ".jar").toUri().toURL());
            urls.add(extract(libDir, "lwjgl-" + LWJGL_VERSION + "-" + natives + ".jar").toUri().toURL());
            how = "the bundled LWJGL " + LWJGL_VERSION + " (" + natives + ")";
        }
        BindingLoader loader = new BindingLoader(urls.toArray(URL[]::new), ga2, core);
        Class<?> impl = loader.loadClass(IMPL);
        ClApi api = (ClApi) impl.getConstructor().newInstance();
        return new Loaded(api, "OpenCL through " + how);
    }

    /**
     * The class loader of the game's LWJGL core, or null on a dedicated server. A 26.1 server carries the core jar without its
     * natives and never loads the core's library. The bundled core and natives serve it instead.
     */
    private static ClassLoader gameCore(ClassLoader ga2) {
        try {
            Class.forName("net.minecraft.client.main.Main", false, ga2);
            return Class.forName("org.lwjgl.system.MemoryUtil", false, ga2).getClassLoader();
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static String coreVersion(ClassLoader core) {
        try {
            return (String) Class.forName("org.lwjgl.Version", true, core).getMethod("getVersion").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return "(unknown version)";
        }
    }

    /** LWJGL's natives jar classifier for this platform, or null. */
    static String nativesClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win")) {
            return arm64 ? "natives-windows-arm64" : x64 ? "natives-windows" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? "natives-macos-arm64" : x64 ? "natives-macos" : null;
        }
        if (os.contains("linux")) {
            return x64 ? "natives-linux" : null; // Mojang's library server, which build.gradle resolves LWJGL from, has no linux-arm64 natives
        }
        return null;
    }

    /**
     * The command that starts {@code gpu.cl.lwjgl.BuildWorker} in a process of its own, without the worker's arguments. Its class path
     * holds the worker class, the bundled LWJGL core and lwjgl-opencl. The core's native library is copied out once beforehand.
     * Processes started together then do not extract it at the same time.
     */
    public static List<String> workerCommand(Path libDir) throws IOException {
        String natives = nativesClassifier();
        if (natives == null) {
            throw new IOException("no LWJGL natives bundled for " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        }
        Path opencl = extract(libDir, "lwjgl-opencl-" + LWJGL_VERSION + ".jar");
        Path core = extract(libDir, "lwjgl-" + LWJGL_VERSION + ".jar");
        Path nativeDir = libDir.resolve(natives);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(extract(libDir, "lwjgl-" + LWJGL_VERSION + "-" + natives + ".jar")))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (!e.isDirectory() && (n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) {
                    writeOnce(nativeDir, n.substring(n.lastIndexOf('/') + 1), zip.readAllBytes());
                }
            }
        }
        String worker = IMPL_PACKAGE + "BuildWorker";
        String path = worker.replace('.', '/') + ".class";
        Path classes = libDir.resolve("worker");
        try (InputStream in = ClLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("GA2's jar has no " + path);
            }
            Path file = classes.resolve(path);
            writeOnce(file.getParent(), file.getFileName().toString(), in.readAllBytes());
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path jvm = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        String cp = String.join(File.pathSeparator, classes.toString(), core.toString(), opencl.toString());
        return List.of(jvm.toString(), "-XX:+IgnoreUnrecognizedVMOptions", "-Xmx64m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Djava.awt.headless=true",
            "-Dorg.lwjgl.librarypath=" + nativeDir, "-cp", cp, worker);
    }

    /** Copies a bundled jar out of GA2's jar, once per content. The file is replaced when its size or contents differ. */
    private static Path extract(Path dir, String name) throws IOException {
        byte[] bytes;
        try (InputStream in = ClLoader.class.getClassLoader().getResourceAsStream(BUNDLE + name)) {
            if (in == null) {
                throw new IOException("GA2's jar has no " + BUNDLE + name);
            }
            bytes = in.readAllBytes();
        }
        return writeOnce(dir, name, bytes);
    }

    /** Writes {@code bytes} to {@code dir/name} unless the file already holds them. */
    private static Path writeOnce(Path dir, String name, byte[] bytes) throws IOException {
        Path out = dir.resolve(name);
        if (Files.isRegularFile(out) && Files.size(out) == bytes.length && Arrays.equals(Files.readAllBytes(out), bytes)) {
            return out;
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, name, ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // another game instance holds the file open (Windows). That instance's copy has the same content.
            Files.deleteIfExists(tmp);
            if (!Files.isRegularFile(out)) {
                throw e;
            }
        }
        return out;
    }

    /** The binding's class loader (see the class comment). */
    private static final class BindingLoader extends URLClassLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        private final ClassLoader ga2;
        private final ClassLoader core;

        BindingLoader(URL[] urls, ClassLoader ga2, ClassLoader core) {
            super("ga2-gpu-binding", urls, ClassLoader.getPlatformClassLoader());
            this.ga2 = ga2;
            this.core = core;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.startsWith(IMPL_PACKAGE)) {
                        c = defineFromGa2(name);
                    } else if (name.startsWith("org.lwjgl.opencl.")) {
                        c = findClass(name);
                    } else if (name.startsWith("org.lwjgl.")) {
                        c = core != null ? core.loadClass(name) : findClass(name);
                    } else if (name.startsWith(GA2_PACKAGE)) {
                        c = ga2.loadClass(name);
                    } else {
                        try {
                            c = getParent().loadClass(name);
                        } catch (ClassNotFoundException e) {
                            c = ga2.loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private Class<?> defineFromGa2(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = ga2.getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name + " (no " + path + " in GA2's jar)");
                }
                byte[] b = in.readAllBytes();
                return defineClass(name, b, 0, b.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
