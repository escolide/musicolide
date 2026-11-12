package io.github.escolide.musicolide.audio;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.resource.ResourcePackBuilder;
import io.github.escolide.musicolide.storage.TrackRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class AudioProcessor implements AutoCloseable {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_]{1,48}");
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final ResourcePackBuilder packBuilder;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "Musicolide-AudioWorker");
        thread.setDaemon(true);
        return thread;
    });

    public AudioProcessor(JavaPlugin plugin, TrackRepository repository, ResourcePackBuilder packBuilder) {
        this.plugin = plugin;
        this.repository = repository;
        this.packBuilder = packBuilder;
    }

    public CompletableFuture<MusicTrack> importFile(String fileName, String requestedTitle) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path incoming = plugin.getDataFolder().toPath().resolve("incoming").toAbsolutePath().normalize();
                Path source = incoming.resolve(fileName).normalize();
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
                String ffmpeg = plugin.getConfig().getString("audio.ffmpeg.executable", "ffmpeg");
                String ffprobe = plugin.getConfig().getString("audio.ffmpeg.ffprobe-executable", "ffprobe");
                long durationMs = probeDuration(ffprobe, source);
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
                Path trackDir = plugin.getDataFolder().toPath().resolve("tracks").resolve(id);
                Path stereoDir = trackDir.resolve("stereo");
                Path monoDir = trackDir.resolve("mono");
                Files.createDirectories(stereoDir);
                Files.createDirectories(monoDir);
                int stereoQuality = plugin.getConfig().getInt("audio.stereo-quality", 5);
                int monoQuality = plugin.getConfig().getInt("audio.mono-quality", 5);
                for (int i = 0; i < count; i++) {
                    double start = i * segmentMs / 1000.0;
                    double length = Math.min(segmentMs, durationMs - (long) i * segmentMs) / 1000.0;
                    String name = String.format(Locale.ROOT, "%05d.ogg", i);
                    runFfmpeg(ffmpeg, source, stereoDir.resolve(name), start, length, 2, stereoQuality);
                    runFfmpeg(ffmpeg, source, monoDir.resolve(name), start, length, 1, monoQuality);
                }
                Path pack = packBuilder.build(namespace, trackDir, count, segmentMs);
                long libraryLimit = plugin.getConfig().getLong("limits.max-track-library-gb", 10) * 1024L * 1024L * 1024L;
                long currentBytes = 0;
                Path tracksRoot = plugin.getDataFolder().toPath().resolve("tracks");
                try (var walk = Files.walk(tracksRoot)) {
                    currentBytes = walk.filter(Files::isRegularFile).mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException ignored) { return 0L; }
                    }).sum();
                }
                Path packsRoot = plugin.getDataFolder().toPath().resolve("packs");
                try (var walk = Files.walk(packsRoot)) {
                    currentBytes += walk.filter(Files::isRegularFile).mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException ignored) { return 0L; }
                    }).sum();
                }
                if (currentBytes > libraryLimit) {
                    deleteTree(trackDir);
                    Files.deleteIfExists(pack);
                    throw new IOException("The configured Musicolide audio-library size limit would be exceeded.");
                }
                String sha1 = sha1(pack);
                MusicTrack track = new MusicTrack(id, rawTitle, "", namespace, pack.getFileName().toString(),
                        sha1, durationMs, segmentMs, count, Files.size(pack));
                repository.saveTrack(track);
                return track;
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, worker);
    }

    private long probeDuration(String executable, Path source) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", source.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IOException("FFprobe failed: " + output);
        try { return Math.round(Double.parseDouble(output) * 1000.0); }
        catch (NumberFormatException ex) { throw new IOException("FFprobe returned an invalid duration: " + output, ex); }
    }

    private void runFfmpeg(String executable, Path source, Path output, double start, double length,
                           int channels, int quality) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", start), "-t", String.format(Locale.ROOT, "%.3f", length),
                "-i", source.toString(), "-vn", "-map", "0:a:0", "-ac", Integer.toString(channels),
                "-ar", Integer.toString(plugin.getConfig().getInt("audio.sample-rate", 44100)),
                "-c:a", "libvorbis", "-q:a", Integer.toString(Math.max(0, Math.min(10, quality))),
                output.toString()).redirectErrorStream(true).start();
        String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg failed: " + outputText);
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
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    @Override public void close() { worker.shutdownNow(); }
}
