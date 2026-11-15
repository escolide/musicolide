package io.github.escolide.musicolide.audio;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.resource.ResourcePackBuilder;
import io.github.escolide.musicolide.storage.TrackRepository;
import io.github.escolide.musicolide.util.LinkNormalizer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class AudioProcessor implements AutoCloseable {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_]{1,48}");

    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final ResourcePackBuilder packBuilder;
    private final NativeToolchain toolchain;
    private final ThreadPoolExecutor worker;
    private final ConcurrentMap<String, CompletableFuture<MusicTrack>> inFlightUrlImports =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<PlaylistImportResult>> inFlightPlaylistImports =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean playlistImportActive =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<String> monoVariantsReady = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, CompletableFuture<MusicTrack>> inFlightMonoVariants =
            new ConcurrentHashMap<>();

    public AudioProcessor(JavaPlugin plugin, TrackRepository repository, ResourcePackBuilder packBuilder,
                          NativeToolchain toolchain) {
        this.plugin = plugin;
        this.repository = repository;
        this.packBuilder = packBuilder;
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
        int maxQueued = bounded(plugin.getConfig().getInt("audio.processing.max-queued-jobs", 8), 1, 64);
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued), runnable -> {
                    Thread thread = new Thread(runnable, "Musicolide-AudioWorker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<MusicTrack> importFile(String fileName, String requestedTitle) {
        return importFile(fileName, requestedTitle, null);
    }

    public CompletableFuture<MusicTrack> importFile(String fileName, String requestedTitle, String customId) {
        final String normalizedCustomId;
        try {
            normalizedCustomId = validateCustomId(customId);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (!toolchain.isInitialized()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Native audio tools are still being checked during startup. Try again in a moment."));
        }
        if (!toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFmpeg must be installed or downloaded before audio can be imported."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> processFile(fileName, requestedTitle, false, normalizedCustomId), worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current import to finish and try again.", ex));
        }
    }

    /**
     * Downloads one administrator-authorized URL into a disposable directory, then
     * uses the same local validation/encoding path as a file import. yt-dlp exits
     * before FFmpeg is launched.
     */

    /**
     * Runs a bounded, metadata-only YouTube search on the serial audio worker.
     * The search itself never downloads media; selected URLs still pass through
     * the normal allowlist and explicit auto-import configuration.
     */
    public CompletableFuture<List<YtDlpDownloader.SearchResult>> searchYouTube(String query) {
        int configured = bounded(plugin.getConfig().getInt("audio.yt-dlp.search.result-count", 6), 1, 20);
        return searchYouTube(query, configured);
    }

    public CompletableFuture<List<YtDlpDownloader.SearchResult>> searchYouTube(String query, int maximumResults) {
        if (!toolchain.isInitialized()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Native audio tools are still being checked during startup. Try again in a moment."));
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "YouTube search is disabled. Enable audio.yt-dlp in config.yml first."));
        }
        if (!toolchain.available(NativeToolchain.Tool.YT_DLP)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "yt-dlp is unavailable. Install it or provide a bundled executable."));
        }
        if (!toolchain.available(NativeToolchain.Tool.JS_RUNTIME)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "QuickJS is unavailable; YouTube search cannot start."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new YtDlpDownloader(plugin, toolchain).searchYouTube(query, bounded(maximumResults, 1, 160));
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }, worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current job to finish and try again.", ex));
        }
    }

    public CompletableFuture<MusicTrack> importUrl(String sourceUrl, String requestedTitle) {
        return importUrl(sourceUrl, requestedTitle, null);
    }

    public CompletableFuture<MusicTrack> importUrl(String sourceUrl, String requestedTitle, String customId) {
        if (YtDlpDownloader.isYouTubePlaylistUrl(sourceUrl)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "YouTube playlist URLs are not accepted by the single-track importer. Use /play with audio.yt-dlp.allow-playlists enabled."));
        }
        String sourceKey = LinkNormalizer.normalize(sourceUrl).orElse("");
        if (sourceKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The source URL is invalid."));
        }

        // Check the persistent alias before checking native tools or queueing a job.
        try {
            Optional<MusicTrack> existing = repository.resolveAlias(sourceKey);
            if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
        } catch (SQLException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Could not check the existing URL catalogue entry.", ex));
        }

        final String normalizedCustomId;
        try {
            normalizedCustomId = validateCustomId(customId);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }

        if (!toolchain.isInitialized()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Native audio tools are still being checked during startup. Try again in a moment."));
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "URL importing is disabled. Enable audio.yt-dlp and configure allowed-hosts first."));
        }
        if (!toolchain.available(NativeToolchain.Tool.YT_DLP)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "yt-dlp is unavailable. Install it or provide a bundled executable."));
        }
        if (!toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFmpeg must be installed or downloaded before audio can be imported."));
        }

        CompletableFuture<MusicTrack> result = new CompletableFuture<>();
        CompletableFuture<MusicTrack> active = inFlightUrlImports.putIfAbsent(sourceKey, result);
        if (active != null) return active;

        try {
            worker.execute(() -> {
                try {
                    result.complete(importUrlNow(sourceUrl, requestedTitle, normalizedCustomId));
                } catch (Throwable error) {
                    result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                } finally {
                    inFlightUrlImports.remove(sourceKey, result);
                }
            });
        } catch (RejectedExecutionException ex) {
            inFlightUrlImports.remove(sourceKey, result);
            result.completeExceptionally(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current job to finish and try again.", ex));
        }
        return result;
    }

    /**
     * Imports only a bounded number of entries from an explicitly permitted
     * YouTube playlist. Playlist metadata is read first; each video then follows
     * the normal single-video (--no-playlist), allowlist and audio validation path.
     */
    public synchronized CompletableFuture<PlaylistImportResult> importPlaylistUrl(String sourceUrl) {
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.allow-playlists", false)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "YouTube playlist importing is disabled. Enable audio.yt-dlp.allow-playlists in config.yml first."));
        }
        if (!YtDlpDownloader.isYouTubePlaylistUrl(sourceUrl)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "The URL is not a recognized YouTube playlist URL."));
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)
                || !toolchain.isInitialized()
                || !toolchain.available(NativeToolchain.Tool.YT_DLP)
                || !toolchain.available(NativeToolchain.Tool.JS_RUNTIME)
                || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The YouTube playlist importer or required audio tools are unavailable."));
        }

        String playlistKey = sourceUrl.trim();
        int maximum = bounded(plugin.getConfig().getInt(
                "audio.yt-dlp.max-playlist-items", 10), 1, 20);
        // Respect the server's general playlist cap as an additional ceiling.
        maximum = Math.min(maximum, bounded(plugin.getConfig().getInt(
                "limits.max-playlist-tracks", 500), 1, 500));
        final int itemLimit = maximum;

        CompletableFuture<PlaylistImportResult> existing = inFlightPlaylistImports.get(playlistKey);
        if (existing != null) return existing;
        if (!playlistImportActive.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Another YouTube playlist batch is already being processed. Try again after it finishes."));
        }

        CompletableFuture<PlaylistImportResult> result = new CompletableFuture<>();
        inFlightPlaylistImports.put(playlistKey, result);
        try {
            worker.execute(() -> {
                try {
                    YtDlpDownloader.PlaylistListing listing =
                            new YtDlpDownloader(plugin, toolchain).listPlaylist(sourceUrl, itemLimit);
                    List<MusicTrack> imported = new ArrayList<>();
                    int failed = 0;
                    int newlyImported = 0;
                    for (YtDlpDownloader.PlaylistEntry entry : listing.entries()) {
                        String videoAlias = "youtube:" + entry.videoId();
                        Optional<MusicTrack> existing = repository.resolveAlias(videoAlias);
                        if (existing.isPresent()) {
                            imported.add(existing.get());
                            continue;
                        }
                        try {
                            imported.add(importUrlNow(entry.url(), entry.title(), null, false));
                            newlyImported++;
                        } catch (Exception itemError) {
                            failed++;
                            plugin.getLogger().warning("Skipped YouTube playlist item '" + entry.title()
                                    + "': " + rootMessage(itemError));
                        }
                    }
                    if (imported.isEmpty()) {
                        throw new IOException("No tracks from the YouTube playlist could be imported.");
                    }
                    // The Java pack must be published before each new catalogue row
                    // becomes visible. The optional Bedrock aggregate can be rebuilt
                    // once after this batch instead of once per playlist entry.
                    if (newlyImported > 0) {
                        try {
                            packBuilder.rebuildBedrockLibrary();
                        } catch (IOException bedrockError) {
                            plugin.getLogger().warning("Playlist tracks were imported, but the Geyser Bedrock library "
                                    + "could not be refreshed: " + bedrockError.getMessage());
                        }
                    }
                    result.complete(new PlaylistImportResult(imported, listing.entries().size(),
                            failed, listing.truncated(), listing.reportedCount()));
                } catch (Throwable error) {
                    result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                } finally {
                    inFlightPlaylistImports.remove(playlistKey, result);
                    playlistImportActive.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            inFlightPlaylistImports.remove(playlistKey, result);
            playlistImportActive.set(false);
            result.completeExceptionally(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current job to finish and try again.", ex));
        }
        return result;
    }

    private MusicTrack importUrlNow(String sourceUrl, String requestedTitle, String customId) throws Exception {
        return importUrlNow(sourceUrl, requestedTitle, customId, true);
    }

    private MusicTrack importUrlNow(String sourceUrl, String requestedTitle,
                                    String customId, boolean rebuildBedrockLibrary) throws Exception {
        String sourceKey = LinkNormalizer.normalize(sourceUrl).orElse("");
        if (sourceKey.isBlank()) throw new IOException("The source URL is invalid.");
        Optional<MusicTrack> existing = repository.resolveAlias(sourceKey);
        if (existing.isPresent()) return existing.get();

        Path incoming = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("incoming");
        Files.createDirectories(incoming);
        Path temporary = Files.createTempDirectory(incoming, ".musicolide-url-");
        try {
            YtDlpDownloader.DownloadResult download =
                    new YtDlpDownloader(plugin, toolchain).download(sourceUrl, temporary);
            String relative = incoming.relativize(download.audioFile()).toString();
            MusicTrack imported = processFile(relative, requestedTitle, true,
                    download.title(), download.artist(), customId, rebuildBedrockLibrary);

            try {
                repository.addAlias(sourceKey, imported.id());
                String extractor = download.extractor() == null ? "" : download.extractor().toLowerCase(Locale.ROOT);
                String sourceId = download.sourceId() == null ? "" : download.sourceId().trim();
                if (!sourceId.isBlank() && extractor.contains("youtube")) {
                    repository.addAlias("youtube:" + sourceId, imported.id());
                }
            } catch (SQLException aliasError) {
                plugin.getLogger().warning("Imported " + imported.title()
                        + " but could not save its source-ID alias: " + aliasError.getMessage());
            }
            return imported;
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record PlaylistImportResult(List<MusicTrack> tracks, int entriesFound,
                                       int failedEntries, boolean truncated, int reportedTotal) {
        public PlaylistImportResult {
            tracks = List.copyOf(tracks);
        }
    }

    public List<NativeToolchain.ToolStatus> toolStatuses() {
        return toolchain.statuses();
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport) {
        return processFile(fileName, requestedTitle, temporaryUrlImport, "", "", null);
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport,
                                   String customId) {
        return processFile(fileName, requestedTitle, temporaryUrlImport, "", "", customId);
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport,
                                   String metadataTitle, String metadataArtist) {
        return processFile(fileName, requestedTitle, temporaryUrlImport, metadataTitle, metadataArtist, null);
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport,
                                   String metadataTitle, String metadataArtist, String requestedCustomId) {
        return processFile(fileName, requestedTitle, temporaryUrlImport,
                metadataTitle, metadataArtist, requestedCustomId, true);
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport,
                                   String metadataTitle, String metadataArtist, String requestedCustomId,
                                   boolean rebuildBedrockLibrary) {
        Path trackDirectory = null;
        Path generatedPack = null;
        Path source = null;
        boolean basePackPublished = false;
        try {
            Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path incoming = data.resolve("incoming");
            Files.createDirectories(incoming);
            source = incoming.resolve(fileName).normalize();
            if (!source.startsWith(incoming) || !Files.isRegularFile(source)) {
                throw new IOException("File not found in plugins/Musicolide/incoming.");
            }
            Path realIncoming = incoming.toRealPath();
            source = source.toRealPath();
            if (!source.startsWith(realIncoming)) {
                throw new IOException("The selected file must remain inside the incoming directory.");
            }

            long maxBytes = plugin.getConfig().getLong("audio.max-input-mb", 100) * 1024L * 1024L;
            if (Files.size(source) > maxBytes) throw new IOException("Input exceeds configured size limit.");

            String ffmpeg = toolchain.executable(NativeToolchain.Tool.FFMPEG);
            long durationMs = probeDuration(ffmpeg, source, data);
            long maxDuration = plugin.getConfig().getLong("audio.max-track-duration-seconds", 1800);
            if (durationMs <= 0 || durationMs > maxDuration * 1000L) {
                throw new IOException("Track duration is invalid or exceeds " + maxDuration + " seconds.");
            }

            String customId = normalizeCustomId(requestedCustomId);
            if (customId != null && repository.getTrack(customId).isPresent()) {
                throw new IOException("The custom track ID '" + customId + "' is already in use.");
            }
            String customDisplayName = requestedCustomId == null ? ""
                    : requestedCustomId.trim().replace('_', ' ').replaceAll("\\s+", " ");
            String rawTitle = customId != null ? customDisplayName
                    : requestedTitle != null && !requestedTitle.isBlank() ? requestedTitle.trim()
                    : metadataTitle != null && !metadataTitle.isBlank() ? metadataTitle.trim()
                    : stripExtension(source.getFileName().toString());
            String artist = metadataArtist == null ? "" : metadataArtist.trim();
            String id = customId != null ? customId
                    : slug(rawTitle) + "-" + UUID.randomUUID().toString().substring(0, 8);
            // The ms_ namespace marks the new single-file streaming format. The
            // older mc_ namespace remains readable for tracks imported with segments.
            String playbackMode = configuredPlaybackMode();
            boolean segmented = playbackMode.equals("segmented");
            String namespace = (segmented ? "mc_" : "ms_") + id.replace('-', '_');
            if (!SAFE_ID.matcher(namespace).matches()) throw new IOException("Generated track identifier is invalid.");

            trackDirectory = data.resolve("tracks").resolve(id);
            Files.createDirectories(trackDirectory);
            int stereoQuality = bounded(plugin.getConfig().getInt("audio.stereo-quality", 5), 0, 10);
            int monoQuality = bounded(plugin.getConfig().getInt("audio.mono-quality", stereoQuality), 0, 10);
            int segmentMs;
            int segmentCount;
            if (segmented) {
                // Legacy mode: create matching stereo and mono chunks from the same source timestamps.
                segmentMs = bounded(plugin.getConfig().getInt("audio.segment-duration-ms", 1000), 250, 60000);
                segmentCount = (int) Math.max(1L, (durationMs + segmentMs - 1L) / segmentMs);
                Path stereo = trackDirectory.resolve("stereo");
                Path mono = trackDirectory.resolve("mono");
                Files.createDirectories(stereo);
                Files.createDirectories(mono);
                for (int i = 0; i < segmentCount; i++) {
                    long startMs = (long) i * segmentMs;
                    long lengthMs = Math.min(segmentMs, durationMs - startMs);
                    String segment = String.format(Locale.ROOT, "%05d.ogg", i);
                    runFfmpeg(ffmpeg, source, stereo.resolve(segment),
                            startMs / 1000.0, lengthMs / 1000.0, stereoQuality, 2);
                    runFfmpeg(ffmpeg, source, mono.resolve(segment),
                            startMs / 1000.0, lengthMs / 1000.0, monoQuality, 1);
                }
                generatedPack = packBuilder.build(namespace, trackDirectory, segmentCount, segmentMs);
            } else {
                // Both continuous and resumable modes contain one OGG per channel layout.
                Path streamedAudio = trackDirectory.resolve("music.ogg");
                Path monoAudio = trackDirectory.resolve("mono.ogg");
                runFfmpegStreamed(ffmpeg, source, streamedAudio, stereoQuality, 2);
                runFfmpegStreamed(ffmpeg, source, monoAudio, monoQuality, 1);
                // Base the playback clock on the stereo encoding's actual duration.
                durationMs = probeDuration(ffmpeg, streamedAudio, data);
                if (durationMs <= 0) throw new IOException("The streamed OGG has an invalid duration.");
                segmentMs = (int) Math.min(Integer.MAX_VALUE, durationMs);
                segmentCount = 1;
                generatedPack = packBuilder.buildStreamed(namespace, streamedAudio, monoAudio);
            }

            // Audio files are build intermediates; the finished ZIP is retained.
            deleteTree(trackDirectory);
            trackDirectory = null;

            long libraryLimit = plugin.getConfig().getLong("limits.max-track-library-gb", 10)
                    * 1024L * 1024L * 1024L;
            long currentBytes = directorySize(data.resolve("tracks")) + directorySize(data.resolve("packs"));
            if (currentBytes > libraryLimit) {
                throw new IOException("The configured Musicolide audio-library size limit would be exceeded.");
            }

            // Publish a fresh immutable Java library snapshot before this track
            // becomes visible in the catalogue. Existing clients receive the new
            // snapshot only when they next need a track from the updated library.
            packBuilder.buildBasePack();
            basePackPublished = true;

            String sha1 = sha1(generatedPack);
            MusicTrack track = new MusicTrack(id, rawTitle, artist, namespace, generatedPack.getFileName().toString(),
                    sha1, durationMs, segmentMs, segmentCount, Files.size(generatedPack));
            if (temporaryUrlImport) {
                long retentionMinutes = bounded(
                        plugin.getConfig().getInt("audio.url-import-retention-minutes", 15), 1, 1440);
                long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(retentionMinutes);
                repository.saveTemporaryTrack(track, expiresAt);
            } else {
                repository.saveTrack(track);
            }
            monoVariantsReady.add(track.id());
            if (rebuildBedrockLibrary) {
                try {
                    packBuilder.rebuildBedrockLibrary();
                } catch (IOException bedrockError) {
                    plugin.getLogger().warning("Track imported, but the Geyser Bedrock library could not be refreshed: "
                            + bedrockError.getMessage());
                }
            }

            // Keep the original input if processing or catalogue persistence failed.
            if (plugin.getConfig().getBoolean("audio.delete-source-after-import", true)) {
                try {
                    Files.deleteIfExists(source);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Imported " + rawTitle
                            + " but could not remove its source file: " + ex.getMessage());
                }
            }
            return track;
        } catch (Exception ex) {
            deleteQuietly(trackDirectory);
            deleteQuietly(generatedPack);
            // The Java library is built before the catalogue write so a track is
            // never visible before its pack is ready. If that write fails, remove
            // the just-published orphan from the active library snapshot as well.
            if (basePackPublished) {
                try {
                    packBuilder.buildBasePack();
                } catch (Exception rollbackError) {
                    ex.addSuppressed(rollbackError);
                    plugin.getLogger().warning("Could not roll back the Java library after a failed import: "
                            + rollbackError.getMessage());
                }
            }
            throw new CompletionException(ex);
        }
    }

    private String validateCustomId(String requestedId) throws IOException {
        String normalized = normalizeCustomId(requestedId);
        if (normalized == null) return null;
        try {
            if (repository.getTrack(normalized).isPresent()) {
                throw new IOException("The custom track ID '" + normalized + "' is already in use.");
            }
        } catch (SQLException error) {
            throw new IOException("Could not check whether custom track ID '" + normalized + "' is already in use.", error);
        }
        return normalized;
    }

    private static String normalizeCustomId(String requestedId) throws IOException {
        if (requestedId == null || requestedId.isBlank()) return null;
        String normalized = requestedId.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (!SAFE_ID.matcher(normalized).matches() || normalized.length() > 45) {
            // The ms_/mc_ sound namespace prefix adds three characters; keep
            // the complete namespace within the same 48-character limit.
            throw new IOException("Custom track IDs must be 1-45 characters using lowercase letters, numbers, and underscores.");
        }
        return normalized;
    }

    private String configuredPlaybackMode() {
        String mode = plugin.getConfig().getString("audio.playback-mode", "resumable");
        mode = mode == null ? "resumable" : mode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("segmented", "continuous", "resumable").contains(mode)) {
            plugin.getLogger().warning("Unknown audio.playback-mode '" + mode
                    + "'; using resumable. Valid values: segmented, continuous, resumable.");
            return "resumable";
        }
        return mode;
    }

    /**
     * Builds a short-lived streamed resource pack containing the unplayed tail
     * of an imported track. Streamed tracks use their OGG directly; segmented
     * tracks are concatenated before extracting the requested timestamp.
     * This runs on the audio worker and is not saved to the catalogue or the
     * Bedrock aggregate pack.
     */
    /**
     * Ensures an older stereo-only import has a matching one-channel variant.
     * New imports already contain both variants, so they complete without re-encoding.
     */
    public CompletableFuture<MusicTrack> ensureMonoVariant(MusicTrack track) {
        if (track == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("A track is required for mono preparation."));
        if (monoVariantsReady.contains(track.id())) return CompletableFuture.completedFuture(track);
        CompletableFuture<MusicTrack> result = new CompletableFuture<>();
        CompletableFuture<MusicTrack> current = inFlightMonoVariants.putIfAbsent(track.id(), result);
        if (current != null) return current;
        try {
            worker.execute(() -> {
                try {
                    MusicTrack prepared = prepareMonoVariant(track);
                    monoVariantsReady.add(track.id());
                    result.complete(prepared);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                } finally {
                    inFlightMonoVariants.remove(track.id(), result);
                }
            });
        } catch (RejectedExecutionException error) {
            inFlightMonoVariants.remove(track.id(), result);
            result.completeExceptionally(new IllegalStateException(
                    "The Musicolide audio queue is full; mono preparation could not be queued.", error));
        }
        return result;
    }

    private MusicTrack prepareMonoVariant(MusicTrack track) throws Exception {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path packs = data.resolve("packs").normalize();
        Path sourcePack = packs.resolve(track.packFileName()).normalize();
        if (!sourcePack.startsWith(packs) || !Files.isRegularFile(sourcePack)) {
            throw new IOException("The original track resource pack is missing; mono audio cannot be prepared.");
        }
        boolean streamed = track.isStreamed();
        try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
            boolean complete = true;
            if (streamed) {
                ZipEntry monoEntry = zip.getEntry("assets/" + track.namespace() + "/sounds/mono.ogg");
                complete = monoEntry != null && !monoEntry.isDirectory() && monoEntry.getSize() > 0;
            } else {
                for (int i = 0; i < track.segmentCount(); i++) {
                    String id = String.format(Locale.ROOT, "%05d", i);
                    ZipEntry monoEntry = zip.getEntry("assets/" + track.namespace() + "/sounds/" + track.namespace()
                            + "/mono/" + id + ".ogg");
                    if (monoEntry == null || monoEntry.isDirectory() || monoEntry.getSize() <= 0) {
                        complete = false;
                        break;
                    }
                }
            }
            if (complete) {
                // Normally startup has already incorporated this archive into the
                // aggregate pack. If the stored checksum is stale, rebuild it once.
                MusicTrack refreshed = refreshPackMetadata(track, sourcePack);
                if (!refreshed.sha1Hex().equals(track.sha1Hex())
                        || refreshed.packSizeBytes() != track.packSizeBytes()) {
                    publishAudioVariants();
                }
                return refreshed;
            }
        }

        if (!toolchain.isInitialized() || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            throw new IllegalStateException("FFmpeg is not ready; mono audio cannot be generated yet.");
        }
        Path incoming = data.resolve("incoming");
        Files.createDirectories(incoming);
        Path work = Files.createTempDirectory(incoming, ".musicolide-mono-");
        try {
            String ffmpeg = toolchain.executable(NativeToolchain.Tool.FFMPEG);
            int quality = bounded(plugin.getConfig().getInt("audio.mono-quality",
                    plugin.getConfig().getInt("audio.stereo-quality", 5)), 0, 10);
            long maximumBytes = plugin.getConfig().getLong("audio.max-input-mb", 100) * 1024L * 1024L;
            Path monoDirectory = work.resolve("mono");
            Files.createDirectories(monoDirectory);
            if (streamed) {
                Path stereo = work.resolve("source.ogg");
                try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
                    copyZipEntry(zip, "assets/" + track.namespace() + "/sounds/music.ogg",
                            stereo, maximumBytes);
                }
                runFfmpegRechannel(ffmpeg, stereo, monoDirectory.resolve("mono.ogg"), quality, 1);
            } else {
                long extractedBytes = 0L;
                try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
                    for (int i = 0; i < track.segmentCount(); i++) {
                        String id = String.format(Locale.ROOT, "%05d", i);
                        Path stereo = work.resolve("stereo-" + id + ".ogg");
                        copyZipEntry(zip, "assets/" + track.namespace() + "/sounds/" + track.namespace()
                                + "/stereo/" + id + ".ogg", stereo, maximumBytes);
                        extractedBytes += Files.size(stereo);
                        if (extractedBytes > maximumBytes) {
                            throw new IOException("The source track exceeds the configured input-size limit.");
                        }
                        runFfmpegRechannel(ffmpeg, stereo, monoDirectory.resolve(id + ".ogg"), quality, 1);
                    }
                }
            }
            packBuilder.addMonoVariant(track.namespace(), sourcePack, monoDirectory, streamed, track.segmentCount());
            MusicTrack refreshed = refreshPackMetadata(track, sourcePack);
            publishAudioVariants();
            return refreshed;
        } finally {
            deleteQuietly(work);
        }
    }

    private void copyZipEntry(ZipFile zip, String entryName, Path destination, long maximumBytes)
            throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("The stereo source asset is missing: " + entryName);
        }
        if (entry.getSize() > maximumBytes) {
            throw new IOException("The stereo source asset exceeds the configured input-size limit.");
        }
        long copied = 0L;
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > maximumBytes) {
                    throw new IOException("The stereo source asset exceeds the configured input-size limit.");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private MusicTrack refreshPackMetadata(MusicTrack track, Path pack) throws Exception {
        String checksum = sha1(pack);
        long size = Files.size(pack);
        if (checksum.equals(track.sha1Hex()) && size == track.packSizeBytes()) return track;
        MusicTrack updated = new MusicTrack(track.id(), track.title(), track.artist(), track.namespace(),
                track.packFileName(), checksum, track.durationMs(), track.segmentMs(),
                track.segmentCount(), size);
        repository.updatePackMetadata(track.id(), checksum, size);
        return updated;
    }

    private void publishAudioVariants() throws IOException {
        packBuilder.buildBasePack();
        try {
            packBuilder.rebuildBedrockLibrary();
        } catch (IOException error) {
            plugin.getLogger().warning("Mono audio is ready for Java, but the Bedrock library could not be refreshed: "
                    + error.getMessage());
        }
    }

    public CompletableFuture<MusicTrack> createResumeTrack(MusicTrack sourceTrack, long startMs) {
        return createResumeTrack(sourceTrack, startMs, false);
    }

    /** Generate a mono tail only when the requesting session is mono. */
    public CompletableFuture<MusicTrack> createResumeTrack(MusicTrack sourceTrack, long startMs,
                                                            boolean includeMono) {
        if (sourceTrack == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "A source track is required for timestamp-based synchronization."));
        }
        if (!toolchain.isInitialized() || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFmpeg is not ready; timestamp-based resume cannot be generated yet."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return processResumeTrack(sourceTrack, startMs, includeMono);
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }, worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full; resume preparation could not be queued.", ex));
        }
    }

    private MusicTrack processResumeTrack(MusicTrack sourceTrack, long requestedStartMs,
                                          boolean includeMono) throws Exception {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path packs = data.resolve("packs").normalize();
        Path sourcePack = packs.resolve(sourceTrack.packFileName()).normalize();
        if (!sourcePack.startsWith(packs) || !Files.isRegularFile(sourcePack)) {
            throw new IOException("The original resource pack is missing; the remaining audio cannot be generated.");
        }
        long startMs = Math.max(0L, Math.min(sourceTrack.durationMs() - 50L, requestedStartMs));
        if (sourceTrack.durationMs() <= 0 || startMs >= sourceTrack.durationMs()) {
            throw new IOException("The track has already reached its end.");
        }

        Path incoming = data.resolve("incoming");
        Files.createDirectories(incoming);
        Path work = Files.createTempDirectory(incoming, ".musicolide-resume-");
        Path outputPack = null;
        try {
            String ffmpeg = toolchain.executable(NativeToolchain.Tool.FFMPEG);
            Path remainder = work.resolve("remainder.ogg");
            Path monoRemainder = includeMono ? work.resolve("mono-remainder.ogg") : null;
            int stereoQuality = bounded(plugin.getConfig().getInt("audio.stereo-quality", 5), 0, 10);
            int monoQuality = includeMono
                    ? bounded(plugin.getConfig().getInt("audio.mono-quality", stereoQuality), 0, 10)
                    : stereoQuality;
            if (sourceTrack.isStreamed()) {
                Path input = work.resolve("source.ogg");
                String entryName = "assets/" + sourceTrack.namespace() + "/sounds/music.ogg";
                try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null || entry.isDirectory()) {
                        throw new IOException("The original streamed OGG is missing from its resource pack.");
                    }
                    long maxBytes = plugin.getConfig().getLong("audio.max-input-mb", 100) * 1024L * 1024L;
                    if (entry.getSize() > maxBytes) {
                        throw new IOException("The streamed OGG exceeds the configured input-size limit.");
                    }
                    try (var stream = zip.getInputStream(entry)) {
                        Files.copy(stream, input, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                runFfmpegRemainder(ffmpeg, input, remainder, startMs, stereoQuality, 2);
                if (includeMono) {
                    runFfmpegRemainder(ffmpeg, input, monoRemainder, startMs, monoQuality, 1);
                }
            } else {
                Path concatList = work.resolve("segments.txt");
                List<String> entries = new ArrayList<>();
                long maximumBytes = plugin.getConfig().getLong("audio.max-input-mb", 100) * 1024L * 1024L;
                long extractedBytes = 0L;
                if (sourceTrack.segmentCount() <= 0) {
                    throw new IOException("The segmented resource pack contains no audio segments.");
                }
                int segmentDurationMs = Math.max(1, sourceTrack.segmentMs());
                int firstSegment = (int) Math.min(sourceTrack.segmentCount() - 1L,
                        startMs / segmentDurationMs);
                long offsetWithinFirstSegmentMs = startMs - (long) firstSegment * segmentDurationMs;
                try (ZipFile zip = new ZipFile(sourcePack.toFile())) {
                    for (int i = firstSegment; i < sourceTrack.segmentCount(); i++) {
                        String segmentId = String.format(Locale.ROOT, "%05d", i);
                        String entryName = "assets/" + sourceTrack.namespace() + "/sounds/"
                                + sourceTrack.namespace() + "/stereo/" + segmentId + ".ogg";
                        ZipEntry entry = zip.getEntry(entryName);
                        if (entry == null || entry.isDirectory()) {
                            throw new IOException("A segmented audio asset is missing: " + segmentId + ".ogg");
                        }
                        if (entry.getSize() > 0) {
                            extractedBytes += entry.getSize();
                            if (extractedBytes > maximumBytes) {
                                throw new IOException("The segmented audio exceeds the configured input-size limit.");
                            }
                        }
                        Path segmentFile = work.resolve("segment-" + segmentId + ".ogg");
                        try (var stream = zip.getInputStream(entry)) {
                            Files.copy(stream, segmentFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        String escapedPath = segmentFile.toAbsolutePath().toString()
                                .replace("\\", "\\\\").replace("'", "\\'");
                        entries.add("file '" + escapedPath + "'");
                    }
                }
                if (entries.isEmpty()) throw new IOException("The segmented resource pack contains no audio segments.");
                Files.writeString(concatList, String.join("\n", entries) + "\n", StandardCharsets.UTF_8);
                runFfmpegSegmentedRemainder(ffmpeg, concatList, remainder,
                        offsetWithinFirstSegmentMs, stereoQuality, 2);
                if (includeMono) {
                    runFfmpegSegmentedRemainder(ffmpeg, concatList, monoRemainder,
                            offsetWithinFirstSegmentMs, monoQuality, 1);
                }
            }
            long durationMs = probeDuration(ffmpeg, remainder, data);
            if (durationMs <= 0) throw new IOException("FFmpeg produced an empty audio remainder.");

            String token = UUID.randomUUID().toString().replace("-", "");
            String namespace = "mr_" + token;
            outputPack = includeMono
                    ? packBuilder.buildStreamed(namespace, remainder, monoRemainder)
                    : packBuilder.buildStreamed(namespace, remainder);
            String id = "resume_" + token;
            String checksum = sha1(outputPack);
            int remainderDuration = (int) Math.min(Integer.MAX_VALUE, durationMs);
            return new MusicTrack(id, sourceTrack.title(), sourceTrack.artist(), namespace,
                    outputPack.getFileName().toString(), checksum, durationMs,
                    Math.max(1, remainderDuration), 1, Files.size(outputPack));
        } catch (Exception ex) {
            deleteQuietly(outputPack);
            throw ex;
        } finally {
            deleteQuietly(work);
        }
    }

    private void runFfmpegRemainder(String executable, Path source, Path output, long startMs,
                                    int quality, int channels)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        // Output-side -ss decodes from the beginning before cutting, prioritizing
        // an accurate resume point over fast keyframe seeking.
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error",
                "-nostdin", "-y", "-i", source.toString(),
                "-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0),
                "-map_metadata", "-1", "-map_chapters", "-1", "-vn", "-sn", "-dn",
                "-map", "0:a:0", "-ac", Integer.toString(channels), "-ar", Integer.toString(sampleRate),
                "-c:a", "libvorbis", "-q:a", Integer.toString(quality),
                "-threads", Integer.toString(threads), output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD));
        int timeout = bounded(plugin.getConfig().getInt("audio.processing.timeout-seconds", 1800), 10, 7200);
        try {
            waitForProcess(process, timeout, "FFmpeg resume-tail encoding");
        } finally {
            toolchain.finished(process);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg completed without producing the resumed OGG.");
        }
    }

    private void runFfmpegSegmentedRemainder(String executable, Path concatList, Path output,
                                               long startMs, int quality, int channels)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error",
                "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", concatList.toString(),
                "-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0),
                "-map_metadata", "-1", "-map_chapters", "-1", "-vn", "-sn", "-dn",
                "-map", "0:a:0", "-ac", Integer.toString(channels), "-ar", Integer.toString(sampleRate),
                "-c:a", "libvorbis", "-q:a", Integer.toString(quality),
                "-threads", Integer.toString(threads), output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD));
        int timeout = bounded(plugin.getConfig().getInt("audio.processing.timeout-seconds", 1800), 10, 7200);
        try {
            waitForProcess(process, timeout, "FFmpeg segmented synchronization-tail encoding");
        } finally {
            toolchain.finished(process);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg completed without producing the synchronized OGG remainder.");
        }
    }

    private long probeDuration(String executable, Path source, Path dataDirectory)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile(dataDirectory, ".musicolide-probe-", ".txt");
        try {
            // FFmpeg reports container duration while opening the input. A zero-length
            // null output lets us read that metadata without decoding the whole track.
            Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-nostdin",
                    "-i", source.toString(), "-t", "0", "-f", "null", "-")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile()));
            try {
                waitForProcess(process, bounded(plugin.getConfig().getInt(
                        "audio.processing.probe-timeout-seconds", 30), 5, 300), "FFmpeg metadata probe");
            } finally {
                toolchain.finished(process);
            }
            return FfmpegDurationParser.parseMillis(Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void runFfmpegStreamed(String executable, Path source, Path output, int quality, int channels)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-map_metadata", "-1", "-map_chapters", "-1",
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-ac", Integer.toString(channels),
                "-ar", Integer.toString(sampleRate), "-c:a", "libvorbis",
                "-q:a", Integer.toString(quality), "-threads", Integer.toString(threads),
                output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD));

        int timeout = bounded(plugin.getConfig().getInt("audio.processing.timeout-seconds", 1800), 10, 7200);
        try {
            waitForProcess(process, timeout, "FFmpeg streamed-track encoding");
        } finally {
            toolchain.finished(process);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg completed without producing the streamed OGG file.");
        }
    }

    private void runFfmpeg(String executable, Path source, Path output, double start,
                           double length, int quality, int channels) throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", start),
                "-t", String.format(Locale.ROOT, "%.3f", length),
                "-i", source.toString(), "-map_metadata", "-1", "-map_chapters", "-1",
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-ac", Integer.toString(channels),
                "-ar", Integer.toString(sampleRate), "-c:a", "libvorbis",
                "-q:a", Integer.toString(quality), "-threads", Integer.toString(threads),
                output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD));

        int timeout = bounded(plugin.getConfig().getInt("audio.processing.timeout-seconds", 1800), 10, 7200);
        try {
            waitForProcess(process, timeout, "FFmpeg");
        } finally {
            toolchain.finished(process);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg completed without producing a valid OGG segment.");
        }
    }

    private void runFfmpegRechannel(String executable, Path source, Path output, int quality, int channels)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-map_metadata", "-1", "-map_chapters", "-1",
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-ac", Integer.toString(channels),
                "-ar", Integer.toString(sampleRate), "-c:a", "libvorbis",
                "-q:a", Integer.toString(quality), "-threads", Integer.toString(threads), output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD));
        int timeout = bounded(plugin.getConfig().getInt("audio.processing.timeout-seconds", 1800), 10, 7200);
        try {
            waitForProcess(process, timeout, "FFmpeg mono variant encoding");
        } finally {
            toolchain.finished(process);
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg completed without producing a valid mono OGG.");
        }
    }

    private void waitForProcess(Process process, int timeoutSeconds, String label)
            throws IOException, InterruptedException {
        final boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            toolchain.terminate(process);
            Thread.currentThread().interrupt();
            throw ex;
        }
        if (!finished) {
            toolchain.terminate(process);
            throw new IOException(label + " exceeded its " + timeoutSeconds + " second timeout.");
        }
        if (process.exitValue() != 0) {
            throw new IOException(label + " exited with code " + process.exitValue() + ".");
        }
    }

    private long directorySize(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0L;
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (IOException ex) { return 0L; }
            }).sum();
        }
    }

    private String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "track" : slug.substring(0, Math.min(32, slug.length()));
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { deleteTree(path); }
        catch (IOException ex) {
            plugin.getLogger().warning("Could not clean temporary audio data at " + path + ": " + ex.getMessage());
        }
    }

    private String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
        worker.shutdownNow();
        toolchain.close();
    }
}
