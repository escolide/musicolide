package io.github.escolide.musicolide.geyser;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which immutable Bedrock library revision was offered to each Bedrock
 * connection identity, then associates that identity with the Java UUID once
 * Geyser reports that the connection is play-ready.
 */
public final class BedrockPackReadiness {
    private final ConcurrentHashMap<String, String> offered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> ready = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> identityByPlayer = new ConcurrentHashMap<>();

    public void offered(String identity, String revision) {
        if (identity == null || identity.isBlank() || revision == null || revision.isBlank()) return;
        ready.remove(identity);
        offered.put(identity, revision);
    }

    public void associate(String identity, String playerId) {
        if (identity == null || identity.isBlank() || playerId == null || playerId.isBlank()) return;
        identityByPlayer.put(playerId, identity);
    }

    public boolean markReady(String identity, String playerId) {
        if (identity == null || identity.isBlank() || playerId == null || playerId.isBlank()) return false;
        String revision = offered.get(identity);
        if (revision == null) return false;
        ready.put(identity, revision);
        identityByPlayer.put(playerId, identity);
        return true;
    }

    public boolean isReady(String playerId, String currentRevision) {
        if (playerId == null || currentRevision == null) return false;
        String identity = identityByPlayer.get(playerId);
        return identity != null && currentRevision.equals(ready.get(identity));
    }

    public boolean isAwaiting(String playerId, String currentRevision) {
        if (playerId == null || currentRevision == null) return false;
        String identity = identityByPlayer.get(playerId);
        return identity != null && currentRevision.equals(offered.get(identity))
                && !currentRevision.equals(ready.get(identity));
    }

    public void remove(String playerId, String identity) {
        if (playerId != null) {
            String mapped = identityByPlayer.remove(playerId);
            if (identity == null) identity = mapped;
        }
        if (identity != null) {
            offered.remove(identity);
            ready.remove(identity);
        }
    }
}
