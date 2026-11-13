package io.github.escolide.musicolide.audio;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads pinned, platform-matched native tools on first startup.
 *
 * Downloads are streamed to disk, SHA-256 verified, and extracted without
 * invoking a shell, tar executable, or external package manager. The active
 * installation is swapped into place only after all three tools are valid.
 */
final class NativeToolDownloader {
    private static final String YT_DLP_VERSION = "2026.10.26";
    private static final String QUICKJS_VERSION = "v0.18.0";
    private static final String FFMPEG_LINUX_WINDOWS_TAG = "autobuild-2026-11-12-15-40";
    private static final String FFMPEG_MACOS_TAG = "ffmpeg-2026.06.11";
    private static final String INSTALL_MARKER = ".downloaded-toolchain.properties";

    private static final long MAX_FFMPEG_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_YT_DLP_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_QUICKJS_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_FFMPEG_BYTES = 256L * 1024L * 1024L;

    enum ArchiveFormat { ZIP, TAR_XZ, TAR_GZ }

    private record Artifact(String fileName, URI uri, String sha256, long maxBytes) {
        private Artifact {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(uri, "uri");
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Native artifacts must use HTTPS.");
            }
            if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Native artifact SHA-256 is invalid.");
            }
        }
    }

    private record PlatformSpec(String id, Artifact ffmpeg, ArchiveFormat ffmpegFormat,
                                Artifact ytDlp, Artifact quickJs) {}

    private final JavaPlugin plugin;
    private final Path nativeRoot;
    private final String platform;

    NativeToolDownloader(JavaPlugin plugin, Path nativeRoot, String platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.nativeRoot = Objects.requireNonNull(nativeRoot, "nativeRoot").toAbsolutePath().normalize();
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    Map<NativeToolchain.Tool, String> install() throws IOException {
        PlatformSpec spec = specFor(platform);
        if (spec == null) {
            plugin.getLogger().warning("Automatic native-tool installation is not available for " + platform
                    + "; configure compatible executables in config.yml or PATH.");
            return Map.of();
        }

        Map<NativeToolchain.Tool, String> installed = existingInstall(spec);
        if (!installed.isEmpty()) {
            return installed;
        }

        Files.createDirectories(nativeRoot);
        cleanupStaleStagingDirectories();
        Path staging = nativeRoot.resolve("." + platform + ".download-" + UUID.randomUUID());
        Path work = staging.resolve("work");
        Path bin = staging.resolve("bin");
        Path licenses = staging.resolve("licenses");
        Files.createDirectories(work);
        Files.createDirectories(bin);
        Files.createDirectories(licenses);

        try {
            plugin.getLogger().info("Downloading the pinned native audio toolchain for " + platform
                    + ". This first-start setup runs in the background and may download large files.");

            Path ffmpegArchive = work.resolve(spec.ffmpeg().fileName());
            download(spec.ffmpeg(), ffmpegArchive);
            Path ffmpeg = bin.resolve(executableName("ffmpeg"));
            extractFfmpegArchive(ffmpegArchive, ffmpeg, spec.ffmpegFormat());
            Files.deleteIfExists(ffmpegArchive);
            setExecutable(ffmpeg);

            Path ytDlp = bin.resolve(executableName("yt-dlp"));
            download(spec.ytDlp(), ytDlp);
            setExecutable(ytDlp);

            Path quickJs = bin.resolve(executableName("qjs"));
            download(spec.quickJs(), quickJs);
            setExecutable(quickJs);

            writeNotices(licenses, spec);
            Properties marker = new Properties();
            marker.setProperty("toolchain", spec.id());
            marker.setProperty("platform", platform);
            marker.setProperty("yt-dlp.version", YT_DLP_VERSION);
            marker.setProperty("quickjs.version", QUICKJS_VERSION);
            marker.setProperty("ffmpeg.archive.sha256", spec.ffmpeg().sha256());
            marker.setProperty("yt-dlp.sha256", spec.ytDlp().sha256());
            marker.setProperty("quickjs.sha256", spec.quickJs().sha256());
            try (OutputStream output = Files.newOutputStream(staging.resolve(INSTALL_MARKER),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                marker.store(output, "Musicolide verified native toolchain");
            }
            deleteTree(work);
            replaceDirectory(staging, nativeRoot.resolve(platform));

            plugin.getLogger().info("Installed and checksum-verified yt-dlp, FFmpeg and QuickJS for " + platform
                    + " under plugins/Musicolide/native/.");
            return toolPaths(nativeRoot.resolve(platform));
        } catch (IOException | RuntimeException ex) {
            deleteTree(staging);
            throw ex;
        }
    }

    private Map<NativeToolchain.Tool, String> existingInstall(PlatformSpec spec) throws IOException {
        Path directory = nativeRoot.resolve(platform).normalize();
        Path markerFile = directory.resolve(INSTALL_MARKER);
        if (!Files.isRegularFile(markerFile, LinkOption.NOFOLLOW_LINKS)) return Map.of();

        Properties marker = new Properties();
        try (InputStream input = Files.newInputStream(markerFile)) {
            marker.load(input);
        }
        if (!spec.id().equals(marker.getProperty("toolchain"))
                || !platform.equals(marker.getProperty("platform"))) {
            return Map.of();
        }

        Map<NativeToolchain.Tool, String> paths = toolPaths(directory);
        if (paths.size() != 3) return Map.of();
        for (String path : paths.values()) {
            Path executable = Path.of(path);
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) return Map.of();
            setExecutable(executable);
        }
        return Map.copyOf(paths);
    }

    private Map<NativeToolchain.Tool, String> toolPaths(Path platformDirectory) {
        String suffix = platform.startsWith("windows-") ? ".exe" : "";
        Map<NativeToolchain.Tool, String> paths = new EnumMap<>(NativeToolchain.Tool.class);
        paths.put(NativeToolchain.Tool.FFMPEG,
                platformDirectory.resolve("bin").resolve("ffmpeg" + suffix).toAbsolutePath().toString());
        paths.put(NativeToolchain.Tool.YT_DLP,
                platformDirectory.resolve("bin").resolve("yt-dlp" + suffix).toAbsolutePath().toString());
        paths.put(NativeToolchain.Tool.JS_RUNTIME,
                platformDirectory.resolve("bin").resolve("qjs" + suffix).toAbsolutePath().toString());
        return paths;
    }

    private void download(Artifact artifact, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(partial);

        HttpURLConnection connection = (HttpURLConnection) artifact.uri().toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(25_000);
        connection.setReadTimeout(60_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "Musicolide-NativeInstaller/1.0");
        connection.setRequestProperty("Accept", "application/octet-stream");

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download of " + artifact.fileName() + " returned HTTP " + status + ".");
            }
            URI finalUri;
            try {
                finalUri = connection.getURL().toURI();
            } catch (java.net.URISyntaxException ex) {
                throw new IOException("Native-tool download redirected to an invalid URL.", ex);
            }
            if (!"https".equalsIgnoreCase(finalUri.getScheme())
                    || !isTrustedDownloadHost(finalUri.getHost())) {
                throw new IOException("Native-tool download redirected to an untrusted host or scheme.");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > artifact.maxBytes()) {
                throw new IOException("Native-tool archive " + artifact.fileName()
                        + " exceeds its configured download-size limit.");
            }

            MessageDigest digest = sha256Digest();
            long total = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream body = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(partial,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int read;
                while ((read = body.read(buffer)) != -1) {
                    total += read;
                    if (total > artifact.maxBytes()) {
                        throw new IOException("Native-tool download " + artifact.fileName()
                                + " exceeded its configured size limit.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            if (total == 0L) throw new IOException("Native-tool download " + artifact.fileName() + " was empty.");
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(artifact.sha256())) {
                throw new IOException("SHA-256 verification failed for " + artifact.fileName()
                        + "; refusing to install the file.");
            }
            moveReplacing(partial, destination);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(partial);
            throw ex;
        } finally {
            connection.disconnect();
        }
    }

    private void cleanupStaleStagingDirectories() {
        String prefix = "." + platform + ".download-";
        try (var children = Files.list(nativeRoot)) {
            for (Path child : children.filter(path -> path.getFileName().toString().startsWith(prefix)).toList()) {
                deleteTree(child);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not clean an abandoned native-tool staging directory: "
                    + ex.getMessage());
        }
    }

    private static boolean isTrustedDownloadHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("github.com")
                || normalized.equals("release-assets.githubusercontent.com")
                || normalized.endsWith(".githubusercontent.com");
    }

    static void extractFfmpegArchive(Path archive, Path destination, ArchiveFormat format) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.deleteIfExists(destination);
        try {
            switch (format) {
                case ZIP -> extractFfmpegZip(archive, destination);
                case TAR_XZ -> extractFfmpegTar(archive, destination, true);
                case TAR_GZ -> extractFfmpegTar(archive, destination, false);
            }
            if (!Files.isRegularFile(destination) || Files.size(destination) == 0L) {
                throw new IOException("The FFmpeg archive did not contain a usable FFmpeg executable.");
            }
        } catch (IOException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
    }

    private static void extractFfmpegZip(Path archive, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isFfmpegEntry(entry.getName())) continue;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(destination,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    copyBounded(input, output, MAX_EXTRACTED_FFMPEG_BYTES);
                }
                return;
            }
        }
        throw new IOException("The FFmpeg ZIP did not contain bin/ffmpeg.exe.");
    }

    private static void extractFfmpegTar(Path archive, Path destination, boolean xz) throws IOException {
        try (InputStream file = Files.newInputStream(archive);
             InputStream decompressed = xz
                     ? new XZCompressorInputStream(file, true)
                     : new GzipCompressorInputStream(file, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!tar.canReadEntryData(entry) || entry.isDirectory()
                        || entry.isSymbolicLink() || entry.isLink()
                        || !isFfmpegEntry(entry.getName())) {
                    continue;
                }
                try (OutputStream output = Files.newOutputStream(destination,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    copyBounded(tar, output, MAX_EXTRACTED_FFMPEG_BYTES);
                }
                return;
            }
        }
        throw new IOException("The FFmpeg tar archive did not contain an FFmpeg executable.");
    }

    private static boolean isFfmpegEntry(String name) {
        if (name == null) return false;
        String normalized = name.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        String basename = lower.substring(lower.lastIndexOf('/') + 1);
        return (basename.equals("ffmpeg") || basename.equals("ffmpeg.exe"))
                && (lower.contains("/bin/") || !lower.contains("/"));
    }

    private static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new IOException("Extracted FFmpeg executable exceeded the size limit.");
            output.write(buffer, 0, read);
        }
        if (total == 0L) throw new IOException("Extracted FFmpeg executable was empty.");
    }

    private static void writeNotices(Path licenses, PlatformSpec spec) throws IOException {
        String text = """
                Musicolide native tools
                =======================

                These are separate executable programs downloaded on first startup.
                They are not linked into Musicolide's Java code.

                yt-dlp %s
                Source: https://github.com/yt-dlp/yt-dlp/releases/tag/%s
                License: Unlicense (public domain dedication); third-party notices are
                maintained by the yt-dlp project.

                QuickJS-ng %s
                Source: https://github.com/quickjs-ng/quickjs/releases/tag/%s
                License: MIT.

                FFmpeg
                Source archive: %s
                SHA-256: %s
                FFmpeg license and included-codec notices depend on the selected build.
                Linux and Windows bundles use the FFmpeg-Builds LGPL variant. The
                macOS bundle's publisher lists GPL and LGPL codecs; consult the source
                project and FFmpeg legal page before redistributing that binary:
                https://ffmpeg.org/legal.html

                Musicolide downloads only from HTTPS release URLs pinned in the code
                and verifies the complete source archive/executable with SHA-256 before
                installation. No shell or external archive utility is used.
                """.formatted(YT_DLP_VERSION, YT_DLP_VERSION, QUICKJS_VERSION, QUICKJS_VERSION,
                spec.ffmpeg().uri(), spec.ffmpeg().sha256());
        Files.writeString(licenses.resolve("NATIVE-TOOLS-NOTICES.txt"), text,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private String executableName(String name) {
        return name + (platform.startsWith("windows-") ? ".exe" : "");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", ex);
        }
    }

    private static void setExecutable(Path file) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ex) {
            if (!file.toFile().setExecutable(true, false) && !Files.isExecutable(file)) {
                throw new IOException("Could not mark a native tool executable.", ex);
            }
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void replaceDirectory(Path staging, Path destination) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path backup = parent.resolve("." + destination.getFileName() + ".backup-" + UUID.randomUUID());
        boolean hadPrevious = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (hadPrevious) moveReplacing(destination, backup);
        try {
            moveReplacing(staging, destination);
        } catch (IOException ex) {
            if (hadPrevious && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                moveReplacing(backup, destination);
            }
            throw ex;
        } finally {
            deleteTree(backup);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }

    static boolean supportsPlatform(String platform) {
        return specFor(platform) != null;
    }

    private static PlatformSpec specFor(String platform) {
        return switch (platform) {
            case "linux-x86_64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-N-128837-x64", githubArtifact(
                    "FFmpeg-Builds/FFmpeg-Builds", FFMPEG_LINUX_WINDOWS_TAG,
                    "ffmpeg-N-128837-g986bab2b47-linux64-lgpl.tar.xz",
                    "92e3204c39c10fc40c0432f1b3be9e5817e0a62d6c638c37170b2cf3b8c6f215",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.TAR_XZ,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp_linux",
                            "767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795",
                            MAX_YT_DLP_BYTES),
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-linux-x86_64",
                            "85ded23b9fabe8c1644fc40a8d2bbdd71157affcbd807359c618cc717ee24d7d",
                            MAX_QUICKJS_BYTES));

            case "linux-aarch64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-N-128837-arm64", githubArtifact(
                    "FFmpeg-Builds/FFmpeg-Builds", FFMPEG_LINUX_WINDOWS_TAG,
                    "ffmpeg-N-128837-g986bab2b47-linuxarm64-lgpl.tar.xz",
                    "c36a52fbe89dacdd3e8b0ea257cb1c1d4a0673cf569f32c967565983dc5e136b",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.TAR_XZ,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp_linux_aarch64",
                            "e87e9533e89fe5889abaa21f5114dfe7756d6aa6219b83551add04d48b18e483",
                            MAX_YT_DLP_BYTES),
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-linux-aarch64",
                            "2c005c7171424a598d31c53c8dc4a0ae2e4958eb6fbb37908c885a307c78187c",
                            MAX_QUICKJS_BYTES));

            case "windows-x86_64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-N-128837-win64", githubArtifact(
                    "FFmpeg-Builds/FFmpeg-Builds", FFMPEG_LINUX_WINDOWS_TAG,
                    "ffmpeg-N-128837-g986bab2b47-win64-lgpl.zip",
                    "06acd66c3de0d99e5a74e3f1704bc2276751dcbb154904d12ec61d5b7a500034",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.ZIP,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp.exe",
                            "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d",
                            MAX_YT_DLP_BYTES),
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-windows-x86_64.exe",
                            "ff03398d74ea6e6bd3a8e58058f7003225abcac229d7d57775b7aaae812a49c7",
                            MAX_QUICKJS_BYTES));

            case "windows-aarch64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-N-128837-winarm64", githubArtifact(
                    "FFmpeg-Builds/FFmpeg-Builds", FFMPEG_LINUX_WINDOWS_TAG,
                    "ffmpeg-N-128837-g986bab2b47-winarm64-lgpl.zip",
                    "0c1e3e79720f1ea6ab6ba1db34cf0b746137dea42b707b079d4ffb721795b3ed",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.ZIP,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp_arm64.exe",
                            "0066dbac3c124028e2dcae2a197fccee33cd91f456313d24f7af89fd54590e98",
                            MAX_YT_DLP_BYTES),
                    // Windows ARM64 can run x64 user-mode tools through its x64 emulation layer.
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-windows-x86_64.exe",
                            "ff03398d74ea6e6bd3a8e58058f7003225abcac229d7d57775b7aaae812a49c7",
                            MAX_QUICKJS_BYTES));

            case "macos-x86_64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-macos-2026.06.11-x64", githubArtifact(
                    "vanloctech/ffmpeg-macos", FFMPEG_MACOS_TAG,
                    "ffmpeg-macos-x64.tar.gz",
                    "8317f17790749fd3502384201fd4d9d2a998ff9b8452d3b8b54b3b9af6a16e39",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.TAR_GZ,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp_macos",
                            "0f922c15ccc0b83eefa1c8bc9104e434318c4bf16e408e1ee93b7745a6efe0aa",
                            MAX_YT_DLP_BYTES),
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-darwin-x86_64",
                            "fa2c5dc78095daff566b32fab995ced12927875253b84216b91b834af2ef2b74",
                            MAX_QUICKJS_BYTES));

            case "macos-aarch64" -> new PlatformSpec("yt" + YT_DLP_VERSION + "-qjs" + QUICKJS_VERSION
                    + "-ffmpeg-macos-2026.06.11-arm64", githubArtifact(
                    "vanloctech/ffmpeg-macos", FFMPEG_MACOS_TAG,
                    "ffmpeg-macos-arm64.tar.gz",
                    "26f5133f88d5ab1254cc03499acef42dae2dbc574dd26e0dade8c968420127e9",
                    MAX_FFMPEG_ARCHIVE_BYTES), ArchiveFormat.TAR_GZ,
                    githubArtifact("yt-dlp/yt-dlp", YT_DLP_VERSION, "yt-dlp_macos",
                            "0f922c15ccc0b83eefa1c8bc9104e434318c4bf16e408e1ee93b7745a6efe0aa",
                            MAX_YT_DLP_BYTES),
                    githubArtifact("quickjs-ng/quickjs", QUICKJS_VERSION, "qjs-darwin-arm64",
                            "91fe89916d3b0a6c01c4dc8382a99c3c1fd91ecfc214aa81ca051902bcce590d",
                            MAX_QUICKJS_BYTES));

            default -> null;
        };
    }

    private static Artifact githubArtifact(String repository, String tag, String file,
                                           String sha256, long maxBytes) {
        String encodedTag = tag.replace("%", "%25").replace("/", "%2F");
        String encodedFile = file.replace("%", "%25").replace(" ", "%20");
        URI uri = URI.create("https://github.com/" + repository + "/releases/download/"
                + encodedTag + "/" + encodedFile);
        return new Artifact(file, uri, sha256, maxBytes);
    }
}
