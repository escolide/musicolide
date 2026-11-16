package io.github.escolide.musicolide;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.audio.NativeToolchain;
import io.github.escolide.musicolide.audio.TemporaryStorageCleaner;
import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.geyser.GeyserBridge;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.region.WorldGuardBridge;
import io.github.escolide.musicolide.resource.PackHttpServer;
import io.github.escolide.musicolide.resource.SharedPortPackServer;
import io.github.escolide.musicolide.resource.PackManager;
import io.github.escolide.musicolide.resource.PublicAddressDiscovery;
import io.github.escolide.musicolide.resource.ResourcePackBuilder;
import io.github.escolide.musicolide.resource.ResourcePackFormat;
import io.github.escolide.musicolide.resource.SoundPackNamespaceMigration;
import io.github.escolide.musicolide.runtime.DiscordRuntimeBundle;
import io.github.escolide.musicolide.storage.TrackRepository;
import io.github.escolide.musicolide.link.DiscordAccountLinkManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MusicolidePlugin extends JavaPlugin implements Listener {
    private TrackRepository repository;
    private AudioProcessor processor;
    private NativeToolchain nativeToolchain;
    private PackManager packManager;
    private PlaybackEngine playback;
    private DiscordAccountLinkManager accountLinks;
    private AutoCloseable discordBroadcastService;
    private ExecutorService discordBootstrapExecutor;
    private final Object discordStartupLock = new Object();
    private boolean discordShuttingDown;
    private GeyserBridge geyserBridge;
    private Object geyserPackIntegration;
    private AutoCloseable packServer;
    private ResourcePackBuilder resourcePackBuilder;
    private BukkitTask temporaryTrackCleanupTask;
    private MusicCommand musicCommand;
    private long discordGeneration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Older config.yml files are not overwritten on upgrades; add this new
        // selector once so administrators can choose a playback format explicitly.
        boolean migratedConfig = false;
        // Existing quality values remain authoritative for installations that predate presets.
        if (!getConfig().contains("audio.profiles.active")) {
            getConfig().set("audio.profiles.active", "custom");
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.balanced.stereo-quality")) {
            getConfig().set("audio.profiles.presets.balanced.stereo-quality", 5);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.balanced.mono-quality")) {
            getConfig().set("audio.profiles.presets.balanced.mono-quality", 5);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.compact.stereo-quality")) {
            getConfig().set("audio.profiles.presets.compact.stereo-quality", 3);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.compact.mono-quality")) {
            getConfig().set("audio.profiles.presets.compact.mono-quality", 3);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.high.stereo-quality")) {
            getConfig().set("audio.profiles.presets.high.stereo-quality", 7);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.profiles.presets.high.mono-quality")) {
            getConfig().set("audio.profiles.presets.high.mono-quality", 7);
            migratedConfig = true;
        }
        if (!getConfig().contains("storage.saved-music.max-library-gb")) {
            getConfig().set("storage.saved-music.max-library-gb", 10);
            migratedConfig = true;
        }
        if (!getConfig().contains("storage.temporary.track-retention-minutes")) {
            getConfig().set("storage.temporary.track-retention-minutes",
                    getConfig().getInt("audio.yt-dlp.url-import-retention-minutes", 15));
            migratedConfig = true;
        }
        if (!getConfig().contains("storage.temporary.cleanup-interval-minutes")) {
            getConfig().set("storage.temporary.cleanup-interval-minutes", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.optimization.avoid-redundant-reloads")) {
            getConfig().set("resource-packs.optimization.avoid-redundant-reloads", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.voice.connect-only-when-playing")) {
            getConfig().set("discord.voice.connect-only-when-playing", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.voice.stay-connected-while-paused")) {
            getConfig().set("discord.voice.stay-connected-while-paused", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.voice.disconnect-delay-seconds")) {
            getConfig().set("discord.voice.disconnect-delay-seconds", 0);
            migratedConfig = true;
        }
        if (!getConfig().contains("playback.continue-without-listeners")) {
            getConfig().set("playback.continue-without-listeners", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("playback.continue-when-server-empty")) {
            getConfig().set("playback.continue-when-server-empty", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("playlists.preimport.interval-seconds")) {
            getConfig().set("playlists.preimport.interval-seconds", 15);
            migratedConfig = true;
        }
        if (!getConfig().contains("playlists.preimport.max-preimported-tracks")) {
            getConfig().set("playlists.preimport.max-preimported-tracks", 2);
            migratedConfig = true;
        }
        if (!getConfig().contains("playback.mono.centered-audio-origin-by-default")) {
            getConfig().set("playback.mono.centered-audio-origin-by-default", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("playback.synchronization.action-bar-show-artist")) {
            getConfig().set("playback.synchronization.action-bar-show-artist", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("permissions.allow-player-self-zones")) {
            getConfig().set("permissions.allow-player-self-zones", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("permissions.allow-player-all-target")) {
            getConfig().set("permissions.allow-player-all-target", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.playback-mode")) {
            getConfig().set("audio.playback-mode", "resumable");
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.download-mode")) {
            getConfig().set("resource-packs.download-mode", "on-play");
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.universal-playback-system")) {
            // Preserve the previous single Java delivery setting for existing installations.
            getConfig().set("resource-packs.universal-playback-system",
                    getConfig().getString("resource-packs.java-delivery-mode", "library"));
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.playlist-playback-system")) {
            getConfig().set("resource-packs.playlist-playback-system", "per-track");
            migratedConfig = true;
        }
        // Existing installations keep Bedrock playback unless an administrator
        // explicitly enables the complete Bedrock exclusion option.
        if (!getConfig().contains("geyser.ignore-bedrock-players")) {
            getConfig().set("geyser.ignore-bedrock-players", false);
            migratedConfig = true;
        }
        // Add optional Discord settings to existing installations without
        // overwriting any token, channel ID, or administrator preference.
        if (!getConfig().contains("discord.enabled")) {
            getConfig().set("discord.enabled", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.broadcast-scope")) {
            getConfig().set("discord.broadcast-scope", "global-only");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.bot-token")) {
            getConfig().set("discord.bot-token", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.voice-channel-id")) {
            getConfig().set("discord.voice-channel-id", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.reconnect-and-resync")) {
            getConfig().set("discord.reconnect-and-resync", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.presence.enabled")) {
            getConfig().set("discord.presence.enabled", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.presence.template")) {
            getConfig().set("discord.presence.template", "{title} • {elapsed}");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.presence.idle-text")) {
            getConfig().set("discord.presence.idle-text", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.presence.update-interval-seconds")) {
            getConfig().set("discord.presence.update-interval-seconds", 15);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.embed-color")) {
            getConfig().set("discord.embed-color", "#9B59B6");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.enabled")) {
            getConfig().set("discord.info.enabled", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.show-thumbnail")) {
            getConfig().set("discord.info.show-thumbnail", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.custom-message")) {
            getConfig().set("discord.info.custom-message", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.server-name")) {
            getConfig().set("discord.info.server-name", "Minecraft Server");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.show-server-address")) {
            getConfig().set("discord.info.show-server-address", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.server-ip")) {
            getConfig().set("discord.info.server-ip", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.info.server-port")) {
            getConfig().set("discord.info.server-port", 25565);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.enabled")) {
            getConfig().set("discord.commands.enabled", true);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.guild-id")) {
            getConfig().set("discord.commands.guild-id", "");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.admin-authorization")) {
            getConfig().set("discord.commands.admin-authorization", "minecraft-op");
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.admin-role-ids")) {
            getConfig().set("discord.commands.admin-role-ids", List.of());
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.allowed-guild-ids")) {
            getConfig().set("discord.commands.allowed-guild-ids", List.of());
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.user-cooldown-ms")) {
            getConfig().set("discord.commands.user-cooldown-ms", 1000);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.global-cooldown-ms")) {
            getConfig().set("discord.commands.global-cooldown-ms", 100);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.autocomplete-cooldown-seconds")) {
            getConfig().set("discord.commands.autocomplete-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.autocomplete-global-cooldown-seconds")) {
            getConfig().set("discord.commands.autocomplete-global-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.autocomplete-response-cooldown-ms")) {
            getConfig().set("discord.commands.autocomplete-response-cooldown-ms", 250);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.autocomplete-global-response-cooldown-ms")) {
            getConfig().set("discord.commands.autocomplete-global-response-cooldown-ms", 50);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.otp-user-attempts-per-minute")) {
            getConfig().set("discord.commands.otp-user-attempts-per-minute", 5);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.otp-global-attempts-per-minute")) {
            getConfig().set("discord.commands.otp-global-attempts-per-minute", 30);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.link-code-cooldown-seconds")) {
            getConfig().set("discord.commands.link-code-cooldown-seconds", 30);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.global-link-code-cooldown-seconds")) {
            getConfig().set("discord.commands.global-link-code-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.playlist-play-cooldown-seconds")) {
            getConfig().set("discord.commands.playlist-play-cooldown-seconds", 30);
            migratedConfig = true;
        }
        if (!getConfig().contains("discord.commands.global-playlist-play-cooldown-seconds")) {
            getConfig().set("discord.commands.global-playlist-play-cooldown-seconds", 2);
            migratedConfig = true;
        }
        for (String commandName : List.of("info", "help", "play", "music")) {
            String visibilityPath = "discord.commands.visibility." + commandName;
            if (!getConfig().contains(visibilityPath)) {
                getConfig().set(visibilityPath, "requester");
                migratedConfig = true;
            }
        }
        if (!getConfig().contains("audio.yt-dlp.search.max-pages")) {
            getConfig().set("audio.yt-dlp.search.max-pages", 4);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.yt-dlp.allow-playlists")) {
            getConfig().set("audio.yt-dlp.allow-playlists", false);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.yt-dlp.max-playlist-items")) {
            getConfig().set("audio.yt-dlp.max-playlist-items", 10);
            migratedConfig = true;
        }

        // Add bounded-request controls to existing installations without
        // disturbing unrelated playback and hosting settings.
        if (!getConfig().contains("resource-packs.request-cooldown-seconds")) {
            getConfig().set("resource-packs.request-cooldown-seconds", 30);
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.global-request-cooldown-seconds")) {
            getConfig().set("resource-packs.global-request-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.import-cooldown-seconds")) {
            getConfig().set("audio.processing.import-cooldown-seconds", 10);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.global-import-cooldown-seconds")) {
            getConfig().set("audio.processing.global-import-cooldown-seconds", 2);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.max-bulk-import-files")) {
            getConfig().set("audio.processing.max-bulk-import-files", 50);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.search-cooldown-seconds")) {
            getConfig().set("audio.processing.search-cooldown-seconds", 3);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.global-search-cooldown-seconds")) {
            getConfig().set("audio.processing.global-search-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.metadata-cooldown-seconds")) {
            getConfig().set("audio.processing.metadata-cooldown-seconds", 5);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.global-metadata-cooldown-seconds")) {
            getConfig().set("audio.processing.global-metadata-cooldown-seconds", 1);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.transform-cooldown-seconds")) {
            getConfig().set("audio.processing.transform-cooldown-seconds", 3);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.processing.global-transform-cooldown-seconds")) {
            getConfig().set("audio.processing.global-transform-cooldown-seconds", 1);
            migratedConfig = true;
        }
        // The previous default was four temporary packs ahead. Move that default
        // to two while retaining deliberately customized non-default values.
        if (getConfig().getInt("resource-packs.prefetch.tracks-ahead", 4) == 4) {
            getConfig().set("resource-packs.prefetch.tracks-ahead", 2);
            migratedConfig = true;
        }
        if (!getConfig().contains("audio.mono-quality")) {
            getConfig().set("audio.mono-quality", getConfig().getInt("audio.stereo-quality", 5));
            migratedConfig = true;
        }
        if (!getConfig().contains("playback.mono.fade-distance")) {
            getConfig().set("playback.mono.fade-distance", 10.0);
            migratedConfig = true;
        }
        if (migratedConfig) saveConfig();
        try {
            Path data = getDataFolder().toPath();
            Path savedMusic = data.resolve("SavedMusic");
            Files.createDirectories(savedMusic);
            Files.createDirectories(data.resolve("temp"));
            // Migrate old user-supplied files from incoming/ without overwriting anything.
            Path legacyIncoming = data.resolve("incoming");
            if (Files.isDirectory(legacyIncoming)) {
                try (var legacyFiles = Files.list(legacyIncoming)) {
                    for (Path legacyFile : legacyFiles.filter(Files::isRegularFile)
                            .filter(path -> !path.getFileName().toString().startsWith(".")).toList()) {
                        Path destination = savedMusic.resolve(legacyFile.getFileName().toString());
                        if (Files.exists(destination)) {
                            getLogger().warning("Kept legacy incoming file because SavedMusic already contains the same name: "
                                    + legacyFile.getFileName());
                            continue;
                        }
                        Files.move(legacyFile, destination);
                    }
                }
            }
            Files.createDirectories(data.resolve("tracks"));
            Files.createDirectories(data.resolve("packs"));
            TemporaryStorageCleaner.Report cleanup = TemporaryStorageCleaner.clean(data);
            if (cleanup.removedEntries() > 0) {
                getLogger().info("Removed " + cleanup.removedEntries()
                        + " abandoned audio-processing temporary entr" + (cleanup.removedEntries() == 1 ? "y." : "ies."));
            }
            cleanup.failures().forEach(failure ->
                    getLogger().warning("Could not clean an abandoned temporary entry: " + failure));
            repository = new TrackRepository(data.resolve("musicolide.db"));
            accountLinks = new DiscordAccountLinkManager(data.resolve("discord-links.db"), getLogger());

            String serverVersion = Bukkit.getBukkitVersion();
            ResourcePackFormat packFormat = ResourcePackFormat.forMinecraftVersion(serverVersion);
            getLogger().info("Detected Minecraft " + serverVersion + "; using resource-pack format "
                    + packFormat.displayName() + ".");
            ResourcePackBuilder builder = new ResourcePackBuilder(data, packFormat);
            resourcePackBuilder = builder;
            SoundPackNamespaceMigration.migrate(data.resolve("packs"), repository, getLogger());
            ResourcePackBuilder.BasePackInfo basePack = builder.buildBasePack();
            // A server restart ends every in-flight HTTP request, so old immutable
            // library revisions can now be safely removed.
            builder.cleanupLibraryArchives(java.util.Set.of(basePack.sha1()));
            try {
                Path bedrockPack = builder.rebuildBedrockLibrary();
                if (bedrockPack != null) getLogger().info("Rebuilt the Bedrock music library from imported tracks.");
            } catch (Exception ex) {
                getLogger().warning("Could not rebuild the Bedrock music library: " + ex.getMessage());
            }

            // Native tools are installed and probed only when a feature first needs them.
            // Existing catalogue playback does not need yt-dlp, FFmpeg or QuickJS running.
            nativeToolchain = new NativeToolchain(this);
            processor = new AudioProcessor(this, repository, builder, nativeToolchain);
            geyserBridge = new GeyserBridge(this, builder.bedrockPackPath());
            initializeGeyserPackIntegration(builder.bedrockPackPath());

            String hostingMode = getConfig().getString("server.hosting-mode", "shared-port")
                    .trim().toLowerCase(java.util.Locale.ROOT);
            String prefix = getConfig().getString("server.path-prefix", "/packs");
            long maxPackBytes = getConfig().getLong("server.max-download-mb", 100) * 1024L * 1024L;
            int publicPort;
            if (hostingMode.equals("shared-port")) {
                SharedPortPackServer sharedServer = SharedPortPackServer.install(
                        this, data.resolve("packs"), prefix, maxPackBytes);
                packServer = sharedServer;
                publicPort = sharedServer.listeningPort();
            } else if (hostingMode.equals("dedicated-port")) {
                String bind = getConfig().getString("server.bind-address", "0.0.0.0");
                int port = getConfig().getInt("server.port", 8765);
                packServer = new PackHttpServer(bind, port, prefix, data.resolve("packs"), maxPackBytes);
                publicPort = port;
                getLogger().info("Dedicated-port resource-pack hosting is active on TCP port " + port + ".");
            } else {
                throw new IllegalArgumentException("Unknown server.hosting-mode '" + hostingMode
                        + "'. Use 'shared-port' or 'dedicated-port'.");
            }
            String configuredPublicBase = getConfig().getString("server.public-base-url", "").trim();
            CompletableFuture<String> publicBaseUrl;
            if (!configuredPublicBase.isEmpty()) {
                // An explicit address always wins. This is required on hosts that
                // route incoming Minecraft traffic through a proxy or NAT hostname.
                publicBaseUrl = CompletableFuture.completedFuture(configuredPublicBase);
                getLogger().info("Using the configured public resource-pack base URL.");
            } else if (getConfig().getBoolean("server.auto-detect-public-address", true)) {
                getLogger().info("No server.public-base-url is configured; discovering the public IP over HTTPS in the background.");
                publicBaseUrl = PublicAddressDiscovery.discoverBaseUrlAsync();
                publicBaseUrl.whenComplete((address, error) -> {
                    if (error == null) {
                        getLogger().info("Automatically detected public resource-pack address " + address
                                + ". If this host uses an inbound proxy/NAT, set server.public-base-url "
                                + "to the hostname players use to join.");
                    } else {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        getLogger().warning("Automatic public-address detection failed: " + cause.getMessage()
                                + " Set server.public-base-url to the public hostname players use to join.");
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (packManager != null) packManager.publicAddressAvailable();
                    });
                });
            } else {
                publicBaseUrl = CompletableFuture.failedFuture(new IllegalStateException(
                        "server.public-base-url is empty and server.auto-detect-public-address is disabled."));
            }
            packManager = new PackManager(this, builder, publicPort, publicBaseUrl, geyserBridge);
            if (packManager.isPerTrackDeliveryEnabled()) {
                getLogger().warning("Experimental Java per-track resource-pack delivery is enabled. "
                        + "First-time tracks are sent individually and may trigger client resource reloads. "
                        + "Geyser/Bedrock continues to use its aggregate pack. Set "
                        + "resource-packs.java-delivery-mode: library to restore the default delivery mode.");
            }
            WorldGuardBridge worldGuard = null;
            if (getConfig().getBoolean("worldguard.enabled", true) && Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
                try {
                    worldGuard = new WorldGuardBridge(this);
                    getLogger().info("WorldGuard region integration enabled.");
                } catch (ReflectiveOperationException ex) {
                    getLogger().warning("WorldGuard was found but its API could not be initialized: " + ex.getMessage());
                }
            }
            if (getConfig().getBoolean("worldguard.require-plugin", false) && worldGuard == null) {
                throw new IllegalStateException("WorldGuard is required but unavailable.");
            }
            playback = new PlaybackEngine(this, repository, packManager, processor, worldGuard, geyserBridge);
            playback.start();
            scheduleTemporaryTrackCleanup();
            MusicCommand command = new MusicCommand(this, repository, processor, playback, accountLinks);
            musicCommand = command;
            if (getCommand("music") != null) {
                getCommand("music").setExecutor(command);
                getCommand("music").setTabCompleter(command);
            }
            if (getCommand("play") != null) {
                getCommand("play").setExecutor(command::onPlayCommand);
                getCommand("play").setTabCompleter(command::onPlayTabComplete);
            }
            if (getCommand("link") != null) getCommand("link").setExecutor(command::onLinkCommand);
            if (getCommand("unlink") != null) getCommand("unlink").setExecutor(command::onUnlinkCommand);
            if (getCommand("deafen") != null) getCommand("deafen").setExecutor(command::onDeafenCommand);
            if (getCommand("undeafen") != null) getCommand("undeafen").setExecutor(command::onUndeafenCommand);

            if (getConfig().getBoolean("discord.enabled", false)) startDiscordServices();
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("Musicolide enabled. Place local audio files in plugins/Musicolide/SavedMusic/. Permanent imports and metadata are stored there.");
        } catch (Exception ex) {
            getLogger().severe("Musicolide could not start: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void startDiscordServices() {
        if (!getConfig().getBoolean("discord.enabled", false) || musicCommand == null
                || discordShuttingDown) return;
        synchronized (discordStartupLock) {
            if (discordBroadcastService != null || discordBootstrapExecutor != null) return;
            long generation = ++discordGeneration;
            ExecutorService bootstrap = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Musicolide-Discord-Bootstrap");
                thread.setDaemon(true);
                return thread;
            });
            discordBootstrapExecutor = bootstrap;
            bootstrap.execute(() -> {
                AutoCloseable startedService = null;
                try {
                    if (nativeToolchain != null) nativeToolchain.initializeAsync().join();
                    startedService = DiscordRuntimeBundle.start(
                            this, playback, nativeToolchain, musicCommand, accountLinks);
                    synchronized (discordStartupLock) {
                        if (!discordShuttingDown && generation == discordGeneration
                                && getConfig().getBoolean("discord.enabled", false)) {
                            discordBroadcastService = startedService;
                            startedService = null;
                        }
                    }
                } catch (Throwable discordError) {
                    Throwable cause = discordError;
                    while (cause instanceof java.lang.reflect.InvocationTargetException
                            && cause.getCause() != null) cause = cause.getCause();
                    getLogger().log(java.util.logging.Level.WARNING,
                            "Discord services could not be started ("
                                    + cause.getClass().getSimpleName()
                                    + "). Minecraft playback is unaffected. Check the server OS/CPU support "
                                    + "and outbound HTTPS access to Maven Central.", cause);
                } finally {
                    if (startedService != null) {
                        try { startedService.close(); }
                        catch (Exception closeError) {
                            getLogger().log(java.util.logging.Level.FINE,
                                    "Discord bootstrap cleanup failed.", closeError);
                        }
                    }
                    synchronized (discordStartupLock) {
                        if (generation == discordGeneration && discordBootstrapExecutor == bootstrap) {
                            discordBootstrapExecutor = null;
                        }
                    }
                    bootstrap.shutdown();
                }
            });
        }
    }

    private void stopDiscordServices() {
        AutoCloseable service;
        ExecutorService bootstrap;
        synchronized (discordStartupLock) {
            discordGeneration++;
            service = discordBroadcastService;
            discordBroadcastService = null;
            bootstrap = discordBootstrapExecutor;
            discordBootstrapExecutor = null;
        }
        if (bootstrap != null) bootstrap.shutdownNow();
        if (service != null) {
            try { service.close(); }
            catch (Exception ex) {
                getLogger().warning("Discord shutdown encountered an error ("
                        + ex.getClass().getSimpleName() + ").");
            }
        }
    }

    /** Changes the optional broadcaster at runtime without changing Minecraft playback. */
    public void setDiscordEnabled(boolean enabled) {
        getConfig().set("discord.enabled", enabled);
        saveConfig();
        if (enabled) startDiscordServices();
        else stopDiscordServices();
    }

    public boolean isPlayerMusicDisabled(java.util.UUID playerId) {
        return packManager != null && packManager.isPlayerDisabled(playerId);
    }

    public void disablePlayerMusic(Player player) {
        if (player == null || packManager == null) return;
        if (playback != null) playback.unloadPlayerAudio(player);
        packManager.disablePlayer(player);
    }

    public void enablePlayerMusic(Player player) {
        if (player == null || packManager == null) return;
        packManager.enablePlayer(player);
    }

    public void unloadPlayerMusic(Player player) {
        if (player == null || packManager == null) return;
        String currentTrackId = playback == null ? null
                : playback.currentPlaybackTrackFor(player).map(MusicTrack::id).orElse(null);
        if (playback != null) playback.unloadPlayerAudio(player);
        packManager.unloadPlayer(player, currentTrackId);
    }

    private void scheduleTemporaryTrackCleanup() {
        long intervalMinutes = Math.max(1L, Math.min(1440L,
                getConfig().getLong("storage.temporary.cleanup-interval-minutes", 1L)));
        long periodTicks = 20L * 60L * intervalMinutes;
        temporaryTrackCleanupTask = Bukkit.getScheduler().runTaskTimer(
                this, this::cleanupExpiredTemporaryTracks, periodTicks, periodTicks);
    }

    /**
     * URL-imported tracks are temporary by default. Cleanup runs on the server
     * thread so it cannot race a command that is starting a session. Tracks
     * remain until no active/queued/repeating session references them and no
     * Java player is still downloading their pack. File deletion and Bedrock
     * library rebuilding are then performed asynchronously.
     */
    private void deleteTemporaryTrackDirectory(MusicTrack track) throws java.io.IOException {
        Path tracksRoot = getDataFolder().toPath().resolve("tracks").toAbsolutePath().normalize();
        Path directory = tracksRoot.resolve(track.id()).normalize();
        if (!directory.startsWith(tracksRoot) || !Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void cleanupExpiredTemporaryTracks() {
        if (repository == null || playback == null || packManager == null || resourcePackBuilder == null) return;

        final List<MusicTrack> expired;
        try {
            expired = repository.expiredTemporaryTracks(System.currentTimeMillis());
        } catch (SQLException ex) {
            getLogger().warning("Could not query expired temporary tracks: " + ex.getMessage());
            return;
        }
        if (expired.isEmpty()) return;

        Path packsDirectory = getDataFolder().toPath().resolve("packs").toAbsolutePath().normalize();
        List<MusicTrack> removed = new ArrayList<>();
        for (MusicTrack track : expired) {
            if (playback.isTrackInUse(track.id()) || packManager.isDownloadInProgress(track.packId())) continue;

            Path packFile = packsDirectory.resolve(track.packFileName()).normalize();
            if (!packFile.startsWith(packsDirectory)) {
                getLogger().warning("Skipped temporary track with unsafe pack path: " + track.id());
                continue;
            }
            try {
                if (repository.deleteTrack(track.id())) removed.add(track);
            } catch (SQLException ex) {
                getLogger().warning("Could not expire temporary track " + track.id() + ": " + ex.getMessage());
            }
        }
        if (removed.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (MusicTrack track : removed) {
                Path packFile = packsDirectory.resolve(track.packFileName()).normalize();
                try {
                    if (packFile.startsWith(packsDirectory)) Files.deleteIfExists(packFile);
                    deleteTemporaryTrackDirectory(track);
                } catch (Exception ex) {
                    getLogger().warning("Could not delete expired resource pack for " + track.id() + ": " + ex.getMessage());
                }
            }
            try {
                resourcePackBuilder.buildBasePack();
                packManager.cleanupObsoleteLibraryArchives();
            } catch (Exception ex) {
                getLogger().warning("Could not refresh the Java music library after temporary-track cleanup: "
                        + ex.getMessage());
            }
            try {
                resourcePackBuilder.rebuildBedrockLibrary();
            } catch (Exception ex) {
                getLogger().warning("Could not refresh the Bedrock library after temporary-track cleanup: "
                        + ex.getMessage());
            }
        });
        getLogger().info("Expired " + removed.size() + " temporary URL-imported track(s).");
    }

    /**
     * Deletes temporary catalogue entries that are not in use or being delivered.
     * Database rows are removed on the server thread before disk cleanup is queued,
     * preventing new commands from starting one of the selected tracks.
     */
    public CompletableFuture<Integer> clearTemporaryTracks() {
        if (repository == null || playback == null || packManager == null || resourcePackBuilder == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Musicolide is not fully initialized."));
        }
        final List<MusicTrack> removed = new ArrayList<>();
        try {
            for (MusicTrack track : repository.temporaryTracks()) {
                if (playback.isTrackInUse(track.id()) || packManager.isDownloadInProgress(track.packId())) continue;
                if (repository.deleteTrack(track.id())) removed.add(track);
            }
        } catch (SQLException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (removed.isEmpty()) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            Path packsDirectory = getDataFolder().toPath().resolve("packs").toAbsolutePath().normalize();
            Path tracksDirectory = getDataFolder().toPath().resolve("tracks").toAbsolutePath().normalize();
            for (MusicTrack track : removed) {
                try {
                    Path pack = packsDirectory.resolve(track.packFileName()).normalize();
                    if (pack.startsWith(packsDirectory)) Files.deleteIfExists(pack);
                    Path directory = tracksDirectory.resolve(track.id()).normalize();
                    if (directory.startsWith(tracksDirectory) && Files.exists(directory)) {
                        try (var walk = Files.walk(directory)) {
                            for (Path file : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                Files.deleteIfExists(file);
                            }
                        }
                    }
                } catch (Exception error) {
                    getLogger().warning("Could not fully clear temporary track " + track.id() + ": " + error.getMessage());
                }
            }
            try {
                resourcePackBuilder.buildBasePack();
                packManager.cleanupObsoleteLibraryArchives();
            } catch (Exception error) {
                getLogger().warning("Could not refresh the Java library after clearing temporary tracks: " + error.getMessage());
            }
            try { resourcePackBuilder.rebuildBedrockLibrary(); }
            catch (Exception error) {
                getLogger().warning("Could not refresh the Bedrock library after clearing temporary tracks: " + error.getMessage());
            }
            return removed.size();
        });
    }

    private void initializeGeyserPackIntegration(Path bedrockPack) {
        if (geyserBridge == null || !geyserBridge.isPresent()) return;
        try {
            Class<?> adapterClass = Class.forName(
                    "io.github.escolide.musicolide.geyser.GeyserPackIntegration",
                    true, getClass().getClassLoader());
            Object adapter = adapterClass.getConstructor(JavaPlugin.class, Path.class, GeyserBridge.class).newInstance(this, bedrockPack, geyserBridge);
            adapterClass.getMethod("register").invoke(adapter);
            geyserPackIntegration = adapter;
        } catch (ReflectiveOperationException | LinkageError ex) {
            getLogger().warning("Geyser was detected, but its Bedrock pack integration could not be started: "
                    + ex.getMessage());
        }
    }

    private void closeGeyserPackIntegration() {
        if (geyserPackIntegration == null) return;
        try {
            geyserPackIntegration.getClass().getMethod("close").invoke(geyserPackIntegration);
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Could not unregister Geyser event listeners: " + ex.getMessage());
        }
        geyserPackIntegration = null;
    }

    public boolean isTrackPackDownloadInProgress(java.util.UUID packId) {
        return packManager != null && packManager.isDownloadInProgress(packId);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (playback != null && playback.isIgnoredBedrockPlayer(event.getPlayer().getUniqueId())) return;
        if (packManager != null) packManager.status(event.getPlayer().getUniqueId(), event.getID(), event.getStatus());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (playback != null && playback.isIgnoredBedrockPlayer(event.getPlayer().getUniqueId())) return;
        if (packManager != null) packManager.onPlayerJoin(event.getPlayer());
        if (playback != null) playback.onJoin(event.getPlayer());
        boolean perTrackForJoiningPlayer = playback != null
                ? playback.isPerTrackDeliveryForPlayer(event.getPlayer())
                : packManager != null && packManager.isPerTrackDeliveryEnabled();
        if (packManager != null && !perTrackForJoiningPlayer
                && getConfig().getString("resource-packs.download-mode", "on-play").equalsIgnoreCase("on-join")) {
            // Give PlaybackEngine's join handler time to identify a reconnecting
            // listener. Their private catch-up tail is enough for this track; the
            // catalogue can be requested normally on the next track.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (event.getPlayer().isOnline() && packManager != null
                        && (playback == null || !playback.shouldDeferLibraryOnJoin(event.getPlayer()))) {
                    packManager.requestLibrary(event.getPlayer(), false);
                }
            }, 3L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (playback != null) playback.onQuit(event.getPlayer());
    }

    @Override
    public void onDisable() {
        if (temporaryTrackCleanupTask != null) temporaryTrackCleanupTask.cancel();
        closeGeyserPackIntegration();
        synchronized (discordStartupLock) {
            discordShuttingDown = true;
        }
        stopDiscordServices();
        if (playback != null) playback.stopAll();
        if (processor != null) processor.close();
        else if (nativeToolchain != null) nativeToolchain.close();
        if (packServer != null) {
            try {
                packServer.close();
            } catch (Exception ex) {
                getLogger().warning("Could not fully close the resource-pack server: " + ex.getMessage());
            }
        }
        if (accountLinks != null) {
            accountLinks.close();
            accountLinks = null;
        }
        if (repository != null) {
            try { repository.close(); }
            catch (Exception ex) { getLogger().warning("Error closing database: " + ex.getMessage()); }
        }
        getLogger().info("Musicolide disabled.");
    }
}
