package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves optional binaries embedded at native/<platform>/ or installed on PATH.
 * Binary payloads are deliberately not committed to this repository; packagers can
 * place license-cleared builds under src/main/native/<platform>/ before building.
 */
public final class NativeToolchain implements AutoCloseable {
    public enum Tool {
        FFMPEG("ffmpeg", "audio.ffmpeg.executable", "-version"),
        FFPROBE("ffprobe", "audio.ffmpeg.ffprobe-executable", "-version"),
        YT_DLP("yt-dlp", "audio.yt-dlp.executable", "--version");

        private final String fileName;
        private final String configPath;
        private final String versionArgument;

        Tool(String fileName, String configPath, String versionArgument) {
            this.fileName = fileName;
            this.configPath = configPath;
            this.versionArgument = versionArgument;
        }
    }

    public record ToolStatus(Tool tool, String executable, boolean available, String version, String detail) {}

    private final JavaPlugin plugin;
    private final Path dataDirectory;
    private final Path binaryDirectory;
    private final String platform;
    private final Map<Tool, String> executables = new EnumMap<>(Tool.class);
    private final Map<Tool, ToolStatus> statuses = new EnumMap<>(Tool.class);
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized;
    private volatile boolean closed;

    public NativeToolchain(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.binaryDirectory = dataDirectory.resolve("native");
        this.platform = platformKey(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Runs executable extraction and version probes away from the Bukkit main thread.
     * The common pool uses daemon workers; close() prevents any later child process
     * from being started if the plugin is disabled during initialization.
     */
    public CompletableFuture<List<ToolStatus>> initializeAsync() {
        return CompletableFuture.supplyAsync(this::initialize);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public synchronized List<ToolStatus> initialize() {
        if (closed) {
            initialized = true;
            return List.of();
        }
        try {
            Files.createDirectories(binaryDirectory);
            Files.createDirectories(dataDirectory.resolve("temp"));
            if (!Files.isWritable(binaryDirectory) || !Files.isWritable(dataDirectory.resolve("temp"))) {
                throw new IOException("Musicolide native/temp directories are not writable.");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Native tool directories could not be prepared: " + ex.getMessage());
        }

        for (Tool tool : Tool.values()) {
            String executable = null;
            try {
                executable = extractBundled(tool);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not extract bundled " + tool.fileName + ": " + ex.getMessage());
            }
            if (executable == null || executable.isBlank()) {
                executable = plugin.getConfig().getString(tool.configPath, tool.fileName);
            }
            if (executable == null || executable.isBlank()) executable = tool.fileName;
            executables.put(tool, executable);
            ToolStatus status = inspect(tool, executable);
            statuses.put(tool, status);
            if (status.available()) {
                plugin.getLogger().info(tool.fileName + " available: " + status.version());
            } else {
                plugin.getLogger().warning(tool.fileName + " unavailable (" + status.detail() + "). "
                        + "Audio importing that needs this tool will be disabled; playback of existing tracks remains available.");
            }
        }
        initialized = true;
        return List.copyOf(statuses.values());
    }

    public String executable(Tool tool) {
        String executable = executables.get(tool);
        if (executable == null) throw new IllegalStateException("Native toolchain has not been initialized.");
        return executable;
    }

    public boolean available(Tool tool) {
        ToolStatus status = statuses.get(tool);
        return status != null && status.available();
    }

    public synchronized List<ToolStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    public Process start(ProcessBuilder builder) throws IOException {
        if (closed) throw new IOException("Musicolide native toolchain is shutting down.");
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    public void finished(Process process) {
        if (process != null) processes.remove(process);
    }

    public void terminate(Process process) {
        if (process == null) return;
        try {
            process.descendants().forEach(handle -> {
                try { handle.destroy(); } catch (Exception ignored) {}
            });
            process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(handle -> {
                    try { handle.destroyForcibly(); } catch (Exception ignored) {}
                });
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            finished(process);
        }
    }

    private String extractBundled(Tool tool) throws IOException {
        if (!plugin.getConfig().getBoolean("audio.tools.bundled-first", true)) return null;
        if (platform.equals("unsupported")) return null;
        String fileName = isWindows() ? tool.fileName + ".exe" : tool.fileName;
        String resource = "native/" + platform + "/" + fileName;
        Path destinationDirectory = binaryDirectory.resolve(platform);
        Path destination = destinationDirectory.resolve(fileName).normalize();
        if (!destination.startsWith(destinationDirectory)) throw new IOException("Invalid native executable path.");
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) return null;
            Files.createDirectories(destinationDirectory);
            Path temporary = Files.createTempFile(destinationDirectory, fileName, ".tmp");
            try {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                setExecutable(temporary);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return destination.toString();
    }

    private void setExecutable(Path file) throws IOException {
        if (isWindows()) return;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ex) {
            if (!file.toFile().setExecutable(true, false) && !Files.isExecutable(file)) {
                throw new IOException("Could not mark native binary executable.");
            }
        }
    }

    private ToolStatus inspect(Tool tool, String executable) {
        Path output = null;
        Process process = null;
        try {
            Files.createDirectories(dataDirectory.resolve("temp"));
            output = Files.createTempFile(dataDirectory.resolve("temp"), ".tool-check-", ".log");
            process = start(new ProcessBuilder(executable, tool.versionArgument)
                    .redirectErrorStream(true).redirectOutput(output.toFile()));
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                terminate(process);
                return new ToolStatus(tool, executable, false, "", "version check timed out");
            }
            int exit = process.exitValue();
            finished(process);
            if (exit != 0) return new ToolStatus(tool, executable, false, "", "version check exited " + exit);
            String version = readBounded(output, 2048).lines().findFirst().orElse("version available").trim();
            return new ToolStatus(tool, executable, true, version, "");
        } catch (Exception ex) {
            if (process != null) terminate(process);
            return new ToolStatus(tool, executable, false, "", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) {}
            }
        }
    }

    private String readBounded(Path file, int maximumBytes) throws IOException {
        long size = Files.size(file);
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(Math.max(0, size - maximumBytes));
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, maximumBytes));
            channel.read(buffer);
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    public static String platformKey(String operatingSystem, String architecture) {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        String normalizedArch = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> "unsupported";
        };
        if (normalizedArch.equals("unsupported")) return "unsupported";
        // Check Darwin before Windows: the word "darwin" contains the substring "win".
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + normalizedArch;
        if (os.startsWith("windows") || os.equals("win")) {
            return normalizedArch.equals("x86_64") ? "windows-x86_64" : "unsupported";
        }
        if (os.contains("linux")) return "linux-" + normalizedArch;
        return "unsupported";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public void close() {
        closed = true;
        for (Process process : new ArrayList<>(processes)) terminate(process);
    }
}
