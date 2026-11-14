package io.github.escolide.musicolide.geyser;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional reflective bridge to the public Geyser API. No Geyser classes are
 * linked by Musicolide, so Java-only servers never need Geyser installed.
 *
 * Tracks the Bedrock library revision offered during Geyser's connection pack
 * phase and marks it ready only after Geyser reports the session play-ready.
 */
public final class GeyserBridge {
    private final JavaPlugin plugin;
    private final Path bedrockPack;
    private final BedrockPackReadiness readiness = new BedrockPackReadiness();
    private final ConcurrentHashMap<UUID, Boolean> bedrockPlayers = new ConcurrentHashMap<>();
    private volatile FileStamp cachedPackStamp;
    private volatile String cachedPackFingerprint;
    private volatile boolean present;
    private volatile boolean unavailableLogged;
    private java.lang.reflect.Method apiMethod;
    private java.lang.reflect.Method isBedrockPlayerMethod;

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

    /**
     * Called from SessionLoadResourcePacksEvent after the current aggregate
     * library has been registered for this Bedrock connection.
     */
    public void recordPackOffered(String identity) {
        if (identity == null || identity.isBlank()) return;
        String fingerprint = currentPackFingerprint();
        if (fingerprint != null) readiness.offered(identity, fingerprint);
    }

    /**
     * Called from Geyser SessionJoinEvent. Geyser fires this after the Bedrock
     * client completes its initial resource-pack exchange and becomes play-ready.
     */
    public void associatePlayer(UUID uuid, String identity) {
        if (uuid != null && identity != null && !identity.isBlank()) {
            readiness.associate(identity, uuid.toString());
        }
    }

    public boolean recordSessionReady(UUID uuid, String identity) {
        if (uuid == null || identity == null || identity.isBlank()) return false;
        return readiness.markReady(identity, uuid.toString()) && hasCurrentPackFor(uuid);
    }

    /**
     * True while this player was offered the current library revision but has
     * not yet reached Geyser's play-ready event. This lets the shared start
     * barrier wait for a Bedrock client that is still completing pack loading.
     */
    public boolean isAwaitingPackReady(UUID uuid) {
        String current = currentPackFingerprint();
        return current != null && readiness.isAwaiting(uuid, current);
    }

    /**
     * True only when Geyser offered this exact current pack revision and then
     * reported the player's connection as play-ready. Old sessions become
     * stale automatically when the aggregate Bedrock pack is rebuilt.
     */
    public boolean hasCurrentPackFor(UUID uuid) {
        String current = currentPackFingerprint();
        return current != null && readiness.isReady(uuid, current);
    }

    private String currentPackFingerprint() {
        if (!isBedrockPackAvailable()) return null;
        try {
            BasicFileAttributes attributes = Files.readAttributes(bedrockPack, BasicFileAttributes.class);
            FileStamp stamp = new FileStamp(
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size(),
                    Objects.toString(attributes.fileKey(), "no-file-key"));
            String cached = cachedPackFingerprint;
            if (stamp.equals(cachedPackStamp) && cached != null) return cached;

            synchronized (this) {
                if (stamp.equals(cachedPackStamp) && cachedPackFingerprint != null) {
                    return cachedPackFingerprint;
                }
                // The builder publishes packs with an atomic file replacement.
                // The file key, size and modification time identify that published revision
                // without hashing a potentially large music library on every playback tick.
                String fingerprint = stamp.modifiedMillis() + ":" + stamp.size() + ":" + stamp.fileKey();
                cachedPackStamp = stamp;
                cachedPackFingerprint = fingerprint;
                return fingerprint;
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public boolean isBedrockPlayer(UUID uuid) {
        if (!present || uuid == null) return false;
        if (bedrockPlayers.containsKey(uuid)) return true;
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

    public void removePlayer(UUID uuid, String identity) {
        if (uuid != null) bedrockPlayers.remove(uuid);
        readiness.remove(uuid == null ? null : uuid.toString(), identity);
    }

    public void removePlayer(UUID uuid) {
        removePlayer(uuid, null);
    }

    private record FileStamp(long modifiedMillis, long size, String fileKey) {}
}
