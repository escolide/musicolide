package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PackManager {
    private final JavaPlugin plugin;
    private final ResourcePackBuilder.BasePackInfo basePack;
    private final int detectedPublicPort;
    private final Set<UUID> baseReady = ConcurrentHashMap.newKeySet();
    private final Set<UUID> baseRequested = ConcurrentHashMap.newKeySet();
    private final Set<UUID> baseDeclined = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> ready = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> declined = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, MusicTrack>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> active = new ConcurrentHashMap<>();
    // Packs whose download is in flight when playback no longer needs them.
    // Remove them from the active client stack as soon as the status event arrives.
    private final Map<UUID, Set<UUID>> removeWhenReady = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inFlight = new ConcurrentHashMap<>();

    public PackManager(JavaPlugin plugin, ResourcePackBuilder.BasePackInfo basePack, int detectedPublicPort) {
        this.plugin = plugin;
        this.basePack = Objects.requireNonNull(basePack, "basePack");
        this.detectedPublicPort = detectedPublicPort;
    }

    public boolean isBaseDeclined(Player player) {
        return baseDeclined.contains(player.getUniqueId());
    }

    public boolean isReady(Player player, MusicTrack track) { return ready.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId()); }
    public boolean isDeclined(Player player, MusicTrack track) { return declined.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId()); }

    /**
     * True while any Java client is downloading this pack. Expiry cleanup must
     * leave the file in place until the resource-pack status event arrives.
     */
    public boolean isDownloadInProgress(UUID packId) {
        return requested.values().stream().anyMatch(requestedPacks -> requestedPacks.contains(packId));
    }

    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming, int tracksAhead) {
        // Send the tiny stable pack first. Paper/Minecraft shows its one-time
        // server consent prompt only for the first request; track packs follow
        // after consent and are downloaded asynchronously in the background.
        if (!ensureBasePack(player)) return;

        UUID playerId = player.getUniqueId();
        LinkedHashMap<UUID, MusicTrack> queue = waiting.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        Set<UUID> desired = new HashSet<>();
        long maxBytes = plugin.getConfig().getLong("resource-packs.prefetch.max-prefetch-mb-per-player", 40) * 1024L * 1024L;
        long bytes = 0;
        if (current != null) {
            desired.add(current.packId());
            bytes += current.packSizeBytes();
            enqueueIfNeeded(playerId, queue, current);
        }
        if (upcoming != null) {
            for (MusicTrack track : upcoming.stream().limit(Math.max(0, tracksAhead)).toList()) {
                if (bytes + track.packSizeBytes() > maxBytes) break;
                desired.add(track.packId());
                bytes += track.packSizeBytes();
                enqueueIfNeeded(playerId, queue, track);
            }
        }
        queue.keySet().removeIf(id -> !desired.contains(id));
        // A pack can become desired again while its download is still in flight.
        // Cancel a deferred removal in that case.
        removeWhenReady.computeIfPresent(playerId, (id, pending) -> {
            pending.removeIf(desired::contains);
            return pending.isEmpty() ? null : pending;
        });
        if (plugin.getConfig().getBoolean("resource-packs.cache.remove-pack-after-session", true)) {
            Set<UUID> activeSet = active.get(playerId);
            if (activeSet != null) {
                for (UUID packId : new HashSet<>(activeSet)) {
                    if (!desired.contains(packId)) {
                        if (requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                            removeWhenReady.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
                        } else {
                            removePack(player, playerId, packId);
                        }
                    }
                }
            }
        }
        pump(player);
    }

    private boolean ensureBasePack(Player player) {
        UUID playerId = player.getUniqueId();
        if (baseReady.contains(playerId)) return true;
        if (baseDeclined.contains(playerId) || baseRequested.contains(playerId)) return false;

        try {
            String url = packUrl(basePack.fileName());
            byte[] hash = HexFormat.of().parseHex(basePack.sha1());
            player.addResourcePack(basePack.id(), url, hash,
                    plugin.getConfig().getString("resource-packs.prompt", "Musicolide music pack"),
                    plugin.getConfig().getBoolean("resource-packs.required-on-first-use", false));
            baseRequested.add(playerId);
        } catch (Exception ex) {
            baseDeclined.add(playerId);
            plugin.getLogger().warning("Could not send the initial Musicolide base pack to "
                    + player.getName() + ": " + ex.getMessage());
        }
        return false;
    }

    private String packUrl(String fileName) {
        String base = plugin.getConfig().getString("server.public-base-url", "http://127.0.0.1");
        String prefix = plugin.getConfig().getString("server.path-prefix", "/packs");
        boolean autoDetectPort = plugin.getConfig().getBoolean("server.auto-detect-port", true);
        int detected = autoDetectPort ? detectedPublicPort : -1;
        int configured = plugin.getConfig().getInt("server.public-port", -1);
        boolean sharedPortMode = plugin.getConfig().getString("server.hosting-mode", "shared-port")
                .equalsIgnoreCase("shared-port");
        return PackUrlBuilder.build(base, prefix, fileName, detected, configured, sharedPortMode);
    }

    private void enqueueIfNeeded(UUID playerId, LinkedHashMap<UUID, MusicTrack> queue, MusicTrack track) {
        if (ready.getOrDefault(playerId, Set.of()).contains(track.packId())
                || requested.getOrDefault(playerId, Set.of()).contains(track.packId())
                || declined.getOrDefault(playerId, Set.of()).contains(track.packId())) return;
        queue.putIfAbsent(track.packId(), track);
    }

    private void pump(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        int maximum = Math.max(1, plugin.getConfig().getInt("resource-packs.prefetch.max-concurrent-downloads", 2));
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue == null) return;
        while (inFlight.getOrDefault(playerId, 0) < maximum && !queue.isEmpty()) {
            Map.Entry<UUID, MusicTrack> next = queue.entrySet().iterator().next();
            queue.remove(next.getKey());
            if (ready.getOrDefault(playerId, Set.of()).contains(next.getKey())) continue;
            send(player, next.getValue());
        }
        if (queue.isEmpty()) waiting.remove(playerId);
    }

    private void send(Player player, MusicTrack track) {
        UUID playerId = player.getUniqueId();
        String url = packUrl(track.packFileName());
        try {
            // The base pack already received the one-time consent prompt.
            // No prompt is sent with subsequent per-track background packs.
            player.addResourcePack(track.packId(), url, track.sha1Bytes(), null, false);
            requested.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            active.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            inFlight.merge(playerId, 1, Integer::sum);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not send resource pack " + track.id() + " to " + player.getName() + ": " + ex.getMessage());
        }
    }

    public void releaseAll(Player player) {
        UUID playerId = player.getUniqueId();
        Set<UUID> activeSet = active.get(playerId);
        if (activeSet != null) {
            for (UUID packId : new HashSet<>(activeSet)) {
                if (requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                    removeWhenReady.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
                } else {
                    removePack(player, playerId, packId);
                }
            }
        }
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue != null) queue.clear();
    }

    private void removePack(Player player, UUID playerId, UUID packId) {
        try { player.removeResourcePack(packId); } catch (Exception ignored) {}
        Set<UUID> activeSet = active.get(playerId);
        if (activeSet != null) activeSet.remove(packId);
        Set<UUID> readySet = ready.get(playerId);
        if (readySet != null) readySet.remove(packId);
        Set<UUID> declinedSet = declined.get(playerId);
        if (declinedSet != null) declinedSet.remove(packId);
        Set<UUID> deferred = removeWhenReady.get(playerId);
        if (deferred != null) deferred.remove(packId);
    }

    /**
     * ACCEPTED and DOWNLOADED are progress notifications, not completion.
     * Keep the request in-flight until the client either applies the pack or
     * reports a terminal result. Status names are used instead of enum
     * constants for newer Paper values so the plugin remains binary-source
     * compatible with the older compileOnly API.
     */
    static boolean isPendingStatus(String statusName) {
        return "ACCEPTED".equals(statusName) || "DOWNLOADED".equals(statusName);
    }

    public void status(UUID playerId, UUID packId, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status) {
        if (status == null) return;
        String statusName = status.name();

        // Both values may arrive while the same download is still running.
        // Clearing requested/baseRequested here makes the 5-tick playback
        // loop enqueue and send the exact same pack repeatedly.
        if (isPendingStatus(statusName)) return;

        if (basePack.id().equals(packId)) {
            baseRequested.remove(playerId);
            if ("SUCCESSFULLY_LOADED".equals(statusName)) {
                baseDeclined.remove(playerId);
                baseReady.add(playerId);
            } else {
                // Every other non-progress status is terminal. Do not let an
                // invalid URL, failed reload, or discard trigger a resend loop.
                baseReady.remove(playerId);
                baseDeclined.add(playerId);
            }
            return;
        }

        Set<UUID> requestedSet = requested.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
        boolean wasRequested = requestedSet.remove(packId);
        if (wasRequested) {
            inFlight.compute(playerId, (key, count) -> Math.max(0, (count == null ? 0 : count) - 1));
        }

        Player player = Bukkit.getPlayer(playerId);
        boolean removeAfterDownload = removeWhenReady.getOrDefault(playerId, Set.of()).contains(packId);
        if ("SUCCESSFULLY_LOADED".equals(statusName)) {
            declined.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).remove(packId);
            if (removeAfterDownload || !active.getOrDefault(playerId, Set.of()).contains(packId)) {
                if (player != null) removePack(player, playerId, packId);
            } else {
                ready.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
            }
        } else {
            ready.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).remove(packId);

            // A discard caused by Musicolide removing a no-longer-needed pack
            // must not poison that pack for a later session. A client discard
            // of a pack that is still wanted is suppressed like a decline.
            boolean stillDesired = (!removeAfterDownload
                    && active.getOrDefault(playerId, Set.of()).contains(packId))
                    || waiting.getOrDefault(playerId, new LinkedHashMap<>()).containsKey(packId);
            Set<UUID> declinedSet = declined.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
            if (!"DISCARDED".equals(statusName) || stillDesired) declinedSet.add(packId);
            else declinedSet.remove(packId);

            Set<UUID> activeSet = active.get(playerId);
            if (activeSet != null) activeSet.remove(packId);
            Set<UUID> deferred = removeWhenReady.get(playerId);
            if (deferred != null) deferred.remove(packId);
        }
        pump(player);
    }

    public void removePlayer(UUID playerId) {
        baseReady.remove(playerId); baseRequested.remove(playerId); baseDeclined.remove(playerId);
        ready.remove(playerId); requested.remove(playerId); declined.remove(playerId);
        waiting.remove(playerId); active.remove(playerId); removeWhenReady.remove(playerId); inFlight.remove(playerId);
    }
}
