package io.github.escolide.musicolide.geyser;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Isolated class with a compileOnly Geyser API dependency. Musicolide loads this
 * class only after detecting Geyser, so Java-only servers do not need Geyser.
 */
public final class GeyserPackIntegration implements EventRegistrar, AutoCloseable {
    private final JavaPlugin plugin;
    private final Path packFile;
    private final GeyserBridge bridge;
    private boolean registered;

    public GeyserPackIntegration(JavaPlugin plugin, Path packFile, GeyserBridge bridge) {
        this.plugin = plugin;
        this.packFile = packFile.toAbsolutePath().normalize();
        this.bridge = bridge;
    }

    public void register() {
        GeyserApi api = GeyserApi.api();
        if (api == null) {
            throw new IllegalStateException("Geyser's public API is not available yet.");
        }
        api.eventBus().subscribe(this, SessionLoadResourcePacksEvent.class, this::onSessionLoad);
        api.eventBus().subscribe(this, SessionJoinEvent.class, this::onSessionJoin);
        api.eventBus().subscribe(this, SessionDisconnectEvent.class, this::onSessionDisconnect);
        registered = true;
        plugin.getLogger().info("Registered Musicolide's Bedrock music library and synchronization events.");
    }

    private void onSessionLoad(SessionLoadResourcePacksEvent event) {
        if (!Files.isRegularFile(packFile)) return;
        try {
            ResourcePack pack = ResourcePack.create(PackCodec.path(packFile));
            event.register(pack);
            bridge.recordPackOffered(identity(event));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not register the Musicolide Bedrock resource pack for "
                    + event.connection().bedrockUsername() + ": " + ex.getMessage());
        }
    }

    private void onSessionJoin(SessionJoinEvent event) {
        UUID playerId = event.connection().javaUuid();
        String identity = identity(event);
        if (playerId != null && bridge.recordSessionReady(playerId, identity)) {
            plugin.getLogger().fine("Bedrock music library is ready for " + event.connection().bedrockUsername() + ".");
        }
    }

    private void onSessionDisconnect(SessionDisconnectEvent event) {
        UUID playerId = event.connection().javaUuid();
        bridge.removePlayer(playerId, identity(event));
    }

    private String identity(SessionLoadResourcePacksEvent event) {
        return identity(event.connection().xuid(), event.connection().bedrockUsername());
    }

    private String identity(SessionJoinEvent event) {
        return identity(event.connection().xuid(), event.connection().bedrockUsername());
    }

    private String identity(SessionDisconnectEvent event) {
        return identity(event.connection().xuid(), event.connection().bedrockUsername());
    }

    private String identity(String xuid, String username) {
        if (xuid != null && !xuid.isBlank()) return "xuid:" + xuid;
        if (username != null && !username.isBlank()) return "name:" + username.toLowerCase(Locale.ROOT);
        return null;
    }

    @Override
    public void close() {
        if (!registered) return;
        GeyserApi api = GeyserApi.api();
        if (api != null) api.eventBus().unregisterAll(this);
        registered = false;
    }
}
