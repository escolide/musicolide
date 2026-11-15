package io.github.escolide.musicolide.discord;

import io.github.escolide.musicolide.audio.NativeToolchain;
import io.github.escolide.musicolide.playback.DiscordPlaybackSnapshot;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.link.DiscordAccountLinkManager;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-way Musicolide -> Discord broadcaster. This class deliberately registers
 * no message, interaction, or command handlers, so Discord cannot control
 * Minecraft playback.
 */
public final class DiscordBroadcastService implements AutoCloseable {
    private static final long DRIFT_CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long MAX_DRIFT_MS = 1_500L;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final PlaybackEngine playback;
    private final DiscordCommandService commandService;
    private final NativeToolchain toolchain;
    private final Logger logger;
    private final String token;
    private final String channelId;
    private final boolean reconnectAndResync;
    private final DiscordAudioSendHandler sendHandler = new DiscordAudioSendHandler();
    private final DiscordTrackStreamer streamer;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile JDA jda;
    private volatile long parsedChannelId = -1L;
    private String lastTimelineKey;
    private long lastDriftCheckNanos;
    private boolean missingChannelLogged;
    private boolean permissionWarningLogged;
    private boolean ffmpegUnavailableLogged;
    private boolean terminalLoginFailureLogged;
    private boolean voiceWasConnected;
    private boolean hasEverConnected;
    private long lastVoiceConnectAttemptNanos;
    private long lastRestartAttemptNanos;
    private long lastPresenceUpdateNanos;
    private String lastPresenceText = "";
    private String lastPresenceIdentity = "";
    private OnlineStatus lastPresenceStatus = null;
    // Musicolide owns one configured voice channel, not every guild cached by JDA.
    private volatile String lastKnownVoiceGuildId;

    public DiscordBroadcastService(org.bukkit.plugin.java.JavaPlugin plugin,
                                   PlaybackEngine playback, NativeToolchain toolchain,
                                   MusicCommand musicCommand, DiscordAccountLinkManager accountLinks) {
        this.plugin = plugin;
        this.playback = playback;
        this.commandService = new DiscordCommandService(plugin, playback, musicCommand, accountLinks);
        this.toolchain = toolchain;
        this.logger = plugin.getLogger();
        this.token = plugin.getConfig().getString("discord.bot-token", "").trim();
        this.channelId = plugin.getConfig().getString("discord.voice-channel-id", "").trim();
        this.reconnectAndResync = plugin.getConfig().getBoolean("discord.reconnect-and-resync", true);
        Path dataDirectory = plugin.getDataFolder().toPath();
        this.streamer = new DiscordTrackStreamer(toolchain, dataDirectory, sendHandler, logger);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Musicolide-Discord-Supervisor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get()) return;
        if (token.isBlank() || channelId.isBlank()) {
            logger.warning("Discord broadcasting is enabled, but discord.bot-token or "
                    + "discord.voice-channel-id is empty. Discord output remains disabled.");
            close();
            return;
        }
        try {
            parsedChannelId = Long.parseLong(channelId);
            if (parsedChannelId <= 0) throw new NumberFormatException("non-positive channel ID");
        } catch (NumberFormatException ex) {
            logger.warning("Discord broadcasting is disabled because discord.voice-channel-id is invalid.");
            close();
            return;
        }
        if (!toolchain.isInitialized()) {
            logger.warning("Discord broadcasting is waiting for the native audio toolchain to initialize.");
        }

        executor.execute(this::login);
    }

    private void login() {
        if (closed.get()) return;
        try {
            NativeDaveFactory.ensureAvailable();
            JDA client = JDABuilder.createLight(token,
                            GatewayIntent.GUILDS, GatewayIntent.GUILD_VOICE_STATES,
                            GatewayIntent.DIRECT_MESSAGES)
                    .setAutoReconnect(reconnectAndResync)
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(
                            new LDJDADaveSessionFactory(new NativeDaveFactory())))
                    .build();
            client.addEventListener(commandService);
            jda = client;
            executor.scheduleWithFixedDelay(this::tickSafely, 500, 500, TimeUnit.MILLISECONDS);
            logger.info("Discord broadcaster initialized. Waiting for the configured voice channel.");
        } catch (Throwable error) {
            logger.log(Level.WARNING, "Discord broadcaster could not initialize ("
                    + error.getClass().getSimpleName() + "). Minecraft playback is unaffected.");
            close();
        }
    }

    private void tickSafely() {
        if (closed.get()) return;
        try {
            tick();
        } catch (Throwable error) {
            streamer.stop();
            lastTimelineKey = null;
            logger.log(Level.WARNING, "Discord broadcaster encountered an error ("
                    + error.getClass().getSimpleName() + "). Minecraft playback is unaffected.");
        }
    }

    private void tick() {
        JDA client = jda;
        if (client == null) return;
        JDA.Status gatewayStatus = client.getStatus();
        if (gatewayStatus == JDA.Status.FAILED_TO_LOGIN || gatewayStatus == JDA.Status.SHUTDOWN) {
            streamer.stop();
            lastTimelineKey = null;
            if (!terminalLoginFailureLogged) {
                logger.warning("Discord connection ended (" + gatewayStatus
                        + "). Check the bot token and Discord application settings. Minecraft playback is unaffected.");
                terminalLoginFailureLogged = true;
            }
            close();
            return;
        }
        if (gatewayStatus != JDA.Status.CONNECTED) {
            streamer.stop();
            lastTimelineKey = null;
            voiceWasConnected = false;
            return;
        }

        Optional<DiscordPlaybackSnapshot> selected = playback.discordBroadcastSnapshot();
        updatePresence(client, selected);

        VoiceChannel channel = client.getVoiceChannelById(parsedChannelId);
        if (channel == null) {
            streamer.stop();
            lastTimelineKey = null;
            Guild managedGuild = lastKnownVoiceGuildId == null ? null
                    : client.getGuildById(lastKnownVoiceGuildId);
            if (managedGuild != null) managedGuild.getAudioManager().closeAudioConnection();
            if (!missingChannelLogged) {
                logger.warning("Discord voice channel " + channelId
                        + " is not visible to the bot. Check the channel ID and bot access.");
                missingChannelLogged = true;
            }
            return;
        }
        missingChannelLogged = false;

        Guild guild = channel.getGuild();
        lastKnownVoiceGuildId = guild.getId();
        Member self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL,
                Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            streamer.stop();
            lastTimelineKey = null;
            guild.getAudioManager().closeAudioConnection();
            voiceWasConnected = false;
            if (!permissionWarningLogged) {
                logger.warning("Discord bot needs View Channel, Connect and Speak permissions in the configured voice channel.");
                permissionWarningLogged = true;
            }
            return;
        }
        permissionWarningLogged = false;

        AudioManager audio = guild.getAudioManager();
        audio.setSendingHandler(sendHandler);
        audio.setAutoReconnect(reconnectAndResync);

        // A previously selected track can briefly have an unstarted clock
        // after a timestamp-tail resume has loaded and is waiting at the
        // Minecraft start barrier. Keep an existing voice connection alive,
        // but never create a new one before the resumed clock actually starts.
        DiscordPlaybackSnapshot snapshot = selected.orElse(null);
        if (snapshot != null && !snapshot.clockStarted()) {
            streamer.stop();
            lastTimelineKey = timelineKey(snapshot) + ":waiting-for-clock";
            if (!audio.isConnected()) voiceWasConnected = false;
            return;
        }

        // Keep the gateway online for slash commands and /info, but only hold a
        // voice connection while a selected Musicolide session has actually
        // started. A paused session remains selected and therefore keeps VC.
        if (selected.isEmpty() || selected.get().track() == null || !selected.get().clockStarted()) {
            streamer.stop();
            lastTimelineKey = null;
            if (audio.isConnected()) audio.closeAudioConnection();
            voiceWasConnected = false;
            hasEverConnected = false;
            return;
        }

        if (audio.isConnected() && !channel.equals(audio.getConnectedChannel())) {
            // JDA moves an existing voice connection directly; closing it first
            // creates an avoidable disconnect/reconnect window.
            audio.openAudioConnection(channel);
            streamer.stop();
            lastTimelineKey = null;
            voiceWasConnected = false;
            return;
        }
        if (!audio.isConnected()) {
            streamer.stop();
            lastTimelineKey = null;
            ConnectionStatus connectionStatus = audio.getConnectionStatus();
            boolean initialConnection = !hasEverConnected;
            boolean retryAllowed = initialConnection || reconnectAndResync;
            long now = System.nanoTime();
            if (retryAllowed && connectionStatus == ConnectionStatus.NOT_CONNECTED
                    && now - lastVoiceConnectAttemptNanos >= TimeUnit.SECONDS.toNanos(5)) {
                audio.openAudioConnection(channel);
                lastVoiceConnectAttemptNanos = now;
            }
            voiceWasConnected = false;
            return;
        }

        if (!voiceWasConnected) {
            // A new voice connection must never replay the old buffered PCM.
            // The next snapshot starts the decoder from Minecraft's current clock.
            streamer.stop();
            lastTimelineKey = null;
            voiceWasConnected = true;
            hasEverConnected = true;
        }

        snapshot = selected.get();
        if (!snapshot.isAudible()) {
            // Paused and resume-preparing sessions keep the bot in voice but
            // stop sending PCM. A later resume starts a fresh decoder at the
            // authoritative Minecraft timestamp.
            streamer.stop();
            lastTimelineKey = timelineKey(snapshot) + ":paused:" + snapshot.preparing();
            return;
        }

        if (!toolchain.isInitialized()) {
            streamer.stop();
            lastTimelineKey = null;
            return;
        }
        if (!toolchain.available(NativeToolchain.Tool.FFMPEG)) {
            streamer.stop();
            lastTimelineKey = null;
            if (!ffmpegUnavailableLogged) {
                logger.warning("Discord broadcasting requires the configured FFmpeg executable. "
                        + "Minecraft playback remains available.");
                ffmpegUnavailableLogged = true;
            }
            return;
        }
        ffmpegUnavailableLogged = false;

        String key = timelineKey(snapshot);
        if (!key.equals(lastTimelineKey)) {
            lastTimelineKey = key;
            streamer.start(snapshot, playback::discordBroadcastSnapshot);
            lastDriftCheckNanos = System.nanoTime();
            lastRestartAttemptNanos = lastDriftCheckNanos;
            return;
        }

        long now = System.nanoTime();
        if (now - lastDriftCheckNanos >= DRIFT_CHECK_INTERVAL_NANOS) {
            lastDriftCheckNanos = now;
            if (snapshot.elapsedMs() < snapshot.track().durationMs() - MAX_DRIFT_MS) {
                if (!streamer.isActive()
                        && now - lastRestartAttemptNanos >= TimeUnit.SECONDS.toNanos(5)) {
                    // Recover a decoder that exited unexpectedly, but avoid tight retry loops.
                    streamer.start(snapshot, playback::discordBroadcastSnapshot);
                    lastRestartAttemptNanos = now;
                } else if (streamer.isActive()) {
                    long drift = Math.abs(snapshot.elapsedMs() - streamer.estimatedPositionMs());
                    if (drift > MAX_DRIFT_MS) {
                        streamer.start(snapshot, playback::discordBroadcastSnapshot);
                        lastRestartAttemptNanos = now;
                    }
                }
            }
        }
    }

    /**
     * Updates Discord profile metadata only. This method does not touch the
     * voice connection, audio sender, decoder, or Minecraft playback engine.
     */
    private void updatePresence(JDA client, Optional<DiscordPlaybackSnapshot> selected) {
        boolean enabled = plugin.getConfig().getBoolean("discord.presence.enabled", true);
        DiscordPlaybackSnapshot snapshot = selected
                .filter(value -> value.track() != null && value.isAudible())
                .orElse(null);
        boolean activelyPlaying = enabled && snapshot != null;
        OnlineStatus desiredStatus = activelyPlaying ? OnlineStatus.ONLINE : OnlineStatus.IDLE;

        if (!enabled) {
            // If presence updates are disabled, clear our activity and leave the
            // bot in a predictable online state rather than retaining stale metadata.
            desiredStatus = OnlineStatus.ONLINE;
        }

        long now = System.nanoTime();
        long intervalSeconds = Math.max(15L,
                plugin.getConfig().getLong("discord.presence.update-interval-seconds", 15L));
        String identity = activelyPlaying
                ? snapshot.sessionId() + ":" + snapshot.track().id()
                : "idle";
        boolean stateChanged = !identity.equals(lastPresenceIdentity)
                || desiredStatus != lastPresenceStatus;
        if (!stateChanged && now - lastPresenceUpdateNanos < TimeUnit.SECONDS.toNanos(intervalSeconds)) return;

        String activityText = "";
        if (activelyPlaying) {
            String template = plugin.getConfig().getString(
                    "discord.presence.template", "🎵 {title} • {elapsed}");
            activityText = template
                    .replace("{title}", snapshot.track().title() == null ? "Unknown" : snapshot.track().title())
                    .replace("{artist}", snapshot.track().artist() == null ? "" : snapshot.track().artist())
                    .replace("{elapsed}", formatPresenceTime(snapshot.elapsedMs()))
                    .replace("{duration}", formatPresenceTime(snapshot.track().durationMs()))
                    .replace("{state}", "Playing")
                    .trim();
            if (activityText.length() > 128) activityText = activityText.substring(0, 127) + "…";
        }

        if (!activityText.equals(lastPresenceText) || stateChanged) {
            client.getPresence().setActivity(activityText.isBlank()
                    ? null : Activity.playing(activityText));
            lastPresenceText = activityText;
        }
        if (desiredStatus != lastPresenceStatus) {
            client.getPresence().setStatus(desiredStatus);
            lastPresenceStatus = desiredStatus;
        }
        lastPresenceIdentity = identity;
        lastPresenceUpdateNanos = now;
    }

    private String formatPresenceTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private String timelineKey(DiscordPlaybackSnapshot snapshot) {
        return snapshot.sessionId() + ":" + snapshot.timelineGeneration() + ":"
                + snapshot.track().id() + ":" + String.format(java.util.Locale.ROOT, "%.3f", snapshot.speed());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        streamer.close();
        sendHandler.close();
        executor.shutdownNow();
        JDA client = jda;
        jda = null;
        if (client != null) {
            try {
                client.removeEventListener(commandService);
                Guild managedGuild = lastKnownVoiceGuildId == null ? null
                        : client.getGuildById(lastKnownVoiceGuildId);
                if (managedGuild != null) {
                    AudioManager manager = managedGuild.getAudioManager();
                    manager.closeAudioConnection();
                    manager.setSendingHandler(null);
                }
                client.shutdown();
            } catch (Exception error) {
                logger.log(Level.FINE, "Discord shutdown completed with " + error.getClass().getSimpleName());
            }
        }
    }
}
