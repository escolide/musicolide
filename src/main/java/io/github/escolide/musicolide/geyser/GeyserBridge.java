package io.github.escolide.musicolide.geyser;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional reflective bridge to the public Geyser API. No Geyser classes are
 * linked by Musicolide, so Java-only servers never need Geyser installed.
 *
 * Identifies Bedrock connections and reports whether the generated Bedrock
 * library pack is available for Geyser's separate connection-time delivery path.
 */
public final class GeyserBridge {
    private final JavaPlugin plugin;
    private final Path bedrockPack;
    private final Map<UUID, Boolean> bedrockPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerJoinTimes = new ConcurrentHashMap<>();
    private volatile boolean present;
    private volatile boolean unavailableLogged;
    private Method apiMethod;
    private Method isBedrockPlayerMethod;

    public GeyserBridge(JavaPlugin plugin, Path bedrockPack) {
        this.plugin = plugin;
        this.bedrockPack = bedrockPack == null ? null : bedrockPack.toAbsolutePath().normalize();
        initialize();
    }

    private void initialize() {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) geyser = Bukkit.getPluginManager().getPlugin("Geyser");
        if (geyser == null || !geyser.isEnabled()) {
            plugin.getLogger().info("Geyser not detected; Java Edition mode only.");
            return;
        }
        try {
            ClassLoader loader = geyser.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", true, loader);
            apiMethod = apiClass.getMethod("api");
            isBedrockPlayerMethod = apiClass.getMethod("isBedrockPlayer", UUID.class);
            Object api = apiMethod.invoke(null);
            if (api == null) {
                plugin.getLogger().warning("Geyser is installed, but its public API is not ready. "
                        + "Bedrock players will be treated as unsupported until the API becomes available.");
                return;
            }
            present = true;
            plugin.getLogger().info("Geyser detected through its public API. Bedrock connections can be identified.");
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Geyser was detected but its public API could not be linked: " + ex.getMessage());
        }
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isBedrockPackAvailable() {
        return bedrockPack != null && Files.isRegularFile(bedrockPack);
    }

    public void recordPlayerJoin(UUID uuid) {
        if (uuid != null) playerJoinTimes.put(uuid, System.currentTimeMillis());
    }

    /**
     * A Bedrock pack is delivered only during Geyser's connection pack phase.
     * When the library ZIP changes after a player joined, require a reconnect
     * rather than attempting to play a sound whose asset the client lacks.
     */
    public boolean hasCurrentPackFor(UUID uuid) {
        if (!isBedrockPackAvailable() || uuid == null) return false;
        long joinedAt = playerJoinTimes.getOrDefault(uuid, 0L);
        if (joinedAt == 0L) return false;
        try {
            return joinedAt >= Files.getLastModifiedTime(bedrockPack).toMillis();
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    public boolean isBedrockPlayer(UUID uuid) {
        if (!present || uuid == null) return false;
        Boolean cached = bedrockPlayers.get(uuid);
        if (cached != null) return cached;
        try {
            Object api = apiMethod.invoke(null);
            if (api == null) return false;
            boolean result = Boolean.TRUE.equals(isBedrockPlayerMethod.invoke(api, uuid));
            // Do not cache false: Geyser may not have completed its connection mapping
            // on the first server tick after the Java player joins.
            if (result) bedrockPlayers.put(uuid, true);
            return result;
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                plugin.getLogger().warning("Geyser player detection stopped working: " + ex.getMessage());
            }
            present = false;
            bedrockPlayers.clear();
            return false;
        }
    }

    public void removePlayer(UUID uuid) {
        bedrockPlayers.remove(uuid);
        playerJoinTimes.remove(uuid);
    }
}
