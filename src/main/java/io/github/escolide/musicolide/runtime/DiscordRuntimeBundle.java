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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

/**
 * Extracts the optional Discord Java runtime from the universal plugin JAR,
 * downloads only the selected platform's DAVE native JAR, and loads everything
 * in an isolated child-first classloader. The server-wide plugin classpath
 * never needs JDA or DAVE.
 */
public final class DiscordRuntimeBundle {
    private static final String BUNDLE_RESOURCE = "META-INF/musicolide/discord-runtime.zip";
    private static final String SERVICE_CLASS =
            "io.github.escolide.musicolide.discord.DiscordBroadcastService";
    private static final String MAVEN_BASE =
            "https://repo.maven.apache.org/maven2/moe/kyokobot/libdave/";
    private static final int MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SINGLE_JAR_BYTES = 24L * 1024L * 1024L;
    private static final long MAX_NATIVE_JAR_BYTES = 48L * 1024L * 1024L;
    private static final Pattern JAR_PATH = Pattern.compile("lib/[A-Za-z0-9._+-]+\\.jar");
    private static final Pattern CHECKSUM_LINE =
            Pattern.compile("([0-9a-f]{64})  (lib/[A-Za-z0-9._+-]+\\.jar)");
    private static final Pattern NATIVE_LINE =
            Pattern.compile("([a-z0-9-]+)\\|([A-Za-z0-9._+-]+\\.jar)\\|([0-9a-f]{64})\\|(https://[^\\s]+)");
    private static final Map<String, String> EXPECTED_NATIVE_FILES = Map.of(
            "linux-x86-64", "natives-linux-x86-64-0.1.2.jar",
            "linux-aarch64", "natives-linux-aarch64-0.1.2.jar",
            "windows-x86-64", "natives-win-x86-64-0.1.2.jar",
            "macos-universal", "natives-darwin-0.1.2.jar"
    );
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private DiscordRuntimeBundle() {}

    public static AutoCloseable start(JavaPlugin plugin, PlaybackEngine playback,
                                      NativeToolchain toolchain) throws Exception {
        URLClassLoader runtimeLoader = null;
        Object service = null;
        try {
            byte[] bundle = readEmbeddedBundle(plugin);
            Path runtimeDirectory = extractAndVerify(plugin, bundle);
            NativeArtifact nativeArtifact = selectNativeArtifact(runtimeDirectory);
            Path nativeJar = downloadAndVerifyNative(runtimeDirectory, nativeArtifact);

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
            urls.add(nativeJar.toUri().toURL());
            if (urls.size() < 5) {
                throw new IOException("The embedded Discord Java runtime is incomplete.");
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
            String nativeManifestText = null;
            long extractedBytes = 0L;
            try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bundle))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[32 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (name.equals("SHA256SUMS") || name.equals("NATIVE_ARTIFACTS")) {
                        ByteArrayOutputStream manifest = new ByteArrayOutputStream();
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            if (manifest.size() + count > 1024 * 1024) {
                                throw new IOException("Discord runtime manifest is too large.");
                            }
                            manifest.write(buffer, 0, count);
                        }
                        if (name.equals("SHA256SUMS")) {
                            if (manifestText != null) throw new IOException("Duplicate Discord runtime checksum manifest.");
                            manifestText = manifest.toString(StandardCharsets.UTF_8);
                        } else {
                            if (nativeManifestText != null) throw new IOException("Duplicate Discord native manifest.");
                            nativeManifestText = manifest.toString(StandardCharsets.UTF_8);
                        }
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
            if (manifestText == null || nativeManifestText == null) {
                throw new IOException("Discord runtime manifests are missing.");
            }
            Files.writeString(staging.resolve("SHA256SUMS"), manifestText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(staging.resolve("NATIVE_ARTIFACTS"), nativeManifestText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            verifyExtractedJars(staging, parseManifest(manifestText));
            parseNativeManifest(nativeManifestText);
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
            Path nativeManifest = directory.resolve("NATIVE_ARTIFACTS");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(nativeManifest, LinkOption.NOFOLLOW_LINKS)) return false;
            verifyExtractedJars(directory, parseManifest(Files.readString(manifest, StandardCharsets.UTF_8)));
            parseNativeManifest(Files.readString(nativeManifest, StandardCharsets.UTF_8));
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

    private static Map<String, NativeArtifact> parseNativeManifest(String text) throws IOException {
        Map<String, NativeArtifact> artifacts = new TreeMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            Matcher matcher = NATIVE_LINE.matcher(line.trim());
            if (!matcher.matches()) throw new IOException("Invalid Discord native artifact manifest.");
            String platform = matcher.group(1);
            String fileName = matcher.group(2);
            String checksum = matcher.group(3);
            URI uri = URI.create(matcher.group(4));
            String expectedFile = EXPECTED_NATIVE_FILES.get(platform);
            if (expectedFile == null || !expectedFile.equals(fileName)) {
                throw new IOException("Unexpected Discord native target: " + platform);
            }
            String artifactId = fileName.substring(0, fileName.lastIndexOf("-0.1.2.jar"));
            URI expectedUri = URI.create(MAVEN_BASE + artifactId + "/0.1.2/" + fileName);
            if (!expectedUri.equals(uri)) {
                throw new IOException("Discord native artifact URL is not an approved Maven Central URL.");
            }
            NativeArtifact artifact = new NativeArtifact(platform, fileName, checksum, uri);
            if (artifacts.putIfAbsent(platform, artifact) != null) {
                throw new IOException("Duplicate Discord native platform entry.");
            }
        }
        if (!artifacts.keySet().equals(EXPECTED_NATIVE_FILES.keySet())) {
            throw new IOException("Discord native manifest does not cover the supported platforms.");
        }
        return artifacts;
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

    private static NativeArtifact selectNativeArtifact(Path runtimeDirectory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String normalizedArchitecture = switch (architecture) {
            case "amd64", "x86_64", "x64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        if (normalizedArchitecture == null) {
            throw new IOException("Discord voice is not bundled for CPU architecture '" + architecture + "'.");
        }

        String platform;
        if (os.contains("linux")) {
            if (isMuslLinux()) {
                throw new IOException("Discord voice currently requires a glibc-based Linux server; musl Linux is not bundled.");
            }
            platform = "linux-" + normalizedArchitecture;
        } else if (os.contains("windows")) {
            platform = "windows-" + normalizedArchitecture;
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos-universal";
        } else {
            throw new IOException("Discord voice is not bundled for operating system '" + os + "'.");
        }

        String manifestText = Files.readString(runtimeDirectory.resolve("NATIVE_ARTIFACTS"),
                StandardCharsets.UTF_8);
        NativeArtifact artifact = parseNativeManifest(manifestText).get(platform);
        if (artifact == null) {
            throw new IOException("Discord voice has no native runtime for platform '" + platform + "'.");
        }
        return artifact;
    }

    private static boolean isMuslLinux() {
        return Files.exists(Path.of("/lib/ld-musl-x86_64.so.1"))
                || Files.exists(Path.of("/lib/ld-musl-aarch64.so.1"))
                || Files.exists(Path.of("/lib/libc.musl-x86_64.so.1"))
                || Files.exists(Path.of("/lib/libc.musl-aarch64.so.1"));
    }

    private static Path downloadAndVerifyNative(Path runtimeDirectory, NativeArtifact artifact)
            throws IOException {
        Path nativeDirectory = runtimeDirectory.resolve("native");
        Files.createDirectories(nativeDirectory);
        Path destination = nativeDirectory.resolve(artifact.fileName());
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                && artifact.sha256().equals(sha256(destination))) {
            return destination;
        }
        Files.deleteIfExists(destination);
        Path temporary = nativeDirectory.resolve("." + artifact.fileName() + "." + UUID.randomUUID() + ".tmp");
        HttpRequest request = HttpRequest.newBuilder(artifact.uri())
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Musicolide-Discord-Runtime/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Maven Central returned HTTP " + response.statusCode()
                            + " while downloading the Discord native runtime.");
                }
                long total = 0L;
                try (OutputStream output = Files.newOutputStream(temporary,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_NATIVE_JAR_BYTES) {
                            throw new IOException("Discord native runtime archive exceeded its safe size limit.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
            String actualChecksum = sha256(temporary);
            if (!artifact.sha256().equals(actualChecksum)) {
                throw new IOException("Discord native runtime SHA-256 verification failed for "
                        + artifact.fileName() + ".");
            }
            moveReplacing(temporary, destination);
            return destination;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Discord native runtime download was interrupted.", interrupted);
        } finally {
            Files.deleteIfExists(temporary);
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
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
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

    private record NativeArtifact(String platform, String fileName, String sha256, URI uri) {}

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
