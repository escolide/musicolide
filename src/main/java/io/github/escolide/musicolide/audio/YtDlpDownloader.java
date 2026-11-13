package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Downloads a single audio source only from administrator-allowlisted hosts.
 * Platform-specific sources are never enabled just by having yt-dlp installed;
 * the administrator must allowlist the host and confirm distribution rights.
 */
public final class YtDlpDownloader {
    private static final List<String> ALWAYS_BLOCKED_HOSTS = List.of("spotify.com", "soundcloud.com");
    private final JavaPlugin plugin;
    private final NativeToolchain toolchain;

    public YtDlpDownloader(JavaPlugin plugin, NativeToolchain toolchain) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
    }

    public Path download(String sourceUrl, Path destinationDirectory) throws IOException, InterruptedException {
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            throw new IOException("Remote importing is disabled in config.yml.");
        }
        if (!toolchain.available(NativeToolchain.Tool.YT_DLP)) {
            throw new IOException("yt-dlp is unavailable. Install it or provide a bundled executable.");
        }

        URI uri = validateSource(sourceUrl,
                plugin.getConfig().getStringList("audio.yt-dlp.allowed-hosts"),
                plugin.getConfig().getStringList("audio.yt-dlp.blocked-hosts"));
        Files.createDirectories(destinationDirectory);

        int maxInputMb = bounded(plugin.getConfig().getInt("audio.max-input-mb", 100), 1, 2048);
        int timeoutSeconds = bounded(plugin.getConfig().getInt(
                "audio.processing.yt-dlp-timeout-seconds", 600), 10, 7200);
        Path errorLog = Files.createTempFile(destinationDirectory, ".yt-dlp-", ".log");
        Process process = null;
        try {
            String outputTemplate = destinationDirectory.resolve("source.%(ext)s").toString();
            List<String> command = new ArrayList<>();
            command.add(toolchain.executable(NativeToolchain.Tool.YT_DLP));
            if (isYouTubeHost(uri.getHost())) {
                if (!toolchain.available(NativeToolchain.Tool.JS_RUNTIME)) {
                    throw new IOException("QuickJS is unavailable; YouTube extraction cannot start.");
                }
                command.add("--js-runtimes");
                command.add("quickjs:" + toolchain.executable(NativeToolchain.Tool.JS_RUNTIME));
            }
            command.addAll(List.of(
                    "--ignore-config",
                    "--no-cache-dir",
                    "--no-playlist",
                    "--no-progress",
                    "--no-warnings",
                    "--no-part",
                    "--max-downloads", "1",
                    "--max-filesize", maxInputMb + "M",
                    "--format", "bestaudio/best",
                    "--match-filter", "duration <= " + plugin.getConfig().getLong("audio.max-track-duration-seconds", 1800),
                    "--output", outputTemplate,
                    "--", uri.toASCIIString()
            ));
            process = toolchain.start(new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(errorLog.toFile()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                toolchain.terminate(process);
                throw new IOException("yt-dlp exceeded its " + timeoutSeconds + " second timeout.");
            }
            int exit = process.exitValue();
            toolchain.finished(process);
            if (exit != 0) {
                throw new IOException("yt-dlp failed with exit code " + exit
                        + ". Check that the source is downloadable and authorized.");
            }

            try (var files = Files.list(destinationDirectory)) {
                Optional<Path> downloaded = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("source."))
                        .filter(path -> !isTemporary(path.getFileName().toString()))
                        .filter(path -> {
                            try { return Files.size(path) > 0 && Files.size(path) <= maxInputMb * 1024L * 1024L; }
                            catch (IOException ex) { return false; }
                        })
                        .findFirst();
                return downloaded.orElseThrow(() -> new IOException(
                        "yt-dlp finished but did not produce one valid audio file."));
            }
        } catch (InterruptedException ex) {
            if (process != null) toolchain.terminate(process);
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            if (process != null) toolchain.finished(process);
            Files.deleteIfExists(errorLog);
        }
    }

    private static boolean isYouTubeHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("youtu.be") || normalized.equals("youtube.com")
                || normalized.endsWith(".youtube.com") || normalized.equals("youtube-nocookie.com")
                || normalized.endsWith(".youtube-nocookie.com");
    }

    static URI validateSource(String sourceUrl, List<String> allowedHosts, List<String> blockedHosts)
            throws IOException {
        final URI uri;
        try {
            uri = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IOException("The source URL is invalid.", ex);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IOException("Only ordinary HTTP(S) URLs without embedded credentials are accepted.");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isBlockedHost(host, blockedHosts)) {
            throw new IOException("This streaming platform is configured for metadata/aliases only, not audio importing.");
        }
        if (!isHostAllowed(host, allowedHosts)) {
            throw new IOException("The source host is not in audio.yt-dlp.allowed-hosts.");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new IOException("Local network source hosts are not permitted.");
        }
        if (isIpLiteral(host)) {
            try {
                if (!isPublicAddress(InetAddress.getByName(host))) {
                    throw new IOException("Private or local network source addresses are not permitted.");
                }
            } catch (java.net.UnknownHostException ex) {
                throw new IOException("The source host could not be resolved.", ex);
            }
        }
        return uri;
    }

    static boolean isHostAllowed(String host, List<String> allowedHosts) {
        if (allowedHosts == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream().filter(Objects::nonNull).map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .anyMatch(value -> normalized.equals(value) || normalized.endsWith("." + value));
    }

    static boolean isBlockedHost(String host, List<String> blockedHosts) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (matchesHost(normalized, ALWAYS_BLOCKED_HOSTS)) return true;
        return blockedHosts != null && matchesHost(normalized, blockedHosts);
    }

    private static boolean matchesHost(String host, List<String> values) {
        return values.stream().filter(Objects::nonNull).map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .anyMatch(value -> host.equals(value) || host.endsWith("." + value));
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    private static boolean isPublicAddress(InetAddress address) {
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }

    private static boolean isTemporary(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".part") || lower.endsWith(".ytdl") || lower.endsWith(".tmp");
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
