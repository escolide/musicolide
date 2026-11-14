package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class PackManager {
    private final JavaPlugin plugin;
    private final ResourcePackBuilder packBuilder;
    private final int detectedPublicPort;
    private final CompletableFuture<String> publicBaseUrl;

    // One stable Java library pack contains all catalogue tracks.
    private final Map<UUID, String> libraryReadyHash = new ConcurrentHashMap<>();
    private final Map<UUID, String> libraryRequestedHash = new ConcurrentHashMap<>();
    private final Map<UUID, String> libraryDeclinedHash = new ConcurrentHashMap<>();
    private final Set<UUID> acceptedLibraryPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> waitingForPublicAddress = ConcurrentHashMap.newKeySet();
    private final Set<UUID> removeLibraryWhenReady = ConcurrentHashMap.newKeySet();

    // Only temporary per-session remainder packs are sent separately.
    private final Map<UUID, Set<UUID>> ready = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> declined = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, MusicTrack>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> active = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> removeWhenReady = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Path> deleteGeneratedWhenIdle = new ConcurrentHashMap<>();
    private final Path acceptedPlayersFile;

    public PackManager(JavaPlugin plugin,
                       ResourcePackBuilder packBuilder,
                       int detectedPublicPort,
                       CompletableFuture<String> publicBaseUrl) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.packBuilder = Objects.requireNonNull(packBuilder, "packBuilder");
        this.detectedPublicPort = detectedPublicPort;
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        this.acceptedPlayersFile = plugin.getDataFolder().toPath().resolve("accepted-resource-pack-players.txt");
        loadAcceptedPlayers();
    }

    public boolean isBaseDeclined(Player player) {
        String hash = currentLibraryHash();
        return hash != null && hash.equals(libraryDeclinedHash.get(player.getUniqueId()));
    }

    public boolean isReady(Player player, MusicTrack track) {
        if (isTemporary(track)) {
            return ready.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId());
        }
        String hash = currentLibraryHash();
        return hash != null && hash.equals(libraryReadyHash.get(player.getUniqueId()));
    }

    public boolean isDeclined(Player player, MusicTrack track) {
        if (isTemporary(track)) {
            return declined.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId());
        }
        return isBaseDeclined(player);
    }

    /**
     * Requests the complete library. Calling this repeatedly is safe: an
     * unchanged pack is neither resent nor removed. retry=true clears a prior
     * decline for the current library version, for the explicit /music download
     * recovery command.
     */
    public boolean requestLibrary(Player player, boolean retry) {
        if (player == null || !player.isOnline()) return false;
        String hash = currentLibraryHash();
        if (hash == null) return false;
        if (retry) libraryDeclinedHash.remove(player.getUniqueId(), hash);
        return ensureLibrary(player);
    }

    /** Called after public-address discovery finishes, mainly for on-join delivery. */
    public void publicAddressAvailable() {
        for (UUID playerId : new HashSet<>(waitingForPublicAddress)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) ensureLibrary(player);
            else waitingForPublicAddress.remove(playerId);
        }
    }

    /**
     * True while a client is downloading this pack. Because the Java library
     * archive contains all catalogue assets, any library download protects any
     * temporary catalogue track from retention cleanup until it completes.
     */
    public boolean isDownloadInProgress(UUID packId) {
        if (!libraryRequestedHash.isEmpty()) return true;
        return requested.values().stream().anyMatch(requestedPacks -> requestedPacks.contains(packId));
    }

    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming, int tracksAhead) {
        if (!ensureLibrary(player)) return;

        // Normal tracks are already in the single library pack. Only FFmpeg
        // generated resume/synchronization tails need their own temporary pack.
        LinkedHashMap<UUID, MusicTrack> queue = waiting.computeIfAbsent(
                player.getUniqueId(), ignored -> new LinkedHashMap<>());
        Set<UUID> desired = new HashSet<>();
        if (isTemporary(current)) {
            desired.add(current.packId());
            enqueueIfNeeded(player.getUniqueId(), queue, current);
        }
        if (upcoming != null) {
            for (MusicTrack track : upcoming.stream().limit(Math.max(0, tracksAhead)).toList()) {
                if (isTemporary(track)) {
                    desired.add(track.packId());
                    enqueueIfNeeded(player.getUniqueId(), queue, track);
                }
            }
        }
        queue.keySet().removeIf(id -> !desired.contains(id));
        removeWhenReady.computeIfPresent(player.getUniqueId(), (id, pending) -> {
            pending.removeIf(desired::contains);
            return pending.isEmpty() ? null : pending;
        });
        pump(player);
    }

    private boolean ensureLibrary(Player player) {
        UUID playerId = player.getUniqueId();
        ResourcePackBuilder.BasePackInfo pack;
        try {
            pack = packBuilder.currentBasePackInfo();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("The Musicolide Java library is unavailable: " + ex.getMessage());
            return false;
        }

        String hash = pack.sha1();
        if (hash.equals(libraryReadyHash.get(playerId))) return true;
        // Do not start a second pack application while an earlier library
        // revision is still downloading. The terminal status handler checks
        // whether a newer revision exists and requests only the latest one.
        if (libraryRequestedHash.containsKey(playerId)) return false;
        if (hash.equals(libraryDeclinedHash.get(playerId))) return false;

        if (!publicBaseUrl.isDone()) {
            waitingForPublicAddress.add(playerId);
            return false;
        }

        try {
            String url = packUrl(pack.fileName());
            byte[] sha1 = HexFormat.of().parseHex(hash);
            // The custom prompt is shown only until this UUID has successfully
            // loaded the library once. Vanilla can reuse its cached SHA-1 later.
            String prompt = acceptedLibraryPlayers.contains(playerId) ? null
                    : plugin.getConfig().getString("resource-packs.prompt", "Musicolide music library");
            libraryRequestedHash.put(playerId, hash);
            waitingForPublicAddress.remove(playerId);
            player.addResourcePack(pack.id(), url, sha1, prompt,
                    plugin.getConfig().getBoolean("resource-packs.required-on-first-use", false));
        } catch (Exception ex) {
            libraryRequestedHash.remove(playerId);
            libraryDeclinedHash.put(playerId, hash);
            cleanupLibraryArchivesAsync();
            plugin.getLogger().warning("Could not send the Musicolide Java library to "
                    + player.getName() + ": " + ex.getMessage());
        }
        return false;
    }

    private String currentLibraryHash() {
        try {
            ResourcePackBuilder.BasePackInfo pack = packBuilder.currentBasePackInfo();
            return pack == null ? null : pack.sha1();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String packUrl(String fileName) {
        final String base;
        try {
            base = publicBaseUrl.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException(cause.getMessage() == null
                    ? "Public resource-pack address discovery failed." : cause.getMessage(), cause);
        }
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("No public resource-pack address is configured or could be discovered.");
        }
        String prefix = plugin.getConfig().getString("server.path-prefix", "/packs");
        boolean autoDetectPort = plugin.getConfig().getBoolean("server.auto-detect-port", true);
        int detected = autoDetectPort ? detectedPublicPort : -1;
        int configured = plugin.getConfig().getInt("server.public-port", -1);
        boolean sharedPortMode = plugin.getConfig().getString("server.hosting-mode", "shared-port")
                .equalsIgnoreCase("shared-port");
        return PackUrlBuilder.build(base, prefix, fileName, detected, configured, sharedPortMode);
    }

    private boolean isTemporary(MusicTrack track) {
        return track != null && track.namespace() != null && track.namespace().startsWith("mr_");
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
            requested.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            active.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            inFlight.merge(playerId, 1, Integer::sum);
            player.addResourcePack(track.packId(), url, track.sha1Bytes(), null, false);
        } catch (Exception ex) {
            requested.getOrDefault(playerId, Set.of()).remove(track.packId());
            inFlight.compute(playerId, (key, count) -> Math.max(0, (count == null ? 0 : count) - 1));
            plugin.getLogger().warning("Could not send temporary playback pack " + track.id()
                    + " to " + player.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Releases an FFmpeg-generated remainder after its session stops using it.
     * A pending client download is allowed to finish before its file is deleted.
     */
    public void releaseGeneratedTrack(MusicTrack track) {
        if (!isTemporary(track)) return;
        UUID packId = track.packId();
        Path directory = plugin.getDataFolder().toPath().resolve("packs").toAbsolutePath().normalize();
        Path file = directory.resolve(track.packFileName()).normalize();
        if (!file.startsWith(directory) || !track.packFileName().matches("[A-Za-z0-9_-]+\\.zip")) return;

        for (LinkedHashMap<UUID, MusicTrack> queue : waiting.values()) queue.remove(packId);
        for (Map.Entry<UUID, Set<UUID>> entry : active.entrySet()) {
            UUID playerId = entry.getKey();
            if (!entry.getValue().contains(packId)) continue;
            if (requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                removeWhenReady.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
            } else {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) removePack(player, playerId, packId);
                else entry.getValue().remove(packId);
            }
        }
        deleteGeneratedWhenIdle.put(packId, file);
        deleteGeneratedFileIfIdle(packId);
    }

    private void deleteGeneratedFileIfIdle(UUID packId) {
        if (requested.values().stream().anyMatch(packs -> packs.contains(packId))) return;
        Path file = deleteGeneratedWhenIdle.remove(packId);
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not delete temporary resumed-audio pack "
                    + file.getFileName() + ": " + ex.getMessage());
        }
    }

    public void releaseAll(Player player) {
        if (!plugin.getConfig().getBoolean("resource-packs.cache.remove-pack-after-session", false)) {
            // When disabled, keep the complete library and transient assets active
            // for this connection. The vanilla client retains downloaded files too.
            return;
        }
        UUID playerId = player.getUniqueId();
        ResourcePackBuilder.BasePackInfo library = packBuilder.currentBasePackInfo();
        String readyHash = libraryReadyHash.remove(playerId);
        if (readyHash != null || libraryRequestedHash.containsKey(playerId)) {
            if (libraryRequestedHash.containsKey(playerId)) {
                removeLibraryWhenReady.add(playerId);
            } else {
                try { player.removeResourcePack(library.id()); } catch (Exception ignored) {}
            }
        }

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

    static boolean isPendingStatus(String statusName) {
        return "ACCEPTED".equals(statusName) || "DOWNLOADED".equals(statusName);
    }

    public void status(UUID playerId, UUID packId,
                       org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status) {
        if (status == null) return;
        String statusName = status.name();
        if (isPendingStatus(statusName)) return;

        ResourcePackBuilder.BasePackInfo library = packBuilder.currentBasePackInfo();
        if (library.id().equals(packId)) {
            String requestedHash = libraryRequestedHash.remove(playerId);
            if (requestedHash == null) return;
            if ("SUCCESSFULLY_LOADED".equals(statusName)) {
                libraryReadyHash.put(playerId, requestedHash);
                libraryDeclinedHash.remove(playerId, requestedHash);
                acceptedLibraryPlayers.add(playerId);
                persistAcceptedPlayers();
                if (removeLibraryWhenReady.remove(playerId)) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        try { player.removeResourcePack(library.id()); } catch (Exception ignored) {}
                    }
                    libraryReadyHash.remove(playerId, requestedHash);
                }
                // If the library changed during a download, request the current revision once.
                String currentHash = currentLibraryHash();
                if (currentHash != null && !currentHash.equals(requestedHash)) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) ensureLibrary(player);
                }
            } else {
                libraryDeclinedHash.put(playerId, requestedHash);
                if (removeLibraryWhenReady.remove(playerId)) removeLibraryWhenReady.remove(playerId);
            }
            cleanupLibraryArchivesAsync();
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
        deleteGeneratedFileIfIdle(packId);
    }

    private void loadAcceptedPlayers() {
        try {
            if (!Files.isRegularFile(acceptedPlayersFile)) return;
            for (String line : Files.readAllLines(acceptedPlayersFile)) {
                try { acceptedLibraryPlayers.add(UUID.fromString(line.trim())); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not load resource-pack acceptance history: " + ex.getMessage());
        }
    }

    private void persistAcceptedPlayers() {
        try {
            Files.createDirectories(acceptedPlayersFile.getParent());
            List<String> lines = acceptedLibraryPlayers.stream().map(UUID::toString).sorted().toList();
            Files.write(acceptedPlayersFile, lines, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save resource-pack acceptance history: " + ex.getMessage());
        }
    }

    public void removePlayer(UUID playerId) {
        libraryReadyHash.remove(playerId);
        libraryRequestedHash.remove(playerId);
        libraryDeclinedHash.remove(playerId);
        waitingForPublicAddress.remove(playerId);
        removeLibraryWhenReady.remove(playerId);
        ready.remove(playerId);
        requested.remove(playerId);
        declined.remove(playerId);
        waiting.remove(playerId);
        active.remove(playerId);
        removeWhenReady.remove(playerId);
        inFlight.remove(playerId);
        for (UUID packId : new HashSet<>(deleteGeneratedWhenIdle.keySet())) deleteGeneratedFileIfIdle(packId);
        cleanupLibraryArchivesAsync();
    }

    private void cleanupLibraryArchivesAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::cleanupLibraryArchives);
    }

    private void cleanupLibraryArchives() {
        Set<String> retained = new HashSet<>(libraryRequestedHash.values());
        try {
            retained.add(packBuilder.currentBasePackInfo().sha1());
            packBuilder.cleanupLibraryArchives(retained);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not clean obsolete Java library revisions: " + ex.getMessage());
        }
    }
}
