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
    public enum TargetScope { SELF, TARGETED, ZONE, PUBLIC }

    private PlaybackAudiencePolicy() {}

    public static TargetScope resolveTargetScope(String selector, boolean spatialZone) {
        if (spatialZone) return TargetScope.ZONE;
        if (selector == null) return TargetScope.TARGETED;
        return switch (selector.toLowerCase(java.util.Locale.ROOT)) {
            case "@s" -> TargetScope.SELF;
            case "@a" -> TargetScope.PUBLIC;
            default -> TargetScope.TARGETED;
        };
    }

    /**
     * Independent owners share the server-wide queue only when both requests are
     * addressed to all players in the exact same zone. Personal sessions stay owned
     * by their initiating player and are never merged merely because they overlap.
     */
    public static boolean sharesQueueAcrossOwners(boolean requestedAllPlayers,
                                                   boolean existingAllPlayers,
                                                   MusicZone requestedZone,
                                                   MusicZone existingZone) {
        return requestedAllPlayers && existingAllPlayers && requestedZone != null
                && requestedZone.sameAs(existingZone);
    }

    /**
     * Manual switch cycle: @s first, then spatial zones and @p/player targets,
     * then server-wide public @a. Current audible playback remains selected until
     * the listener explicitly switches.
     */
    public static int switchCycleRank(TargetScope scope) {
        if (scope == null) return 1;
        return switch (scope) {
            case SELF -> 0;
            case ZONE, TARGETED -> 1;
            case PUBLIC -> 2;
        };
    }

    public static boolean isPersonalTarget(TargetScope scope, boolean allPlayers,
                                           Collection<UUID> explicitListeners, UUID playerId) {
        return scope == TargetScope.SELF && !allPlayers && playerId != null
                && explicitListeners != null && explicitListeners.size() == 1
                && explicitListeners.contains(playerId);
    }

    /**
     * Returns the explicit manual choice when it is still eligible; otherwise
     * preserve the session the player is already hearing before falling back to
     * normal arbitration. Invalid/stale IDs are ignored.
     */
    public static UUID preferredEligibleSession(UUID manualSelection,
                                                UUID currentlyAudible,
                                                Collection<UUID> eligibleSessionIds) {
        if (eligibleSessionIds == null || eligibleSessionIds.isEmpty()) return null;
        if (manualSelection != null && eligibleSessionIds.contains(manualSelection)) {
            return manualSelection;
        }
        if (currentlyAudible != null && eligibleSessionIds.contains(currentlyAudible)) {
            return currentlyAudible;
        }
        return null;
    }
}
