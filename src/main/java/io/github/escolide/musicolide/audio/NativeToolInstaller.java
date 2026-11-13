package io.github.escolide.musicolide.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Locates previously installed native tools. Current releases bundle their
 * platform tools in the JAR; this class preserves compatibility with existing
 * installations that already have tools in plugins/Musicolide/native/.
 *
 * There is intentionally no network downloader or shell-based archive extractor
 * in the normal runtime installer.
 */
final class NativeToolInstaller {
    private final JavaPlugin plugin;
    private final Path binaryDirectory;
    private final String platform;

    NativeToolInstaller(JavaPlugin plugin, NativeToolchain toolchain, Path dataDirectory,
                        Path binaryDirectory, String platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(toolchain, "toolchain");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.binaryDirectory = Objects.requireNonNull(binaryDirectory, "binaryDirectory");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    String existing(NativeToolchain.Tool tool) {
        if (platform.equals("unsupported")) return null;
        String name = fileName(tool) + (platform.startsWith("windows-") ? ".exe" : "");
        Path platformDirectory = binaryDirectory.resolve(platform);
        for (Path candidate : List.of(platformDirectory.resolve(name),
                platformDirectory.resolve("bin").resolve(name))) {
            if (!Files.isRegularFile(candidate)) continue;
            if (platform.startsWith("windows-") || Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    void cleanupObsoleteTools() {
        if (!platform.equals("linux-x86_64")) return;
        Path obsoleteProbe = binaryDirectory.resolve(platform).resolve("ffprobe");
        try {
            if (Files.deleteIfExists(obsoleteProbe)) {
                plugin.getLogger().info("Removed obsolete FFprobe; FFmpeg now handles duration probing.");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not remove obsolete FFprobe: " + ex.getMessage());
        }
    }

    private String fileName(NativeToolchain.Tool tool) {
        return tool == NativeToolchain.Tool.YT_DLP ? "yt-dlp" : "ffmpeg";
    }
}
