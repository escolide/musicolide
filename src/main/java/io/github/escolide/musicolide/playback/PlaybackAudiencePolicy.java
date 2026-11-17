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
     * Independent owners share a queue only when they request the same all-player
     * audience in the exact same zone. A personal target must never be merged into
     * somebody else's queue merely because it currently overlaps them.
     */
    public static boolean sharesQueueAcrossOwners(boolean requestedAllPlayers,
                                                   Collection<UUID> requestedListeners,
                                                   boolean existingAllPlayers,
                                                   Collection<UUID> existingListeners,
                                                   MusicZone requestedZone,
                                                   MusicZone existingZone) {
        if (requestedAllPlayers != existingAllPlayers || requestedZone == null
                || !requestedZone.sameAs(existingZone)) return false;
        java.util.Set<UUID> requested = new java.util.HashSet<>(
                requestedListeners == null ? java.util.List.of() : requestedListeners);
        java.util.Set<UUID> existing = new java.util.HashSet<>(
                existingListeners == null ? java.util.List.of() : existingListeners);
        return requested.equals(existing);
    }

    /**
     * Manual switch cycle: personal target (@s/@p/player), spatial zone, then
     * server-wide public (@a). Normal arbitration still preserves the session
     * already audible to a listener until they explicitly switch.
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
