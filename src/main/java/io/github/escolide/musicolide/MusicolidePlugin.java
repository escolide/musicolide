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
import io.github.escolide.musicolide.storage.TrackRepository;
import org.bukkit.Bukkit;
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

public final class MusicolidePlugin extends JavaPlugin implements Listener {
    private TrackRepository repository;
    private AudioProcessor processor;
    private NativeToolchain nativeToolchain;
    private PackManager packManager;
    private PlaybackEngine playback;
    private GeyserBridge geyserBridge;
    private Object geyserPackIntegration;
    private AutoCloseable packServer;
    private ResourcePackBuilder resourcePackBuilder;
    private BukkitTask temporaryTrackCleanupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Older config.yml files are not overwritten on upgrades; add this new
        // selector once so administrators can choose a playback format explicitly.
        boolean migratedConfig = false;
        if (!getConfig().contains("audio.playback-mode")) {
            getConfig().set("audio.playback-mode", "resumable");
            migratedConfig = true;
        }
        if (!getConfig().contains("resource-packs.download-mode")) {
            getConfig().set("resource-packs.download-mode", "on-play");
            migratedConfig = true;
        }
        if (migratedConfig) saveConfig();
        try {
            Path data = getDataFolder().toPath();
            Files.createDirectories(data.resolve("incoming"));
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

            String serverVersion = Bukkit.getBukkitVersion();
            ResourcePackFormat packFormat = ResourcePackFormat.forMinecraftVersion(serverVersion);
            getLogger().info("Detected Minecraft " + serverVersion + "; using resource-pack format "
                    + packFormat.displayName() + ".");
            ResourcePackBuilder builder = new ResourcePackBuilder(data, packFormat);
            resourcePackBuilder = builder;
            SoundPackNamespaceMigration.migrate(data.resolve("packs"), repository, getLogger());
            ResourcePackBuilder.BasePackInfo basePack = builder.buildBasePack();
            try {
                Path bedrockPack = builder.rebuildBedrockLibrary();
                if (bedrockPack != null) getLogger().info("Rebuilt the Bedrock music library from imported tracks.");
            } catch (Exception ex) {
                getLogger().warning("Could not rebuild the Bedrock music library: " + ex.getMessage());
            }

            nativeToolchain = new NativeToolchain(this);
            nativeToolchain.initializeAsync().thenAccept(statuses -> {
                if (getConfig().getBoolean("audio.yt-dlp.enabled", false)
                        && !nativeToolchain.available(NativeToolchain.Tool.YT_DLP)) {
                    getLogger().warning("URL importing is enabled in config, but yt-dlp did not pass its startup check.");
                }
            }).exceptionally(error -> {
                getLogger().warning("Native tool initialization failed: " + error.getMessage());
                return null;
            });
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
            packManager = new PackManager(this, builder::currentBasePackInfo, publicPort, publicBaseUrl);
            WorldGuardBridge worldGuard = null;
            if (getConfig().getBoolean("worldguard.enabled", true) && Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
                try {
                    worldGuard = new WorldGuardBridge();
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
            MusicCommand command = new MusicCommand(this, repository, processor, playback);
            if (getCommand("music") != null) {
                getCommand("music").setExecutor(command);
                getCommand("music").setTabCompleter(command);
            }
            if (getCommand("play") != null) {
                getCommand("play").setExecutor(command::onPlayCommand);
                getCommand("play").setTabCompleter(command::onPlayTabComplete);
            }
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("Musicolide enabled. Import rights-cleared audio into plugins/Musicolide/incoming/.");
        } catch (Exception ex) {
            getLogger().severe("Musicolide could not start: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void scheduleTemporaryTrackCleanup() {
        long periodTicks = 20L * 60L;
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
                } catch (Exception ex) {
                    getLogger().warning("Could not delete expired resource pack for " + track.id() + ": " + ex.getMessage());
                }
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

    private void initializeGeyserPackIntegration(Path bedrockPack) {
        if (geyserBridge == null || !geyserBridge.isPresent()) return;
        try {
            Class<?> adapterClass = Class.forName(
                    "io.github.escolide.musicolide.geyser.GeyserPackIntegration",
                    true, getClass().getClassLoader());
            Object adapter = adapterClass.getConstructor(JavaPlugin.class, Path.class).newInstance(this, bedrockPack);
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

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (packManager != null) packManager.status(event.getPlayer().getUniqueId(), event.getID(), event.getStatus());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (geyserBridge != null) geyserBridge.recordPlayerJoin(event.getPlayer().getUniqueId());
        if (packManager != null && getConfig().getString(
                "resource-packs.download-mode", "on-play").equalsIgnoreCase("on-join")) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (event.getPlayer().isOnline() && packManager != null) {
                    packManager.requestLibrary(event.getPlayer(), false);
                }
            }, 1L);
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
        if (repository != null) {
            try { repository.close(); }
            catch (Exception ex) { getLogger().warning("Error closing database: " + ex.getMessage()); }
        }
        getLogger().info("Musicolide disabled.");
    }
}
