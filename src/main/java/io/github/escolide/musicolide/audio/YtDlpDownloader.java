package io.github.escolide.musicolide.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
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
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "aac", "aiff", "aif", "flac", "m4a", "mka", "mkv", "mp3", "mp4",
            "mpga", "ogg", "opus", "wav", "webm", "wma");
    private static final long MAX_INFO_JSON_BYTES = 10L * 1024L * 1024L;

    public record DownloadResult(Path audioFile, String title, String artist,
                                 String sourceId, String extractor) {}

    public record SearchResult(String videoId, String title, String uploader,
                               long durationSeconds, String url) {}

    private record RankedSearchResult(SearchResult result, int score, int originalOrder) {}
    private final JavaPlugin plugin;
    private final NativeToolchain toolchain;

    public YtDlpDownloader(JavaPlugin plugin, NativeToolchain toolchain) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
    }

    public DownloadResult download(String sourceUrl, Path destinationDirectory) throws IOException, InterruptedException {
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
        Path cacheDirectory = plugin.getDataFolder().toPath().resolve("cache").resolve("yt-dlp");
        Files.createDirectories(cacheDirectory);
        Path errorLog = Files.createTempFile(destinationDirectory, ".yt-dlp-", ".log");
        Process process = null;
        try {
            String outputTemplate = destinationDirectory.resolve("source.%(ext)s").toString();
            String jsRuntimeExecutable = null;
            if (isYouTubeHost(uri.getHost())) {
                if (!toolchain.available(NativeToolchain.Tool.JS_RUNTIME)) {
                    throw new IOException("QuickJS is unavailable; YouTube extraction cannot start.");
                }
                jsRuntimeExecutable = toolchain.executable(NativeToolchain.Tool.JS_RUNTIME);
            }
            List<String> command = buildCommand(
                    toolchain.executable(NativeToolchain.Tool.YT_DLP),
                    uri,
                    jsRuntimeExecutable,
                    cacheDirectory,
                    maxInputMb,
                    plugin.getConfig().getLong("audio.max-track-duration-seconds", 1800),
                    Path.of(outputTemplate));
            process = toolchain.start(new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(errorLog.toFile()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                toolchain.terminate(process);
                throw new IOException("yt-dlp exceeded its " + timeoutSeconds + " second timeout.");
            }
            int exit = process.exitValue();
            toolchain.finished(process);
            if (exit != 0) {
                String details = readErrorSummary(errorLog, sourceUrl);
                throw new IOException("yt-dlp failed with exit code " + exit
                        + (details.isBlank()
                        ? ". Check that the source is reachable and authorized."
                        : ". Details: " + details));
            }

            try (var files = Files.list(destinationDirectory)) {
                Optional<Path> downloaded = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("source."))
                        .filter(path -> !path.getFileName().toString().endsWith(".info.json"))
                        .filter(path -> !isTemporary(path.getFileName().toString()))
                        .filter(path -> AUDIO_EXTENSIONS.contains(extension(path.getFileName().toString())))
                        .filter(path -> {
                            try { return Files.size(path) > 0 && Files.size(path) <= maxInputMb * 1024L * 1024L; }
                            catch (IOException ex) { return false; }
                        })
                        .findFirst();
                Path audio = downloaded.orElseThrow(() -> new IOException(
                        "yt-dlp finished but did not produce one valid audio file."));
                Path infoJson = destinationDirectory.resolve("source.info.json");
                Metadata metadata = readMetadata(infoJson);
                return new DownloadResult(audio, metadata.title(), metadata.artist(),
                        metadata.sourceId(), metadata.extractor());
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



    /**
     * Searches YouTube for metadata only. No media formats are selected and
     * --skip-download is always present; a player must explicitly choose a result
     * before the normal, allowlist-checked import path can download audio.
     */
    public List<SearchResult> searchYouTube(String query) throws IOException, InterruptedException {
        int configured = bounded(plugin.getConfig().getInt("audio.yt-dlp.search.result-count", 6), 1, 20);
        return searchYouTube(query, configured);
    }

    public List<SearchResult> searchYouTube(String query, int maximumResults) throws IOException, InterruptedException {
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            throw new IOException("YouTube search is disabled. Enable audio.yt-dlp first.");
        }
        if (!toolchain.available(NativeToolchain.Tool.YT_DLP)) {
            throw new IOException("yt-dlp is unavailable. Install it or provide a bundled executable.");
        }
        if (!toolchain.available(NativeToolchain.Tool.JS_RUNTIME)) {
            throw new IOException("QuickJS is unavailable; YouTube search cannot start.");
        }

        String normalizedQuery = query == null ? "" : query.trim().replace('_', ' ').replaceAll("[\\r\\n\\t]+", " ");
        if (normalizedQuery.isBlank()) throw new IOException("Enter a YouTube search query.");
        if (normalizedQuery.length() > 180) throw new IOException("Search queries must be 180 characters or fewer.");

        List<String> allowedHosts = plugin.getConfig().getStringList("audio.yt-dlp.allowed-hosts");
        List<String> blockedHosts = plugin.getConfig().getStringList("audio.yt-dlp.blocked-hosts");
        if (isBlockedHost("youtube.com", blockedHosts) || isBlockedHost("youtu.be", blockedHosts)) {
            throw new IOException("YouTube is blocked in audio.yt-dlp.blocked-hosts.");
        }
        if (!isHostAllowed("youtube.com", allowedHosts)
                && !isHostAllowed("youtu.be", allowedHosts)) {
            throw new IOException("Add youtube.com or youtu.be to audio.yt-dlp.allowed-hosts before searching.");
        }

        int resultCount = bounded(maximumResults, 1, 160);
        boolean prioritizeSongs = plugin.getConfig().getBoolean(
                "audio.yt-dlp.search.prioritize-songs", true);
        int fetchCount = prioritizeSongs ? Math.min(160, Math.max(resultCount, resultCount * 3)) : resultCount;

        Path temporaryDirectory = plugin.getDataFolder().toPath().resolve("temp");
        Path cacheDirectory = plugin.getDataFolder().toPath().resolve("cache").resolve("yt-dlp");
        Files.createDirectories(temporaryDirectory);
        Files.createDirectories(cacheDirectory);
        Path output = null;
        Path errorLog = null;
        Process process = null;
        try {
            output = Files.createTempFile(temporaryDirectory, ".youtube-search-", ".json");
            errorLog = Files.createTempFile(temporaryDirectory, ".youtube-search-", ".log");
            String jsRuntime = toolchain.executable(NativeToolchain.Tool.JS_RUNTIME);
            List<String> command = buildSearchCommand(
                    toolchain.executable(NativeToolchain.Tool.YT_DLP), jsRuntime, cacheDirectory,
                    fetchCount, normalizedQuery);
            process = toolchain.start(new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(errorLog.toFile()));

            int timeoutSeconds = bounded(plugin.getConfig().getInt(
                    "audio.processing.yt-dlp-search-timeout-seconds", 45), 10, 180);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                toolchain.terminate(process);
                throw new IOException("YouTube search exceeded its " + timeoutSeconds + " second timeout.");
            }
            int exit = process.exitValue();
            toolchain.finished(process);
            if (exit != 0) {
                String details = readErrorSummary(errorLog, normalizedQuery);
                throw new IOException("YouTube search failed with exit code " + exit
                        + (details.isBlank() ? ". Check server connectivity and yt-dlp diagnostics."
                        : ". Details: " + details));
            }

            long outputSize = Files.size(output);
            if (outputSize <= 0 || outputSize > MAX_INFO_JSON_BYTES) {
                throw new IOException("YouTube search returned an empty or oversized metadata response.");
            }
            String json = Files.readString(output, StandardCharsets.UTF_8);
            return parseSearchResults(json, resultCount, prioritizeSongs);
        } catch (InterruptedException ex) {
            if (process != null) toolchain.terminate(process);
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            if (process != null) toolchain.finished(process);
            if (output != null) Files.deleteIfExists(output);
            if (errorLog != null) Files.deleteIfExists(errorLog);
        }
    }

    static List<String> buildSearchCommand(String executable, String jsRuntimeExecutable,
                                           Path cacheDirectory, int searchLimit, String query) {
        if (jsRuntimeExecutable == null || jsRuntimeExecutable.isBlank()) {
            throw new IllegalArgumentException("A JavaScript runtime is required for YouTube search.");
        }
        int boundedLimit = bounded(searchLimit, 1, 160);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(
                "--js-runtimes", "quickjs:" + jsRuntimeExecutable,
                "--remote-components", "ejs:github",
                "--ignore-config",
                "--cache-dir", cacheDirectory.toAbsolutePath().toString(),
                "--no-warnings",
                "--no-progress",
                "--no-colors",
                "--socket-timeout", "15",
                "--retries", "1",
                "--flat-playlist",
                "--skip-download",
                "--dump-single-json",
                "--playlist-end", Integer.toString(boundedLimit),
                "--", "ytsearch" + boundedLimit + ":" + query
        ));
        return List.copyOf(command);
    }

    static List<SearchResult> parseSearchResults(String json, int resultLimit, boolean prioritizeSongs)
            throws IOException {
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            throw new IOException("yt-dlp returned invalid YouTube search JSON.", ex);
        }
        if (!root.isJsonObject()) throw new IOException("yt-dlp returned an unexpected YouTube search response.");
        JsonElement entriesElement = root.getAsJsonObject().get("entries");
        JsonArray entries;
        if (entriesElement == null || entriesElement.isJsonNull()) {
            // Some extractors return a single video object instead of a playlist
            // wrapper; preserve that usable result rather than silently showing none.
            JsonObject single = root.getAsJsonObject();
            if (!single.has("id") || !single.has("title")) return List.of();
            entries = new JsonArray();
            entries.add(single.deepCopy());
        } else if (!entriesElement.isJsonArray()) {
            throw new IOException("yt-dlp returned an unexpected YouTube search result list.");
        } else {
            entries = entriesElement.getAsJsonArray();
        }

        List<RankedSearchResult> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int order = 0;
        for (JsonElement entry : entries) {
            int currentOrder = order++;
            if (entry == null || !entry.isJsonObject()) continue;
            JsonObject object = entry.getAsJsonObject();
            String id = firstNonBlank(object, "id");
            String title = firstNonBlank(object, "title");
            if (!id.matches("[A-Za-z0-9_-]{6,32}") || title.isBlank() || !seenIds.add(id)) continue;

            String liveStatus = firstNonBlank(object, "live_status").toLowerCase(Locale.ROOT);
            boolean live = object.has("is_live") && !object.get("is_live").isJsonNull()
                    && object.get("is_live").isJsonPrimitive()
                    && object.get("is_live").getAsBoolean();
            if (live || liveStatus.equals("is_live") || liveStatus.equals("is_upcoming")) continue;

            String uploader = firstNonBlank(object, "uploader", "channel", "channel_name", "creator");
            long durationSeconds = -1L;
            JsonElement duration = object.get("duration");
            if (duration != null && duration.isJsonPrimitive() && duration.getAsJsonPrimitive().isNumber()) {
                double value = duration.getAsDouble();
                if (Double.isFinite(value) && value >= 0 && value <= Long.MAX_VALUE) {
                    durationSeconds = Math.round(value);
                }
            }

            SearchResult result = new SearchResult(id, title, uploader, durationSeconds,
                    "https://www.youtube.com/watch?v=" + id);
            int score = prioritizeSongs ? songMaterialScore(title, uploader, durationSeconds) : 0;
            results.add(new RankedSearchResult(result, score, currentOrder));
        }

        if (prioritizeSongs) {
            results.sort(Comparator.comparingInt(RankedSearchResult::score).reversed()
                    .thenComparingInt(RankedSearchResult::originalOrder));
        } else {
            results.sort(Comparator.comparingInt(RankedSearchResult::originalOrder));
        }
        return results.stream().limit(bounded(resultLimit, 1, 160))
                .map(RankedSearchResult::result).toList();
    }

    static int songMaterialScore(String title, String uploader, long durationSeconds) {
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String normalizedUploader = uploader == null ? "" : uploader.toLowerCase(Locale.ROOT);
        int score = 0;

        if (normalizedTitle.contains("official audio")) score += 9;
        if (normalizedTitle.contains("official music video")) score += 7;
        else if (normalizedTitle.contains("official video")) score += 4;
        if (normalizedTitle.contains("lyric video") || normalizedTitle.contains("lyrics")) score += 4;
        if (normalizedTitle.contains("visualizer")) score += 3;
        if (normalizedUploader.endsWith(" - topic")) score += 7;
        else if (normalizedUploader.contains("vevo")) score += 3;

        for (String term : List.of("reaction", "tutorial", "how to", "review", "karaoke",
                "full album", "compilation", "playlist", "live stream", "explained")) {
            if (normalizedTitle.contains(term)) score -= 8;
        }
        if (normalizedTitle.contains("cover")) score -= 2;
        if (normalizedTitle.contains("fan made") || normalizedTitle.contains("fan-made")) score -= 3;
        if (durationSeconds >= 120 && durationSeconds <= 600) score += 2;
        else if (durationSeconds > 0 && durationSeconds < 45) score -= 3;
        else if (durationSeconds > 900) score -= 2;
        return score;
    }

    record Metadata(String title, String artist, String sourceId, String extractor) {
        private static final Metadata EMPTY = new Metadata("", "", "", "");
    }

    static Metadata readMetadata(Path infoJson) {
        try {
            if (!Files.isRegularFile(infoJson) || Files.size(infoJson) <= 0
                    || Files.size(infoJson) > MAX_INFO_JSON_BYTES) {
                return Metadata.EMPTY;
            }
            try (var reader = Files.newBufferedReader(infoJson, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return Metadata.EMPTY;
                JsonObject object = parsed.getAsJsonObject();
                String title = firstNonBlank(object, "title", "fulltitle", "track");
                String artist = firstNonBlank(object, "artist", "artists", "creator",
                        "creators", "uploader", "channel");
                String id = firstNonBlank(object, "id");
                String extractor = firstNonBlank(object, "extractor_key", "extractor");
                return new Metadata(title, artist, id, extractor);
            }
        } catch (Exception ignored) {
            // Metadata is an enhancement; a valid audio download must still import.
            return Metadata.EMPTY;
        }
    }

    private static String firstNonBlank(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            String text = jsonValueText(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String jsonValueText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<String> values = new ArrayList<>();
            for (JsonElement item : array) {
                if (item != null && !item.isJsonNull()) {
                    String text = item.getAsString().trim();
                    if (!text.isBlank()) values.add(text);
                }
            }
            return String.join(", ", values);
        }
        if (value.isJsonPrimitive()) {
            String text = value.getAsString();
            return text == null ? "" : text.trim();
        }
        return "";
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Build a single-source download command. Do not add --max-downloads 1:
     * yt-dlp returns exit code 101 when that limit is reached, even after the
     * requested single source has downloaded successfully. --no-playlist is
     * sufficient to ensure that one input URL yields at most one video.
     */
    static List<String> buildCommand(String executable, URI uri, String jsRuntimeExecutable,
                                     Path cacheDirectory, int maxInputMb, long maxTrackDurationSeconds,
                                     Path outputTemplate) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (isYouTubeHost(uri.getHost())) {
            if (jsRuntimeExecutable == null || jsRuntimeExecutable.isBlank()) {
                throw new IllegalArgumentException("A JavaScript runtime is required for YouTube extraction.");
            }
            command.add("--js-runtimes");
            command.add("quickjs:" + jsRuntimeExecutable);
            // yt-dlp's maintained EJS scripts are retrieved from the official
            // yt-dlp/ejs GitHub project and cached by yt-dlp as remote components.
            command.add("--remote-components");
            command.add("ejs:github");
        }
        command.addAll(List.of(
                "--ignore-config",
                "--cache-dir", cacheDirectory.toAbsolutePath().toString(),
                "--no-playlist",
                "--no-progress",
                "--no-colors",
                "--socket-timeout", "20",
                "--retries", "3",
                "--fragment-retries", "3",
                "--concurrent-fragments", "1",
                "--no-part",
                "--max-filesize", maxInputMb + "M",
                "--write-info-json",
                "--format", "bestaudio/best",
                "--match-filter", "duration <= " + maxTrackDurationSeconds,
                "--output", outputTemplate.toString(),
                "--", uri.toASCIIString()
        ));
        return List.copyOf(command);
    }

    /**
     * Keep a short tail of yt-dlp's combined output so an administrator can
     * diagnose extractor/runtime failures without allowing an unbounded log into chat.
     */
    private static String readErrorSummary(Path errorLog, String sourceUrl) {
        try {
            long size = Files.size(errorLog);
            int bytesToRead = (int) Math.min(size, 16 * 1024L);
            if (bytesToRead == 0) return "";

            String raw;
            try (SeekableByteChannel channel = Files.newByteChannel(errorLog, StandardOpenOption.READ)) {
                channel.position(Math.max(0L, size - bytesToRead));
                ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer);
                    if (read < 0) break;
                }
                buffer.flip();
                raw = StandardCharsets.UTF_8.decode(buffer).toString();
            }

            String normalized = raw.replace(sourceUrl, "[source URL]")
                    .replaceAll("(?i)(authorization|cookie|token)=([^&\\s]+)", "$1=[redacted]");
            List<String> lines = normalized.lines().map(String::trim)
                    .filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) return "";
            int start = Math.max(0, lines.size() - 6);
            String summary = String.join(" | ", lines.subList(start, lines.size()));
            return summary.length() <= 1000 ? summary : summary.substring(summary.length() - 1000);
        } catch (IOException ex) {
            return "";
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
