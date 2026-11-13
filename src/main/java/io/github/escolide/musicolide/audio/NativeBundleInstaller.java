package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the compressed native tool bundle embedded in the plugin JAR.
 *
 * The bundle is built by Gradle from src/main/native-bundles/<platform>/ and
 * contains bin/, lib/, runtime/, licenses/, plus manifest.properties. This
 * runtime installer never invokes a shell or downloads executable payloads.
 */
final class NativeBundleInstaller {
    private static final long MAX_ARCHIVE_BYTES = 30L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 2_048;

    private final JavaPlugin plugin;
    private final Path dataDirectory;
    private final Path nativeDirectory;
    private final String platform;

    NativeBundleInstaller(JavaPlugin plugin, Path dataDirectory, Path nativeDirectory, String platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.nativeDirectory = Objects.requireNonNull(nativeDirectory, "nativeDirectory");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * Returns an empty map when this JAR has no bundle for the current platform.
     * A present but invalid bundle is an error and is never partially installed.
     */
    Map<NativeToolchain.Tool, String> install() throws IOException {
        if (platform.equals("unsupported")) return Map.of();

        String resourceBase = "native-bundles/" + platform;
        Path tempDirectory = dataDirectory.resolve("temp");
        Files.createDirectories(tempDirectory);

        try (InputStream bundle = plugin.getResource(resourceBase + ".zip")) {
            if (bundle == null) return Map.of();

            String expectedDigest;
            try (InputStream digest = plugin.getResource(resourceBase + ".sha256")) {
                if (digest == null) {
                    throw new IOException("Embedded " + platform + " bundle has no SHA-256 sidecar.");
                }
                expectedDigest = readExpectedDigest(digest);
            }

            Path archive = Files.createTempFile(tempDirectory, ".musicolide-bundle-", ".zip");
            try {
                copyBounded(bundle, archive, MAX_ARCHIVE_BYTES);
                String actualDigest = sha256(archive);
                if (!MessageDigest.isEqual(
                        expectedDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IOException("Embedded " + platform + " native bundle failed SHA-256 verification.");
                }
                return extract(archive, nativeDirectory, platform);
            } finally {
                Files.deleteIfExists(archive);
            }
        }
    }

    static Map<NativeToolchain.Tool, String> extract(Path archive, Path nativeDirectory,
                                                      String expectedPlatform) throws IOException {
        Path staging = nativeDirectory.resolve("." + expectedPlatform + ".staging-" + UUID.randomUUID());
        Path destination = nativeDirectory.resolve(expectedPlatform).normalize();
        if (!destination.startsWith(nativeDirectory.normalize())) {
            throw new IOException("Invalid native bundle destination.");
        }

        long totalBytes = 0L;
        int entryCount = 0;
        try {
            Files.createDirectories(staging);
            try (InputStream fileInput = Files.newInputStream(archive);
                 ZipInputStream zip = new ZipInputStream(fileInput)) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entryCount > MAX_ENTRIES) {
                        throw new IOException("Native bundle contains too many entries.");
                    }
                    String name = entry.getName();
                    Path relative = safeRelativePath(name);
                    Path output = staging.resolve(relative).normalize();
                    if (!output.startsWith(staging.normalize())) {
                        throw new IOException("Native bundle entry escapes its extraction directory.");
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                        zip.closeEntry();
                        continue;
                    }

                    Files.createDirectories(output.getParent());
                    long entryBytes = 0L;
                    try (OutputStream target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("Native bundle exceeded its extraction size limit.");
                            }
                            target.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            }

            Path manifestFile = staging.resolve("manifest.properties");
            if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Native bundle is missing manifest.properties.");
            }
            Properties manifest = new Properties();
            try (InputStream input = Files.newInputStream(manifestFile)) {
                manifest.load(input);
            }
            if (!expectedPlatform.equals(manifest.getProperty("platform"))) {
                throw new IOException("Native bundle platform does not match this server (" + expectedPlatform + ").");
            }

            String ffmpegRelative = requiredExecutable(manifest, "ffmpeg");
            String ytDlpRelative = requiredExecutable(manifest, "yt-dlp");
            String jsRuntimeRelative = optionalExecutable(manifest, "js-runtime");

            Path stagedFfmpeg = resolveRegularFile(staging, ffmpegRelative);
            Path stagedYtDlp = resolveRegularFile(staging, ytDlpRelative);
            Path stagedJsRuntime = jsRuntimeRelative == null ? null : resolveRegularFile(staging, jsRuntimeRelative);

            Files.createDirectories(destination);
            try (Stream<Path> paths = Files.walk(staging)) {
                for (Path source : paths.sorted().toList()) {
                    if (source.equals(staging) || source.equals(manifestFile)) continue;
                    Path relative = staging.relativize(source);
                    Path target = destination.resolve(relative).normalize();
                    if (!target.startsWith(destination)) {
                        throw new IOException("Invalid extracted native bundle path.");
                    }
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        throw new IOException("Native bundle contains an unsupported filesystem entry.");
                    }
                }
            }

            Path ffmpeg = destination.resolve(staging.relativize(stagedFfmpeg)).normalize();
            Path ytDlp = destination.resolve(staging.relativize(stagedYtDlp)).normalize();
            setExecutable(ffmpeg, expectedPlatform);
            setExecutable(ytDlp, expectedPlatform);

            Map<NativeToolchain.Tool, String> result = new EnumMap<>(NativeToolchain.Tool.class);
            result.put(NativeToolchain.Tool.FFMPEG, ffmpeg.toAbsolutePath().toString());
            result.put(NativeToolchain.Tool.YT_DLP, ytDlp.toAbsolutePath().toString());
            if (stagedJsRuntime != null) {
                Path jsRuntime = destination.resolve(staging.relativize(stagedJsRuntime)).normalize();
                setExecutable(jsRuntime, expectedPlatform);
            }
            return Map.copyOf(result);
        } finally {
            deleteTree(staging);
        }
    }

    private static String requiredExecutable(Properties manifest, String key) throws IOException {
        String value = optionalExecutable(manifest, key);
        if (value == null) throw new IOException("Native bundle manifest is missing " + key + ".");
        return value;
    }

    private static String optionalExecutable(Properties manifest, String key) throws IOException {
        String value = manifest.getProperty(key);
        if (value == null || value.isBlank()) return null;
        Path relative = safeRelativePath(value);
        if (!relative.toString().replace(FileSystems.getDefault().getSeparator(), "/").startsWith("bin/")) {
            throw new IOException("Native bundle " + key + " must be inside bin/.");
        }
        return relative.toString();
    }

    private static Path resolveRegularFile(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root.normalize()) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Native bundle is missing required file: " + relative);
        }
        return path;
    }

    private static Path safeRelativePath(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.contains(":")) {
            throw new IOException("Native bundle contains an unsafe path.");
        }
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isBlank()) throw new IOException("Native bundle contains an empty path.");
        for (String part : trimmed.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IOException("Native bundle contains a path traversal entry.");
            }
        }
        Path relative = Path.of(trimmed);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new IOException("Native bundle contains an absolute or escaping path.");
        }
        return relative;
    }

    private static void setExecutable(Path executable, String platform) throws IOException {
        if (platform.startsWith("windows-")) return;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(executable);
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_READ);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_READ);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        } catch (UnsupportedOperationException ex) {
            if (!executable.toFile().setExecutable(true, false) && !Files.isExecutable(executable)) {
                throw new IOException("Could not mark bundled tool executable.", ex);
            }
        }
    }

    private static String readExpectedDigest(InputStream input) throws IOException {
        String line = new String(input.readNBytes(512), java.nio.charset.StandardCharsets.US_ASCII).trim();
        String digest = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) throw new IOException("Native bundle SHA-256 sidecar is malformed.");
        return digest;
    }

    private static void copyBounded(InputStream input, Path destination, long maximumBytes) throws IOException {
        long total = 0L;
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) throw new IOException("Embedded native bundle exceeds 30 MiB.");
                output.write(buffer, 0, read);
            }
        }
        if (total == 0L) throw new IOException("Embedded native bundle is empty.");
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", ex);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }
}
