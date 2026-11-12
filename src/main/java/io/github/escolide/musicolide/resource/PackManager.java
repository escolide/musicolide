package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PackManager {
    private final JavaPlugin plugin;
    private final Map<UUID, Set<UUID>> ready = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> declined = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, MusicTrack>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> active = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inFlight = new ConcurrentHashMap<>();

    public PackManager(JavaPlugin plugin) { this.plugin = plugin; }
    public boolean isReady(Player player, MusicTrack track) { return ready.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId()); }
    public boolean isDeclined(Player player, MusicTrack track) { return declined.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId()); }

    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming, int tracksAhead) {
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
        if (plugin.getConfig().getBoolean("resource-packs.cache.remove-pack-after-session", true)) {
            Set<UUID> activeSet = active.get(playerId);
            if (activeSet != null) {
                for (UUID packId : new HashSet<>(activeSet)) {
                    if (!desired.contains(packId) && !requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                        try { player.removeResourcePack(packId); } catch (Exception ignored) {}
                        activeSet.remove(packId);
                        Set<UUID> readySet = ready.get(playerId);
                        Set<UUID> declinedSet = declined.get(playerId);
                        if (readySet != null) readySet.remove(packId);
                        if (declinedSet != null) declinedSet.remove(packId);
                    }
                }
            }
        }
        pump(player);
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
        String base = plugin.getConfig().getString("server.public-base-url", "http://127.0.0.1:8765");
        String prefix = plugin.getConfig().getString("server.path-prefix", "/packs");
        String url = base.replaceAll("/+$", "") + (prefix.startsWith("/") ? prefix : "/" + prefix) + "/" + track.packFileName();
        try {
            player.addResourcePack(track.packId(), url, track.sha1Bytes(),
                    plugin.getConfig().getString("resource-packs.prompt", "Musicolide music pack"),
                    plugin.getConfig().getBoolean("resource-packs.required-on-first-use", false));
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
                if (!requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                    try { player.removeResourcePack(packId); } catch (Exception ignored) {}
                    activeSet.remove(packId);
                    Set<UUID> readySet = ready.get(playerId);
                    if (readySet != null) readySet.remove(packId);
                }
            }
        }
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue != null) queue.clear();
    }

    public void status(UUID playerId, UUID packId, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status) {
        Set<UUID> requestedSet = requested.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
        boolean wasRequested = requestedSet.remove(packId);
        if (wasRequested) inFlight.compute(playerId, (key, count) -> Math.max(0, (count == null ? 0 : count) - 1));
        boolean stillActive = active.getOrDefault(playerId, Set.of()).contains(packId);
        if (status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            declined.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).remove(packId);
            if (stillActive) ready.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
        } else if (status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED
                || status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            ready.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).remove(packId);
            declined.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
        }
        pump(Bukkit.getPlayer(playerId));
    }

    public void releaseAll(Player player) {
        UUID playerId = player.getUniqueId();
        Set<UUID> activeSet = active.get(playerId);
        if (activeSet != null) {
            for (UUID packId : new HashSet<>(activeSet)) {
                if (!requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                    try { player.removeResourcePack(packId); } catch (Exception ignored) {}
                    activeSet.remove(packId);
                    Set<UUID> readySet = ready.get(playerId);
                    if (readySet != null) readySet.remove(packId);
                }
            }
        }
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue != null) queue.clear();
    }

    public void removePlayer(UUID playerId) {
        ready.remove(playerId); requested.remove(playerId); declined.remove(playerId);
        waiting.remove(playerId); active.remove(playerId); inFlight.remove(playerId);
    }
}
