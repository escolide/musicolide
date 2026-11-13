package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * First-start installer for the MineStrator target (Debian/Linux x86_64).
 * Executables are downloaded to the plugin data directory, never packaged in
 * the distributable plugin JAR.
 */
final class NativeToolInstaller {
    private static final String YT_DLP_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp_linux";
    private static final String YT_DLP_SHA256 =
            "767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795";

    private static final String FFMPEG_ARCHIVE_URL =
            "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-lgpl.tar.xz";
    private static final String FFMPEG_ARCHIVE_SHA256 =
            "a954b5f03a1b9c379113a40bcc84d2f773a7453d8bd555f4b600da8b39958586";

    private static final long MAX_YT_DLP_BYTES = 64L * 1024 * 1024;
    private static final long MAX_FFMPEG_ARCHIVE_BYTES = 256L * 1024 * 1024;

    private final JavaPlugin plugin;
    private final NativeToolchain toolchain;
    private final Path dataDirectory;
    private final Path binaryDirectory;
    private final String platform;
    private final HttpClient httpClient;

    NativeToolInstaller(JavaPlugin plugin, NativeToolchain toolchain, Path dataDirectory,
                        Path binaryDirectory, String platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.binaryDirectory = Objects.requireNonNull(binaryDirectory, "binaryDirectory");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    String existing(NativeToolchain.Tool tool) {
        if (!platform.equals("linux-x86_64")) return null;
        Path executable = binaryDirectory.resolve(platform).resolve(fileName(tool));
        return Files.isRegularFile(executable) && Files.isExecutable(executable)
                ? executable.toAbsolutePath().toString() : null;
    }

    String install(NativeToolchain.Tool tool) throws IOException {
        if (!platform.equals("linux-x86_64")) return null;

        Path temporaryRoot = dataDirectory.resolve("temp");
        Path destinationDirectory = binaryDirectory.resolve(platform);
        Files.createDirectories(temporaryRoot);
        Files.createDirectories(destinationDirectory);
        Path destination = destinationDirectory.resolve(fileName(tool));

        plugin.getLogger().info("Preparing " + fileName(tool)
                + " for Linux x86_64. Downloading it once into plugins/Musicolide/native/.");

        if (tool == NativeToolchain.Tool.FFMPEG) {
            installFfmpeg(destination, temporaryRoot);
        } else if (tool == NativeToolchain.Tool.YT_DLP) {
            installYtDlp(destination, temporaryRoot);
        } else {
            return null;
        }

        if (!Files.isRegularFile(destination) || !Files.isExecutable(destination)) {
            throw new IOException("The downloaded " + fileName(tool) + " did not install as an executable.");
        }
        return destination.toAbsolutePath().toString();
    }

    private void installYtDlp(Path destination, Path temporaryRoot) throws IOException {
        Path downloaded = downloadVerified(YT_DLP_URL, YT_DLP_SHA256,
                temporaryRoot, ".yt-dlp-", ".download", MAX_YT_DLP_BYTES);
        try {
            replaceExecutable(downloaded, destination);
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    private void installFfmpeg(Path destination, Path temporaryRoot) throws IOException {
        Path work = Files.createTempDirectory(temporaryRoot, ".ffmpeg-install-");
        Path archive = null;
        try {
            archive = downloadVerified(FFMPEG_ARCHIVE_URL, FFMPEG_ARCHIVE_SHA256,
                    work, "ffmpeg-", ".tar.xz", MAX_FFMPEG_ARCHIVE_BYTES);

            Path extracted = work.resolve("extract");
            Files.createDirectories(extracted);
            Path log = work.resolve("tar-extract.log");
            Process process = null;
            try {
                // Extract only ffmpeg; the plugin uses FFmpeg itself for quick duration probing.
                process = toolchain.start(new ProcessBuilder("tar", "-xJf", archive.toString(),
                        "-C", extracted.toString(), "--wildcards", "*/bin/ffmpeg")
                        .redirectErrorStream(true).redirectOutput(log.toFile()));
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                    toolchain.terminate(process);
                    throw new IOException("FFmpeg archive extraction exceeded 10 minutes.");
                }
                int exit = process.exitValue();
                toolchain.finished(process);
                if (exit != 0) {
                    throw new IOException("Could not extract the FFmpeg executable. "
                            + readTail(log));
                }
            } catch (InterruptedException ex) {
                if (process != null) toolchain.terminate(process);
                Thread.currentThread().interrupt();
                throw new IOException("FFmpeg installation was interrupted.", ex);
            } finally {
                if (process != null) toolchain.finished(process);
            }

            Path ffmpeg;
            try (Stream<Path> paths = Files.walk(extracted)) {
                ffmpeg = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("ffmpeg"))
                        .findFirst()
                        .orElseThrow(() -> new IOException("The verified FFmpeg archive did not contain bin/ffmpeg."));
            }
            if (!ffmpeg.toAbsolutePath().normalize().startsWith(extracted.toAbsolutePath().normalize())) {
                throw new IOException("The FFmpeg archive produced an invalid executable path.");
            }
            replaceExecutable(ffmpeg, destination);
        } finally {
            deleteTree(work);
        }
    }

    private Path downloadVerified(String url, String expectedSha256, Path directory,
                                  String prefix, String suffix, long maximumBytes) throws IOException {
        Path downloaded = Files.createTempFile(directory, prefix, suffix);
        boolean keep = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .header("User-Agent", "Musicolide-native-bootstrap/1.0")
                    .GET()
                    .build();

            final HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Native tool download was interrupted.", ex);
            }

            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    throw new IOException("Native tool server returned HTTP " + response.statusCode() + ".");
                }
            }

            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maximumBytes) {
                try (InputStream ignored = response.body()) {
                    throw new IOException("Native tool download exceeded its configured size limit.");
                }
            }

            long total = 0L;
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(downloaded,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("Native tool download exceeded its configured size limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }

            if (total == 0L) throw new IOException("Native tool server returned an empty file.");
            String actualSha256 = sha256(downloaded);
            if (!MessageDigest.isEqual(expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IOException("Native tool SHA-256 verification failed; refusing to install the downloaded file.");
            }

            keep = true;
            return downloaded;
        } finally {
            if (!keep) Files.deleteIfExists(downloaded);
        }
    }

    private String sha256(Path file) throws IOException {
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

    private void replaceExecutable(Path source, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".install");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(temporary, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ex) {
                if (!temporary.toFile().setExecutable(true, false) && !Files.isExecutable(temporary)) {
                    throw new IOException("Could not mark the native executable as executable.", ex);
                }
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String readTail(Path file) {
        if (!Files.isRegularFile(file)) return "No extraction log was produced.";
        try {
            long size = Files.size(file);
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                channel.position(Math.max(0L, size - 4096));
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, 4096));
                channel.read(buffer);
                buffer.flip();
                return StandardCharsets.UTF_8.decode(buffer).toString().trim();
            }
        } catch (IOException ex) {
            return "Could not read extraction log: " + ex.getMessage();
        }
    }

    private String fileName(NativeToolchain.Tool tool) {
        return tool == NativeToolchain.Tool.YT_DLP ? "yt-dlp" : "ffmpeg";
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }
}
