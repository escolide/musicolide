package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.geyser.GeyserBridge;
import io.github.escolide.musicolide.model.MusicTrack;
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

public final class PlaybackEngine {
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final PackManager packs;
    private final List<PlaybackSession> sessions = new ArrayList<>();
    private final Map<UUID, PlayerPreferences> preferences = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPlayback> playerPlayback = new HashMap<>();
    private final Map<UUID, Long> warningTimes = new HashMap<>();
    private final Map<UUID, Long> declinedWarnings = new HashMap<>();
    private final WorldGuardBridge worldGuard;
    private final GeyserBridge geyser;
    private final Map<UUID, Long> bedrockWarnings = new HashMap<>();
    private BukkitTask task;

    public PlaybackEngine(JavaPlugin plugin, TrackRepository repository, PackManager packs,
                          WorldGuardBridge worldGuard, GeyserBridge geyser) {
        this.plugin = plugin;
        this.repository = repository;
        this.packs = packs;
        this.worldGuard = worldGuard;
        this.geyser = geyser;
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig().getLong("general.check-interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, interval);
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

    public synchronized Optional<PlaybackSession> findOwned(UUID owner) {
        return sessions.stream().filter(s -> s.isOwner(owner)).max(Comparator.comparingLong(PlaybackSession::startedAt));
    }

    public synchronized List<PlaybackSession> sessions() { return List.copyOf(sessions); }

    public synchronized boolean control(UUID actor, boolean admin, String action) {
        PlaybackSession session = findOwned(actor).orElseGet(() -> admin && !sessions.isEmpty()
                ? sessions.get(sessions.size() - 1) : null);
        if (session == null) return false;
        switch (action) {
            case "pause" -> session.pause();
            case "resume" -> session.resume();
            case "skip" -> {
                session.advance();
                if (session.isEmpty()) sessions.remove(session);
            }
            case "stop" -> {
                sessions.remove(session);
                stopPlayersInSession(session);
            }
            default -> { return false; }
        }
        return true;
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
            for (PlaybackSession session : sessions) if (session.finished()) session.advance();
            sessions.removeIf(PlaybackSession::isEmpty);
            active = List.copyOf(sessions);
        }

        Map<UUID, Candidate> winners = new HashMap<>();
        Map<PlaybackSession, Set<UUID>> suppressed = new HashMap<>();
        for (Player player : online) {
            if (geyser != null && geyser.isBedrockPlayer(player.getUniqueId())) {
                stopPlayer(player);
                packs.releaseAll(player);
                long now = System.currentTimeMillis();
                long lastWarning = bedrockWarnings.getOrDefault(player.getUniqueId(), 0L);
                if (now - lastWarning > 300000L) {
                    player.sendMessage(message("messages.bedrock-not-supported"));
                    bedrockWarnings.put(player.getUniqueId(), now);
                }
                continue;
            }
            Candidate winner = null;
            for (PlaybackSession session : active) {
                if (!session.targets(player.getUniqueId()) || session.current() == null) continue;
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

        if (plugin.getConfig().getBoolean("playback.overlap.warn-session-owner", true)) {
            long cooldown = plugin.getConfig().getLong("playback.overlap.warning-cooldown-seconds", 60) * 1000L;
            for (Map.Entry<PlaybackSession, Set<UUID>> entry : suppressed.entrySet()) {
                PlaybackSession session = entry.getKey();
                if (session.owner() == null || entry.getValue().isEmpty()) continue;
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
            if (winner == null || isPlayerMuted(player)) {
                stopPlayer(player);
                packs.releaseAll(player);
                continue;
            }
            playFor(player, winner);
        }
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
        MusicTrack track = session.current();
        PlayerPlayback state = playerPlayback.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerPlayback());
        if (session.paused()) {
            stopPlayer(player);
            return;
        }
        int prefetch = Math.max(0, plugin.getConfig().getInt("resource-packs.prefetch.tracks-ahead", 4));
        packs.prefetch(player, track, session.upcoming(prefetch),
                plugin.getConfig().getBoolean("resource-packs.prefetch.enabled", true) ? prefetch : 0);
        if (packs.isDeclined(player, track)) {
            long now = System.currentTimeMillis();
            if (now - declinedWarnings.getOrDefault(player.getUniqueId(), 0L) > 30000) {
                player.sendMessage(message("messages.pack-declined"));
                declinedWarnings.put(player.getUniqueId(), now);
            }
            stopPlayer(player);
            return;
        }
        if (!packs.isReady(player, track)) {
            stopPlayer(player);
            player.sendActionBar(message("messages.pack-not-ready"));
            return;
        }
        session.startClockIfNeeded();
        int segment = session.segmentIndex();
        boolean fading = candidate.mode == MusicZone.AudioMode.FADE;
        String sound = track.soundKey(segment);
        PlayerPreferences pref = preferences.computeIfAbsent(player.getUniqueId(), this::loadPreferences);
        float volume = (pref.volume() / 100.0f)
                * (plugin.getConfig().getInt("playback.default-volume", 100) / 100.0f);
        if (fading) {
            volume *= (float) session.zone().fadeFactor(player.getX(), player.getZ());
        }
        volume = Math.max(0, Math.min(1, volume));
        if (volume <= 0) {
            stopPlayer(player);
            return;
        }
        boolean changed = !session.id().equals(state.sessionId) || !track.id().equals(state.trackId)
                || state.segment != segment || state.fading != fading;
        if (changed) {
            stopPlayer(player);
            player.playSound(player.getLocation(), sound, SoundCategory.MUSIC, volume, 1.0f);
            state.sessionId = session.id();
            state.trackId = track.id();
            state.segment = segment;
            state.fading = fading;
            state.sound = sound;
            playerPlayback.put(player.getUniqueId(), state);
        }
        if (plugin.getConfig().getBoolean("playback.synchronization.action-bar-now-playing", true)) {
            player.sendActionBar(Component.text("♫ " + track.title() + "  •  " + formatTime(session.elapsedMs())
                    + " / " + formatTime(track.durationMs())));
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

    private void stopPlayer(Player player) {
        PlayerPlayback state = playerPlayback.remove(player.getUniqueId());
        if (state != null && state.sound != null) player.stopSound(state.sound, SoundCategory.MUSIC);
    }

    private void stopPlayersInSession(PlaybackSession session) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerPlayback state = playerPlayback.get(player.getUniqueId());
            if (state != null && session.id().equals(state.sessionId)) stopPlayer(player);
        }
    }

    public void onQuit(Player player) {
        stopPlayer(player);
        packs.removePlayer(player.getUniqueId());
        preferences.remove(player.getUniqueId());
        bedrockWarnings.remove(player.getUniqueId());
        if (geyser != null) geyser.removePlayer(player.getUniqueId());
    }

    public void stopAll() {
        if (task != null) task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            stopPlayer(player);
            packs.releaseAll(player);
        }
        synchronized (this) { sessions.clear(); }
        bedrockWarnings.clear();
    }

    private record Candidate(PlaybackSession session, MusicZone.AudioMode mode) {}
    private static final class PlayerPlayback {
        UUID sessionId;
        String trackId;
        int segment = -1;
        boolean fading;
        String sound;
    }
}
