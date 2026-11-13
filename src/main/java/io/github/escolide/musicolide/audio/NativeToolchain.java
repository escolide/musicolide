package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves administrator-configured tools or downloads a pinned, checksum-
 * verified native toolchain on first startup. Downloads, installation and
 * executable probes run away from the Bukkit server thread.
 */
public final class NativeToolchain implements AutoCloseable {
    public enum Tool {
        FFMPEG("ffmpeg", "audio.ffmpeg.executable", "-version"),
        YT_DLP("yt-dlp", "audio.yt-dlp.executable", "--version"),
        JS_RUNTIME("qjs", "audio.yt-dlp.js-runtime-executable", "-v");

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
    private final NativeToolInstaller installer;
    private final NativeToolDownloader downloadInstaller;
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
        this.installer = new NativeToolInstaller(plugin, this, dataDirectory, binaryDirectory, platform);
        this.downloadInstaller = new NativeToolDownloader(plugin, binaryDirectory, platform);
    }

    /**
     * Runs bundle extraction and executable probes away from the Bukkit main
     * thread. The common-pool worker is a daemon thread.
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

        installer.cleanupObsoleteTools();

        Map<Tool, String> downloadedTools = Map.of();
        if (plugin.getConfig().getBoolean("audio.tools.download-on-first-start", true)) {
            try {
                downloadedTools = downloadInstaller.install();
            } catch (IOException ex) {
                plugin.getLogger().warning("Automatic native-tool setup failed: " + ex.getMessage()
                        + ". Musicolide will try administrator-configured executables or PATH.");
            }
        }

        for (Tool tool : Tool.values()) {
            String configured = plugin.getConfig().getString(tool.configPath, tool.fileName);
            if (configured == null || configured.isBlank()) configured = tool.fileName;

            String downloaded = downloadedTools.get(tool);
            String existing = installer.existing(tool);
            String executable = downloaded != null ? downloaded : (existing != null ? existing : configured);
            ToolStatus status = inspect(tool, executable);

            // A broken/obsolete installed copy must not prevent a working PATH entry.
            if (!status.available() && !configured.equals(executable)) {
                ToolStatus configuredStatus = inspect(tool, configured);
                if (configuredStatus.available()) {
                    executable = configured;
                    status = configuredStatus;
                }
            }

            executables.put(tool, executable);
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
        configureNativeEnvironment(builder);
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

    private void configureNativeEnvironment(ProcessBuilder builder) {
        if (platform.equals("unsupported")) return;
        Path root = binaryDirectory.resolve(platform);
        Path bin = root.resolve("bin");
        Path lib = root.resolve("lib");
        Path runtime = root.resolve("runtime");
        String currentPath = builder.environment().getOrDefault("PATH", "");
        List<String> pathEntries = new ArrayList<>();
        if (Files.isDirectory(bin)) pathEntries.add(bin.toAbsolutePath().toString());
        if (Files.isDirectory(runtime)) pathEntries.add(runtime.toAbsolutePath().toString());
        if (Files.isDirectory(lib)) pathEntries.add(lib.toAbsolutePath().toString());
        if (!currentPath.isBlank()) pathEntries.add(currentPath);
        if (!pathEntries.isEmpty()) {
            builder.environment().put("PATH", String.join(java.io.File.pathSeparator, pathEntries));
        }
        if (platform.startsWith("linux-") && Files.isDirectory(lib)) {
            prependEnvironmentPath(builder, "LD_LIBRARY_PATH", lib);
        } else if (platform.startsWith("macos-") && Files.isDirectory(lib)) {
            prependEnvironmentPath(builder, "DYLD_LIBRARY_PATH", lib);
        }
    }

    private void prependEnvironmentPath(ProcessBuilder builder, String key, Path directory) {
        String current = builder.environment().getOrDefault(key, "");
        String prefix = directory.toAbsolutePath().toString();
        builder.environment().put(key, current.isBlank() ? prefix
                : prefix + java.io.File.pathSeparator + current);
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
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, maximumBytes));
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
            return "windows-" + normalizedArch;
        }
        if (os.contains("linux")) return "linux-" + normalizedArch;
        return "unsupported";
    }

    @Override
    public void close() {
        closed = true;
        for (Process process : new ArrayList<>(processes)) terminate(process);
    }
}
