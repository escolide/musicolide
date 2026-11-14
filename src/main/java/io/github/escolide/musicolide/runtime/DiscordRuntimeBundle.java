package io.github.escolide.musicolide.runtime;

import io.github.escolide.musicolide.audio.NativeToolchain;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

/**
 * Extracts the optional Discord runtime from the universal plugin JAR and
 * loads it in an isolated child-first classloader. Minecraft and the rest of
 * Musicolide never need JDA or DAVE on their classpath.
 */
public final class DiscordRuntimeBundle {
    private static final String BUNDLE_RESOURCE = "META-INF/musicolide/discord-runtime.zip";
    private static final String SERVICE_CLASS =
            "io.github.escolide.musicolide.discord.DiscordBroadcastService";
    private static final int MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SINGLE_JAR_BYTES = 24L * 1024L * 1024L;
    private static final Pattern JAR_PATH = Pattern.compile("lib/[A-Za-z0-9._+-]+\\.jar");
    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-f]{64})  (lib/[A-Za-z0-9._+-]+\\.jar)");

    private DiscordRuntimeBundle() {}

    public static AutoCloseable start(JavaPlugin plugin, PlaybackEngine playback,
                                      NativeToolchain toolchain) throws Exception {
        URLClassLoader runtimeLoader = null;
        Object service = null;
        try {
            byte[] bundle = readEmbeddedBundle(plugin);
            Path runtimeDirectory = extractAndVerify(plugin, bundle);
            List<URL> urls = new ArrayList<>();
            URL pluginJar = plugin.getClass().getProtectionDomain().getCodeSource() == null
                    ? null : plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (pluginJar == null) {
                throw new IOException("Could not locate the Musicolide plugin JAR for the isolated Discord loader.");
            }
            urls.add(pluginJar);
            try (var jars = Files.list(runtimeDirectory)) {
                for (Path jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted().toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
            if (urls.size() < 5) {
                throw new IOException("The embedded Discord runtime is incomplete; expected JDA, DAVE and native libraries.");
            }

            runtimeLoader = new IsolatedDiscordClassLoader(
                    urls.toArray(URL[]::new), plugin.getClass().getClassLoader());
            Thread currentThread = Thread.currentThread();
            ClassLoader previousContextLoader = currentThread.getContextClassLoader();
            try {
                // JDA and its worker threads inherit the isolated loader as their
                // context loader, while Bukkit-facing types remain parent-loaded.
                currentThread.setContextClassLoader(runtimeLoader);
                Class<?> serviceType = Class.forName(SERVICE_CLASS, true, runtimeLoader);
                service = serviceType.getConstructor(JavaPlugin.class, PlaybackEngine.class,
                                NativeToolchain.class)
                        .newInstance(plugin, playback, toolchain);
                serviceType.getMethod("start").invoke(service);
            } finally {
                currentThread.setContextClassLoader(previousContextLoader);
            }
            return new RuntimeHandle(service, runtimeLoader);
        } catch (Throwable error) {
            if (service instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception closeError) {
                    plugin.getLogger().log(Level.FINE,
                            "Discord cleanup after initialization failure also failed.", closeError);
                }
            }
            if (runtimeLoader != null) {
                try {
                    runtimeLoader.close();
                } catch (IOException ignored) {
                }
            }
            if (error instanceof InvocationTargetException invocation && invocation.getCause() != null) {
                Throwable cause = invocation.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error fatal) throw fatal;
            }
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Discord runtime initialization failed.", error);
        }
    }

    private static byte[] readEmbeddedBundle(JavaPlugin plugin) throws IOException {
        try (InputStream input = plugin.getResource(BUNDLE_RESOURCE)) {
            if (input == null) {
                throw new IOException("This plugin JAR does not contain the optional Discord runtime bundle.");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BUNDLE_BYTES) {
                    throw new IOException("The embedded Discord runtime bundle exceeds its safe size limit.");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException("The embedded Discord runtime bundle is empty.");
            return output.toByteArray();
        }
    }

    private static Path extractAndVerify(JavaPlugin plugin, byte[] bundle) throws IOException {
        String fingerprint = sha256(bundle);
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize()
                .resolve("runtime").resolve("discord");
        Files.createDirectories(root);
        Path destination = root.resolve(fingerprint.substring(0, 20));
        if (isValidRuntime(destination)) return destination;
        deleteTree(destination);

        Path staging = root.resolve(".discord-" + UUID.randomUUID() + ".tmp");
        Files.createDirectories(staging);
        boolean installed = false;
        try {
            String manifestText = null;
            long extractedBytes = 0L;
            try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bundle))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[32 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (name.equals("SHA256SUMS")) {
                        if (manifestText != null) throw new IOException("Duplicate Discord runtime checksum manifest.");
                        ByteArrayOutputStream manifest = new ByteArrayOutputStream();
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            if (manifest.size() + count > 1024 * 1024) {
                                throw new IOException("Discord runtime checksum manifest is too large.");
                            }
                            manifest.write(buffer, 0, count);
                        }
                        manifestText = manifest.toString(StandardCharsets.UTF_8);
                        continue;
                    }
                    if (!JAR_PATH.matcher(name).matches()) {
                        throw new IOException("Unexpected path in Discord runtime bundle: " + name);
                    }
                    String fileName = name.substring("lib/".length());
                    Path target = staging.resolve(fileName).normalize();
                    if (!target.getParent().equals(staging)) {
                        throw new IOException("Unsafe Discord runtime archive path.");
                    }
                    long fileBytes = 0L;
                    try (OutputStream output = Files.newOutputStream(target,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            fileBytes += count;
                            extractedBytes += count;
                            if (fileBytes > MAX_SINGLE_JAR_BYTES || extractedBytes > MAX_EXTRACTED_BYTES) {
                                throw new IOException("Discord runtime archive exceeds its extraction size limit.");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
            if (manifestText == null) throw new IOException("Discord runtime checksum manifest is missing.");
            Files.writeString(staging.resolve("SHA256SUMS"), manifestText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Map<String, String> checksums = parseManifest(manifestText);
            verifyExtractedJars(staging, checksums);
            moveReplacing(staging, destination);
            installed = true;
            return destination;
        } finally {
            if (!installed) deleteTree(staging);
        }
    }

    private static boolean isValidRuntime(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            Path manifest = directory.resolve("SHA256SUMS");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return false;
            verifyExtractedJars(directory, parseManifest(Files.readString(manifest, StandardCharsets.UTF_8)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, String> parseManifest(String text) throws IOException {
        Map<String, String> checksums = new TreeMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            Matcher matcher = CHECKSUM_LINE.matcher(line.trim());
            if (!matcher.matches()) throw new IOException("Invalid Discord runtime checksum manifest.");
            if (checksums.putIfAbsent(matcher.group(2), matcher.group(1)) != null) {
                throw new IOException("Duplicate entry in Discord runtime checksum manifest.");
            }
        }
        if (checksums.size() < 4) {
            throw new IOException("Discord runtime checksum manifest is incomplete.");
        }
        return checksums;
    }

    private static void verifyExtractedJars(Path directory, Map<String, String> checksums) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            jars.addAll(paths.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList());
        }
        if (jars.size() != checksums.size()) {
            throw new IOException("Discord runtime JAR count does not match its checksum manifest.");
        }
        for (Path jar : jars) {
            String relative = "lib/" + jar.getFileName();
            String expected = checksums.get(relative);
            if (expected == null || !expected.equals(sha256(jar))) {
                throw new IOException("Discord runtime checksum verification failed for " + jar.getFileName());
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", impossible);
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static final class IsolatedDiscordClassLoader extends URLClassLoader {
        static {
            registerAsParallelCapable();
        }

        private IsolatedDiscordClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isParentFirst(name)) {
                        loaded = getParent().loadClass(name);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException missingFromBundle) {
                            loaded = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private static boolean isParentFirst(String name) {
            return name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("org.bukkit.") || name.startsWith("io.papermc.")
                    || name.startsWith("net.minecraft.")
                    || (name.startsWith("io.github.escolide.musicolide.")
                    && !name.startsWith("io.github.escolide.musicolide.discord."));
        }
    }

    private static final class RuntimeHandle implements AutoCloseable {
        private final Object service;
        private final URLClassLoader loader;
        private boolean closed;

        private RuntimeHandle(Object service, URLClassLoader loader) {
            this.service = service;
            this.loader = loader;
        }

        @Override
        public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                if (service instanceof AutoCloseable closeable) closeable.close();
            } finally {
                loader.close();
            }
        }
    }
}
