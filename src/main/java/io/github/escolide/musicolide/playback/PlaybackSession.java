package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;

import java.util.*;

public final class PlaybackSession {
    private final UUID id = UUID.randomUUID();
    private final UUID owner;
    private final String ownerName;
    private final long startedAt;
    private volatile MusicZone zone;
    private final Set<UUID> explicitListeners;
    private volatile boolean allPlayers;
    private final boolean monoAudio;
    private final PlaybackAudiencePolicy.TargetScope targetScope;
    private final Deque<QueueEntry> queue = new ArrayDeque<>();
    private final List<MusicTrack> repeatSource;
    private MusicTrack loopTrack;
    private MusicTrack current;
    private MusicTrack playbackTrack;
    private long trackStartedAt;
    private long elapsedBaseMs;
    private long pausedOffsetMs;
    private long pendingOffsetMs = -1L;
    private boolean resumeAfterPreparation;
    private boolean paused;
    private boolean resumePreparing;
    private long resumeGeneration;
    private long timelineGeneration;
    private boolean clockStarted;
    private boolean repeat;
    private boolean queuePending;
    private boolean overlapSuspended;
    private boolean urlPlaylist;
    private boolean playlistPerTrackDelivery;
    private int priority;
    private double speed = 1.0;
    private Double pendingSpeed;
    // null follows the configured server default; a value applies to this track only.
    private Boolean discordBroadcastOverride;
    private boolean serverWidePromotion;
    private MusicZone preBroadcastZone;
    private boolean preBroadcastAllPlayers;
    private Set<UUID> preBroadcastListeners;

    public PlaybackSession(UUID owner, String ownerName, MusicZone zone, boolean allPlayers,
                           Collection<UUID> listeners, Collection<MusicTrack> tracks, int priority) {
        this(owner, ownerName, zone, allPlayers, listeners, tracks, priority, false);
    }

    public PlaybackSession(UUID owner, String ownerName, MusicZone zone, boolean allPlayers,
                           Collection<UUID> listeners, Collection<MusicTrack> tracks, int priority,
                           boolean monoAudio) {
        this(owner, ownerName, zone, allPlayers, listeners, tracks, priority, monoAudio,
                zone != null && zone.shape() != MusicZone.Shape.GLOBAL
                        ? PlaybackAudiencePolicy.TargetScope.ZONE
                        : allPlayers ? PlaybackAudiencePolicy.TargetScope.PUBLIC
                        : PlaybackAudiencePolicy.TargetScope.TARGETED);
    }

    public PlaybackSession(UUID owner, String ownerName, MusicZone zone, boolean allPlayers,
                           Collection<UUID> listeners, Collection<MusicTrack> tracks, int priority,
                           boolean monoAudio, PlaybackAudiencePolicy.TargetScope targetScope) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.zone = Objects.requireNonNull(zone, "zone");
        this.allPlayers = allPlayers;
        this.monoAudio = monoAudio;
        this.targetScope = targetScope == null
                ? zone.shape() != MusicZone.Shape.GLOBAL ? PlaybackAudiencePolicy.TargetScope.ZONE
                : allPlayers ? PlaybackAudiencePolicy.TargetScope.PUBLIC
                : PlaybackAudiencePolicy.TargetScope.TARGETED : targetScope;
        this.explicitListeners = new HashSet<>(listeners == null ? List.of() : listeners);
        for (MusicTrack track : tracks) this.queue.addLast(new QueueEntry(track, false));
        this.repeatSource = List.copyOf(tracks);
        this.startedAt = System.currentTimeMillis();
        this.priority = priority;
        advance();
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public long startedAt() { return startedAt; }
    public synchronized MusicZone zone() { return zone; }
    public boolean isMono() { return monoAudio; }
    /** True only for sessions explicitly addressed to all players in the global zone. */
    public synchronized boolean isServerWideGlobalSession() {
        return allPlayers && zone.shape() == MusicZone.Shape.GLOBAL;
    }
    public synchronized Boolean discordBroadcastOverride() { return discordBroadcastOverride; }
    public synchronized void discordBroadcastOverride(Boolean value) { discordBroadcastOverride = value; }
    public synchronized boolean serverWidePromotion() { return serverWidePromotion; }
    /** Temporarily expands only this track's audience; advance() restores the original scope. */
    public synchronized boolean promoteToServerWide() {
        if (current == null || (allPlayers && zone.shape() == MusicZone.Shape.GLOBAL)) return false;
        if (!serverWidePromotion) {
            preBroadcastZone = zone;
            preBroadcastAllPlayers = allPlayers;
            preBroadcastListeners = new HashSet<>(explicitListeners);
        }
        allPlayers = true;
        explicitListeners.clear();
        zone = MusicZone.global();
        serverWidePromotion = true;
        return true;
    }
    public synchronized MusicTrack current() { return current; }
    /** Original catalogue track or the temporary OGG tail currently being played. */
    public synchronized MusicTrack playbackTrack() { return playbackTrack == null ? current : playbackTrack; }
    public synchronized boolean paused() { return paused; }
    public synchronized boolean queuePending() { return queuePending; }
    public synchronized void queuePending(boolean pending) { queuePending = pending; }
    /** Marks a yt-dlp URL playlist and its session-scoped Java pack delivery override. */
    public synchronized void configureUrlPlaylist(boolean perTrackDelivery) {
        urlPlaylist = true;
        playlistPerTrackDelivery = perTrackDelivery;
    }
    public synchronized boolean isUrlPlaylist() { return urlPlaylist; }
    public synchronized boolean playlistPerTrackDelivery() {
        return urlPlaylist && playlistPerTrackDelivery;
    }
    public synchronized boolean resumePreparing() { return resumePreparing; }
    public synchronized boolean clockStarted() { return clockStarted; }
    /** Changes whenever this session starts a new track timeline, including a restart of the same track. */
    public synchronized long timelineGeneration() { return timelineGeneration; }
    public synchronized int priority() { return priority; }
    public synchronized void priority(int priority) { this.priority = priority; }
    public synchronized double speed() { return speed; }
    public synchronized Double pendingSpeed() { return pendingSpeed; }
    public synchronized boolean speedIsCurrentOrPending(double value) {
        return Double.compare(pendingSpeed == null ? speed : pendingSpeed, value) == 0;
    }

    /**
     * Changes sound pitch (and therefore playback rate) without modifying audio.
     * An already-playing Minecraft sound cannot have its pitch changed in place,
     * so live changes are staged for the next track.
     *
     * @return true when the speed can affect the current track immediately
     */
    public synchronized boolean setSpeed(double value) {
        if (!Double.isFinite(value) || value < 0.5 || value > 2.0) {
            throw new IllegalArgumentException("Playback speed must be between 0.5x and 2.0x.");
        }
        if (clockStarted && !paused && !resumePreparing) {
            pendingSpeed = value;
            return false;
        }
        speed = value;
        pendingSpeed = null;
        return true;
    }

    public synchronized void repeat(boolean repeat) { this.repeat = repeat; }
    public synchronized boolean isEmpty() { return current == null && queue.isEmpty() && !queuePending; }
    public synchronized int queueSize() { return queue.size(); }
    public synchronized List<MusicTrack> upcoming(int count) {
        return queue.stream().limit(Math.max(0, count)).map(QueueEntry::track).toList();
    }
    public synchronized boolean targets(UUID player) { return allPlayers || explicitListeners.contains(player); }
    public synchronized boolean allPlayers() { return allPlayers; }
    public synchronized PlaybackAudiencePolicy.TargetScope targetScope() {
        return serverWidePromotion ? PlaybackAudiencePolicy.TargetScope.PUBLIC : targetScope;
    }
    public synchronized boolean isPersonalTarget(UUID player) {
        return PlaybackAudiencePolicy.isPersonalTarget(targetScope, allPlayers, explicitListeners, player);
    }
    public boolean isOwner(UUID player) { return owner != null && owner.equals(player); }

    /** Checks whether a new /play request has the same audience and zone. */
    public synchronized boolean matchesPlaybackRequest(boolean requestedAllPlayers,
                                                       Collection<UUID> requestedListeners,
                                                       MusicZone requestedZone) {
        return matchesPlaybackRequest(requestedAllPlayers, requestedListeners, requestedZone, false);
    }

    public synchronized boolean matchesPlaybackAudienceAndZone(boolean requestedAllPlayers,
                                                                 Collection<UUID> requestedListeners,
                                                                 MusicZone requestedZone) {
        Set<UUID> listeners = new HashSet<>(requestedListeners == null ? List.of() : requestedListeners);
        return allPlayers == requestedAllPlayers
                && explicitListeners.equals(listeners)
                && zone.sameAs(requestedZone);
    }

    /**
     * Standard single-track requests must not join a URL-playlist importer.
     * Playlist sessions have their own bounded producer and queue cadence.
     */
    public synchronized boolean matchesStandardPlaybackQueue(boolean requestedAllPlayers,
                                                               Collection<UUID> requestedListeners,
                                                               MusicZone requestedZone) {
        return !urlPlaylist
                && matchesPlaybackAudienceAndZone(requestedAllPlayers, requestedListeners, requestedZone);
    }

    public synchronized boolean matchesPlaybackRequest(boolean requestedAllPlayers,
                                                       Collection<UUID> requestedListeners,
                                                       MusicZone requestedZone,
                                                       boolean requestedMono) {
        return matchesPlaybackAudienceAndZone(requestedAllPlayers, requestedListeners, requestedZone)
                && monoAudio == requestedMono;
    }

    public synchronized boolean referencesTrack(String trackId) {
        if (trackId == null) return false;
        if (current != null && current.id().equals(trackId)) return true;
        if (playbackTrack != null && playbackTrack.id().equals(trackId)) return true;
        if (loopTrack != null && loopTrack.id().equals(trackId)) return true;
        if (queue.stream().anyMatch(entry -> entry.track().id().equals(trackId))) return true;
        return repeat && repeatSource.stream().anyMatch(track -> track.id().equals(trackId));
    }

    public enum QueueAddResult { ADDED, LOOP_TERMINAL, INVALID_TRACK }

    /** Adds an item; a loop item is terminal and is repeated after it becomes current. */
    public synchronized QueueAddResult addToQueue(MusicTrack track, boolean loop) {
        if (track == null) return QueueAddResult.INVALID_TRACK;
        if (hasLoopTerminal()) return QueueAddResult.LOOP_TERMINAL;
        queue.addLast(new QueueEntry(track, loop));
        // Preserve URL-import producer behavior when a session is awaiting its next item.
        if (current == null && !paused && !resumePreparing) advance();
        return QueueAddResult.ADDED;
    }

    /** Existing producer API, retained for the URL-playlist importer. */
    public synchronized void enqueue(MusicTrack track) {
        addToQueue(track, false);
    }

    /** Marks the current track in a newly created one-track session as the loop item. */
    public synchronized boolean markCurrentAsLoop() {
        if (current == null || loopTrack != null || !queue.isEmpty()) return false;
        loopTrack = current;
        return true;
    }

    public synchronized boolean hasLoopTerminal() {
        return loopTrack != null || queue.stream().anyMatch(QueueEntry::loop);
    }

    public synchronized boolean isLoopActive() {
        return loopTrack != null;
    }

    /** Removes the first queued occurrence; the active item is never removable here. */
    public synchronized boolean removeQueuedTrack(String trackId) {
        if (trackId == null || trackId.isBlank() || loopTrack != null) return false;
        Iterator<QueueEntry> iterator = queue.iterator();
        while (iterator.hasNext()) {
            QueueEntry entry = iterator.next();
            if (entry.track().id().equalsIgnoreCase(trackId)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    /** Clears waiting entries. A running loop must be stopped as a session. */
    public synchronized int clearQueuedTracks() {
        if (loopTrack != null) return 0;
        int removed = queue.size();
        queue.clear();
        return removed;
    }

    public synchronized boolean advance() {
        overlapSuspended = false;
        if (serverWidePromotion) {
            zone = preBroadcastZone == null ? zone : preBroadcastZone;
            allPlayers = preBroadcastAllPlayers;
            explicitListeners.clear();
            if (preBroadcastListeners != null) explicitListeners.addAll(preBroadcastListeners);
            preBroadcastZone = null;
            preBroadcastListeners = null;
            serverWidePromotion = false;
        }
        // Per-track overrides never leak into the next queued/playlist track.
        discordBroadcastOverride = null;
        timelineGeneration++;
        if (pendingSpeed != null) {
            speed = pendingSpeed;
            pendingSpeed = null;
        }

        QueueEntry next;
        if (loopTrack != null) {
            // Preserve session identity, audience and priority while resetting the track timeline.
            next = new QueueEntry(loopTrack, true);
        } else {
            if (queue.isEmpty() && repeat && !repeatSource.isEmpty()) {
                for (MusicTrack track : repeatSource) queue.addLast(new QueueEntry(track, false));
            }
            next = queue.pollFirst();
            if (next != null && next.loop()) loopTrack = next.track();
        }

        resumeGeneration++;
        resumePreparing = false;
        current = next == null ? null : next.track();
        playbackTrack = current;
        paused = false;
        elapsedBaseMs = 0;
        pausedOffsetMs = 0;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        clockStarted = false;
        trackStartedAt = 0;
        return current != null;
    }

    public synchronized void startClockIfNeeded() {
        if (!clockStarted && current != null && !paused && !resumePreparing) {
            trackStartedAt = System.currentTimeMillis();
            clockStarted = true;
        }
    }

    /**
     * Holds a session whose otherwise-eligible listeners all selected another
     * overlapping source. Unlike pause(), this is automatic and is resumed by
     * the playback engine when at least one listener selects this session again.
     */
    public synchronized void suspendClockForOverlap() {
        if (!clockStarted || current == null || paused || resumePreparing) return;
        elapsedBaseMs = elapsedMs();
        trackStartedAt = 0;
        clockStarted = false;
        overlapSuspended = true;
    }

    /** Consumed once when this session is selected again after an overlap hold. */
    public synchronized boolean consumeOverlapSuspended() {
        boolean suspended = overlapSuspended;
        overlapSuspended = false;
        return suspended;
    }

    public synchronized void pause() {
        overlapSuspended = false;
        if (resumePreparing) {
            // A second pause cancels an outstanding resume/seek preparation.
            resumeGeneration++;
            resumePreparing = false;
            pendingOffsetMs = -1L;
            resumeAfterPreparation = false;
            paused = true;
            return;
        }
        if (!paused && current != null) {
            pausedOffsetMs = clockStarted ? elapsedMs() : elapsedBaseMs;
            paused = true;
            resumePreparing = false;
            resumeGeneration++;
        }
    }

    /** Existing behaviour for legacy segmented and continuous modes. */
    public synchronized void resume() {
        if (!paused || current == null) return;
        overlapSuspended = false;
        timelineGeneration++;
        resumeGeneration++;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        playbackTrack = current;
        if (current.isStreamed()) {
            // Plain continuous mode cannot seek a client-side streamed decoder.
            // Restart at zero, then let the playback engine release all ready
            // listeners together through its pack-readiness start barrier.
            elapsedBaseMs = 0;
            pausedOffsetMs = 0;
        } else {
            // Segmented playback can resume at the saved absolute timestamp.
            elapsedBaseMs = pausedOffsetMs;
            pausedOffsetMs = 0;
        }
        trackStartedAt = 0;
        clockStarted = false;
        paused = false;
    }

    /**
     * Starts asynchronous timestamp-based regeneration. The session stays paused
     * until FFmpeg and the transient resource pack are ready for the listener set.
     */
    public synchronized long beginResumePreparation() {
        if (!paused || current == null || !current.isStreamed() || resumePreparing) return -1L;
        timelineGeneration++;
        resumePreparing = true;
        pendingOffsetMs = pausedOffsetMs;
        resumeAfterPreparation = true;
        return ++resumeGeneration;
    }

    /**
     * Starts an asynchronous seek for a streamed track. Playback is stopped
     * immediately; a seek issued while paused remains paused after preparation.
     */
    public synchronized long beginSeekPreparation(long targetMs) {
        if (current == null || !current.isStreamed() || resumePreparing) return -1L;
        timelineGeneration++;
        boolean wasPlaying = !paused;
        if (wasPlaying) pausedOffsetMs = elapsedMs();
        paused = true;
        resumePreparing = true;
        pendingOffsetMs = Math.max(0L, Math.min(current.durationMs() - 1L, targetMs));
        resumeAfterPreparation = wasPlaying;
        return ++resumeGeneration;
    }

    public synchronized boolean completeResumePreparation(long generation, MusicTrack remainder) {
        if (!paused || !resumePreparing || generation != resumeGeneration || current == null
                || remainder == null) return false;
        playbackTrack = remainder;
        pausedOffsetMs = pendingOffsetMs >= 0 ? pendingOffsetMs : pausedOffsetMs;
        elapsedBaseMs = pausedOffsetMs;
        // Start the clock only when playFor sees that the new pack is loaded.
        clockStarted = false;
        trackStartedAt = 0;
        paused = !resumeAfterPreparation;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        return true;
    }

    public synchronized boolean failResumePreparation(long generation) {
        if (generation != resumeGeneration) return false;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        return true;
    }

    public synchronized void cancelResumePreparation() {
        resumeGeneration++;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
    }

    public synchronized void seek(long millis) {
        if (current == null) return;
        timelineGeneration++;
        if (current.isStreamed()) {
            throw new IllegalArgumentException(
                    "Seeking within continuous streamed tracks requires resumable playback mode.");
        }
        playbackTrack = current;
        elapsedBaseMs = Math.max(0, Math.min(current.durationMs() - 1, millis));
        pausedOffsetMs = 0;
        // The playback engine starts the clock after all current listeners
        // are ready, so a seek restarts as a synchronized cohort.
        trackStartedAt = 0;
        clockStarted = false;
    }

    public synchronized long elapsedMs() {
        return elapsedMsAt(System.currentTimeMillis());
    }

    /** Deterministic clock projection used by the unit tests and playback engine. */
    synchronized long elapsedMsAt(long nowMillis) {
        if (paused) return pausedOffsetMs;
        if (!clockStarted) return elapsedBaseMs;
        long wallElapsed = Math.max(0L, nowMillis - trackStartedAt);
        return Math.max(0L, elapsedBaseMs + Math.round(wallElapsed * speed));
    }

    public synchronized int segmentIndex() {
        MusicTrack track = playbackTrack();
        if (track == null) return -1;
        long trackPosition = track == current ? elapsedMs() : Math.max(0, elapsedMs() - elapsedBaseMs);
        return (int) Math.min(track.segmentCount() - 1,
                Math.max(0, trackPosition) / Math.max(1, track.segmentMs()));
    }

    public synchronized boolean finished() {
        return current != null && clockStarted && !paused && !resumePreparing
                && elapsedMs() >= current.durationMs();
    }

    public synchronized void addListener(UUID uuid) { explicitListeners.add(uuid); }
    public synchronized void removeListener(UUID uuid) { explicitListeners.remove(uuid); }
    private record QueueEntry(MusicTrack track, boolean loop) {}
}
