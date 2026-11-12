package io.github.escolide.musicolide.audio;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.resource.ResourcePackBuilder;
import io.github.escolide.musicolide.storage.TrackRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public final class AudioProcessor implements AutoCloseable {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_]{1,48}");

    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final ResourcePackBuilder packBuilder;
    private final NativeToolchain toolchain;
    private final ThreadPoolExecutor worker;

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
        if (!toolchain.available(NativeToolchain.Tool.FFPROBE)
                || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFprobe and FFmpeg must be installed or bundled before audio can be imported."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> processFile(fileName, requestedTitle), worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current import to finish and try again.", ex));
        }
    }

    /**
     * Downloads one administrator-authorized URL into a disposable directory, then
     * uses the same local validation/encoding path as a file import. yt-dlp exits
     * before FFprobe or FFmpeg is launched.
     */
    public CompletableFuture<MusicTrack> importUrl(String sourceUrl, String requestedTitle) {
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
        if (!toolchain.available(NativeToolchain.Tool.FFPROBE)
                || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FFprobe and FFmpeg must be installed or bundled before audio can be imported."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                Path incoming = plugin.getDataFolder().toPath().toAbsolutePath().normalize().resolve("incoming");
                Path temporary = null;
                try {
                    Files.createDirectories(incoming);
                    temporary = Files.createTempDirectory(incoming, ".musicolide-url-");
                    Path downloaded = new YtDlpDownloader(plugin, toolchain).download(sourceUrl, temporary);
                    String relative = incoming.relativize(downloaded).toString();
                    return processFile(relative, requestedTitle);
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                } finally {
                    deleteQuietly(temporary);
                }
            }, worker);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The Musicolide audio queue is full. Wait for the current import to finish and try again.", ex));
        }
    }

    public List<NativeToolchain.ToolStatus> toolStatuses() {
        return toolchain.statuses();
    }

    private MusicTrack processFile(String fileName, String requestedTitle) {
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
            String ffprobe = toolchain.executable(NativeToolchain.Tool.FFPROBE);
            long durationMs = probeDuration(ffprobe, source, data);
            long maxDuration = plugin.getConfig().getLong("audio.max-track-duration-seconds", 1800);
            if (durationMs <= 0 || durationMs > maxDuration * 1000L) {
                throw new IOException("Track duration is invalid or exceeds " + maxDuration + " seconds.");
            }

            String rawTitle = requestedTitle == null || requestedTitle.isBlank()
                    ? stripExtension(source.getFileName().toString()) : requestedTitle.trim();
            String id = slug(rawTitle) + "-" + UUID.randomUUID().toString().substring(0, 8);
            String namespace = "mc_" + id.replace('-', '_');
            if (!SAFE_ID.matcher(namespace).matches()) throw new IOException("Generated track identifier is invalid.");

            int segmentMs = Math.max(500, plugin.getConfig().getInt("audio.segment-duration-ms", 1000));
            int count = (int) Math.ceil(durationMs / (double) segmentMs);
            trackDirectory = data.resolve("tracks").resolve(id);
            Path stereoDirectory = trackDirectory.resolve("stereo");
            Files.createDirectories(stereoDirectory);

            int quality = bounded(plugin.getConfig().getInt("audio.stereo-quality", 5), 0, 10);
            for (int i = 0; i < count; i++) {
                double start = i * segmentMs / 1000.0;
                double length = Math.min(segmentMs, durationMs - (long) i * segmentMs) / 1000.0;
                String name = String.format(Locale.ROOT, "%05d.ogg", i);
                runFfmpeg(ffmpeg, source, stereoDirectory.resolve(name), start, length, quality);
            }

            generatedPack = packBuilder.build(namespace, trackDirectory, count, segmentMs);

            // Segment OGGs are build intermediates and must not count against retained library storage.
            deleteTree(trackDirectory);
            trackDirectory = null;

            long libraryLimit = plugin.getConfig().getLong("limits.max-track-library-gb", 10)
                    * 1024L * 1024L * 1024L;
            long currentBytes = directorySize(data.resolve("tracks")) + directorySize(data.resolve("packs"));
            if (currentBytes > libraryLimit) {
                throw new IOException("The configured Musicolide audio-library size limit would be exceeded.");
            }

            String sha1 = sha1(generatedPack);
            MusicTrack track = new MusicTrack(id, rawTitle, "", namespace, generatedPack.getFileName().toString(),
                    sha1, durationMs, segmentMs, count, Files.size(generatedPack));
            repository.saveTrack(track);
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

    private long probeDuration(String executable, Path source, Path dataDirectory)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile(dataDirectory, ".musicolide-probe-", ".txt");
        try {
            Process process = toolchain.start(new ProcessBuilder(executable, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", source.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile()));
            try {
                waitForProcess(process, bounded(plugin.getConfig().getInt(
                        "audio.processing.probe-timeout-seconds", 30), 5, 300), "FFprobe");
            } finally {
                toolchain.finished(process);
            }
            String result = Files.readString(output, StandardCharsets.UTF_8).trim();
            try {
                return Math.round(Double.parseDouble(result) * 1000.0);
            } catch (NumberFormatException ex) {
                throw new IOException("FFprobe returned an invalid duration.", ex);
            }
        } finally {
            Files.deleteIfExists(output);
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
