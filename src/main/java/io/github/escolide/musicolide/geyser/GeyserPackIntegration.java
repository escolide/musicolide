package io.github.escolide.musicolide.geyser;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Isolated class with a compileOnly Geyser API dependency. Musicolide loads this
 * class only after detecting Geyser, so Java-only servers do not need Geyser.
 */
public final class GeyserPackIntegration implements EventRegistrar, AutoCloseable {
    private final JavaPlugin plugin;
    private final Path packFile;
    private boolean registered;

    public GeyserPackIntegration(JavaPlugin plugin, Path packFile) {
        this.plugin = plugin;
        this.packFile = packFile.toAbsolutePath().normalize();
    }

    public void register() {
        GeyserApi api = GeyserApi.api();
        if (api == null) {
            throw new IllegalStateException("Geyser's public API is not available yet.");
        }
        api.eventBus().subscribe(this, SessionLoadResourcePacksEvent.class, this::onSessionLoad);
        registered = true;
        plugin.getLogger().info("Registered Musicolide's Bedrock music library for new Geyser connections.");
    }

    private void onSessionLoad(SessionLoadResourcePacksEvent event) {
        if (!Files.isRegularFile(packFile)) return;
        try {
            ResourcePack pack = ResourcePack.create(PackCodec.path(packFile));
            event.register(pack);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not register the Musicolide Bedrock resource pack for "
                    + event.connection().bedrockUsername() + ": " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        if (!registered) return;
        GeyserApi api = GeyserApi.api();
        if (api != null) api.eventBus().unregisterAll(this);
        registered = false;
    }
}
