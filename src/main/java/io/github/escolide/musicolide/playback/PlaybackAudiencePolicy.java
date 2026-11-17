package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicZone;

import java.util.Collection;
import java.util.UUID;

/**
 * Small, Bukkit-independent rules for sessions that can affect the same listener.
 * These rules do not pre-empt the currently selected session; they are used only
 * to decide which requests share a queue and the order of manual switching.
 */
public final class PlaybackAudiencePolicy {
    private PlaybackAudiencePolicy() {}

    /**
     * Independent owners share a queue only when they request the same all-player
     * audience in the exact same zone. A personal target must never be merged into
     * somebody else's queue merely because it currently overlaps them.
     */
    public static boolean sharesQueueAcrossOwners(boolean requestedAllPlayers,
                                                   boolean existingAllPlayers,
                                                   MusicZone requestedZone,
                                                   MusicZone existingZone) {
        return requestedAllPlayers && existingAllPlayers
                && requestedZone != null && requestedZone.sameAs(existingZone);
    }

    /**
     * Manual switch cycle: personal target (@s/@p/player), spatial zone, then
     * server-wide public (@a). Normal arbitration still preserves the session
     * already audible to a listener until they explicitly switch.
     */
    public static int switchCycleRank(boolean allPlayers, MusicZone zone) {
        if (zone != null && zone.shape() != MusicZone.Shape.GLOBAL) return 1;
        return allPlayers ? 2 : 0;
    }

    public static boolean isPersonalTarget(boolean allPlayers,
                                           Collection<UUID> explicitListeners,
                                           UUID playerId) {
        return !allPlayers && playerId != null && explicitListeners != null
                && explicitListeners.size() == 1 && explicitListeners.contains(playerId);
    }
}
