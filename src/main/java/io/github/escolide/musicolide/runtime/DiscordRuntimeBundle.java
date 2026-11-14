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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

/**
 * Downloads the optional Discord Java runtime from a build-generated manifest,
 * verifies every artifact with its pinned SHA-256 hash, and loads the runtime
 * in an isolated child-first classloader. Only the selected platform's DAVE
 * native archive is downloaded. All bootstrap work is invoked asynchronously
 * by MusicolidePlugin, never on the Bukkit main thread.
 */
public final class DiscordRuntimeBundle {
    private static final String BUNDLE_RESOURCE = "META-INF/musicolide/discord-runtime.zip";
    private static final String SERVICE_CLASS =
            "io.github.escolide.musicolide.discord.DiscordBroadcastService";
    private static final String MAVEN_BASE = "https://repo.maven.apache.org/maven2/";
    private static final Set<String> REQUIRED_RUNTIME_FILES = Set.of(
            "JDA-6.7.0.jar",
            "adapter-jda-0.1.2.jar",
            "impl-jni-0.1.2.jar"
    );
    private static final Map<String, String> EXPECTED_NATIVE_FILES = Map.of(
            "linux-x86-64", "natives-linux-x86-64-0.1.2.jar",
            "linux-aarch64", "natives-linux-aarch64-0.1.2.jar",
            "windows-x86-64", "natives-win-x86-64-0.1.2.jar",
            "macos-universal", "natives-darwin-0.1.2.jar"
    );
    private static final int MAX_BUNDLE_BYTES = 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final long MAX_SINGLE_RUNTIME_JAR_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_TOTAL_RUNTIME_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_NATIVE_JAR_BYTES = 48L * 1024L * 1024L;
    private static final Pattern RUNTIME_LINE = Pattern.compile(
            "([A-Za-z0-9._+-]+\\.jar)\\|([0-9a-f]{64})\\|(https://[^\\s]+)");
    private static final Pattern NATIVE_LINE = Pattern.compile(
            "([a-z0-9-]+)\\|([A-Za-z0-9._+-]+\\.jar)\\|([0-9a-f]{64})\\|(https://[^\\s]+)");
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
            Path runtimeDirectory = prepareRuntimeDirectory(plugin, bundle);
            List<RuntimeArtifact> runtimeArtifacts = parseRuntimeManifest(
                    Files.readString(runtimeDirectory.resolve("RUNTIME_ARTIFACTS"), StandardCharsets.UTF_8));
            Path librariesDirectory = runtimeDirectory.resolve("lib");
            List<Path> runtimeJars = downloadAndVerifyRuntime(librariesDirectory, runtimeArtifacts);

            NativeArtifact nativeArtifact = selectNativeArtifact(runtimeDirectory);
            Path nativeJar = downloadAndVerify(
                    runtimeDirectory.resolve("native"), nativeArtifact.asRuntimeArtifact(), MAX_NATIVE_JAR_BYTES);

            List<URL> urls = new ArrayList<>();
            URL pluginJar = plugin.getClass().getProtectionDomain().getCodeSource() == null
                    ? null : plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (pluginJar == null) {
                throw new IOException("Could not locate the Musicolide plugin JAR for the isolated Discord loader.");
            }
            urls.add(pluginJar);
            for (Path jar : runtimeJars) urls.add(jar.toUri().toURL());
            urls.add(nativeJar.toUri().toURL());
            if (urls.size() < 5) {
                throw new IOException("The downloaded Discord Java runtime is incomplete.");
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
                throw new IOException("This plugin JAR does not contain the optional Discord runtime manifest.");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BUNDLE_BYTES) {
                    throw new IOException("The embedded Discord runtime manifest exceeds its safe size limit.");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException("The embedded Discord runtime manifest is empty.");
            return output.toByteArray();
        }
    }

    private static Path prepareRuntimeDirectory(JavaPlugin plugin, byte[] bundle) throws IOException {
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
            String runtimeManifest = null;
            String nativeManifest = null;
            try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bundle))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[8 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        throw new IOException("Directory entries are not permitted in the Discord runtime manifest.");
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (output.size() + count > MAX_MANIFEST_BYTES) {
                            throw new IOException("Discord runtime manifest entry is too large.");
                        }
                        output.write(buffer, 0, count);
                    }
                    if (entry.getName().equals("RUNTIME_ARTIFACTS")) {
                        if (runtimeManifest != null) throw new IOException("Duplicate Discord runtime artifact manifest.");
                        runtimeManifest = output.toString(StandardCharsets.UTF_8);
                    } else if (entry.getName().equals("NATIVE_ARTIFACTS")) {
                        if (nativeManifest != null) throw new IOException("Duplicate Discord native artifact manifest.");
                        nativeManifest = output.toString(StandardCharsets.UTF_8);
                    } else {
                        throw new IOException("Unexpected entry in Discord runtime manifest: " + entry.getName());
                    }
                }
            }
            if (runtimeManifest == null || nativeManifest == null) {
                throw new IOException("Discord runtime manifests are missing.");
            }
            parseRuntimeManifest(runtimeManifest);
            parseNativeManifest(nativeManifest);
            Files.writeString(staging.resolve("RUNTIME_ARTIFACTS"), runtimeManifest, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(staging.resolve("NATIVE_ARTIFACTS"), nativeManifest, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
            Path runtimeManifest = directory.resolve("RUNTIME_ARTIFACTS");
            Path nativeManifest = directory.resolve("NATIVE_ARTIFACTS");
            if (!Files.isRegularFile(runtimeManifest, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(nativeManifest, LinkOption.NOFOLLOW_LINKS)) return false;
            parseRuntimeManifest(Files.readString(runtimeManifest, StandardCharsets.UTF_8));
            parseNativeManifest(Files.readString(nativeManifest, StandardCharsets.UTF_8));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<RuntimeArtifact> parseRuntimeManifest(String text) throws IOException {
        Map<String, RuntimeArtifact> artifacts = new TreeMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            Matcher matcher = RUNTIME_LINE.matcher(line.trim());
            if (!matcher.matches()) throw new IOException("Invalid Discord runtime artifact manifest.");
            String fileName = matcher.group(1);
            String checksum = matcher.group(2);
            URI uri = validatedMavenUri(matcher.group(3), fileName);
            RuntimeArtifact artifact = new RuntimeArtifact(fileName, checksum, uri);
            if (artifacts.putIfAbsent(fileName, artifact) != null) {
                throw new IOException("Duplicate Discord runtime artifact: " + fileName);
            }
        }
        if (!artifacts.keySet().containsAll(REQUIRED_RUNTIME_FILES)) {
            throw new IOException("Discord runtime manifest does not include the required JDA/DAVE Java libraries.");
        }
        return List.copyOf(artifacts.values());
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
            URI uri = validatedMavenUri(matcher.group(4), fileName);
            String expectedFile = EXPECTED_NATIVE_FILES.get(platform);
            if (expectedFile == null || !expectedFile.equals(fileName)) {
                throw new IOException("Unexpected Discord native target: " + platform);
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

    private static URI validatedMavenUri(String value, String fileName) throws IOException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Maven Central artifact URL.", invalid);
        }
        if (!"https".equals(uri.getScheme()) || !"repo.maven.apache.org".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().endsWith("/" + fileName)
                || !value.startsWith(MAVEN_BASE)) {
            throw new IOException("Discord artifact URL is not an approved Maven Central URL.");
        }
        return uri;
    }

    private static List<Path> downloadAndVerifyRuntime(Path libraryDirectory,
                                                        List<RuntimeArtifact> artifacts) throws IOException {
        Files.createDirectories(libraryDirectory);
        List<Path> paths = new ArrayList<>();
        long totalBytes = 0L;
        for (RuntimeArtifact artifact : artifacts) {
            Path jar = downloadAndVerify(libraryDirectory, artifact, MAX_SINGLE_RUNTIME_JAR_BYTES);
            totalBytes += Files.size(jar);
            if (totalBytes > MAX_TOTAL_RUNTIME_BYTES) {
                throw new IOException("Discord Java runtime exceeds its total download size limit.");
            }
            paths.add(jar);
        }
        return paths;
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

    private static Path downloadAndVerify(Path destinationDirectory, RuntimeArtifact artifact,
                                          long maxBytes) throws IOException {
        Files.createDirectories(destinationDirectory);
        Path destination = destinationDirectory.resolve(artifact.fileName());
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                && artifact.sha256().equals(sha256(destination))) {
            return destination;
        }
        Files.deleteIfExists(destination);
        Path temporary = destinationDirectory.resolve("." + artifact.fileName() + "." + UUID.randomUUID() + ".tmp");
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
                            + " while downloading " + artifact.fileName() + ".");
                }
                long total = 0L;
                try (OutputStream output = Files.newOutputStream(temporary,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            throw new IOException("Discord runtime artifact exceeded its safe size limit: "
                                    + artifact.fileName());
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
            String actualChecksum = sha256(temporary);
            if (!artifact.sha256().equals(actualChecksum)) {
                throw new IOException("Discord runtime SHA-256 verification failed for "
                        + artifact.fileName() + ".");
            }
            moveReplacing(temporary, destination);
            return destination;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Discord runtime download was interrupted.", interrupted);
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

    private record RuntimeArtifact(String fileName, String sha256, URI uri) {}

    private record NativeArtifact(String platform, String fileName, String sha256, URI uri) {
        private RuntimeArtifact asRuntimeArtifact() {
            return new RuntimeArtifact(fileName, sha256, uri);
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
