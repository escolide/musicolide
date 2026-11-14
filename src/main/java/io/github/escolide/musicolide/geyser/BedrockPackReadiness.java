package io.github.escolide.musicolide.geyser;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which immutable Bedrock library revision was offered to each player
 * and whether Geyser subsequently reported that connection as play-ready.
 */
public final class BedrockPackReadiness {
    private final ConcurrentHashMap<UUID, String> offered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> ready = new ConcurrentHashMap<>();

    public void offered(UUID playerId, String revision) {
        if (playerId == null || revision == null || revision.isBlank()) return;
        ready.remove(playerId);
        offered.put(playerId, revision);
    }

    public boolean markReady(UUID playerId) {
        if (playerId == null) return false;
        String revision = offered.get(playerId);
        if (revision == null) return false;
        ready.put(playerId, revision);
        return true;
    }

    public boolean isReady(UUID playerId, String currentRevision) {
        return playerId != null && currentRevision != null
                && currentRevision.equals(ready.get(playerId));
    }

    public boolean isAwaiting(UUID playerId, String currentRevision) {
        return playerId != null && currentRevision != null
                && currentRevision.equals(offered.get(playerId))
                && !currentRevision.equals(ready.get(playerId));
    }

    public void remove(UUID playerId) {
        if (playerId == null) return;
        offered.remove(playerId);
        ready.remove(playerId);
    }
}
