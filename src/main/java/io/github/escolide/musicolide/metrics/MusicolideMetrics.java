package io.github.escolide.musicolide.metrics;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Objects;

/**
 * Optional bStats integration. Only fixed, non-identifying configuration
 * categories and optional integration availability are reported.
 */
public final class MusicolideMetrics {
    private final Metrics metrics;

    private MusicolideMetrics(Metrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static MusicolideMetrics start(JavaPlugin plugin, int pluginId) {
        Objects.requireNonNull(plugin, "plugin");
        if (pluginId <= 0) {
            throw new IllegalArgumentException("A positive bStats plugin ID is required.");
        }

        FileConfiguration config = plugin.getConfig();
        Metrics metrics = new Metrics(plugin, pluginId);

        addChart(metrics, config, "playback-mode", "playback_mode",
                category(config.getString("audio.playback-mode"),
                        "segmented", "continuous", "resumable"));
        addChart(metrics, config, "audio-profile", "audio_profile",
                category(config.getString("audio.profiles.active"),
                        "balanced", "compact", "high", "custom"));
        addChart(metrics, config, "resource-pack-delivery", "resource_pack_delivery",
                category(config.getString("resource-packs.universal-playback-system"),
                        "library", "pre-track", "per-track"));
        addChart(metrics, config, "playlist-delivery", "playlist_delivery",
                category(config.getString("resource-packs.playlist-playback-system"),
                        "per-track", "library"));
        addChart(metrics, config, "remote-import-enabled", "remote_import_enabled",
                enabled(config.getBoolean("audio.yt-dlp.enabled", false)));
        addChart(metrics, config, "discord-enabled", "discord_enabled",
                enabled(config.getBoolean("discord.enabled", false)));

        boolean geyserInstalled = plugin.getServer().getPluginManager().isPluginEnabled("Geyser-Spigot");
        String geyserState = !geyserInstalled ? "not-installed"
                : config.getBoolean("geyser.ignore-bedrock-players", false)
                ? "bedrock-excluded" : "bedrock-supported";
        addChart(metrics, config, "geyser-integration", "geyser_integration", geyserState);

        boolean worldGuardConfigured = config.getBoolean("worldguard.enabled", true);
        boolean worldGuardInstalled = plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard");
        String worldGuardState = !worldGuardConfigured ? "disabled"
                : worldGuardInstalled ? "enabled" : "not-installed";
        addChart(metrics, config, "worldguard-integration", "worldguard_integration", worldGuardState);

        plugin.getLogger().info("bStats metrics initialized for Musicolide.");
        return new MusicolideMetrics(metrics);
    }

    private static void addChart(Metrics metrics, FileConfiguration config,
                                 String configKey, String chartId, String value) {
        if (!config.getBoolean("bstats.charts." + configKey, true)) return;
        metrics.addCustomChart(new SimplePie(chartId, () -> value));
    }

    static String category(String raw, String... allowedValues) {
        if (raw == null) return "other";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (String allowed : allowedValues) {
            if (allowed.equals(normalized)) return normalized;
        }
        return "other";
    }

    private static String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }

    public void shutdown() {
        metrics.shutdown();
    }
}
