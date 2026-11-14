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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
        if (!toolchain.isInitialized()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Native audio tools are still being checked during startup. Try again in a moment."));
        }
        if (!toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFmpeg must be installed or downloaded before audio can be imported."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> processFile(fileName, requestedTitle, false), worker);
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
    public CompletableFuture<MusicTrack> importUrl(String sourceUrl, String requestedTitle) {
        String sourceKey = LinkNormalizer.normalize(sourceUrl).orElse("");
        if (sourceKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The source URL is invalid."));
        }

        // Check the persistent alias before checking native tools or queueing a job.
        // A previously imported video must not be downloaded again just because
        // yt-dlp is currently unavailable or a second player pasted another URL variant.
        try {
            Optional<MusicTrack> existing = repository.resolveAlias(sourceKey);
            if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
        } catch (SQLException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Could not check the existing URL catalogue entry.", ex));
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
                Path temporary = null;
                try {
                    Path incoming = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("incoming");
                    Files.createDirectories(incoming);
                    temporary = Files.createTempDirectory(incoming, ".musicolide-url-");
                    YtDlpDownloader.DownloadResult download =
                            new YtDlpDownloader(plugin, toolchain).download(sourceUrl, temporary);
                    String relative = incoming.relativize(download.audioFile()).toString();
                    MusicTrack imported = processFile(relative, requestedTitle, true,
                            download.title(), download.artist());

                    // LinkNormalizer maps YouTube watch, youtu.be, music.youtube.com,
                    // and Shorts URLs to the same youtube:<video-id> key.
                    try {
                        repository.addAlias(sourceKey, imported.id());
                        String extractor = download.extractor() == null ? "" : download.extractor().toLowerCase(Locale.ROOT);
                        String sourceId = download.sourceId() == null ? "" : download.sourceId().trim();
                        if (!sourceId.isBlank() && extractor.contains("youtube")) {
                            // The extractor-provided ID is authoritative if a future
                            // YouTube URL form is not recognized by LinkNormalizer.
                            repository.addAlias("youtube:" + sourceId, imported.id());
                        }
                    } catch (SQLException aliasError) {
                        plugin.getLogger().warning("Imported " + imported.title()
                                + " but could not save its source-ID alias: " + aliasError.getMessage());
                    }
                    result.complete(imported);
                } catch (Throwable ex) {
                    result.completeExceptionally(ex instanceof CompletionException && ex.getCause() != null
                            ? ex.getCause() : ex);
                } finally {
                    deleteQuietly(temporary);
                    inFlightUrlImports.remove(sourceKey, result);
                }
            });
        } catch (RejectedExecutionException ex) {
            inFlightUrlImports.remove(sourceKey, result);
            result.completeExceptionally(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current import to finish and try again.", ex));
        }
        return result;
    }

    public List<NativeToolchain.ToolStatus> toolStatuses() {
        return toolchain.statuses();
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport) {
        return processFile(fileName, requestedTitle, temporaryUrlImport, "", "");
    }

    private MusicTrack processFile(String fileName, String requestedTitle, boolean temporaryUrlImport,
                                   String metadataTitle, String metadataArtist) {
        Path trackDirectory = null;
        Path generatedPack = null;
        Path source = null;
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

            String rawTitle = requestedTitle != null && !requestedTitle.isBlank() ? requestedTitle.trim()
                    : metadataTitle != null && !metadataTitle.isBlank() ? metadataTitle.trim()
                    : stripExtension(source.getFileName().toString());
            String artist = metadataArtist == null ? "" : metadataArtist.trim();
            String id = slug(rawTitle) + "-" + UUID.randomUUID().toString().substring(0, 8);
            // The ms_ namespace marks the new single-file streaming format. The
            // older mc_ namespace remains readable for tracks imported with segments.
            String playbackMode = configuredPlaybackMode();
            boolean segmented = playbackMode.equals("segmented");
            String namespace = (segmented ? "mc_" : "ms_") + id.replace('-', '_');
            if (!SAFE_ID.matcher(namespace).matches()) throw new IOException("Generated track identifier is invalid.");

            trackDirectory = data.resolve("tracks").resolve(id);
            Files.createDirectories(trackDirectory);
            int quality = bounded(plugin.getConfig().getInt("audio.stereo-quality", 5), 0, 10);
            int segmentMs;
            int segmentCount;
            if (segmented) {
                // Legacy mode: encode independently playable OGG chunks.
                segmentMs = bounded(plugin.getConfig().getInt("audio.segment-duration-ms", 1000), 250, 60000);
                segmentCount = (int) Math.max(1L, (durationMs + segmentMs - 1L) / segmentMs);
                Path stereo = trackDirectory.resolve("stereo");
                Files.createDirectories(stereo);
                for (int i = 0; i < segmentCount; i++) {
                    long startMs = (long) i * segmentMs;
                    long lengthMs = Math.min(segmentMs, durationMs - startMs);
                    runFfmpeg(ffmpeg, source, stereo.resolve(String.format(Locale.ROOT, "%05d.ogg", i)),
                            startMs / 1000.0, lengthMs / 1000.0, quality);
                }
                generatedPack = packBuilder.build(namespace, trackDirectory, segmentCount, segmentMs);
            } else {
                // Both continuous and resumable modes start as one streamed OGG.
                // Resumable mode regenerates a timestamped remainder when resumed.
                Path streamedAudio = trackDirectory.resolve("music.ogg");
                runFfmpegStreamed(ffmpeg, source, streamedAudio, quality);
                // Base the playback clock on the encoded OGG's actual duration.
                durationMs = probeDuration(ffmpeg, streamedAudio, data);
                if (durationMs <= 0) throw new IOException("The streamed OGG has an invalid duration.");
                segmentMs = (int) Math.min(Integer.MAX_VALUE, durationMs);
                segmentCount = 1;
                generatedPack = packBuilder.buildStreamed(namespace, streamedAudio);
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

            // Keep the startup library snapshot immutable. Players who need this
            // newly imported track receive only this track's own incremental pack.
            // The next server restart can fold it into a new startup snapshot.
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
            try {
                packBuilder.rebuildBedrockLibrary();
            } catch (IOException bedrockError) {
                plugin.getLogger().warning("Track imported, but the Geyser Bedrock library could not be refreshed: "
                        + bedrockError.getMessage());
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
            throw new CompletionException(ex);
        }
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
     * of an imported streamed track. This runs on the audio worker, never the
     * Minecraft server thread. The returned track is intentionally not saved
     * to the catalogue or the Bedrock aggregate pack.
     */
    public CompletableFuture<MusicTrack> createResumeTrack(MusicTrack sourceTrack, long startMs) {
        if (sourceTrack == null || !sourceTrack.isStreamed()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Only continuous streamed tracks can use timestamp-based resume."));
        }
        if (!toolchain.isInitialized() || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFmpeg is not ready; timestamp-based resume cannot be generated yet."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return processResumeTrack(sourceTrack, startMs);
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }, worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full; resume preparation could not be queued.", ex));
        }
    }

    private MusicTrack processResumeTrack(MusicTrack sourceTrack, long requestedStartMs) throws Exception {
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

            String ffmpeg = toolchain.executable(NativeToolchain.Tool.FFMPEG);
            Path remainder = work.resolve("remainder.ogg");
            runFfmpegRemainder(ffmpeg, input, remainder, startMs, 
                    bounded(plugin.getConfig().getInt("audio.stereo-quality", 5), 0, 10));
            long durationMs = probeDuration(ffmpeg, remainder, data);
            if (durationMs <= 0) throw new IOException("FFmpeg produced an empty audio remainder.");

            String token = UUID.randomUUID().toString().replace("-", "");
            String namespace = "mr_" + token;
            outputPack = packBuilder.buildStreamed(namespace, remainder);
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

    private void runFfmpegRemainder(String executable, Path source, Path output, long startMs, int quality)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        // Output-side -ss decodes from the beginning before cutting, prioritizing
        // an accurate resume point over fast keyframe seeking.
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error",
                "-nostdin", "-y", "-i", source.toString(),
                "-ss", String.format(Locale.ROOT, "%.3f", startMs / 1000.0),
                "-vn", "-map", "0:a:0", "-ac", "2", "-ar", Integer.toString(sampleRate),
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

    private void runFfmpegStreamed(String executable, Path source, Path output, int quality)
            throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-vn", "-map", "0:a:0", "-ac", "2",
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
                           double length, int quality) throws IOException, InterruptedException {
        int sampleRate = bounded(plugin.getConfig().getInt("audio.sample-rate", 44100), 8000, 192000);
        int threads = bounded(plugin.getConfig().getInt("audio.processing.ffmpeg-threads", 1), 1, 4);
        Process process = toolchain.start(new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", start),
                "-t", String.format(Locale.ROOT, "%.3f", length),
                "-i", source.toString(), "-vn", "-map", "0:a:0", "-ac", "2",
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
