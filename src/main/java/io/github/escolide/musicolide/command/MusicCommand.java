package io.github.escolide.musicolide.command;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.playback.PlaybackSession;
import io.github.escolide.musicolide.storage.TrackRepository;
import io.github.escolide.musicolide.util.CatalogMatcher;
import io.github.escolide.musicolide.util.ExternalMetadataResolver;
import io.github.escolide.musicolide.util.LinkNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final AudioProcessor processor;
    private final PlaybackEngine playback;
    private final ExternalMetadataResolver metadataResolver = new ExternalMetadataResolver();
    private final Map<UUID, PendingPlay> pending = new ConcurrentHashMap<>();

    public MusicCommand(JavaPlugin plugin, TrackRepository repository, AudioProcessor processor, PlaybackEngine playback) {
        this.plugin = plugin;
        this.repository = repository;
        this.processor = processor;
        this.playback = playback;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "import" -> importTrack(sender, args);
                case "list" -> listTracks(sender, args);
                case "play" -> play(sender, args);
                case "confirm" -> confirm(sender);
                case "pause", "resume", "skip", "stop" -> control(sender, sub);
                case "queue" -> queue(sender, args);
                case "nowplaying", "progress" -> nowPlaying(sender);
                case "volume" -> volume(sender, args);
                case "mute" -> mute(sender, true);
                case "unmute" -> mute(sender, false);
                case "seek" -> seek(sender, args);
                case "playlist" -> playlist(sender, args);
                case "alias" -> alias(sender, args);
                case "priority" -> priority(sender, args);
                case "reload" -> reload(sender);
                default -> help(sender);
            }
        } catch (SQLException ex) {
            sender.sendMessage(msg("&cDatabase error: " + ex.getMessage()));
            plugin.getLogger().warning("Musicolide database error: " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            sender.sendMessage(msg("&c" + ex.getMessage()));
        }
        return true;
    }

    private void importTrack(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music import <filename> [title]")); return; }
        String title = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        sender.sendMessage(msg("&aPreparing audio. Place the file in plugins/Musicolide/incoming/."));
        processor.importFile(args[1], title).whenComplete((track, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cImport failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aImported &f" + track.title() + " &7(ID: &f" + track.id() + "&7)"));
                }));
    }

    private void listTracks(CommandSender sender, String[] args) throws SQLException {
        String query = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        List<MusicTrack> tracks = query.isBlank() ? repository.allTracks(50) : repository.search(query, 50);
        sender.sendMessage(msg("&bMusicolide tracks (&f" + tracks.size() + "&b):"));
        for (MusicTrack track : tracks) sender.sendMessage(msg("&7- &f" + track.id() + " &8| &b" + track.title()
                + " &8| &7" + format(track.durationMs())));
    }

    private void play(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music play <track> [--all|--player name|--circle radius|--square WxL|--region id] [--fade blocks]")); return; }
        String input = args[1];
        PlayRequest parsed = parseRequest(sender, args);
        PlayRequest request = new PlayRequest(parsed.zone, parsed.all, parsed.listeners, parsed.priority, input);
        Optional<MusicTrack> track = resolveTrack(input);
        if (track.isPresent()) {
            offerMatch(sender, track.get(), request, isExternal(input) ? -1.0 : 1.0);
            return;
        }
        if (!isExternal(input)) {
            sender.sendMessage(msg("&cTrack not found. Import an authorized audio file first."));
            return;
        }
        if (!plugin.getConfig().getBoolean("matching.enabled", true)
                || !plugin.getConfig().getBoolean("matching.metadata-lookup", true)) {
            sender.sendMessage(msg("&cNo saved alias exists for this URL. Ask an administrator to add one."));
            return;
        }
        sender.sendMessage(msg("&eLooking up public metadata and searching your local catalogue. No audio is downloaded from the streaming service."));
        metadataResolver.resolve(input).thenAccept(metadata -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (metadata.isEmpty()) {
                sender.sendMessage(msg("&cCould not read public metadata for that URL. Use /music alias add to map it manually."));
                return;
            }
            try {
                List<MusicTrack> candidates = repository.search(metadata.get().title(), 20);
                Optional<CatalogMatcher.Match> match = CatalogMatcher.best(metadata.get().title(),
                        metadata.get().creator(), candidates);
                if (match.isEmpty()) {
                    sender.sendMessage(msg("&cNo likely match was found in your local authorized catalogue."));
                    return;
                }
                offerMatch(sender, match.get().track(), request, match.get().confidence());
            } catch (SQLException ex) {
                sender.sendMessage(msg("&cCatalogue search failed: " + ex.getMessage()));
            }
        }));
    }

    private void offerMatch(CommandSender sender, MusicTrack track, PlayRequest request, double confidence) {
        if (sender instanceof Player player && (isExternal(request.inputUrl)
                || plugin.getConfig().getBoolean("matching.require-confirmation-for-alias", true) && confidence < 1.0)) {
            pending.put(player.getUniqueId(), new PendingPlay(track, request));
            if (confidence < 0) {
                sender.sendMessage(msg("&eThis URL is mapped to local track: &f" + track.title()));
            } else {
                sender.sendMessage(msg("&ePossible catalogue match: &f" + track.title() + " &7(" + Math.round(confidence * 100) + "% title-match estimate)"));
            }
            sender.sendMessage(msg("&eIt may be a different recording. Type &f/music confirm &eto play."));
            return;
        }
        start(sender, track, request);
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cOnly players can confirm a pending match.")); return; }
        PendingPlay value = pending.remove(player.getUniqueId());
        if (value == null) { sender.sendMessage(msg("&cYou have no pending music match.")); return; }
        start(sender, value.track, value.request);
    }

    private Optional<MusicTrack> resolveTrack(String token) throws SQLException {
        if (isExternal(token)) {
            Optional<String> normalized = LinkNormalizer.normalize(token);
            return normalized.isEmpty() ? Optional.empty() : repository.resolveAlias(normalized.get());
        }
        Optional<MusicTrack> exact = repository.getTrack(token);
        if (exact.isPresent()) return exact;
        return repository.search(token, 1).stream().findFirst();
    }

    private boolean isExternal(String token) { return token != null && (token.startsWith("http://") || token.startsWith("https://")); }

    private PlayRequest parseRequest(CommandSender sender, String[] args) {
        boolean global = false;
        UUID target = null;
        double radius = plugin.getConfig().getDouble("playback.range.circle.radius", 60);
        double width = plugin.getConfig().getDouble("playback.range.square.width", 50);
        double length = plugin.getConfig().getDouble("playback.range.square.length", 50);
        String region = null;
        boolean square = false;
        boolean fadeEnabled = plugin.getConfig().getBoolean("playback.fade.enabled-by-default", false);
        double fade = fadeEnabled ? plugin.getConfig().getDouble("playback.fade.distance", 10) : 0;
        for (int i = 2; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--all" -> global = true;
                case "--player" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--player requires a player name.");
                    Player found = Bukkit.getPlayerExact(args[i]);
                    if (found == null) throw new IllegalArgumentException("Player is not online: " + args[i]);
                    target = found.getUniqueId();
                }
                case "--circle" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--circle requires a radius.");
                    radius = Double.parseDouble(args[i]); square = false;
                }
                case "--square" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--square requires WIDTHxLENGTH.");
                    String[] dims = args[i].toLowerCase(Locale.ROOT).split("x", 2);
                    if (dims.length != 2) throw new IllegalArgumentException("Square dimensions must look like 50x50.");
                    width = Double.parseDouble(dims[0]); length = Double.parseDouble(dims[1]); square = true;
                }
                case "--fade" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--fade requires a distance.");
                    fade = Double.parseDouble(args[i]); fadeEnabled = fade > 0;
                }
                case "--region" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--region requires a region ID.");
                    region = args[i];
                }
            }
        }
        if (global && target != null) throw new IllegalArgumentException("Choose either --all or --player.");
        if (!Double.isFinite(radius) || !Double.isFinite(width) || !Double.isFinite(length)
                || !Double.isFinite(fade) || radius < 0 || width <= 0 || length <= 0 || fade < 0)
            throw new IllegalArgumentException("Zone dimensions must be finite and positive.");
        if (sender instanceof Player player) {
            MusicZone zone;
            boolean limited = plugin.getConfig().getString("playback.range.vertical.mode", "unlimited").equalsIgnoreCase("limited");
            double vertical = plugin.getConfig().getDouble("playback.range.vertical.max-distance", 32);
            if (global) zone = MusicZone.global();
            else if (target != null) zone = MusicZone.global();
            else if (region != null) zone = MusicZone.region(player.getWorld().getUID(), region);
            else if (square) {
                double rotation = plugin.getConfig().getString("playback.range.square.rotation", "world")
                        .equalsIgnoreCase("player-facing") ? Math.toRadians(player.getLocation().getYaw()) : 0;
                zone = MusicZone.square(player.getWorld().getUID(), player.getX(), player.getY(), player.getZ(),
                        width, length, rotation, limited, vertical, fadeEnabled, fade);
            } else zone = MusicZone.circle(player.getWorld().getUID(), player.getX(), player.getY(), player.getZ(),
                    radius, limited, vertical, fadeEnabled, fade);
            if (global) return new PlayRequest(zone, true, List.of(), 0, null);
            if (target != null) return new PlayRequest(zone, false, List.of(target), 0, null);
            return new PlayRequest(zone, true, List.of(), 0, null);
        }
        if (!global && target == null) throw new IllegalArgumentException("From console, specify --all or --player <name>.");
        return target != null ? new PlayRequest(MusicZone.global(), false, List.of(target), 0, null)
                : new PlayRequest(MusicZone.global(), true, List.of(), 0, null);
    }

    private void start(CommandSender sender, MusicTrack track, PlayRequest request) {
        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        PlaybackSession session = playback.startSession(owner, sender.getName(), request.zone, request.all,
                request.listeners, List.of(track), request.priority);
        sender.sendMessage(msg("&aStarted &f" + track.title() + " &7(session " + session.id().toString().substring(0, 8) + ")"));
    }

    private void control(CommandSender sender, String action) {
        if (!dj(sender)) return;
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        if (actor == null) { sender.sendMessage(msg("&cConsole control is not supported; use an in-game DJ.")); return; }
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        if (!playback.control(actor, admin, action)) sender.sendMessage(msg("&cNo controllable session was found."));
        else sender.sendMessage(msg("&a" + action.substring(0, 1).toUpperCase(Locale.ROOT) + action.substring(1) + " applied."));
    }

    private void queue(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music queue <track-id>")); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        MusicTrack track = resolveTrack(args[1]).orElseThrow(() -> new IllegalArgumentException("Track not found."));
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        if (!playback.enqueue(player.getUniqueId(), admin, track)) sender.sendMessage(msg("&cNo session found or queue is full."));
        else sender.sendMessage(msg("&aQueued: &f" + track.title()));
    }

    private void nowPlaying(CommandSender sender) {
        if (playback.sessions().isEmpty()) { sender.sendMessage(msg("&eNo active music sessions.")); return; }
        for (PlaybackSession session : playback.sessions()) {
            MusicTrack track = session.current();
            sender.sendMessage(msg("&b" + (track == null ? "Unknown" : track.title()) + " &7by " + session.ownerName()
                    + " &8| &7" + format(session.elapsedMs()) + " / " + format(track == null ? 0 : track.durationMs())
                    + (session.paused() ? " &e(PAUSED)" : "")));
        }
    }

    private void volume(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        if (args.length < 2) { sender.sendMessage(msg("&eYour volume: " + playback.getPlayerVolume(player) + "%")); return; }
        int value = Integer.parseInt(args[1]);
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be between 0 and 100.");
        playback.setPlayerVolume(player, value);
        sender.sendMessage(msg("&aYour music volume is now " + value + "%."));
    }

    private void mute(CommandSender sender, boolean muted) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        playback.setPlayerMuted(player, muted);
        sender.sendMessage(msg(muted ? "&eMusic muted." : "&aMusic unmuted."));
    }

    private void seek(CommandSender sender, String[] args) {
        if (!dj(sender)) return;
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music seek <seconds>")); return; }
        double seconds = Double.parseDouble(args[1]);
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Seek position must be finite and non-negative.");
        PlaybackSession session = playback.findOwned(player.getUniqueId()).orElseThrow(() -> new IllegalArgumentException("No session found."));
        session.seek((long) (seconds * 1000));
        sender.sendMessage(msg("&aSeeked to " + format((long) (seconds * 1000)) + "."));
    }

    private void playlist(CommandSender sender, String[] args) throws SQLException {
        if (!plugin.getConfig().getBoolean("playlists.enabled", true)) throw new IllegalArgumentException("Playlists are disabled.");
        if (args.length < 2) throw new IllegalArgumentException("Usage: /music playlist <create|add|list|play> ...");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!dj(sender)) return;
                if (args.length < 3) throw new IllegalArgumentException("Usage: /music playlist create <name>");
                repository.createPlaylist(args[2]);
                sender.sendMessage(msg("&aPlaylist created: " + args[2]));
            }
            case "add" -> {
                if (!dj(sender)) return;
                if (args.length < 4) throw new IllegalArgumentException("Usage: /music playlist add <name> <track-id>");
                MusicTrack track = resolveTrack(args[3]).orElseThrow(() -> new IllegalArgumentException("Track not found."));
                repository.addPlaylistTrack(args[2], track.id());
                sender.sendMessage(msg("&aAdded " + track.title() + " to " + args[2]));
            }
            case "list" -> sender.sendMessage(msg("&bPlaylists: &f" + String.join(", ", repository.listPlaylists())));
            case "play" -> {
                if (!dj(sender)) return;
                if (args.length < 3) throw new IllegalArgumentException("Usage: /music playlist play <name>");
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("Players only for spatial playlists.");
                List<MusicTrack> tracks = new ArrayList<>(repository.getPlaylist(args[2],
                        plugin.getConfig().getInt("limits.max-playlist-tracks", 500)));
                if (tracks.isEmpty()) throw new IllegalArgumentException("Playlist is empty.");
                if (plugin.getConfig().getBoolean("playlists.shuffle", false)) Collections.shuffle(tracks);
                MusicZone zone = defaultPlayerZone(player);
                PlaybackSession session = playback.startSession(player.getUniqueId(), player.getName(), zone,
                        true, List.of(), tracks, 0);
                session.repeat(plugin.getConfig().getBoolean("playlists.repeat", false));
                sender.sendMessage(msg("&aStarted playlist &f" + args[2] + " &7(" + tracks.size() + " tracks; packs load gradually)."));
            }
            default -> throw new IllegalArgumentException("Unknown playlist subcommand.");
        }
    }

    private MusicZone defaultPlayerZone(Player player) {
        boolean limited = plugin.getConfig().getString("playback.range.vertical.mode", "unlimited").equalsIgnoreCase("limited");
        return MusicZone.circle(player.getWorld().getUID(), player.getX(), player.getY(), player.getZ(),
                plugin.getConfig().getDouble("playback.range.circle.radius", 60), limited,
                plugin.getConfig().getDouble("playback.range.vertical.max-distance", 32),
                plugin.getConfig().getBoolean("playback.fade.enabled-by-default", false),
                plugin.getConfig().getDouble("playback.fade.distance", 10));
    }

    private void alias(CommandSender sender, String[] args) throws SQLException {
        if (!admin(sender)) return;
        if (args.length < 4 || !args[1].equalsIgnoreCase("add")) {
            sender.sendMessage(msg("&eUsage: /music alias add <external-url> <track-id>")); return;
        }
        String key = LinkNormalizer.normalize(args[2]).orElseThrow(() -> new IllegalArgumentException("Invalid URL."));
        if (repository.getTrack(args[3]).isEmpty()) throw new IllegalArgumentException("Local track ID not found.");
        repository.addAlias(key, args[3]);
        sender.sendMessage(msg("&aURL alias saved. It points to your local authorized audio file."));
    }

    private void priority(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Players only.");
        if (args.length < 2) throw new IllegalArgumentException("Usage: /music priority <number>");
        int value = Integer.parseInt(args[1]);
        if (value < -1000 || value > 1000) throw new IllegalArgumentException("Priority must be between -1000 and 1000.");
        PlaybackSession session = playback.findOwned(player.getUniqueId()).orElseThrow(() -> new IllegalArgumentException("No session found."));
        session.priority(value);
        sender.sendMessage(msg("&aSession priority set to " + value + "."));
    }

    private void reload(CommandSender sender) {
        if (!admin(sender)) return;
        plugin.reloadConfig();
        sender.sendMessage(msg("&aConfiguration reloaded. HTTP listener settings require restart."));
    }

    private boolean dj(CommandSender sender) {
        if (sender.hasPermission(plugin.getConfig().getString("permissions.dj", "musicolide.dj"))) return true;
        sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
        return false;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"))) return true;
        sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
        return false;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(msg("&bMusicolide commands:"));
        sender.sendMessage(msg("&f/music import <file> [title] &7- import authorized local audio"));
        sender.sendMessage(msg("&f/music list [search] &7- list tracks"));
        sender.sendMessage(msg("&f/music play <track-or-url> [--all|--player name|--circle r|--square WxL] [--fade n]"));
        sender.sendMessage(msg("&f/music pause|resume|skip|stop|queue <track>"));
        sender.sendMessage(msg("&f/music playlist create|add|list|play ..."));
        sender.sendMessage(msg("&f/music volume <0-100>|mute|unmute|seek <seconds>|nowplaying"));
        sender.sendMessage(msg("&f/music alias add <url> <track-id> &7- map a URL to local audio"));
        sender.sendMessage(msg("&f/music priority <number> &7- admin session priority"));
    }

    private Component msg(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(
                plugin.getConfig().getString("messages.prefix", "") + text);
    }

    private String format(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return partial(List.of("help", "import", "list", "play", "confirm", "pause", "resume",
                "skip", "stop", "queue", "nowplaying", "progress", "volume", "mute", "unmute", "seek",
                "playlist", "alias", "priority", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("playlist"))
            return partial(List.of("create", "add", "list", "play"), args[1]);
        return List.of();
    }

    private List<String> partial(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private record PlayRequest(MusicZone zone, boolean all, Collection<UUID> listeners, int priority, String inputUrl) {}
    private record PendingPlay(MusicTrack track, PlayRequest request) {}
}
