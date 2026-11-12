package io.github.escolide.musicolide;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.region.WorldGuardBridge;
import io.github.escolide.musicolide.resource.PackHttpServer;
import io.github.escolide.musicolide.resource.PackManager;
import io.github.escolide.musicolide.resource.ResourcePackBuilder;
import io.github.escolide.musicolide.resource.ResourcePackFormat;
import io.github.escolide.musicolide.storage.TrackRepository;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MusicolidePlugin extends JavaPlugin implements Listener {
    private TrackRepository repository;
    private AudioProcessor processor;
    private NativeToolchain nativeToolchain;
    private PackManager packManager;
    private PlaybackEngine playback;
    private PackHttpServer packServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Path data = getDataFolder().toPath();
            Files.createDirectories(data.resolve("incoming"));
            Files.createDirectories(data.resolve("tracks"));
            Files.createDirectories(data.resolve("packs"));
            repository = new TrackRepository(data.resolve("musicolide.db"));

            String serverVersion = Bukkit.getBukkitVersion();
            ResourcePackFormat packFormat = ResourcePackFormat.forMinecraftVersion(serverVersion);
            getLogger().info("Detected Minecraft " + serverVersion + "; using resource-pack format "
                    + packFormat.displayName() + ".");
            ResourcePackBuilder builder = new ResourcePackBuilder(data, packFormat);

            nativeToolchain = new NativeToolchain(this);
            nativeToolchain.initialize();
            if (getConfig().getBoolean("audio.yt-dlp.enabled", false)
                    && !nativeToolchain.available(NativeToolchain.Tool.YT_DLP)) {
                getLogger().warning("URL importing is enabled in config, but yt-dlp did not pass its startup check.");
            }
            processor = new AudioProcessor(this, repository, builder, nativeToolchain);
            packManager = new PackManager(this);
            String bind = getConfig().getString("server.bind-address", "0.0.0.0");
            int port = getConfig().getInt("server.port", 8765);
            String prefix = getConfig().getString("server.path-prefix", "/packs");
            long maxPackBytes = getConfig().getLong("server.max-download-mb", 100) * 1024L * 1024L;
            packServer = new PackHttpServer(bind, port, prefix, data.resolve("packs"), maxPackBytes);
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
            playback = new PlaybackEngine(this, repository, packManager, worldGuard);
            playback.start();
            MusicCommand command = new MusicCommand(this, repository, processor, playback);
            if (getCommand("music") != null) {
                getCommand("music").setExecutor(command);
                getCommand("music").setTabCompleter(command);
            }
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("Musicolide enabled. Import rights-cleared audio into plugins/Musicolide/incoming/.");
        } catch (Exception ex) {
            getLogger().severe("Musicolide could not start: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (packManager != null) packManager.status(event.getPlayer().getUniqueId(), event.getID(), event.getStatus());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (playback != null) playback.onQuit(event.getPlayer());
    }

    @Override
    public void onDisable() {
        if (playback != null) playback.stopAll();
        if (processor != null) processor.close();
        else if (nativeToolchain != null) nativeToolchain.close();
        if (packServer != null) packServer.close();
        if (repository != null) {
            try { repository.close(); }
            catch (Exception ex) { getLogger().warning("Error closing database: " + ex.getMessage()); }
        }
        getLogger().info("Musicolide disabled.");
    }
}
