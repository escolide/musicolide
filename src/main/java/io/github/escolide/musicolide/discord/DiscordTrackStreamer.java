package io.github.escolide.musicolide.discord;

import io.github.escolide.musicolide.audio.NativeToolchain;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.playback.DiscordPlaybackSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decodes the exact imported Musicolide archive into bounded PCM frames.
 * It never touches Bukkit playback state and never downloads or imports audio.
 */
final class DiscordTrackStreamer implements AutoCloseable {
    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9_]{1,48}");
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L;

    private final NativeToolchain toolchain;
    private final Path dataDirectory;
    private final Path packsDirectory;
    private final Path temporaryDirectory;
    private final DiscordAudioSendHandler sendHandler;
    private final Logger logger;
    private final AtomicLong generation = new AtomicLong();

    private volatile Process process;
    private volatile Thread worker;
    private volatile long startPositionMs;
    private volatile double playbackSpeed = 1.0;
    private volatile boolean closed;

    DiscordTrackStreamer(NativeToolchain toolchain, Path dataDirectory,
                         DiscordAudioSendHandler sendHandler, Logger logger) {
        this.toolchain = toolchain;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.packsDirectory = this.dataDirectory.resolve("packs").normalize();
        this.temporaryDirectory = this.dataDirectory.resolve("temp").resolve("discord-broadcast").normalize();
        this.sendHandler = sendHandler;
        this.logger = logger;
    }

    synchronized void start(DiscordPlaybackSnapshot initial,
                            Supplier<Optional<DiscordPlaybackSnapshot>> latestSnapshot) {
        stopCurrent();
        if (closed || initial == null || !initial.isAudible()) return;
        long token = generation.incrementAndGet();
        Thread thread = new Thread(() -> decode(token, initial, latestSnapshot),
                "Musicolide-Discord-Decoder");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    synchronized void stop() {
        stopCurrent();
        generation.incrementAndGet();
    }

    private void stopCurrent() {
        sendHandler.reset();
        Process active = process;
        process = null;
        if (active != null) {
            try {
                active.destroy();
                if (active.isAlive()) active.destroyForcibly();
            } catch (Exception ignored) {
                // Discord playback is an optional output; never propagate into Minecraft.
            }
        }
        Thread activeWorker = worker;
        worker = null;
        if (activeWorker != null && activeWorker != Thread.currentThread()) activeWorker.interrupt();
    }

    private void decode(long token, DiscordPlaybackSnapshot initial,
                        Supplier<Optional<DiscordPlaybackSnapshot>> latestSnapshot) {
        Path workDirectory = null;
        Process started = null;
        try {
            if (!toolchain.isInitialized() || !toolchain.available(NativeToolchain.Tool.FFMPEG)) {
                throw new IOException("FFmpeg is not available for Discord audio output.");
            }
            Files.createDirectories(temporaryDirectory);
            workDirectory = Files.createTempDirectory(temporaryDirectory, "track-");
            Path input = prepareInput(initial.track(), workDirectory);

            Optional<DiscordPlaybackSnapshot> refreshed = latestSnapshot.get();
            if (refreshed.isEmpty() || !sameTimeline(initial, refreshed.get())
                    || !refreshed.get().isAudible() || !isCurrent(token)) {
                return;
            }
            DiscordPlaybackSnapshot current = refreshed.get();
            long offsetMs = Math.max(0L, Math.min(current.track().durationMs(), current.elapsedMs()));
            double speed = clampSpeed(current.speed());
            startPositionMs = offsetMs;
            playbackSpeed = speed;
            sendHandler.reset();

            List<String> command = new ArrayList<>();
            command.add(toolchain.executable(NativeToolchain.Tool.FFMPEG));
            command.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
            if (!current.track().isStreamed()) command.addAll(List.of("-f", "concat", "-safe", "0"));
            command.addAll(List.of(
                    "-i", input.toString(),
                    "-ss", String.format(Locale.ROOT, "%.3f", offsetMs / 1000.0),
                    "-map", "0:a:0", "-vn", "-sn", "-dn",
                    "-af", "atempo=" + String.format(Locale.ROOT, "%.4f", speed),
                    "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16be",
                    "-f", "s16be", "pipe:1"
            ));

            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            started = toolchain.start(builder);
            if (!isCurrent(token)) {
                started.destroyForcibly();
                toolchain.finished(started);
                return;
            }
            process = started;

            try (InputStream audio = started.getInputStream()) {
                byte[] frame = new byte[DiscordAudioSendHandler.FRAME_BYTES];
                while (isCurrent(token) && !Thread.currentThread().isInterrupted()) {
                    int count = readFrame(audio, frame);
                    if (count == 0) break;
                    if (count != frame.length) break;
                    if (!sendHandler.offer(frame.clone(), token, generation::get)) break;
                }
            }

            if (isCurrent(token) && started.isAlive()) {
                if (!started.waitFor(2, TimeUnit.SECONDS)) {
                    started.destroyForcibly();
                    started.waitFor(1, TimeUnit.SECONDS);
                }
            }
            if (isCurrent(token) && !started.isAlive() && started.exitValue() != 0) {
                logger.warning("Discord audio decoder exited with code " + started.exitValue()
                        + "; Minecraft playback is continuing independently.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            if (isCurrent(token)) {
                logger.log(Level.WARNING, "Discord audio output could not be prepared: "
                        + safeMessage(ex) + ". Minecraft playback is unaffected.");
            }
        } finally {
            if (started != null) {
                if (started.isAlive()) started.destroyForcibly();
                toolchain.finished(started);
                if (process == started) process = null;
            }
            deleteTreeQuietly(workDirectory);
            if (isCurrent(token) && worker == Thread.currentThread()) {
                worker = null;
            }
        }
    }

    private Path prepareInput(MusicTrack track, Path workDirectory) throws IOException {
        if (track == null || !SAFE_NAMESPACE.matcher(track.namespace()).matches()) {
            throw new IOException("The active track metadata is invalid.");
        }
        String packName = track.packFileName();
        if (packName == null || packName.isBlank() || Path.of(packName).getNameCount() != 1
                || packName.contains("..")) {
            throw new IOException("The active track pack filename is invalid.");
        }
        Path archive = packsDirectory.resolve(packName).normalize();
        if (!archive.startsWith(packsDirectory) || !Files.isRegularFile(archive)) {
            throw new IOException("The active track resource-pack archive is missing.");
        }

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (track.isStreamed()) {
                String entryName = "assets/" + track.namespace() + "/sounds/music.ogg";
                Path output = workDirectory.resolve("music.ogg");
                extract(zip, entryName, output, 0L);
                return output;
            }

            int count = track.segmentCount();
            if (count < 1 || count > 10_000) {
                throw new IOException("The active track has an invalid segment count.");
            }
            StringBuilder concat = new StringBuilder("ffconcat version 1.0\n");
            long extracted = 0L;
            for (int index = 0; index < count; index++) {
                String segment = String.format(Locale.ROOT, "%05d", index);
                String entryName = "assets/" + track.namespace() + "/sounds/"
                        + track.namespace() + "/stereo/" + segment + ".ogg";
                Path output = workDirectory.resolve("segment-" + segment + ".ogg");
                extracted = extract(zip, entryName, output, extracted);
                concat.append("file '").append(escapeConcatPath(output.toAbsolutePath().toString()))
                        .append("'\n");
            }
            Path concatFile = workDirectory.resolve("segments.ffconcat");
            Files.writeString(concatFile, concat, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return concatFile;
        }
    }

    private long extract(ZipFile zip, String name, Path output, long alreadyExtracted) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("The active track archive is incomplete.");
        long declared = entry.getSize();
        if (declared > MAX_EXTRACTED_BYTES || (declared >= 0 && alreadyExtracted + declared > MAX_EXTRACTED_BYTES)) {
            throw new IOException("The active track archive exceeds the Discord extraction safety limit.");
        }
        long copied = 0L;
        try (InputStream input = zip.getInputStream(entry);
             var target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied + alreadyExtracted > MAX_EXTRACTED_BYTES) {
                    throw new IOException("The active track archive exceeds the Discord extraction safety limit.");
                }
                target.write(buffer, 0, read);
            }
        }
        if (copied == 0) throw new IOException("The active track archive contains an empty audio asset.");
        return alreadyExtracted + copied;
    }

    private String escapeConcatPath(String path) {
        return path.replace("\\", "\\\\").replace("'", "'\\''");
    }

    private int readFrame(InputStream input, byte[] frame) throws IOException {
        int offset = 0;
        while (offset < frame.length) {
            int read = input.read(frame, offset, frame.length - offset);
            if (read < 0) return offset;
            if (read == 0) continue;
            offset += read;
        }
        return offset;
    }

    private boolean isCurrent(long token) {
        return !closed && generation.get() == token;
    }

    private boolean sameTimeline(DiscordPlaybackSnapshot first, DiscordPlaybackSnapshot second) {
        return first.sessionId().equals(second.sessionId())
                && first.timelineGeneration() == second.timelineGeneration()
                && first.track() != null && second.track() != null
                && first.track().id().equals(second.track().id());
    }

    private double clampSpeed(double speed) {
        if (!Double.isFinite(speed)) return 1.0;
        return Math.max(0.5, Math.min(2.0, speed));
    }

    long estimatedPositionMs() {
        return startPositionMs + Math.round(sendHandler.providedFrames() * 20.0 * playbackSpeed);
    }

    boolean isActive() {
        Thread activeWorker = worker;
        return activeWorker != null && activeWorker.isAlive();
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private void deleteTreeQuietly(Path path) {
        if (path == null) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try { Files.deleteIfExists(entry); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
            // Startup cleanup removes abandoned decoder work directories.
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        stopCurrent();
        generation.incrementAndGet();
    }
}
