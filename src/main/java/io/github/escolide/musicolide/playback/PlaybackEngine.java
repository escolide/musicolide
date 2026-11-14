package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.geyser.GeyserBridge;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.util.TextUtil;
import io.github.escolide.musicolide.model.MusicZone;
import io.github.escolide.musicolide.region.WorldGuardBridge;
import io.github.escolide.musicolide.resource.PackManager;
import io.github.escolide.musicolide.storage.TrackRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PlaybackEngine {
    // Minecraft labels SoundCategory.RECORDS as "Jukebox/Note Blocks".
    private static final SoundCategory SOUND_CATEGORY = SoundCategory.RECORDS;
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final PackManager packs;
    private final AudioProcessor audioProcessor;
    private final List<PlaybackSession> sessions = new ArrayList<>();
    private final Map<UUID, PlayerPreferences> preferences = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPlayback> playerPlayback = new HashMap<>();
    private final Map<UUID, Long> warningTimes = new HashMap<>();
    private final Map<UUID, String> declinedWarnings = new HashMap<>();
    private final WorldGuardBridge worldGuard;
    private final GeyserBridge geyser;
    private final Map<UUID, Long> bedrockWarnings = new HashMap<>();
    // Per-session barrier deadline and players excluded after missing that deadline.
    private final Map<UUID, BarrierWait> barrierWaits = new HashMap<>();
    private final Map<UUID, String> barrierExcludedTracks = new HashMap<>();
    private final Map<UUID, String> automaticSyncAttempts = new HashMap<>();
    private final Map<UUID, Long> syncGenerations = new HashMap<>();
    private BukkitTask task;
    private BukkitTask segmentTask;

    public PlaybackEngine(JavaPlugin plugin, TrackRepository repository, PackManager packs,
                          AudioProcessor audioProcessor, WorldGuardBridge worldGuard, GeyserBridge geyser) {
        this.plugin = plugin;
        this.repository = repository;
        this.packs = packs;
        this.audioProcessor = audioProcessor;
        this.worldGuard = worldGuard;
        this.geyser = geyser;
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig().getLong("general.check-interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, interval);
        // Segment changes are time-critical: the normal zone/pack loop may run less often.
        // This lightweight watcher only checks players who are already receiving audio.
        segmentTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSegments, 1L, 1L);
    }

    public synchronized PlaybackSession startSession(UUID owner, String ownerName, MusicZone zone,
                                                       boolean allPlayers, Collection<UUID> listeners,
                                                       Collection<MusicTrack> tracks, int priority) {
        int maximum = plugin.getConfig().getInt("limits.max-sessions", 20);
        if (sessions.size() >= maximum) throw new IllegalStateException("Maximum number of music sessions reached.");
        PlaybackSession session = new PlaybackSession(owner, ownerName, zone, allPlayers, listeners, tracks, priority);
        if (!session.isEmpty()) sessions.add(session);
        return session;
    }

    public enum PlayDisposition { STARTED, QUEUED, QUEUE_FULL }

    /**
     * Starts a new playback session, or appends /play to the sender's existing
     * session when its audience and listening zone are identical. This avoids
     * accidental per-player overlap when the DJ simply wants the next song.
     */
    public synchronized PlayDisposition playOrQueue(UUID owner, String ownerName, MusicZone zone,
                                                      boolean allPlayers, Collection<UUID> listeners,
                                                      MusicTrack track, int priority) {
        PlaybackSession existing = sessions.stream()
                .filter(session -> owner == null
                        ? session.owner() == null && session.ownerName().equals(ownerName)
                        : session.isOwner(owner))
                .filter(session -> !session.paused() && !session.resumePreparing())
                .filter(session -> session.matchesPlaybackRequest(allPlayers, listeners, zone))
                .max(Comparator.comparingLong(PlaybackSession::startedAt))
                .orElse(null);
        if (existing != null && !existing.isEmpty()) {
            int maximumQueue = plugin.getConfig().getInt("limits.max-queue-length", 500);
            if (existing.queueSize() >= maximumQueue) return PlayDisposition.QUEUE_FULL;
            existing.enqueue(track);
            return PlayDisposition.QUEUED;
        }
        startSession(owner, ownerName, zone, allPlayers, listeners, List.of(track), priority);
        return PlayDisposition.STARTED;
    }

    public synchronized Optional<PlaybackSession> findOwned(UUID owner) {
        return sessions.stream().filter(s -> s.isOwner(owner)).max(Comparator.comparingLong(PlaybackSession::startedAt));
    }

    public synchronized List<PlaybackSession> sessions() { return List.copyOf(sessions); }

    /**
     * Used by retention cleanup to avoid deleting a temporary pack while a
     * session still references it as the current, queued, or repeating track.
     */
    public synchronized boolean isTrackInUse(String trackId) {
        return sessions.stream().anyMatch(session -> session.referencesTrack(trackId));
    }

    public synchronized boolean control(UUID actor, boolean admin, String action) {
        PlaybackSession session = findOwned(actor).orElseGet(() -> admin && !sessions.isEmpty()
                ? sessions.get(sessions.size() - 1) : null);
        if (session == null) return false;
        switch (action) {
            case "pause" -> {
                session.pause();
                stopPlayersInSession(session);
            }
            case "resume" -> session.resume();
            case "skip" -> {
                MusicTrack previous = session.playbackTrack();
                stopPlayersInSession(session);
                session.advance();
                releaseGeneratedTrack(previous);
                if (session.isEmpty()) sessions.remove(session);
            }
            case "stop" -> {
                sessions.remove(session);
                stopPlayersInSession(session);
                releaseGeneratedTrack(session.playbackTrack());
                session.cancelResumePreparation();
            }
            default -> { return false; }
        }
        return true;
    }

    public enum ResumeStart { NOT_FOUND, RESUMED, PREPARING, BEDROCK_FALLBACK, ALREADY_PREPARING }

    /**
     * Resumes a paused session. In resumable mode, Java Edition gets a newly
     * encoded OGG tail and a transient pack; the session stays paused during
     * processing and starts its clock when the new pack becomes playable.
     */
    public ResumeStart resume(UUID actor, boolean admin, Consumer<String> completion) {
        PlaybackSession session = findControllable(actor, admin);
        if (session == null) return ResumeStart.NOT_FOUND;
        if (!session.paused()) return ResumeStart.RESUMED;

        MusicTrack original = session.current();
        boolean resumableMode = "resumable".equalsIgnoreCase(
                plugin.getConfig().getString("audio.playback-mode", "resumable"));
        if (!resumableMode || original == null || !original.isStreamed()) {
            session.resume();
            return ResumeStart.RESUMED;
        }

        // Geyser's aggregate pack is assembled at startup and cannot receive
        // these per-resume transient packs. Keep mixed sessions compatible.
        if (hasOnlineBedrockTarget(session)) {
            session.resume();
            return ResumeStart.BEDROCK_FALLBACK;
        }

        long offsetMs = session.elapsedMs();
        long generation = session.beginResumePreparation();
        if (generation < 0) return ResumeStart.ALREADY_PREPARING;
        stopPlayersInSession(session);
        generateAndInstallRemainder(session, original, offsetMs, generation,
                "&aThe remaining audio is ready from " + formatTime(offsetMs)
                        + ". Waiting for the resource pack to load.", completion);
        return ResumeStart.PREPARING;
    }

    public enum SeekStart { NOT_FOUND, SEEKED, PREPARING, BEDROCK_UNSUPPORTED, UNSUPPORTED, ALREADY_PREPARING }

    /**
     * Seeks legacy segmented tracks directly. In resumable mode, seeking a
     * streamed track regenerates the remaining OGG from the requested timestamp.
     */
    public SeekStart seek(UUID actor, boolean admin, long targetMs, Consumer<String> completion) {
        PlaybackSession session = findControllable(actor, admin);
        if (session == null || session.current() == null) return SeekStart.NOT_FOUND;

        MusicTrack original = session.current();
        if (targetMs < 0 || targetMs >= original.durationMs()) {
            throw new IllegalArgumentException("Seek position must be within the track duration.");
        }
        if (!original.isStreamed()) {
            session.seek(targetMs);
            stopPlayersInSession(session);
            return SeekStart.SEEKED;
        }
        boolean resumableMode = "resumable".equalsIgnoreCase(
                plugin.getConfig().getString("audio.playback-mode", "resumable"));
        if (!resumableMode) return SeekStart.UNSUPPORTED;
        if (hasOnlineBedrockTarget(session)) return SeekStart.BEDROCK_UNSUPPORTED;

        boolean wasPaused = session.paused();
        long generation = session.beginSeekPreparation(targetMs);
        if (generation < 0) return SeekStart.ALREADY_PREPARING;
        stopPlayersInSession(session);
        String message = wasPaused
                ? "&aSeek position set to " + formatTime(targetMs) + ". Use /music resume to continue."
                : "&aSeeking to " + formatTime(targetMs)
                        + ". Playback will restart after the temporary resource pack loads.";
        generateAndInstallRemainder(session, original, targetMs, generation, message, completion);
        return SeekStart.PREPARING;
    }

    private PlaybackSession findControllable(UUID actor, boolean admin) {
        synchronized (this) {
            return findOwned(actor).orElseGet(() -> admin && !sessions.isEmpty()
                    ? sessions.get(sessions.size() - 1) : null);
        }
    }

    private boolean hasOnlineBedrockTarget(PlaybackSession session) {
        return geyser != null && Bukkit.getOnlinePlayers().stream()
                .anyMatch(player -> session.targets(player.getUniqueId())
                        && geyser.isBedrockPlayer(player.getUniqueId()));
    }

    private void generateAndInstallRemainder(PlaybackSession session, MusicTrack original,
                                              long targetMs, long generation, String readyMessage,
                                              Consumer<String> completion) {
        audioProcessor.createResumeTrack(original, targetMs).whenComplete((remainder, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        boolean currentOperation = session.failResumePreparation(generation);
                        if (currentOperation && completion != null) {
                            completion.accept("&cCould not prepare audio: " + rootMessage(error));
                        }
                        return;
                    }

                    MusicTrack previous = session.playbackTrack();
                    boolean stillActive;
                    synchronized (PlaybackEngine.this) {
                        stillActive = sessions.contains(session);
                    }
                    if (!stillActive || !session.completeResumePreparation(generation, remainder)) {
                        packs.releaseGeneratedTrack(remainder);
                        return;
                    }
                    if (previous != remainder) releaseGeneratedTrack(previous);
                    if (completion != null) completion.accept(readyMessage);
                }));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void releaseGeneratedTrack(MusicTrack track) {
        if (track != null && track.namespace() != null && track.namespace().startsWith("mr_")) {
            packs.releaseGeneratedTrack(track);
        }
    }

    public synchronized boolean enqueue(UUID actor, boolean admin, MusicTrack track) {
        PlaybackSession session = findOwned(actor).orElseGet(() -> admin && !sessions.isEmpty()
                ? sessions.get(sessions.size() - 1) : null);
        if (session == null) return false;
        int maximum = plugin.getConfig().getInt("limits.max-queue-length", 500);
        if (session.queueSize() >= maximum) return false;
        session.enqueue(track);
        return true;
    }

    public void setPlayerVolume(Player player, int volume) {
        PlayerPreferences pref = preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences);
        pref.volume(volume);
        savePreferences(player.getUniqueId(), pref);
    }

    public void setPlayerMuted(Player player, boolean muted) {
        PlayerPreferences pref = preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences);
        pref.muted(muted);
        savePreferences(player.getUniqueId(), pref);
        if (muted) stopPlayer(player);
    }

    /** Explicitly retries the Java music-library request for this player. */
    public boolean requestLibrary(Player player) {
        return packs.requestLibrary(player, true);
    }

    public enum SyncStart {
        PREPARING, NO_SESSION, NOT_PLAYING, PAUSED, PACK_NOT_READY, PACK_DECLINED,
        BEDROCK_UNSUPPORTED, TRACK_NEAR_END, ALREADY_PREPARING
    }

    /**
     * Re-synchronizes only this Java player with the currently audible session.
     * It generates a private OGG tail from the shared session timeline plus the
     * configured preparation offset; other listeners and the session clock are
     * never paused or changed.
     */
    public SyncStart synchronizePlayer(Player player, Consumer<String> completion) {
        if (player == null || !player.isOnline()) return SyncStart.NO_SESSION;
        Candidate candidate = findCandidate(player);
        if (candidate == null) return SyncStart.NO_SESSION;

        PlaybackSession session = candidate.session;
        MusicTrack sourceTrack = session.current();
        if (sourceTrack == null) return SyncStart.NO_SESSION;
        if (session.paused() || session.resumePreparing()) return SyncStart.PAUSED;
        if (!session.clockStarted()) return SyncStart.NOT_PLAYING;
        if (isPlayerMuted(player)
                || playbackVolume(player, session, candidate.mode == MusicZone.AudioMode.FADE) <= 0) {
            return SyncStart.NO_SESSION;
        }
        if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) {
            return SyncStart.BEDROCK_UNSUPPORTED;
        }
        if (packs.isBaseDeclined(player) || packs.isDeclined(player, sourceTrack)) {
            return SyncStart.PACK_DECLINED;
        }
        if (!packs.isReady(player, sourceTrack)) return SyncStart.PACK_NOT_READY;

        UUID playerId = player.getUniqueId();
        PlayerPlayback existing = playerPlayback.get(playerId);
        if (existing != null && (existing.syncPreparing
                || (existing.syncTrack != null && !packs.isReady(player, existing.syncTrack)))) {
            return SyncStart.ALREADY_PREPARING;
        }

        double delaySeconds = plugin.getConfig().getDouble(
                "playback.synchronization.late-join.delay-seconds", 4.0);
        if (!Double.isFinite(delaySeconds) || delaySeconds < 0) delaySeconds = 4.0;
        long targetMs = session.elapsedMs() + Math.round(delaySeconds * 1000.0);
        if (targetMs >= sourceTrack.durationMs()) return SyncStart.TRACK_NEAR_END;

        // Keep this player excluded from the ordinary timeline while their
        // private synchronized tail is being generated and downloaded.
        barrierExcludedTracks.put(playerId, barrierTrackKey(session, session.playbackTrack()));
        stopPlayer(player);

        long generation = syncGenerations.merge(playerId, 1L, Long::sum);
        PlayerPlayback pending = new PlayerPlayback();
        pending.sessionId = session.id();
        pending.syncSessionId = session.id();
        pending.syncSourceTrackId = sourceTrack.id();
        pending.syncPreparing = true;
        pending.fading = candidate.mode == MusicZone.AudioMode.FADE;
        playerPlayback.put(playerId, pending);

        audioProcessor.createResumeTrack(sourceTrack, targetMs).whenComplete((remainder, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean currentRequest = syncGenerations.getOrDefault(playerId, 0L) == generation
                            && playerPlayback.get(playerId) == pending;
                    if (!currentRequest) {
                        if (remainder != null) releaseGeneratedTrack(remainder);
                        return;
                    }
                    if (error != null) {
                        playerPlayback.remove(playerId, pending);
                        if (completion != null && player.isOnline()) {
                            completion.accept("&cCould not prepare synchronized audio: " + rootMessage(error));
                        }
                        return;
                    }

                    Candidate currentCandidate = player.isOnline() ? findCandidate(player) : null;
                    boolean stillActive;
                    synchronized (PlaybackEngine.this) {
                        stillActive = sessions.contains(session);
                    }
                    if (!stillActive || currentCandidate == null || currentCandidate.session != session
                            || session.current() == null || !sourceTrack.id().equals(session.current().id())) {
                        playerPlayback.remove(playerId, pending);
                        releaseGeneratedTrack(remainder);
                        if (completion != null && player.isOnline()) {
                            completion.accept("&eThe playback session changed before synchronization finished. Run /music sync again.");
                        }
                        return;
                    }

                    pending.syncPreparing = false;
                    pending.syncTrack = remainder;
                    pending.trackId = remainder.id();
                    if (!packs.requestTemporaryTrack(player, remainder)) {
                        playerPlayback.remove(playerId, pending);
                        releaseGeneratedTrack(remainder);
                        if (completion != null && player.isOnline()) {
                            completion.accept("&cThe synchronized audio pack could not be requested. Your playback remains stopped until you sync again.");
                        }
                        return;
                    }
                    if (completion != null && player.isOnline()) {
                        completion.accept("&aSynchronized audio prepared from "
                                + formatTime(targetMs) + ". It will start when its temporary pack finishes loading.");
                    }
                }));
        return SyncStart.PREPARING;
    }

    public int getPlayerVolume(Player player) {
        return preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences).volume();
    }

    public boolean isPlayerMuted(Player player) {
        return preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences).muted();
    }

    private PlayerPreferences loadPreferences(UUID uuid) {
        try {
            int[] values = repository.getPreference(uuid.toString());
            return new PlayerPreferences(values[0], values[1] != 0);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Could not load preferences: " + ex.getMessage());
            return new PlayerPreferences(100, false);
        }
    }

    private void savePreferences(UUID uuid, PlayerPreferences pref) {
        try { repository.savePreference(uuid.toString(), pref.volume(), pref.muted()); }
        catch (SQLException ex) { plugin.getLogger().warning("Could not save preferences: " + ex.getMessage()); }
    }

    private void tick() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<PlaybackSession> active;
        synchronized (this) {
            for (PlaybackSession session : sessions) {
                if (session.finished()) {
                    MusicTrack previous = session.playbackTrack();
                    session.advance();
                    releaseGeneratedTrack(previous);
                }
            }
            sessions.removeIf(PlaybackSession::isEmpty);
            active = List.copyOf(sessions);
        }

        Set<String> activeTrackKeys = new HashSet<>();
        for (PlaybackSession session : active) {
            MusicTrack track = session.playbackTrack();
            if (track != null) activeTrackKeys.add(barrierTrackKey(session, track));
        }
        barrierWaits.entrySet().removeIf(entry ->
                !activeTrackKeys.contains(entry.getKey() + ":" + entry.getValue().trackId()));
        barrierExcludedTracks.entrySet().removeIf(entry -> !activeTrackKeys.contains(entry.getValue()));

        Map<UUID, Candidate> winners = new HashMap<>();
        Map<PlaybackSession, Set<UUID>> suppressed = new HashMap<>();
        for (Player player : online) {
            Candidate winner = null;
            for (PlaybackSession session : active) {
                if (!session.targets(player.getUniqueId()) || session.current() == null
                        || session.paused() || session.resumePreparing() || isPlayerMuted(player)) continue;

                // A recipient that cannot use this pack must not reserve the
                // player and make a different, playable session look like an overlap.
                if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) {
                    if (!geyser.isBedrockPackAvailable()
                            || !geyser.hasCurrentPackFor(player.getUniqueId())) continue;
                } else if (packs.isBaseDeclined(player)
                        || packs.isDeclined(player, session.playbackTrack())) {
                    warnPackDeclined(player, session.current(), session.playbackTrack());
                    continue;
                }

                MusicZone zone = session.zone();
                if (zone.worldId() != null && !zone.worldId().equals(player.getWorld().getUID())) continue;
                MusicZone.AudioMode mode;
                if (zone.shape() == MusicZone.Shape.REGION) {
                    boolean inside = worldGuard != null && worldGuard.isInside(player.getLocation(), zone.regionId());
                    mode = inside ? MusicZone.AudioMode.STEREO : MusicZone.AudioMode.NONE;
                } else {
                    mode = zone.audioMode(player.getWorld().getUID(), player.getX(), player.getY(), player.getZ());
                }
                if (mode == MusicZone.AudioMode.NONE) continue;
                Candidate candidate = new Candidate(session, mode);
                if (winner == null || compare(candidate, winner) < 0) {
                    if (winner != null) suppressed.computeIfAbsent(winner.session, ignored -> new HashSet<>()).add(player.getUniqueId());
                    winner = candidate;
                } else {
                    suppressed.computeIfAbsent(session, ignored -> new HashSet<>()).add(player.getUniqueId());
                }
            }
            if (winner != null) winners.put(player.getUniqueId(), winner);
        }

        // Request packs for every current recipient before starting the shared
        // session clock. Otherwise the first client whose pack downloads fastest
        // starts the clock while slower clients later play the stream from 00:00.
        for (Player player : online) {
            Candidate candidate = winners.get(player.getUniqueId());
            if (candidate != null && !isPlayerMuted(player)
                    && (geyser == null || !geyser.isBedrockPlayer(player.getUniqueId()))) {
                int prefetch = Math.max(0, plugin.getConfig().getInt(
                        "resource-packs.prefetch.tracks-ahead", 4));
                packs.prefetch(player, candidate.session.playbackTrack(),
                        candidate.session.upcoming(prefetch),
                        plugin.getConfig().getBoolean("resource-packs.prefetch.enabled", true) ? prefetch : 0);
            }
        }
        startReadySessionClocks(online, winners);

        if (plugin.getConfig().getBoolean("playback.overlap.warn-session-owner", true)) {
            long cooldown = plugin.getConfig().getLong("playback.overlap.warning-cooldown-seconds", 60) * 1000L;
            for (Map.Entry<PlaybackSession, Set<UUID>> entry : suppressed.entrySet()) {
                PlaybackSession session = entry.getKey();
                if (session.owner() == null || entry.getValue().isEmpty()) continue;
                boolean blockedByAudibleSession = entry.getValue().stream()
                        .map(winners::get)
                        .filter(Objects::nonNull)
                        .anyMatch(winner -> winner.session.clockStarted()
                                && !winner.session.paused() && !winner.session.resumePreparing());
                if (!blockedByAudibleSession) continue;
                long last = warningTimes.getOrDefault(session.id(), 0L);
                if (System.currentTimeMillis() - last >= cooldown) {
                    Player owner = Bukkit.getPlayer(session.owner());
                    if (owner != null) owner.sendMessage(message("messages.overlap-warning"));
                    warningTimes.put(session.id(), System.currentTimeMillis());
                }
            }
        }

        for (Player player : online) {
            Candidate winner = winners.get(player.getUniqueId());
            if (winner == null) {
                stopPlayer(player);
                boolean hasTargetedSession = active.stream()
                        .anyMatch(session -> session.targets(player.getUniqueId()));
                // "remove-pack-after-session" means after the player's targeted
                // session ends, not whenever they step outside a spatial zone.
                if (!hasTargetedSession) packs.releaseAll(player);
                continue;
            }
            if (isPlayerMuted(player)) {
                stopPlayer(player);
                continue;
            }

            startAutomaticCatchUpIfNeeded(player, winner);
            playFor(player, winner);
        }
    }

    /**
     * Java-only bounded start barrier. Bedrock clients never participate in
     * this wait. Once at least one Java recipient is ready, pending clients may
     * be excluded at the deadline and automatically catch up when their pack
     * finishes loading. If nobody is ready yet, the shared clock stays stopped
     * so a slow solo player does not lose the track before their pack arrives.
     * A declined Java pack is already settled and never holds the barrier.
     */
    private void startReadySessionClocks(List<Player> online, Map<UUID, Candidate> winners) {
        if (!plugin.getConfig().getBoolean("playback.synchronization.start-barrier", true)) {
            for (Candidate candidate : winners.values()) candidate.session.startClockIfNeeded();
            return;
        }

        Set<PlaybackSession> candidates = new HashSet<>();
        winners.values().forEach(candidate -> candidates.add(candidate.session));
        long timeoutMillis = Math.max(0L,
                plugin.getConfig().getLong("playback.synchronization.start-barrier-timeout-seconds", 7L)) * 1000L;
        long now = System.currentTimeMillis();

        for (PlaybackSession session : candidates) {
            if (session.clockStarted() || session.paused() || session.resumePreparing()) continue;
            MusicTrack track = session.playbackTrack();
            if (track == null) continue;

            boolean allJavaRecipientsSettled = true;
            boolean hasReadyJavaRecipient = false;
            boolean hasPendingJavaRecipient = false;
            for (Player player : online) {
                Candidate candidate = winners.get(player.getUniqueId());
                if (candidate == null || candidate.session != session || isPlayerMuted(player)) continue;
                if (playbackVolume(player, session, candidate.mode == MusicZone.AudioMode.FADE) <= 0) continue;

                // Bedrock readiness is deliberately independent of the Java barrier.
                if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) continue;

                if (packs.isReady(player, track)) {
                    hasReadyJavaRecipient = true;
                } else if (!packs.isDeclined(player, track)) {
                    hasPendingJavaRecipient = true;
                    allJavaRecipientsSettled = false;
                }
            }

            if (allJavaRecipientsSettled) {
                session.startClockIfNeeded();
                barrierWaits.remove(session.id());
                continue;
            }

            BarrierWait wait = barrierWaits.get(session.id());
            if (wait == null || !wait.trackId().equals(track.id())) {
                wait = new BarrierWait(track.id(), now);
                barrierWaits.put(session.id(), wait);
            }

            if (now - wait.startedAtMillis() >= timeoutMillis) {
                // Do not consume the song while every Java recipient is still
                // downloading. The timeout caps the wait for lagging clients,
                // not the wait for the first client that can actually play.
                // As soon as one Java recipient is ready, release the cohort and
                // let late clients use the automatic per-player catch-up path.
                if (hasPendingJavaRecipient && !hasReadyJavaRecipient) continue;

                String trackKey = barrierTrackKey(session, track);
                for (Player player : online) {
                    Candidate candidate = winners.get(player.getUniqueId());
                    if (candidate == null || candidate.session != session || isPlayerMuted(player)) continue;
                    if (playbackVolume(player, session, candidate.mode == MusicZone.AudioMode.FADE) <= 0) continue;
                    if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) continue;
                    if (!packs.isReady(player, track) && !packs.isDeclined(player, track)) {
                        barrierExcludedTracks.put(player.getUniqueId(), trackKey);
                    }
                }
                session.startClockIfNeeded();
                barrierWaits.remove(session.id());
            }
        }
    }

    private String barrierTrackKey(PlaybackSession session, MusicTrack track) {
        return session.id() + ":" + track.id();
    }

    /**
     * Advances audio segments on a one-tick cadence without forcing the more
     * expensive zone and resource-pack checks to run every tick. This avoids
     * audible gaps caused by noticing a segment boundary late in the normal loop.
     */
    private void tickSegments() {
        if (playerPlayback.isEmpty()) return;

        Map<UUID, PlaybackSession> activeById = new HashMap<>();
        synchronized (this) {
            if (sessions.isEmpty()) return;
            for (PlaybackSession session : sessions) activeById.put(session.id(), session);
        }

        for (Map.Entry<UUID, PlayerPlayback> entry : new ArrayList<>(playerPlayback.entrySet())) {
            UUID playerId = entry.getKey();
            PlayerPlayback state = entry.getValue();
            if (state.sessionId == null || state.trackId == null) continue;

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;

            PlaybackSession session = activeById.get(state.sessionId);
            if (session == null || session.paused() || !session.targets(playerId)
                    || state.syncPreparing || state.syncTrack != null) continue;
            MusicTrack track = session.playbackTrack();
            if (track == null || !track.id().equals(state.trackId)) continue;

            int segment = session.segmentIndex();
            if (segment < 0 || segment >= track.segmentCount() || segment == state.segment) continue;

            float volume = playbackVolume(player, session, state.fading);
            if (volume <= 0) {
                stopPlayer(player);
                continue;
            }

            String sound = track.soundKey(segment);
            if (state.sound != null) player.stopSound(state.sound, SOUND_CATEGORY);
            player.playSound(player.getLocation(), sound, SOUND_CATEGORY, volume, 1.0f);
            state.segment = segment;
            state.sound = sound;
        }
    }

    private float playbackVolume(Player player, PlaybackSession session, boolean fading) {
        PlayerPreferences pref = preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences);
        float volume = (pref.volume() / 100.0f)
                * (plugin.getConfig().getInt("playback.default-volume", 100) / 100.0f);
        if (fading) volume *= (float) session.zone().fadeFactor(player.getX(), player.getZ());
        return Math.max(0, Math.min(1, volume));
    }

    private int compare(Candidate a, Candidate b) {
        boolean manual = plugin.getConfig().getBoolean("playback.overlap.manual-priority-enabled", true);
        if (manual && a.session.priority() != b.session.priority()) {
            return Integer.compare(b.session.priority(), a.session.priority());
        }
        int started = Long.compare(a.session.startedAt(), b.session.startedAt());
        return started != 0 ? started : a.session.id().compareTo(b.session.id());
    }

    private void playFor(Player player, Candidate candidate) {
        PlaybackSession session = candidate.session;
        MusicTrack originalTrack = session.current();
        UUID playerId = player.getUniqueId();
        PlayerPlayback state = playerPlayback.get(playerId);
        if (state != null && state.syncSessionId != null
                && (!state.syncSessionId.equals(session.id()) || originalTrack == null
                || !Objects.equals(state.syncSourceTrackId, originalTrack.id()))) {
            stopPlayer(player);
            state = null;
        }
        if (state == null) state = new PlayerPlayback();
        if (session.paused() || session.resumePreparing()) {
            stopPlayer(player);
            return;
        }
        boolean bedrock = geyser != null && geyser.isBedrockPlayer(playerId);
        boolean synchronizedTail = !bedrock && state.syncTrack != null
                && session.id().equals(state.syncSessionId) && originalTrack != null
                && originalTrack.id().equals(state.syncSourceTrackId);
        if (!bedrock && state.syncPreparing) {
            stopCurrentSound(player, state);
            player.sendActionBar(message("messages.playback-synchronizing"));
            return;
        }

        MusicTrack track = synchronizedTail ? state.syncTrack : session.playbackTrack();
        String excludedTrack = barrierExcludedTracks.get(playerId);
        if (!bedrock && !synchronizedTail
                && barrierTrackKey(session, session.playbackTrack()).equals(excludedTrack)) {
            stopPlayer(player);
            return;
        }
        if (synchronizedTail && packs.isReady(player, track)) {
            barrierExcludedTracks.remove(playerId, barrierTrackKey(session, session.playbackTrack()));
        }
        if (bedrock) {
            // Bedrock receives the aggregate mcpack at Geyser connection time.
            // A late Bedrock joiner cannot receive an in-session regenerated Java pack.
            // Do not play the original from 00:00 against an already-advanced session clock.
            if (track != null && track.namespace() != null && track.namespace().startsWith("mr_")) {
                warnBedrockUnavailable(player, "messages.bedrock-resume-unsupported");
                stopPlayer(player);
                return;
            }
            // Never send Java Edition packs to a Bedrock client.
            packs.releaseAll(player);
            if (!geyser.isBedrockPackAvailable()) {
                warnBedrockUnavailable(player, "messages.bedrock-not-supported");
                stopPlayer(player);
                return;
            }
            if (!geyser.hasCurrentPackFor(player.getUniqueId())) {
                stopPlayer(player);
                if (geyser.isAwaitingPackReady(player.getUniqueId())) {
                    player.sendActionBar(message("messages.playback-synchronizing"));
                } else {
                    // The player is already in-game with an older pack revision.
                    // Bedrock cannot add the changed library to this live connection.
                    warnBedrockUnavailable(player, "messages.bedrock-pack-reconnect");
                }
                return;
            }
        } else {
            if (packs.isBaseDeclined(player) || packs.isDeclined(player, track)) {
                warnPackDeclined(player, originalTrack, track);
                stopPlayer(player);
                return;
            }
            if (!packs.isReady(player, track)) {
                if (synchronizedTail) {
                    stopCurrentSound(player, state);
                } else {
                    stopPlayer(player);
                }
                player.sendActionBar(message("messages.pack-not-ready"));
                return;
            }
        }
        if (!plugin.getConfig().getBoolean("playback.synchronization.start-barrier", true)) {
            session.startClockIfNeeded();
        }
        if (!session.clockStarted()) {
            stopPlayer(player);
            player.sendActionBar(message("messages.playback-synchronizing"));
            return;
        }
        int segment = synchronizedTail ? 0 : session.segmentIndex();
        if (segment < 0 || segment >= track.segmentCount()) {
            stopPlayer(player);
            return;
        }
        boolean fading = candidate.mode == MusicZone.AudioMode.FADE;
        String sound = track.soundKey(segment);
        float volume = playbackVolume(player, session, fading);
        if (volume <= 0) {
            stopPlayer(player);
            return;
        }
        boolean changed = !session.id().equals(state.sessionId) || !track.id().equals(state.trackId)
                || state.segment != segment || state.fading != fading;
        if (changed) {
            stopCurrentSound(player, state);
            player.playSound(player.getLocation(), sound, SOUND_CATEGORY, volume, 1.0f);
            state.sessionId = session.id();
            state.trackId = track.id();
            state.segment = segment;
            state.fading = fading;
            state.sound = sound;
            playerPlayback.put(player.getUniqueId(), state);
        }
        if (plugin.getConfig().getBoolean("playback.synchronization.action-bar-now-playing", true)) {
            MusicTrack displayTrack = originalTrack == null ? track : originalTrack;
            String title = TextUtil.truncate(displayTrack.title(), 34);
            String artist = TextUtil.truncate(displayTrack.artist(), 18);
            String label = artist.isBlank() ? title : title + " by " + artist;
            player.sendActionBar(Component.text("♫ " + label + "  •  " + formatTime(session.elapsedMs())
                    + " / " + formatTime(displayTrack.durationMs())));
        }
    }

    private void warnPackDeclined(Player player, MusicTrack originalTrack, MusicTrack playbackTrack) {
        if (playbackTrack == null) return;
        // One rejection notice per catalogue song, even if the playback tick
        // repeats hundreds of times. A different song gets its own notice.
        String songId = originalTrack == null ? playbackTrack.id() : originalTrack.id();
        if (!songId.equals(declinedWarnings.get(player.getUniqueId()))) {
            player.sendMessage(message("messages.pack-declined"));
            declinedWarnings.put(player.getUniqueId(), songId);
        }
    }

    private void warnBedrockUnavailable(Player player, String messagePath) {
        long now = System.currentTimeMillis();
        long lastWarning = bedrockWarnings.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastWarning > 300000L) {
            player.sendMessage(message(messagePath));
            bedrockWarnings.put(player.getUniqueId(), now);
        }
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private Component message(String path) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = plugin.getConfig().getString(path, "");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text);
    }

    private void stopCurrentSound(Player player, PlayerPlayback state) {
        if (state != null && state.sound != null) {
            player.stopSound(state.sound, SOUND_CATEGORY);
            state.sound = null;
        }
    }

    private void stopPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerPlayback state = playerPlayback.remove(playerId);
        if (state != null) {
            syncGenerations.merge(playerId, 1L, Long::sum);
            stopCurrentSound(player, state);
            if (state.syncTrack != null) releaseGeneratedTrack(state.syncTrack);
        }
    }

    private void stopPlayersInSession(PlaybackSession session) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerPlayback state = playerPlayback.get(player.getUniqueId());
            if (state != null && session.id().equals(state.sessionId)) stopPlayer(player);
        }
    }

    public void onQuit(Player player) {
        stopPlayer(player);
        barrierExcludedTracks.remove(player.getUniqueId());
        packs.removePlayer(player.getUniqueId());
        preferences.remove(player.getUniqueId());
        declinedWarnings.remove(player.getUniqueId());
        bedrockWarnings.remove(player.getUniqueId());
        syncGenerations.remove(player.getUniqueId());
        automaticSyncAttempts.remove(player.getUniqueId());
        if (geyser != null) geyser.removePlayer(player.getUniqueId());
    }

    public void stopAll() {
        if (task != null) task.cancel();
        if (segmentTask != null) segmentTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            stopPlayer(player);
            packs.releaseAll(player);
        }
        synchronized (this) {
            for (PlaybackSession session : sessions) releaseGeneratedTrack(session.playbackTrack());
            sessions.clear();
        }
        bedrockWarnings.clear();
        barrierWaits.clear();
        barrierExcludedTracks.clear();
        syncGenerations.clear();
        automaticSyncAttempts.clear();
    }

    /**
     * If the bounded start barrier released this player before their pack was
     * ready, automatically run the same private-tail recovery as /music sync
     * once the original track's pack is finally available.
     */
    private void startAutomaticCatchUpIfNeeded(Player player, Candidate candidate) {
        if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) return;
        UUID playerId = player.getUniqueId();
        MusicTrack sessionTrack = candidate.session.playbackTrack();
        if (sessionTrack == null) return;
        String trackKey = barrierTrackKey(candidate.session, sessionTrack);
        if (!trackKey.equals(barrierExcludedTracks.get(playerId))) return;
        if (trackKey.equals(automaticSyncAttempts.get(playerId))) return;

        PlayerPlayback state = playerPlayback.get(playerId);
        if (state != null && (state.syncPreparing || state.syncTrack != null)) return;
        if (!packs.isReady(player, sessionTrack) || packs.isDeclined(player, sessionTrack)) return;

        // Attempt once per session-track. A failed temporary-pack delivery must
        // not start a new FFmpeg job every playback tick; the player can still
        // request a fresh attempt with /music sync.
        automaticSyncAttempts.put(playerId, trackKey);
        SyncStart result = synchronizePlayer(player, notification -> {
            if (player.isOnline() && notification != null && notification.startsWith("&c")) {
                player.sendMessage(message(notification));
            }
        });
        if (result == SyncStart.TRACK_NEAR_END) {
            // There is too little remaining audio to build a meaningful tail.
            // Let the ordinary playback path attempt the track instead.
            barrierExcludedTracks.remove(playerId, trackKey);
        } else if (result != SyncStart.PREPARING && result != SyncStart.ALREADY_PREPARING) {
            player.sendActionBar(message("messages.playback-synchronizing"));
        }
    }

    private Candidate findCandidate(Player player) {
        Candidate winner = null;
        for (PlaybackSession session : sessions()) {
            if (!session.targets(player.getUniqueId()) || session.current() == null
                    || session.paused() || session.resumePreparing() || isPlayerMuted(player)) continue;
            if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) {
                if (!geyser.isBedrockPackAvailable()
                        || !geyser.hasCurrentPackFor(player.getUniqueId())) continue;
            } else if (packs.isBaseDeclined(player)
                    || packs.isDeclined(player, session.playbackTrack())) {
                continue;
            }
            MusicZone zone = session.zone();
            if (zone.worldId() != null && !zone.worldId().equals(player.getWorld().getUID())) continue;
            MusicZone.AudioMode mode;
            if (zone.shape() == MusicZone.Shape.REGION) {
                boolean inside = worldGuard != null && worldGuard.isInside(player.getLocation(), zone.regionId());
                mode = inside ? MusicZone.AudioMode.STEREO : MusicZone.AudioMode.NONE;
            } else {
                mode = zone.audioMode(player.getWorld().getUID(), player.getX(), player.getY(), player.getZ());
            }
            if (mode == MusicZone.AudioMode.NONE) continue;
            Candidate current = new Candidate(session, mode);
            if (winner == null || compare(current, winner) < 0) winner = current;
        }
        return winner;
    }

    private record BarrierWait(String trackId, long startedAtMillis) {}
    private record Candidate(PlaybackSession session, MusicZone.AudioMode mode) {}
    private static final class PlayerPlayback {
        UUID sessionId;
        String trackId;
        int segment = -1;
        boolean fading;
        String sound;
        UUID syncSessionId;
        String syncSourceTrackId;
        MusicTrack syncTrack;
        boolean syncPreparing;
    }
}
