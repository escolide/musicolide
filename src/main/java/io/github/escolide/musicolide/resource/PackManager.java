package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.geyser.GeyserBridge;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.util.RequestCooldowns;
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
    private final GeyserBridge geyser;

    // One stable Java library pack contains all catalogue tracks.
    private final Map<UUID, String> libraryReadyHash = new ConcurrentHashMap<>();
    private final Map<UUID, String> libraryRequestedHash = new ConcurrentHashMap<>();
    private final Map<UUID, String> libraryDeclinedHash = new ConcurrentHashMap<>();
    private final Set<UUID> acceptedLibraryPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> waitingForPublicAddress = ConcurrentHashMap.newKeySet();
    private final Set<UUID> removeLibraryWhenReady = ConcurrentHashMap.newKeySet();
    // Explicit /music download requests are rate-limited; playback-driven ensureLibrary calls are not.
    private final RequestCooldowns manualLibraryRequestCooldowns = new RequestCooldowns();

    // Only temporary per-session remainder packs are sent separately.
    private final Map<UUID, Set<UUID>> ready = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> declined = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, MusicTrack>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> active = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> removeWhenReady = new ConcurrentHashMap<>();
    // Keeps explicitly requested per-player synchronization tails in the prefetch queue.
    private final Map<UUID, Set<UUID>> pinnedTemporaryPacks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Path> deleteGeneratedWhenIdle = new ConcurrentHashMap<>();
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> deferredLibraryReload = new ConcurrentHashMap<>();
    // Back off immediate request failures (for example a temporary URL/address issue)
    // so a current track is not re-requested and logged on every playback tick.
    private final Map<UUID, Long> sendRetryAfter = new ConcurrentHashMap<>();
    // A playlist may require strictly serial per-track pack application even when
    // the server-wide default remains library mode.
    private final Set<UUID> serialDeliveryPlayers = ConcurrentHashMap.newKeySet();
    // URL-playlist library revisions are coalesced until the next track boundary.
    private final Set<UUID> deferredPlaylistLibraryUpdates = ConcurrentHashMap.newKeySet();
    private final Path acceptedPlayersFile;
    private final Path disabledPlayersFile;

    public PackManager(JavaPlugin plugin,
                       ResourcePackBuilder packBuilder,
                       int detectedPublicPort,
                       CompletableFuture<String> publicBaseUrl) {
        this(plugin, packBuilder, detectedPublicPort, publicBaseUrl, null);
    }

    public PackManager(JavaPlugin plugin,
                       ResourcePackBuilder packBuilder,
                       int detectedPublicPort,
                       CompletableFuture<String> publicBaseUrl,
                       GeyserBridge geyser) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.packBuilder = Objects.requireNonNull(packBuilder, "packBuilder");
        this.detectedPublicPort = detectedPublicPort;
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        this.geyser = geyser;
        this.acceptedPlayersFile = plugin.getDataFolder().toPath().resolve("accepted-resource-pack-players.txt");
        this.disabledPlayersFile = plugin.getDataFolder().toPath().resolve("disabled-players.txt");
        loadAcceptedPlayers();
        loadDisabledPlayers();
    }

    /**
     * Called once for each joining Java player. By default, the last successfully
     * loaded library revision is remembered so an unchanged pack is not offered
     * again on reconnect. Disabling the optimization forces one fresh application
     * on the next pack request after each join.
     */
    public void onPlayerJoin(Player player) {
        if (player == null || isPlayerDisabled(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("resource-packs.optimization.avoid-redundant-reloads", true)) {
            UUID playerId = player.getUniqueId();
            libraryReadyHash.remove(playerId);
            libraryDeclinedHash.remove(playerId);
        }
    }

    public boolean isPlayerDisabled(UUID playerId) {
        return playerId != null && disabledPlayers.contains(playerId);
    }

    /** True when this player still has an applied, requested, or queued Musicolide pack. */
    public boolean hasPlayerPackState(UUID playerId) {
        if (playerId == null) return false;
        return libraryReadyHash.containsKey(playerId)
                || libraryRequestedHash.containsKey(playerId)
                || waitingForPublicAddress.contains(playerId)
                || !active.getOrDefault(playerId, Set.of()).isEmpty()
                || !requested.getOrDefault(playerId, Set.of()).isEmpty()
                || !ready.getOrDefault(playerId, Set.of()).isEmpty()
                || !waiting.getOrDefault(playerId, new LinkedHashMap<>()).isEmpty()
                || !pinnedTemporaryPacks.getOrDefault(playerId, Set.of()).isEmpty();
    }

    /** Disables Musicolide once and immediately unloads their cached packs. */
    public boolean disablePlayer(Player player) {
        if (player == null || !disabledPlayers.add(player.getUniqueId())) return false;
        persistDisabledPlayers();
        deferredLibraryReload.remove(player.getUniqueId());
        unloadPlayerPacks(player);
        return true;
    }

    /** Re-enables Musicolide once; packs are requested by the next eligible playback event. */
    public boolean enablePlayer(Player player) {
        if (player == null || !disabledPlayers.remove(player.getUniqueId())) return false;
        persistDisabledPlayers();
        deferredLibraryReload.remove(player.getUniqueId());
        libraryReadyHash.remove(player.getUniqueId());
        libraryDeclinedHash.remove(player.getUniqueId());
        return true;
    }

    /**
     * Explicitly unloads this player's library and temporary pack cache.
     * When a current track ID is supplied, no replacement library is requested
     * until playback moves to a different track.
     */
    public boolean unloadPlayer(Player player, String currentTrackId) {
        if (player == null || !hasPlayerPackState(player.getUniqueId())) return false;
        deferredLibraryReload.put(player.getUniqueId(), currentTrackId == null ? "" : currentTrackId);
        unloadPlayerPacks(player);
        return true;
    }

    private void unloadPlayerPacks(Player player) {
        UUID playerId = player.getUniqueId();
        ResourcePackBuilder.BasePackInfo library;
        try {
            library = packBuilder.currentBasePackInfo();
        } catch (RuntimeException unavailable) {
            library = null;
        }
        String readyHash = libraryReadyHash.remove(playerId);
        libraryDeclinedHash.remove(playerId);
        waitingForPublicAddress.remove(playerId);
        if (libraryRequestedHash.containsKey(playerId)) {
            removeLibraryWhenReady.add(playerId);
        } else if (readyHash != null && library != null) {
            try { player.removeResourcePack(library.id()); } catch (Exception ignored) {}
        }

        Set<UUID> temporaryPacks = new HashSet<>(active.getOrDefault(playerId, Set.of()));
        temporaryPacks.addAll(requested.getOrDefault(playerId, Set.of()));
        temporaryPacks.addAll(ready.getOrDefault(playerId, Set.of()));
        for (UUID packId : temporaryPacks) {
            if (requested.getOrDefault(playerId, Set.of()).contains(packId)) {
                removeWhenReady.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(packId);
            } else {
                removePack(player, playerId, packId);
            }
        }
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue != null) queue.clear();
        pinnedTemporaryPacks.remove(playerId);
        cleanupLibraryArchivesAsync();
    }

    private void loadDisabledPlayers() {
        if (!Files.isRegularFile(disabledPlayersFile)) return;
        try {
            for (String line : Files.readAllLines(disabledPlayersFile)) {
                try {
                    if (!line.isBlank()) disabledPlayers.add(UUID.fromString(line.trim()));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not read disabled Musicolide player states: " + ex.getMessage());
        }
    }

    private void persistDisabledPlayers() {
        try {
            Files.createDirectories(disabledPlayersFile.getParent());
            List<String> values = disabledPlayers.stream().map(UUID::toString).sorted().toList();
            Files.write(disabledPlayersFile, values, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save disabled Musicolide player states: " + ex.getMessage());
        }
    }

    private boolean isIgnoredBedrockPlayer(Player player) {
        return player != null && geyser != null
                && geyser.isIgnoredBedrockPlayer(player.getUniqueId());
    }

    public boolean isBaseDeclined(Player player) {
        return isBaseDeclined(player, isPerTrackDeliveryEnabled());
    }

    /** Uses the session's effective delivery mode when a playlist overrides the universal mode. */
    public boolean isBaseDeclined(Player player, boolean perTrackDelivery) {
        if (player == null || perTrackDelivery) return false;
        String hash = currentLibraryHash();
        return hash != null && hash.equals(libraryDeclinedHash.get(player.getUniqueId()));
    }

    public boolean isReady(Player player, MusicTrack track) {
        return isReady(player, track, false, isPerTrackDeliveryEnabled());
    }

    public boolean isReady(Player player, MusicTrack track, boolean allowOlderLibraryRevision) {
        return isReady(player, track, allowOlderLibraryRevision, isPerTrackDeliveryEnabled());
    }

    /**
     * An already-playing track may continue using the previous aggregate library
     * revision after a new import is published. The next track must wait for the
     * latest revision, but updating the pack during the current song can interrupt
     * its client-side sound and trigger another resource-pack application.
     */
    public boolean isReady(Player player, MusicTrack track, boolean allowOlderLibraryRevision,
                           boolean perTrackDelivery) {
        if (player == null || isPlayerDisabled(player.getUniqueId())) return false;
        if (isTemporary(track) || perTrackDelivery) {
            return ready.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId());
        }
        String hash = currentLibraryHash();
        String readyHash = libraryReadyHash.get(player.getUniqueId());
        return hash != null && (hash.equals(readyHash)
                || allowOlderLibraryRevision && readyHash != null);
    }

    public boolean isDeclined(Player player, MusicTrack track) {
        return isDeclined(player, track, isPerTrackDeliveryEnabled());
    }

    public boolean isDeclined(Player player, MusicTrack track, boolean perTrackDelivery) {
        if (isTemporary(track) || perTrackDelivery) {
            return declined.getOrDefault(player.getUniqueId(), Set.of()).contains(track.packId());
        }
        return isBaseDeclined(player, perTrackDelivery);
    }

    /**
     * Requests the complete library. Calling this repeatedly is safe: an
     * unchanged pack is neither resent nor removed. retry=true clears a prior
     * decline for the current library version, for the explicit /music download
     * recovery command.
     */
    public boolean requestLibrary(Player player, boolean retry) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)
                || isPlayerDisabled(player.getUniqueId())) return false;
        UUID playerId = player.getUniqueId();
        String hash = currentLibraryHash();
        if (hash == null) return false;
        if (hash.equals(libraryReadyHash.get(playerId))) return true;
        // A pending request for any library revision, or a request waiting for
        // public-address discovery, already has work queued. Never duplicate it.
        if (libraryRequestedHash.containsKey(playerId) || waitingForPublicAddress.contains(playerId)) return true;

        long playerCooldown = Math.max(0L,
                plugin.getConfig().getLong("resource-packs.request-cooldown-seconds", 30L));
        long globalCooldown = Math.max(0L,
                plugin.getConfig().getLong("resource-packs.global-request-cooldown-seconds", 1L));
        if (manualLibraryRequestCooldowns.tryAcquire("manual-library", playerId.toString(),
                playerCooldown, globalCooldown) > 0L) return false;

        if (retry) libraryDeclinedHash.remove(playerId, hash);
        ensureLibrary(player);
        // ensureLibrary returns false when it starts a new addResourcePack request;
        // for the manual command, that is a successful request, not a failure.
        return hash.equals(libraryReadyHash.get(playerId))
                || libraryRequestedHash.containsKey(playerId)
                || waitingForPublicAddress.contains(playerId);
    }

    /** Called after public-address discovery finishes, mainly for on-join delivery. */
    public void publicAddressAvailable() {
        for (UUID playerId : new HashSet<>(waitingForPublicAddress)) {
            if (isPlayerDisabled(playerId)) {
                waitingForPublicAddress.remove(playerId);
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !isIgnoredBedrockPlayer(player)) {
                // A queued temporary resume tail is self-contained and can be
                // delivered in library mode without first applying the catalogue.
                if (isPerTrackDeliveryEnabled() || waiting.containsKey(playerId)) pump(player);
                else ensureLibrary(player);
            } else {
                waitingForPublicAddress.remove(playerId);
            }
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

    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming,
                         int tracksAhead, boolean deferLibraryRefresh) {
        prefetch(player, current, upcoming, tracksAhead, deferLibraryRefresh, isPerTrackDeliveryEnabled());
    }

    /**
     * Requests resources using the effective mode for this listener/session.
     * URL playlists can override the server-wide default without changing ordinary /play.
     */
    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming,
                         int tracksAhead, boolean deferLibraryRefresh, boolean perTrackDelivery) {
        prefetch(player, current, upcoming, tracksAhead, deferLibraryRefresh, perTrackDelivery, false);
    }

    /**
     * URL-playlist library mode can coalesce several server-side imports into one
     * aggregate-pack update at the next track boundary, instead of reloading the
     * resource stack while the current song is still playing.
     */
    public void prefetch(Player player, MusicTrack current, List<MusicTrack> upcoming,
                         int tracksAhead, boolean deferLibraryRefresh, boolean perTrackDelivery,
                         boolean coalescePlaylistLibraryUpdates) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)
                || isPlayerDisabled(player.getUniqueId())) return;
        UUID playerId = player.getUniqueId();
        if (perTrackDelivery) serialDeliveryPlayers.add(playerId);
        else serialDeliveryPlayers.remove(playerId);
        if (coalescePlaylistLibraryUpdates && deferLibraryRefresh && !perTrackDelivery) {
            deferredPlaylistLibraryUpdates.add(playerId);
        } else if (!deferLibraryRefresh || perTrackDelivery || !coalescePlaylistLibraryUpdates) {
            deferredPlaylistLibraryUpdates.remove(playerId);
        }
        String deferredTrackId = deferredLibraryReload.get(playerId);
        if (deferredTrackId != null) {
            if (current == null || (!deferredTrackId.isBlank() && deferredTrackId.equals(current.id()))) return;
            deferredLibraryReload.remove(playerId, deferredTrackId);
        }
        // Existing listeners keep their current immutable library revision until
        // this track finishes. A newly joining player still requests the latest pack.
        if (!perTrackDelivery) {
            if (deferLibraryRefresh) {
                if (!libraryReadyHash.containsKey(playerId) && !ensureLibrary(player)) return;
            } else if (!ensureLibrary(player)) {
                return;
            }
        }

        // Normal catalogue tracks use the aggregate library only in library mode.
        // Generated resume tails remain self-contained packs in either mode.
        LinkedHashMap<UUID, MusicTrack> queue = waiting.computeIfAbsent(
                player.getUniqueId(), ignored -> new LinkedHashMap<>());
        Set<UUID> desired = new HashSet<>(pinnedTemporaryPacks.getOrDefault(player.getUniqueId(), Set.of()));
        if (current != null && (isTemporary(current) || perTrackDelivery)) {
            desired.add(current.packId());
            enqueueIfNeeded(playerId, queue, current);
        }
        if (upcoming != null) {
            for (MusicTrack track : upcoming.stream().limit(Math.max(0, tracksAhead)).toList()) {
                if (isTemporary(track) || perTrackDelivery) {
                    desired.add(track.packId());
                    enqueueIfNeeded(playerId, queue, track);
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

    /**
     * Explicitly requests a generated per-player sync/resume tail. Unlike ordinary
     * prefetch entries, this pack remains pinned until releaseGeneratedTrack is
     * called, so a later normal prefetch pass cannot accidentally discard it.
     */
    public boolean requestTemporaryTrack(Player player, MusicTrack track) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)
                || isPlayerDisabled(player.getUniqueId()) || !isTemporary(track)) return false;

        UUID playerId = player.getUniqueId();
        // A private resume/synchronization tail is a complete resource pack of
        // its own. Send it directly in both Java delivery modes; requiring the
        // aggregate library here creates two consecutive client resource reloads.
        pinnedTemporaryPacks.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet())
                .add(track.packId());
        LinkedHashMap<UUID, MusicTrack> queue = waiting.computeIfAbsent(
                playerId, ignored -> new LinkedHashMap<>());
        enqueueIfNeeded(playerId, queue, track);
        pump(player);
        return true;
    }

    private boolean ensureLibrary(Player player) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)
                || isPlayerDisabled(player.getUniqueId())) return false;
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

    /** True when ordinary Java playback uses independent, cached packs for each track. */
    public boolean isPerTrackDeliveryEnabled() {
        String legacy = plugin.getConfig().getString("resource-packs.java-delivery-mode", "library");
        String mode = plugin.getConfig().getString("resource-packs.universal-playback-system", legacy);
        return mode != null && mode.trim().equalsIgnoreCase("per-track");
    }

    private boolean isTemporary(MusicTrack track) {
        return track != null && track.namespace() != null && track.namespace().startsWith("mr_");
    }

    private void enqueueIfNeeded(UUID playerId, LinkedHashMap<UUID, MusicTrack> queue, MusicTrack track) {
        UUID packId = track.packId();
        if (ready.getOrDefault(playerId, Set.of()).contains(packId)
                || requested.getOrDefault(playerId, Set.of()).contains(packId)
                || declined.getOrDefault(playerId, Set.of()).contains(packId)) return;
        Long retryAt = sendRetryAfter.get(packId);
        if (retryAt != null) {
            if (System.currentTimeMillis() < retryAt) return;
            sendRetryAfter.remove(packId, retryAt);
        }
        queue.putIfAbsent(packId, track);
    }

    private void pump(Player player) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)) return;
        UUID playerId = player.getUniqueId();
        int maximum = Math.max(1, plugin.getConfig().getInt("resource-packs.prefetch.max-concurrent-downloads", 2));
        // Applying multiple independent Java packs at once can cause concurrent
        // client resource-stack rebuilds. Keep per-track delivery serial, including
        // playlist-only overrides when universal playback is library mode.
        if (isPerTrackDeliveryEnabled() || serialDeliveryPlayers.contains(playerId)) maximum = 1;
        LinkedHashMap<UUID, MusicTrack> queue = waiting.get(playerId);
        if (queue == null) return;
        // Address discovery is asynchronous. Never block the Bukkit main thread
        // by joining its future while trying to send an on-demand track pack.
        if (!publicBaseUrl.isDone()) {
            waitingForPublicAddress.add(playerId);
            return;
        }
        waitingForPublicAddress.remove(playerId);
        while (inFlight.getOrDefault(playerId, 0) < maximum && !queue.isEmpty()) {
            Map.Entry<UUID, MusicTrack> next = queue.entrySet().iterator().next();
            queue.remove(next.getKey());
            if (ready.getOrDefault(playerId, Set.of()).contains(next.getKey())) continue;
            send(player, next.getValue());
        }
        if (queue.isEmpty()) waiting.remove(playerId);
    }

    private void send(Player player, MusicTrack track) {
        if (player == null || !player.isOnline() || isIgnoredBedrockPlayer(player)) return;
        UUID playerId = player.getUniqueId();
        boolean registered = false;
        try {
            String url = packUrl(track.packFileName());
            requested.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            active.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(track.packId());
            inFlight.merge(playerId, 1, Integer::sum);
            registered = true;
            player.addResourcePack(track.packId(), url, track.sha1Bytes(), null, false);
        } catch (Exception ex) {
            if (registered) {
                requested.getOrDefault(playerId, Set.of()).remove(track.packId());
                active.getOrDefault(playerId, Set.of()).remove(track.packId());
                inFlight.compute(playerId, (key, count) -> Math.max(0, (count == null ? 0 : count) - 1));
            }
            sendRetryAfter.put(track.packId(), System.currentTimeMillis() + 5000L);
            plugin.getLogger().warning("Could not send playback pack " + track.id()
                    + " to " + player.getName() + ": " + ex.getMessage()
                    + " (will retry after a short back-off if the track is still needed).");
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
        for (Set<UUID> pinned : pinnedTemporaryPacks.values()) pinned.remove(packId);
        pinnedTemporaryPacks.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        // Removing an applied pack forces vanilla to rebuild the resource stack
        // and commonly displays a loading screen. Keep the completed temporary
        // pack active for this connection; its bytes can be deleted server-side
        // once in-flight downloads finish. releaseAll() handles optional cleanup
        // only after the player's whole playback session has ended.
        sendRetryAfter.remove(packId);
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
        Player statusPlayer = Bukkit.getPlayer(playerId);
        if (isIgnoredBedrockPlayer(statusPlayer)) return;
        String statusName = status.name();
        if (isPlayerDisabled(playerId)) {
            if (isPendingStatus(statusName)) return;
            libraryRequestedHash.remove(playerId);
            libraryReadyHash.remove(playerId);
            removeLibraryWhenReady.remove(playerId);
            if (statusPlayer != null) {
                try { statusPlayer.removeResourcePack(packId); } catch (Exception ignored) {}
            }
            removePack(statusPlayer, playerId, packId);
            cleanupLibraryArchivesAsync();
            return;
        }
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
                if (currentHash != null && !currentHash.equals(requestedHash)
                        && !deferredPlaylistLibraryUpdates.contains(playerId)) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) ensureLibrary(player);
                }
                // Resume/synchronization tails can be queued while the library
                // is loading. Start those downloads only after the base pack is ready.
                Player readyPlayer = Bukkit.getPlayer(playerId);
                if (readyPlayer != null && readyPlayer.isOnline()
                        && libraryReadyHash.containsKey(playerId)) {
                    pump(readyPlayer);
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
        sendRetryAfter.remove(packId);

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
        deferredLibraryReload.remove(playerId);
        libraryDeclinedHash.remove(playerId);
        waitingForPublicAddress.remove(playerId);
        removeLibraryWhenReady.remove(playerId);
        ready.remove(playerId);
        requested.remove(playerId);
        declined.remove(playerId);
        waiting.remove(playerId);
        active.remove(playerId);
        removeWhenReady.remove(playerId);
        pinnedTemporaryPacks.remove(playerId);
        serialDeliveryPlayers.remove(playerId);
        deferredPlaylistLibraryUpdates.remove(playerId);
        inFlight.remove(playerId);
        for (UUID packId : new HashSet<>(deleteGeneratedWhenIdle.keySet())) deleteGeneratedFileIfIdle(packId);
        cleanupLibraryArchivesAsync();
    }

    /** Removes obsolete Java library revisions after their replacement has been published. */
    public void cleanupObsoleteLibraryArchives() {
        cleanupLibraryArchivesAsync();
    }

    private void cleanupLibraryArchivesAsync() {
        CompletableFuture.runAsync(this::cleanupLibraryArchives);
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
