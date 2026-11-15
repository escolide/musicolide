package io.github.escolide.musicolide.command;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.audio.YtDlpDownloader;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.link.DiscordAccountLinkManager;
import io.github.escolide.musicolide.model.MusicZone;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.playback.PlaybackSession;
import io.github.escolide.musicolide.storage.TrackRepository;
import io.github.escolide.musicolide.util.CatalogMatcher;
import io.github.escolide.musicolide.util.DiscordCommandPolicy;
import io.github.escolide.musicolide.util.ExternalMetadataResolver;
import io.github.escolide.musicolide.util.LinkNormalizer;
import io.github.escolide.musicolide.util.RequestCooldowns;
import io.github.escolide.musicolide.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MusicCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final AudioProcessor processor;
    private final PlaybackEngine playback;
    private final DiscordAccountLinkManager accountLinks;
    private final ExternalMetadataResolver metadataResolver = new ExternalMetadataResolver();
    private final Map<UUID, PendingPlay> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSearch> pendingSearches = new ConcurrentHashMap<>();
    private static final long SEARCH_RESULT_TTL_MILLIS = 5 * 60 * 1000L;
    private final RequestCooldowns requestCooldowns = new RequestCooldowns();


    public MusicCommand(JavaPlugin plugin, TrackRepository repository, AudioProcessor processor, PlaybackEngine playback,
                        DiscordAccountLinkManager accountLinks) {
        this.plugin = plugin;
        this.repository = repository;
        this.processor = processor;
        this.playback = playback;
        this.accountLinks = accountLinks;
    }

    public boolean onPlayCommand(CommandSender sender, Command command, String label, String[] args) {
        String[] delegated = new String[args.length + 1];
        delegated[0] = "play";
        System.arraycopy(args, 0, delegated, 1, args.length);
        return onCommand(sender, command, "music", delegated);
    }

    public List<String> onPlayTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String[] delegated = new String[args.length + 1];
        delegated[0] = "play";
        System.arraycopy(args, 0, delegated, 1, args.length);
        return onTabComplete(sender, command, "music", delegated);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Excluded Bedrock players receive no Musicolide command feedback and
        // cannot initiate plugin actions through Geyser's command bridge.
        if (sender instanceof Player player
                && playback.isIgnoredBedrockPlayer(player.getUniqueId())) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "link" -> link(sender);
                case "unlink" -> unlink(sender);
                case "import" -> importTrack(sender, args);
                case "import-url" -> importUrl(sender, args);
                case "diagnostics" -> diagnostics(sender);
                case "download" -> downloadLibrary(sender);
                case "list" -> listTracks(sender, args);
                case "search" -> searchYouTube(sender, args);
                case "select" -> selectYouTubeResult(sender, args);
                case "page" -> showNextSearchPage(sender, args);
                case "play" -> play(sender, args);
                case "confirm" -> confirm(sender);
                case "pause", "resume", "skip", "stop" -> control(sender, sub);
                case "queue" -> queue(sender, args);
                case "nowplaying", "progress" -> nowPlaying(sender);
                case "volume" -> volume(sender, args);
                case "speed" -> speed(sender, args);
                case "mute" -> mute(sender, true);
                case "unmute" -> mute(sender, false);
                case "seek" -> seek(sender, args);
                case "sync" -> sync(sender, args);
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


    /** Entry point for the Discord bot's /link Minecraft-side command. */
    public boolean onLinkCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && playback.isIgnoredBedrockPlayer(player.getUniqueId())) return true;
        link(sender);
        return true;
    }

    /** Entry point for the Discord bot's /unlink Minecraft-side command. */
    public boolean onUnlinkCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && playback.isIgnoredBedrockPlayer(player.getUniqueId())) return true;
        unlink(sender);
        return true;
    }

    private void link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cOnly a Minecraft player can create a Discord link code."));
            return;
        }
        if (!player.hasPermission(plugin.getConfig().getString("permissions.use", "musicolide.use"))) {
            sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
            return;
        }
        if (!plugin.getConfig().getBoolean("discord.enabled", false)
                || !plugin.getConfig().getBoolean("discord.commands.enabled", true)
                || accountLinks == null) {
            sender.sendMessage(msg("&cDiscord account linking is disabled on this server."));
            return;
        }
        if (!admitHeavyRequest(sender, "discord-link-code",
                "discord.commands.link-code-cooldown-seconds", 30,
                "discord.commands.global-link-code-cooldown-seconds", 1, "another Discord link code")) return;
        try {
            String code = accountLinks.createCode(player.getUniqueId(), player.getName());
            sender.sendMessage(msg("&aYour one-time Discord link code is: &f&l" + code));
            sender.sendMessage(msg("&7DM this code to the Musicolide Discord bot. It expires in 5 minutes and can only be used once."));
            sender.sendMessage(msg("&8No password, Microsoft login, or session token is needed."));
        } catch (SQLException error) {
            plugin.getLogger().warning("Could not create a Discord account-link code: " + error.getClass().getSimpleName());
            sender.sendMessage(msg("&cCould not create a Discord link code. Please try again later."));
        } catch (IllegalStateException error) {
            sender.sendMessage(msg("&e" + error.getMessage()));
        }
    }

    private void unlink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cOnly a Minecraft player can unlink their Discord account."));
            return;
        }
        if (accountLinks == null) {
            sender.sendMessage(msg("&cDiscord account linking is unavailable."));
            return;
        }
        try {
            if (accountLinks.unlinkMinecraft(player.getUniqueId())) {
                sender.sendMessage(msg("&aYour Discord account has been unlinked from Minecraft."));
            } else {
                sender.sendMessage(msg("&eYour Minecraft account is not currently linked."));
            }
        } catch (SQLException error) {
            plugin.getLogger().warning("Could not unlink a Discord account: " + error.getClass().getSimpleName());
            sender.sendMessage(msg("&cCould not unlink your account. Please try again later."));
        }
    }

    /**
     * Runs an existing Musicolide command as the linked, online Minecraft
     * player. A proxy preserves Player identity and permission checks while
     * forwarding command feedback to the Discord interaction.
     */
    public void executeDiscordCommand(Player actualPlayer, String commandLine,
                                      boolean elevateAdmin, Consumer<String> output) {
        Objects.requireNonNull(actualPlayer, "actualPlayer");
        Objects.requireNonNull(output, "output");
        String[] args = tokenizeDiscordCommand(commandLine);
        if (args.length == 0) {
            output.accept("Enter a Musicolide command, such as: pause, queue <track-id>, or playlist list.");
            return;
        }
        if (args[0].equalsIgnoreCase("music") || args[0].equalsIgnoreCase("/music")) {
            args = Arrays.copyOfRange(args, 1, args.length);
        } else if (args[0].equalsIgnoreCase("/play")) {
            args[0] = "play";
        } else if (args[0].startsWith("/")) {
            args[0] = args[0].substring(1);
        }
        if (args.length == 0) {
            output.accept("Enter a Musicolide command, such as: pause, queue <track-id>, or playlist list.");
            return;
        }
        // Discord can invoke Musicolide's own command surface only. Never route
        // arbitrary text into Bukkit's general command dispatcher; account-link
        // management also remains Minecraft-only.
        if (!isDiscordCommandAllowed(args[0])) {
            output.accept("That command is not available through the Musicolide Discord bot.");
            return;
        }
        Player linkedSender = createDiscordPlayerProxy(actualPlayer, elevateAdmin, output);
        onCommand(linkedSender, null, "music", args);
    }

    static boolean isDiscordCommandAllowed(String command) {
        return DiscordCommandPolicy.isAllowedRoot(command);
    }

    /** YouTube metadata-only suggestions for Discord's 3-second autocomplete response. */
    public CompletableFuture<List<DiscordPlaySuggestion>> discordPlaySuggestions(String query) {
        return discordPlaySuggestions(query, true);
    }

    /** Allows the Discord adapter to serve local results when remote lookup is rate-limited. */
    public CompletableFuture<List<DiscordPlaySuggestion>> discordPlaySuggestions(String query, boolean allowRemoteLookup) {
        String cleaned = normalizeSearchQuery(query);
        if (cleaned.length() < 2 || cleaned.length() > 100) {
            return CompletableFuture.completedFuture(List.of());
        }

        final List<DiscordPlaySuggestion> local;
        try {
            local = repository.searchForPlayback(cleaned, 25, 0).stream()
                    .map(track -> new DiscordPlaySuggestion(
                            TextUtil.truncate(track.title()
                                    + (track.artist() == null || track.artist().isBlank()
                                    ? "" : " — " + track.artist()), 100),
                            track.id()))
                    .toList();
        } catch (SQLException error) {
            return CompletableFuture.failedFuture(error);
        }

        // Remote autocomplete choices are only actionable when the server
        // administrator explicitly enabled automatic importing of allowlisted
        // YouTube URLs. Otherwise, expose only tracks already in the local
        // authorized catalogue, matching /play's existing safety policy.
        boolean allowRemote = allowRemoteLookup
                && plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)
                && plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false);
        if (!allowRemote) return CompletableFuture.completedFuture(local);
        if (local.size() >= 25) return CompletableFuture.completedFuture(local);

        return processor.searchYouTube(cleaned).handle((results, error) -> {
            List<DiscordPlaySuggestion> suggestions = new ArrayList<>(local);
            if (error != null || results == null) return List.copyOf(suggestions);
            Set<String> existing = new HashSet<>();
            for (DiscordPlaySuggestion suggestion : local) existing.add(suggestion.value());
            for (YtDlpDownloader.SearchResult result : results) {
                if (suggestions.size() >= 25) break;
                String url = result.url();
                if (url == null || url.isBlank()) {
                    url = "https://www.youtube.com/watch?v=" + result.videoId();
                }
                if (!existing.add(url)) continue;
                String title = result.title() == null ? "Untitled" : result.title();
                String uploader = result.uploader() == null ? "" : result.uploader();
                String label = title + (uploader.isBlank() ? "" : " — " + uploader);
                if (result.durationSeconds() > 0) {
                    long seconds = result.durationSeconds();
                    label += " • " + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
                }
                suggestions.add(new DiscordPlaySuggestion(TextUtil.truncate(label, 100), url));
            }
            return List.copyOf(suggestions);
        });
    }

    private Player createDiscordPlayerProxy(Player target, boolean elevateAdmin, Consumer<String> output) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    String methodName = method.getName();
                    if ((methodName.equals("sendMessage") || methodName.equals("sendActionBar"))
                            && arguments != null) {
                        for (Object argument : arguments) {
                            if (argument instanceof String text) output.accept(text);
                            else if (argument instanceof String[] texts) {
                                for (String text : texts) output.accept(text);
                            } else if (argument instanceof net.kyori.adventure.text.Component component) {
                                output.accept(PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (argument instanceof net.kyori.adventure.text.ComponentLike componentLike) {
                                output.accept(PlainTextComponentSerializer.plainText().serialize(componentLike.asComponent()));
                            }
                        }
                        return null;
                    }
                    if (elevateAdmin && methodName.equals("hasPermission") && arguments != null
                            && arguments.length == 1 && arguments[0] instanceof String permission) {
                        String adminPermission = plugin.getConfig().getString("permissions.admin", "musicolide.admin");
                        String djPermission = plugin.getConfig().getString("permissions.dj", "musicolide.dj");
                        if (permission.equalsIgnoreCase(adminPermission)
                                || permission.equalsIgnoreCase(djPermission)) return true;
                    }
                    try {
                        return method.invoke(target, arguments);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private String[] tokenizeDiscordCommand(String commandLine) {
        if (commandLine == null) return new String[0];
        String value = commandLine.trim();
        if (value.length() > 512) throw new IllegalArgumentException("Discord command text is too long (maximum 512 characters).");
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
            } else if (ch == '\\' && quote != 0) {
                escaped = true;
            } else if ((ch == '"' || ch == '\'') && (quote == 0 || quote == ch)) {
                quote = quote == 0 ? ch : 0;
            } else if (Character.isWhitespace(ch) && quote == 0) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (escaped || quote != 0) throw new IllegalArgumentException("Close the quote in your command text.");
        if (!current.isEmpty()) tokens.add(current.toString());
        if (tokens.size() > 32) throw new IllegalArgumentException("A Discord command may contain at most 32 arguments.");
        return tokens.toArray(String[]::new);
    }

    public record DiscordPlaySuggestion(String label, String value) {}

    private void importTrack(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(msg("&eUsage: /music import <filename> [title] [--id custom_id] or /music import all"));
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            if (args.length != 2) throw new IllegalArgumentException("Bulk import does not accept a custom ID or title.");
            importAllTracks(sender);
            return;
        }
        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        String customId = optionValue(args, 2, "--id");
        String title = joinWithoutOption(args, 2, "--id");
        sender.sendMessage(msg("&aPreparing audio. Place the file in plugins/Musicolide/incoming/."));
        processor.importFile(args[1], title.isBlank() ? null : title, customId).whenComplete((track, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cImport failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aImported &f" + track.title() + " &7(ID: &f" + track.id() + "&7)"));
                }));
    }

    private void importUrl(CommandSender sender, String[] args) throws SQLException {
        if (!admin(sender)) return;
        if (args.length < 3 || !args[2].equalsIgnoreCase("--rights-confirmed")) {
            sender.sendMessage(msg("&eUsage: /music import-url <http(s)-url> --rights-confirmed [title] [--id custom_id]"));
            sender.sendMessage(msg("&7Only import audio you own or are authorized to redistribute. YouTube must be allowlisted; Spotify and SoundCloud remain blocked."));
            return;
        }
        if (YtDlpDownloader.isYouTubePlaylistUrl(args[1])) {
            throw new IllegalArgumentException("The admin single-track importer does not accept playlist URLs. Use /play with audio.yt-dlp.allow-playlists enabled.");
        }
        String customId = optionValue(args, 3, "--id");
        String title = joinWithoutOption(args, 3, "--id");
        // Reusing a previously imported URL is a catalogue lookup, not a new download.
        Optional<String> sourceKey = LinkNormalizer.normalize(args[1]);
        boolean alreadyImported = sourceKey.isPresent()
                && repository.resolveAlias(sourceKey.get()).isPresent();
        if (!alreadyImported && !admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        sender.sendMessage(msg("&aStarting an allowlisted URL import. yt-dlp will finish before FFmpeg starts."));
        processor.importUrl(args[1], title.isBlank() ? null : title, customId).whenComplete((track, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cURL import failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aImported &f" + track.title() + " &7(ID: &f" + track.id() + "&7)"));
                }));
    }

    private String optionValue(String[] args, int start, String option) {
        for (int i = Math.max(0, start); i < args.length; i++) {
            if (args[i].equalsIgnoreCase(option)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(option + " requires a value.");
                }
                return args[i + 1];
            }
        }
        return null;
    }

    private String joinWithoutOption(String[] args, int start, String option) {
        List<String> values = new ArrayList<>();
        for (int i = Math.max(0, start); i < args.length; i++) {
            if (args[i].equalsIgnoreCase(option)) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(option + " requires a value.");
                i++;
            } else {
                values.add(args[i]);
            }
        }
        return String.join(" ", values).trim();
    }

    private void downloadLibrary(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cUse /music download in game to request the music library for your own client."));
            return;
        }
        if (playback.isIgnoredBedrockPlayer(player.getUniqueId())) {
            sender.sendMessage(msg("&cBedrock players are excluded by the server configuration."));
            return;
        }
        if (playback.requestLibrary(player)) {
            sender.sendMessage(msg("&aYour Musicolide library resource pack was requested. Cached packs may be reused."));
        } else {
            sender.sendMessage(msg("&cThe music library pack could not be requested yet. It may already be loading, be rate-limited, or have a pack-hosting issue; check the server logs."));
        }
    }

    private void diagnostics(CommandSender sender) {
        if (!admin(sender)) return;
        sender.sendMessage(msg("&5Musicolide native tool diagnostics:"));
        if (processor.toolStatuses().isEmpty()) {
            sender.sendMessage(msg("&eNative tools are still initializing. Run /music diagnostics again shortly."));
            return;
        }
        for (var status : processor.toolStatuses()) {
            if (status.available()) {
                sender.sendMessage(msg("&a" + status.tool().name().toLowerCase(Locale.ROOT)
                        + " &7- &f" + status.version() + " &8(" + status.executable() + ")"));
            } else {
                sender.sendMessage(msg("&c" + status.tool().name().toLowerCase(Locale.ROOT)
                        + " unavailable &7- " + status.detail()));
            }
        }
        boolean remoteEnabled = plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false);
        boolean autoImport = plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false);
        List<String> allowedHosts = plugin.getConfig().getStringList("audio.yt-dlp.allowed-hosts");
        sender.sendMessage(msg("&7URL importer: " + (remoteEnabled ? "&aenabled" : "&cdisabled")
                + " &8| &7/play auto-import: " + (autoImport ? "&aenabled" : "&cdisabled")));
        sender.sendMessage(msg("&7Allowlisted source hosts: &f"
                + (allowedHosts.isEmpty() ? "(none)" : String.join(", ", allowedHosts))));
        sender.sendMessage(msg("&7URL imports are admin-only and require redistribution rights."));
        sender.sendMessage(msg("&7Geyser detection is optional; Bedrock playback requires its separate pack adapter."));
    }

    /**
     * Imports every supported audio file in the incoming directory sequentially.
     * Sequential processing avoids launching an unbounded number of FFmpeg jobs
     * and gives hosts with strict CPU/RAM limits a predictable workload.
     */
    private void importAllTracks(CommandSender sender) {
        Path incoming = plugin.getDataFolder().toPath().resolve("incoming");
        final List<String> files;
        try {
            Files.createDirectories(incoming);
            try (var stream = Files.list(incoming)) {
                files = stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(MusicCommand::isSupportedAudioFile)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
        } catch (IOException ex) {
            sender.sendMessage(msg("&cCould not scan the incoming directory: " + ex.getMessage()));
            return;
        }

        if (files.isEmpty()) {
            sender.sendMessage(msg("&eNo supported audio files were found in plugins/Musicolide/incoming/."));
            return;
        }

        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        sender.sendMessage(msg("&aBulk import started for &f" + files.size()
                + " &aaudio file(s). Files are processed one at a time."));
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (String file : files) {
            chain = chain.thenCompose(ignored -> processor.importFile(file, null).handle((track, error) -> {
                if (error == null) {
                    imported.incrementAndGet();
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                            msg("&aImported &f" + track.title() + " &7(ID: &f" + track.id() + "&7)")));
                } else {
                    failed.incrementAndGet();
                    String reason = rootMessage(error);
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                            msg("&cSkipped &f" + file + "&c: " + reason)));
                }
                return (Void) null;
            }));
        }

        chain.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () ->
                sender.sendMessage(msg("&5Bulk import finished. &aSuccessful: &f" + imported.get()
                        + " &7| &cFailed: &f" + failed.get() + " &7| &5Scanned: &f" + files.size()))));
    }

    private static boolean isSupportedAudioFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 1 || dot == fileName.length() - 1) return false;
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Set.of("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "wma", "aiff", "aif")
                .contains(extension);
    }

    private void listTracks(CommandSender sender, String[] args) throws SQLException {
        String query = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        List<MusicTrack> tracks = query.isBlank() ? repository.allTracks(50) : repository.search(query, 50);
        sender.sendMessage(msg("&5Musicolide tracks (&f" + tracks.size() + "&5):"));
        for (MusicTrack track : tracks) {
            String title = TextUtil.truncate(track.title(), 48);
            String artist = TextUtil.truncate(track.artist(), 28);
            String details = artist.isBlank() ? title : title + " &7by &f" + artist;
            sender.sendMessage(msg("&7- &f" + track.id() + " &8| &5" + details
                    + " &8| &7" + format(track.durationMs())));
        }
    }


    private void play(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(msg("&eUsage: /play <track-id, title, YouTube URL or search query> [@a/@p/@s/player] [--target selector] [--id custom_id] [--circle radius/--square WxL/--region id] [--mode mono/stereo]"));
            return;
        }
        int modifierStart = firstPlayModifierIndex(sender, args);
        String rawInput = String.join(" ", Arrays.copyOfRange(args, 1, modifierStart)).trim();
        String input = isExternal(rawInput) ? rawInput : normalizeSearchQuery(rawInput);
        if (input.isBlank()) {
            sender.sendMessage(msg("&eEnter a track title, YouTube URL or search query."));
            return;
        }
        String[] requestArgs = withPlayModifiers(args[0], input, args, modifierStart);
        PlayRequest parsed = parseRequest(sender, requestArgs);
        PlayRequest request = new PlayRequest(parsed.zone, parsed.all, parsed.listeners, parsed.priority,
                input, parsed.customId, parsed.audioMode);

        if (YtDlpDownloader.isYouTubePlaylistUrl(rawInput)) {
            if (!plugin.getConfig().getBoolean("audio.yt-dlp.allow-playlists", false)) {
                sender.sendMessage(msg("&cYouTube playlist URLs are disabled by the server administrator."));
                sender.sendMessage(msg("&7An administrator can enable audio.yt-dlp.allow-playlists and set audio.yt-dlp.max-playlist-items in config.yml."));
                return;
            }
            if (!plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)
                    || !plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
                sender.sendMessage(msg("&cYouTube playlist importing requires audio.yt-dlp.enabled and audio.yt-dlp.auto-import-on-play."));
                return;
            }
            if (request.customId != null && !request.customId.isBlank()) {
                sender.sendMessage(msg("&cA custom track ID cannot be applied to a multi-track playlist import."));
                return;
            }
            if (!admitHeavyRequest(sender, "import",
                    "audio.processing.import-cooldown-seconds", 10,
                    "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;

            int maximumItems = Math.max(1, Math.min(20,
                    plugin.getConfig().getInt("audio.yt-dlp.max-playlist-items", 10)));
            sender.sendMessage(msg("&eImporting up to &f" + maximumItems
                    + " &eauthorized YouTube playlist tracks. Downloads and audio processing run one at a time."));
            processor.importPlaylistUrl(rawInput).whenComplete((result, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player player && !player.isOnline()) return;
                        if (error != null) {
                            sender.sendMessage(msg("&cYouTube playlist import failed: " + rootMessage(error)));
                            return;
                        }
                        String summary = "&aPlaylist ready: &f" + result.tracks().size()
                                + " &atrack(s) imported or reused.";
                        if (result.failedEntries() > 0) {
                            summary += " &eSkipped: &f" + result.failedEntries() + ".";
                        }
                        if (result.truncated()) {
                            summary += " &7The source playlist exceeds the configured item limit ("
                                    + maximumItems + "); only the first allowed entries were considered.";
                        }
                        sender.sendMessage(msg(summary));
                        startImportedPlaylist(sender, result.tracks(), request);
                    }));
            return;
        }

        Optional<MusicTrack> track = resolvePlayTrack(rawInput, input);
        if (track.isPresent()) {
            offerMatch(sender, track.get(), request, isExternal(rawInput) ? -1.0 : 1.0);
            return;
        }
        if (!isExternal(rawInput)) {
            beginYouTubeSearch(sender, input, request);
            return;
        }
        if (plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)) {
            if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
                sender.sendMessage(msg("&cURL importing is disabled by the server administrator."));
                return;
            }
            if (!admitHeavyRequest(sender, "import",
                    "audio.processing.import-cooldown-seconds", 10,
                    "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
            sender.sendMessage(msg("&eImporting this allowlisted URL. Confirm that your server may distribute this audio."));
            processor.importUrl(rawInput, null, request.customId).whenComplete((imported, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player player && !player.isOnline()) return;
                        if (error != null) sender.sendMessage(msg("&cURL import failed: " + rootMessage(error)));
                        else {
                            sender.sendMessage(msg("&aImported &f" + imported.title() + "&a; starting playback."));
                            start(sender, imported, request);
                        }
                    }));
            return;
        }
        if (isYouTubeUrl(rawInput)) {
            sender.sendMessage(msg("&cYouTube URL auto-import is disabled."));
            sender.sendMessage(msg("&7An administrator can enable audio.yt-dlp.auto-import-on-play and allow youtube.com/youtu.be in config.yml."));
            sender.sendMessage(msg("&7For a one-time authorized import, use /music import-url <url> --rights-confirmed, then /music play <track-id>."));
            return;
        }
        if (!plugin.getConfig().getBoolean("matching.enabled", true)
                || !plugin.getConfig().getBoolean("matching.metadata-lookup", true)) {
            sender.sendMessage(msg("&cNo saved alias exists for this URL. Ask an administrator to add one."));
            return;
        }
        if (!admitHeavyRequest(sender, "metadata",
                "audio.processing.metadata-cooldown-seconds", 5,
                "audio.processing.global-metadata-cooldown-seconds", 1,
                "another external metadata lookup")) return;
        sender.sendMessage(msg("&eLooking up public metadata and searching your local catalogue. No audio is downloaded from the streaming service."));
        metadataResolver.resolve(input).thenAccept(metadata -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (metadata.isEmpty()) {
                sender.sendMessage(msg("&cCould not read public metadata for that URL. Use /music alias add to map it manually."));
                return;
            }
            try {
                List<MusicTrack> candidates = repository.searchForPlayback(metadata.get().title(), 20, 0);
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

    private int firstPlayModifierIndex(CommandSender sender, String[] args) {
        for (int i = 2; i < args.length; i++) {
            String value = args[i];
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("--") || normalized.equals("@a")
                    || normalized.equals("@p") || normalized.equals("@s")
                    || Bukkit.getPlayerExact(value) != null) return i;
        }
        return args.length;
    }

    private String[] withPlayModifiers(String label, String input, String[] original, int modifierStart) {
        List<String> values = new ArrayList<>();
        values.add(label);
        values.add(input);
        for (int i = modifierStart; i < original.length; i++) values.add(original[i]);
        return values.toArray(String[]::new);
    }

    private void searchYouTube(CommandSender sender, String[] args) {
        if (!dj(sender)) return;
        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("&cYouTube result selection is player-specific; run this command in game."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("&eUsage: /music search <song or artist> [target] [zone options]"));
            return;
        }
        int modifierStart = firstPlayModifierIndex(sender, args);
        String query = normalizeSearchQuery(String.join(" ", Arrays.copyOfRange(args, 1, modifierStart)));
        if (query.isBlank()) {
            sender.sendMessage(msg("&eEnter a song or artist to search for."));
            return;
        }
        String[] requestArgs = withPlayModifiers("play", query, args, modifierStart);
        PlayRequest parsed = parseRequest(sender, requestArgs);
        PlayRequest request = new PlayRequest(parsed.zone, parsed.all, parsed.listeners, parsed.priority,
                query, parsed.customId, parsed.audioMode);
        beginYouTubeSearch(sender, query, request);
    }

    private void beginYouTubeSearch(CommandSender sender, String rawQuery, PlayRequest request) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cSearch result selection is player-specific; run /music search in game."));
            return;
        }
        String query = normalizeSearchQuery(rawQuery);
        long now = System.currentTimeMillis();
        pendingSearches.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis > SEARCH_RESULT_TTL_MILLIS);
        PendingSearch search = new PendingSearch(query, request, now);
        pendingSearches.put(player.getUniqueId(), search);
        sender.sendMessage(msg("&eSearching the local catalogue first, then YouTube for: &f" + safeChat(query)));
        loadSearchPage(player, search, 1);
    }

    private void showNextSearchPage(CommandSender sender, String[] args) {
        if (!dj(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cSearch pages are player-specific; run this command in game."));
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Usage: /music page <number>");
        PendingSearch search = pendingSearches.get(player.getUniqueId());
        if (search == null || System.currentTimeMillis() - search.createdAtMillis > SEARCH_RESULT_TTL_MILLIS) {
            pendingSearches.remove(player.getUniqueId());
            throw new IllegalArgumentException("No active search. Run /play <query> or /music search <query> first.");
        }
        final int page;
        try { page = Integer.parseInt(args[1]); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Page must be a number."); }
        int maximum = configuredSearchMaxPages();
        if (page < 2 || page > maximum) throw new IllegalArgumentException("Page must be between 2 and " + maximum + ".");
        if (search.loading) throw new IllegalArgumentException("The current search page is still loading.");
        if (page != search.currentPage + 1) {
            throw new IllegalArgumentException("Open page " + (search.currentPage + 1) + " before requesting page " + page + ".");
        }
        loadSearchPage(player, search, page);
    }

    private void loadSearchPage(Player player, PendingSearch search, int page) {
        int localRequired = Math.max(0, page * SEARCH_PAGE_SIZE - search.localTracks.size());
        if (localRequired > 0 && !search.localExhausted) {
            try {
                List<MusicTrack> nextLocal = repository.searchForPlayback(
                        search.query, localRequired, search.localTracks.size());
                search.localTracks.addAll(nextLocal);
                if (nextLocal.size() < localRequired) search.localExhausted = true;
            } catch (SQLException error) {
                player.sendMessage(msg("&cLocal catalogue search failed: " + error.getMessage()));
                return;
            }
        }
        int remoteRequired = Math.max(0, page * SEARCH_PAGE_SIZE - search.localTracks.size());
        if (remoteRequired == 0 || search.remoteResults.size() >= remoteRequired
                || search.remoteExhausted
                || !plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            renderSearchPage(player, search, page, null);
            return;
        }
        if (!admitHeavyRequest(player, "search",
                "audio.processing.search-cooldown-seconds", 3,
                "audio.processing.global-search-cooldown-seconds", 1, "another remote search")) {
            // Still show any local catalogue matches collected above; only the
            // remote metadata lookup is postponed by this cooldown.
            renderSearchPage(player, search, page, null);
            return;
        }
        search.loading = true;
        processor.searchYouTube(search.query, remoteRequired).whenComplete((results, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || pendingSearches.get(player.getUniqueId()) != search) return;
                    search.loading = false;
                    if (error == null && results != null) {
                        search.remoteResults = List.copyOf(results);
                        if (results.size() < remoteRequired) search.remoteExhausted = true;
                    }
                    renderSearchPage(player, search, page, error);
                }));
    }

    private void renderSearchPage(Player player, PendingSearch search, int page, Throwable searchError) {
        List<SearchEntry> entries = new ArrayList<>(SEARCH_PAGE_SIZE);
        int start = (page - 1) * SEARCH_PAGE_SIZE;
        for (int index = start; index < start + SEARCH_PAGE_SIZE; index++) {
            if (index < search.localTracks.size()) entries.add(SearchEntry.local(search.localTracks.get(index)));
            else {
                int remoteIndex = index - search.localTracks.size();
                if (remoteIndex >= search.remoteResults.size()) break;
                entries.add(SearchEntry.remote(search.remoteResults.get(remoteIndex)));
            }
        }
        search.currentPage = page;
        search.pageResults = List.copyOf(entries);
        player.sendMessage(msg("&5Search results for &f" + safeChat(search.query) + " &7(page " + page
                + "/" + configuredSearchMaxPages() + "):"));
        for (int i = 0; i < entries.size(); i++) {
            SearchEntry entry = entries.get(i);
            if (entry.localTrack != null) {
                MusicTrack track = entry.localTrack;
                String artist = safeChat(track.artist());
                player.sendMessage(msg("&e" + (i + 1) + ". &a[Local] &f" + TextUtil.truncate(safeChat(track.title()), 58)
                        + (artist.isBlank() ? "" : " &7- " + TextUtil.truncate(artist, 24))
                        + " &8(ID: " + safeChat(track.id()) + ") &7[" + format(track.durationMs()) + "]"));
            } else {
                YtDlpDownloader.SearchResult result = entry.remoteResult;
                String duration = result.durationSeconds() > 0 ? format(result.durationSeconds() * 1000L) : "duration ?";
                player.sendMessage(msg("&e" + (i + 1) + ". &5[YouTube] &f" + TextUtil.truncate(safeChat(result.title()), 58)
                        + (result.uploader().isBlank() ? "" : " &7- " + TextUtil.truncate(safeChat(result.uploader()), 24))
                        + " &8[" + duration + "]"));
                player.sendMessage(msg("&8" + result.url()));
            }
        }
        if (entries.isEmpty()) {
            if (searchError != null) player.sendMessage(msg("&cYouTube search failed: " + rootMessage(searchError)));
            else player.sendMessage(msg("&eNo local or YouTube results were found for that query."));
            return;
        }
        player.sendMessage(msg("&aChoose one with &f/music select <number>&a. Results expire in 5 minutes."));
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)
                && entries.stream().anyMatch(entry -> entry.remoteResult != null)) {
            player.sendMessage(msg("&7YouTube selection imports audio; an admin must enable audio.yt-dlp.auto-import-on-play first."));
        }
        boolean moreLocal = !search.localExhausted;
        boolean moreRemote = plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)
                && !search.remoteExhausted;
        if (page < configuredSearchMaxPages() && (moreLocal || moreRemote)) {
            player.sendMessage(msg("&7Next page: &f/music page " + (page + 1) + " &8(loaded only when requested)."));
        }
        if (searchError != null) player.sendMessage(msg("&eYouTube results could not be refreshed; local results remain available."));
    }

    private int configuredSearchMaxPages() {
        return Math.max(1, Math.min(8, plugin.getConfig().getInt("audio.yt-dlp.search.max-pages", 4)));
    }

    private static String normalizeSearchQuery(String value) {
        return value == null ? "" : value.trim().replace('_', ' ').replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ");
    }

    private void selectYouTubeResult(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only a player can select their own search result.");
        if (args.length != 2) throw new IllegalArgumentException("Usage: /music select <number>");
        PendingSearch search = pendingSearches.get(player.getUniqueId());
        if (search == null || System.currentTimeMillis() - search.createdAtMillis > SEARCH_RESULT_TTL_MILLIS) {
            pendingSearches.remove(player.getUniqueId());
            throw new IllegalArgumentException("No current search results. Run /play <query> first.");
        }
        if (search.loading || search.selecting) throw new IllegalArgumentException("Your current search action is still processing.");
        int selection;
        try { selection = Integer.parseInt(args[1]); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Choose a result number shown on the current page."); }
        if (selection < 1 || selection > search.pageResults.size()) {
            throw new IllegalArgumentException("Choose a result between 1 and " + search.pageResults.size() + " from the current page.");
        }
        SearchEntry selected = search.pageResults.get(selection - 1);
        if (selected.localTrack != null) {
            pendingSearches.remove(player.getUniqueId(), search);
            start(sender, selected.localTrack, search.request);
            return;
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) throw new IllegalArgumentException("URL importing is disabled by the server administrator.");
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)) {
            sender.sendMessage(msg("&cSelecting a YouTube result imports audio, but audio.yt-dlp.auto-import-on-play is disabled."));
            sender.sendMessage(msg("&7Ask an administrator to enable it after confirming redistribution rights."));
            return;
        }
        YtDlpDownloader.SearchResult remote = selected.remoteResult;
        Optional<String> aliasKey = LinkNormalizer.normalize(remote.url());
        if (aliasKey.isPresent()) {
            Optional<MusicTrack> existing = repository.resolveAlias(aliasKey.get());
            if (existing.isPresent()) {
                pendingSearches.remove(player.getUniqueId(), search);
                sender.sendMessage(msg("&aAlready imported: &f" + existing.get().title() + "&a. Starting the saved copy without another download."));
                start(sender, existing.get(), search.request);
                return;
            }
        }
        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        search.selecting = true;
        sender.sendMessage(msg("&eImporting selected result: &f" + safeChat(remote.title())));
        processor.importUrl(remote.url(), null, search.request.customId).whenComplete((imported, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    search.selecting = false;
                    if (!player.isOnline()) return;
                    if (error != null) sender.sendMessage(msg("&cSelected result could not be imported: " + rootMessage(error)));
                    else {
                        pendingSearches.remove(player.getUniqueId(), search);
                        sender.sendMessage(msg("&aImported &f" + imported.title() + "&a; starting playback."));
                        start(sender, imported, search.request);
                    }
                }));
    }

    private String safeChat(String value) {
        if (value == null) return "";
        return value.replace('§', ' ').replace("&", "＆")
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private void offerMatch(CommandSender sender, MusicTrack track, PlayRequest request, double confidence) {
        if (confidence < 0) {
            sender.sendMessage(msg("&aThis URL was already imported as &f" + track.title()
                    + "&a. Playing the saved copy without downloading it again."));
            start(sender, track, request);
            return;
        }
        if (sender instanceof Player player && plugin.getConfig().getBoolean(
                "matching.require-confirmation-for-alias", true) && confidence < 1.0) {
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

    private Optional<MusicTrack> resolvePlayTrack(String rawToken, String normalizedToken) throws SQLException {
        if (isExternal(rawToken)) {
            Optional<String> normalized = LinkNormalizer.normalize(rawToken);
            return normalized.isEmpty() ? Optional.empty() : repository.resolveAlias(normalized.get());
        }
        Optional<MusicTrack> exactId = repository.getTrack(rawToken);
        return exactId.isPresent() ? exactId : repository.findExactTitle(normalizedToken);
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

    private boolean isExternal(String token) {
        if (token == null) return false;
        try {
            String scheme = URI.create(token).getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isYouTubeUrl(String token) {
        if (token == null) return false;
        try {
            String host = URI.create(token).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private PlayRequest parseRequest(CommandSender sender, String[] args) {
        String selector = null;
        double radius = plugin.getConfig().getDouble("playback.range.circle.radius", 60);
        double width = plugin.getConfig().getDouble("playback.range.square.width", 50);
        double length = plugin.getConfig().getDouble("playback.range.square.length", 50);
        String region = null;
        boolean circle = false;
        boolean square = false;
        boolean fadeEnabled = false;
        boolean fadeSpecified = false;
        double fade = 0;
        String customId = null;
        String audioMode = "stereo";

        for (int i = 2; i < args.length; i++) {
            String option = args[i].toLowerCase(Locale.ROOT);
            switch (option) {
                case "--all", "--player" -> throw new IllegalArgumentException(
                        "Use @a, @p, @s, or a player name instead of --all/--player.");
                case "--target" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--target requires @a, @p, @s, or a player name.");
                    if (selector != null) throw new IllegalArgumentException("Specify only one target.");
                    selector = args[i];
                }
                case "--id" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--id requires a custom track ID.");
                    customId = args[i];
                }
                case "--mode" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--mode requires mono or stereo.");
                    audioMode = args[i].toLowerCase(Locale.ROOT);
                    if (!audioMode.equals("mono") && !audioMode.equals("stereo")) throw new IllegalArgumentException("Audio mode must be mono or stereo.");
                }
                case "mono", "stereo" -> audioMode = option;
                case "--circle" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--circle requires a radius.");
                    radius = Double.parseDouble(args[i]);
                    circle = true;
                    square = false;
                }
                case "--square" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--square requires WIDTHxLENGTH.");
                    String[] dims = args[i].toLowerCase(Locale.ROOT).split("x", 2);
                    if (dims.length != 2) throw new IllegalArgumentException("Square dimensions must look like 50x50.");
                    width = Double.parseDouble(dims[0]);
                    length = Double.parseDouble(dims[1]);
                    square = true;
                    circle = false;
                }
                case "--fade" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--fade requires a distance.");
                    fadeSpecified = true;
                    fade = Double.parseDouble(args[i]);
                    fadeEnabled = fade > 0;
                    if (fadeEnabled && !circle && !square && region == null) circle = true;
                }
                case "--region" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--region requires a region ID.");
                    region = args[i];
                    circle = false;
                    square = false;
                }
                default -> {
                    if (option.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown play option: " + args[i]);
                    }
                    if (selector != null) {
                        throw new IllegalArgumentException("Specify only one target: @a, @p, @s, or one player name.");
                    }
                    selector = args[i];
                }
            }
        }

        // Mono has a default outer attenuation band for geometries where distance
        // from the zone edge is well-defined. An explicit --fade (including 0)
        // always overrides this default. WorldGuard polygon regions do not expose
        // a reliable edge-distance here, so their boundary remains hard.
        if (audioMode.equals("mono") && (circle || square) && !fadeSpecified) {
            fade = plugin.getConfig().getDouble("playback.mono.fade-distance", 10.0);
            fadeEnabled = fade > 0;
        }
        if (region != null && fadeEnabled) {
            throw new IllegalArgumentException("Distance fade is supported for circle and square zones, not WorldGuard regions.");
        }

        boolean spatialOption = circle || square || region != null || fadeEnabled;
        if (selector == null) {
            selector = PlayTargetDefaults.defaultSelector(sender instanceof Player, spatialOption);
        }

        boolean all = selector.equalsIgnoreCase("@a");
        List<Player> targets = new ArrayList<>();
        if (!all) {
            Player target;
            if (selector.equalsIgnoreCase("@s")) {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException("@s requires the command sender to be a player.");
                }
                target = player;
            } else if (selector.equalsIgnoreCase("@p")) {
                if (!(sender instanceof Player source)) {
                    throw new IllegalArgumentException("@p requires a player location; use @a or a player name from console.");
                }
                target = Bukkit.getOnlinePlayers().stream()
                        .filter(candidate -> candidate.getWorld().equals(source.getWorld()))
                        .min(Comparator.comparingDouble(candidate ->
                                candidate.getLocation().distanceSquared(source.getLocation())))
                        .orElseThrow(() -> new IllegalArgumentException("No online players are available for @p."));
            } else {
                target = Bukkit.getPlayerExact(selector);
                if (target == null) {
                    throw new IllegalArgumentException("Player is not online: " + selector);
                }
            }
            targets.add(target);
        }

        boolean requiresAdmin = spatialOption || all
                || (sender instanceof Player source && (targets.isEmpty()
                || !targets.get(0).getUniqueId().equals(source.getUniqueId())))
                || !(sender instanceof Player);
        if (requiresAdmin && !sender.hasPermission(plugin.getConfig().getString(
                "permissions.admin", "musicolide.admin"))) {
            throw new IllegalArgumentException("Targeting other players or using a spatial zone requires OP or the Musicolide admin permission.");
        }

        boolean limited = plugin.getConfig().getString("playback.range.vertical.mode", "unlimited")
                .equalsIgnoreCase("limited");
        double vertical = plugin.getConfig().getDouble("playback.range.vertical.max-distance", 32);
        if (!Double.isFinite(radius) || !Double.isFinite(width) || !Double.isFinite(length)
                || !Double.isFinite(fade) || radius < 0 || width <= 0 || length <= 0 || fade < 0
                || limited && (!Double.isFinite(vertical) || vertical < 0)) {
            throw new IllegalArgumentException(
                    "Zone dimensions and enabled vertical limits must be finite; radius/fade cannot be negative and square dimensions must be positive.");
        }
        if (region != null && !playback.canUseWorldGuardRegions()) {
            throw new IllegalArgumentException(
                    "--region requires WorldGuard installed and worldguard.enabled: true with a compatible API.");
        }

        MusicZone zone = MusicZone.global();
        if (spatialOption) {
            if (!(sender instanceof Player center)) {
                throw new IllegalArgumentException("Spatial range options require an in-game player as the command sender.");
            }
            if (region != null) {
                zone = MusicZone.region(center.getWorld().getUID(), region);
            } else if (square) {
                double rotation = plugin.getConfig().getString("playback.range.square.rotation", "world")
                        .equalsIgnoreCase("player-facing")
                        ? Math.toRadians(center.getLocation().getYaw()) : 0;
                zone = MusicZone.square(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                        width, length, rotation, limited, vertical, fadeEnabled, fade);
            } else {
                zone = MusicZone.circle(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                        radius, limited, vertical, fadeEnabled, fade);
            }
        }

        return new PlayRequest(zone, all, all ? List.of()
                : List.of(targets.get(0).getUniqueId()), 0, null, customId, audioMode);
    }

    private void start(CommandSender sender, MusicTrack track, PlayRequest request) {
        boolean mono = "mono".equalsIgnoreCase(request.audioMode);
        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        Optional<Boolean> activeMode = playback.activeSessionMono(owner, sender.getName(),
                request.zone, request.all, request.listeners);
        if (activeMode.isPresent() && activeMode.get() != mono) {
            sender.sendMessage(msg("&cAn active session for this target and zone is already using "
                    + (activeMode.get() ? "mono" : "stereo") + ". Stop it before switching audio modes."));
            return;
        }
        if (mono) {
            sender.sendMessage(msg("&ePreparing mono audio for &f" + track.title() + "&e."));
            processor.ensureMonoVariant(track).whenComplete((readyTrack, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player player && !player.isOnline()) return;
                        if (error != null) {
                            sender.sendMessage(msg("&cMono audio could not be prepared: " + rootMessage(error)));
                            return;
                        }
                        startPrepared(sender, readyTrack, request, true);
                    }));
            return;
        }
        startPrepared(sender, track, request, false);
    }

    private void startImportedPlaylist(CommandSender sender, List<MusicTrack> tracks, PlayRequest request) {
        if (tracks == null || tracks.isEmpty()
                || sender instanceof Player player && !player.isOnline()) return;
        boolean mono = "mono".equalsIgnoreCase(request.audioMode);
        if (!mono) {
            for (MusicTrack track : tracks) startPrepared(sender, track, request, false);
            return;
        }

        List<MusicTrack> prepared = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MusicTrack track : tracks) {
            chain = chain.thenCompose(ignored ->
                    processor.ensureMonoVariant(track).thenAccept(prepared::add));
        }
        chain.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            if (error != null) {
                sender.sendMessage(msg("&cCould not prepare the mono playlist: " + rootMessage(error)));
                return;
            }
            for (MusicTrack track : prepared) startPrepared(sender, track, request, true);
        }));
    }

    private void startPrepared(CommandSender sender, MusicTrack track, PlayRequest request, boolean mono) {
        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        PlaybackEngine.PlayDisposition disposition = playback.playOrQueue(owner, sender.getName(),
                request.zone, request.all, request.listeners, track, request.priority, mono);
        String mode = mono ? " &7(mono)" : "";
        switch (disposition) {
            case STARTED -> sender.sendMessage(msg("&aStarted &f" + track.title() + mode + "."));
            case QUEUED -> sender.sendMessage(msg("&aAdded &f" + track.title() + mode + " &ato your current playback queue."));
            case QUEUE_FULL -> sender.sendMessage(msg("&cYour playback queue is full. The new track was not added."));
            case MODE_MISMATCH -> sender.sendMessage(msg("&cAn active session for this target and zone uses another audio mode. Stop it before switching modes."));
        }
    }

    private void control(CommandSender sender, String action) {
        if (!dj(sender)) return;
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        if (actor == null) { sender.sendMessage(msg("&cConsole control is not supported; use an in-game DJ.")); return; }
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        if (action.equals("resume")) {
            Optional<PlaybackSession> resumableSession = playback.findControllableSession(actor, admin);
            boolean buildsAudioTail = "resumable".equalsIgnoreCase(
                    plugin.getConfig().getString("audio.playback-mode", "resumable"))
                    && resumableSession.filter(session -> session.paused()
                    && session.current() != null && session.current().isStreamed()).isPresent();
            if (buildsAudioTail && !admitHeavyRequest(sender, "transform",
                    "audio.processing.transform-cooldown-seconds", 3,
                    "audio.processing.global-transform-cooldown-seconds", 1,
                    "another audio preparation")) return;
            PlaybackEngine.ResumeStart result = playback.resume(actor, admin, notification -> {
                if (sender instanceof Player player && player.isOnline()) sender.sendMessage(msg(notification));
            });
            switch (result) {
                case NOT_FOUND -> sender.sendMessage(msg("&cNo controllable session was found."));
                case RESUMED -> sender.sendMessage(msg("&aPlayback resumed."));
                case PREPARING -> sender.sendMessage(msg(
                        "&ePreparing the remaining audio. Playback will continue after its temporary pack is ready."));
                case BEDROCK_FALLBACK -> sender.sendMessage(msg(
                        "&eA Bedrock player is in this session, so playback restarted from the beginning for compatibility."));
                case ALREADY_PREPARING -> sender.sendMessage(msg("&eThe remaining audio is already being prepared."));
            }
            return;
        }
        if (!playback.control(actor, admin, action)) {
            sender.sendMessage(msg("&cNo controllable session was found."));
        } else {
            sender.sendMessage(msg("&a" + action.substring(0, 1).toUpperCase(Locale.ROOT) + action.substring(1) + " applied."));
        }
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
            String title = track == null ? "Unknown" : TextUtil.truncate(track.title(), 48);
            String artist = track == null ? "" : TextUtil.truncate(track.artist(), 28);
            sender.sendMessage(msg("&5" + title + (artist.isBlank() ? "" : " &7by &f" + artist)
                    + " &7| " + session.ownerName()
                    + " &8| &7" + format(session.elapsedMs()) + " / " + format(track == null ? 0 : track.durationMs())
                    + (session.paused() ? " &e(PAUSED)" : "")));
        }
    }

    private void volume(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        if (args.length < 2) { sender.sendMessage(msg("&eYour volume: " + playback.getPlayerVolume(player) + "%")); return; }
        int value = Integer.parseInt(args[1]);
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be between 0 and 100.");
        if ("precise".equalsIgnoreCase(plugin.getConfig().getString(
                "playback.volume-change-mode", "next-track"))
                && !admitHeavyRequest(sender, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1,
                "another audio preparation")) return;
        PlaybackEngine.VolumeChangeResult result = playback.setPlayerVolume(player, value, notification -> {
            if (player.isOnline()) sender.sendMessage(msg(notification));
        });
        sender.sendMessage(msg("&aYour music volume is now " + value + "%."));
        if (result == PlaybackEngine.VolumeChangeResult.RESYNCHRONIZING) {
            sender.sendMessage(msg("&eYour current track is being reloaded at the new volume."));
        } else if (result == PlaybackEngine.VolumeChangeResult.NEXT_TRACK) {
            sender.sendMessage(msg("&eThis change will take effect with the next track."));
        } else if (result == PlaybackEngine.VolumeChangeResult.RESTARTED) {
            sender.sendMessage(msg("&eThe current segment restarted at your new volume."));
        }
    }

    private void speed(CommandSender sender, String[] args) {
        if (!dj(sender)) return;
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        if (args.length < 2) {
            PlaybackSession session = playback.findControllableSession(actor, admin)
                    .orElseThrow(() -> new IllegalArgumentException("No controllable playback session was found."));
            Double pending = session.pendingSpeed();
            String status = "&5Current playback speed: &f"
                    + String.format(Locale.ROOT, "%.2f", session.speed()) + "x";
            if (pending != null) status += " &7| Next track: &f" + String.format(Locale.ROOT, "%.2f", pending) + "x";
            sender.sendMessage(msg(status));
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Usage: /music speed <0.5-2.0>");
        double value = Double.parseDouble(args[1]);
        if (!Double.isFinite(value) || value < 0.5 || value > 2.0) {
            throw new IllegalArgumentException("Playback speed must be between 0.5x and 2.0x.");
        }
        PlaybackEngine.SpeedChangeResult result = playback.setPlaybackSpeed(actor, admin, value);
        switch (result) {
            case NO_SESSION -> throw new IllegalArgumentException("No controllable playback session was found.");
            case APPLIED_NOW -> sender.sendMessage(msg("&aPlayback speed set to &f"
                    + String.format(Locale.ROOT, "%.2f", value) + "x&a."));
            case NEXT_TRACK -> sender.sendMessage(msg("&aPlayback speed set to &f"
                    + String.format(Locale.ROOT, "%.2f", value)
                    + "x&a. It will take effect on the next track."));
        }
    }

    private void mute(CommandSender sender, boolean muted) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        if (!muted && "precise".equalsIgnoreCase(plugin.getConfig().getString(
                "playback.volume-change-mode", "next-track"))
                && !admitHeavyRequest(sender, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1,
                "another audio preparation")) return;
        PlaybackEngine.VolumeChangeResult result = playback.setPlayerMuted(player, muted, notification -> {
            if (player.isOnline()) sender.sendMessage(msg(notification));
        });
        sender.sendMessage(msg(muted ? "&eMusic muted." : "&aMusic unmuted."));
        if (!muted && result == PlaybackEngine.VolumeChangeResult.RESYNCHRONIZING) {
            sender.sendMessage(msg("&eYour current track is being reloaded at your saved volume."));
        } else if (!muted && result == PlaybackEngine.VolumeChangeResult.NEXT_TRACK) {
            sender.sendMessage(msg("&eYour saved volume will apply from the next track."));
        }
    }

    private void sync(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("Players only. Use /music sync from the player who needs synchronization.");
            }
            if (!player.hasPermission(plugin.getConfig().getString("permissions.use", "musicolide.use"))) {
                sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
                return;
            }

            if (!admitHeavyRequest(sender, "transform",
                    "audio.processing.transform-cooldown-seconds", 3,
                    "audio.processing.global-transform-cooldown-seconds", 1, "another audio preparation")) return;
            PlaybackEngine.SyncStart result = playback.synchronizePlayer(player, notification -> {
                if (player.isOnline()) sender.sendMessage(msg(notification));
            });
            switch (result) {
                case PREPARING -> sender.sendMessage(msg(
                        "&ePreparing a synchronized audio tail. Your audio will restart when its temporary pack is ready."));
                case NO_SESSION -> throw new IllegalArgumentException(
                        "No active music session is audible to you right now.");
                case NOT_PLAYING -> throw new IllegalArgumentException(
                        "The active session has not started its playback clock yet.");
                case PAUSED -> throw new IllegalArgumentException(
                        "The active session is paused or preparing a resume.");
                case PACK_NOT_READY -> throw new IllegalArgumentException(
                        "Your Musicolide library is not loaded yet. Use /music download, then run /music sync.");
                case PACK_DECLINED -> throw new IllegalArgumentException(
                        "Your Musicolide library was declined. Accept or retry it with /music download before syncing.");
                case BEDROCK_UNSUPPORTED -> throw new IllegalArgumentException(
                        "Manual timestamp synchronization currently requires a Java Edition client; Geyser cannot receive the temporary sync pack mid-session.");
                case TRACK_NEAR_END -> throw new IllegalArgumentException(
                        "The current track is too close to its end for the configured synchronization offset. An administrator can lower it with /music sync <seconds>.");
                case ALREADY_PREPARING -> sender.sendMessage(msg(
                        "&eA synchronization request is already being prepared for you."));
            }
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(msg("&eUsage: /music sync [seconds]"));
            return;
        }
        if (!admin(sender)) return;

        double seconds = Double.parseDouble(args[1]);
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 120) {
            throw new IllegalArgumentException("Synchronization offset must be between 0 and 120 seconds.");
        }
        plugin.getConfig().set("playback.synchronization.late-join.delay-seconds", seconds);
        plugin.saveConfig();
        sender.sendMessage(msg("&aShared synchronization offset set to &f"
                + String.format(Locale.ROOT, "%.2f", seconds) + " &aseconds."));
    }

    private void seek(CommandSender sender, String[] args) {
        if (!dj(sender)) return;
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music seek <seconds>")); return; }
        double seconds = Double.parseDouble(args[1]);
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > Long.MAX_VALUE / 1000.0)
            throw new IllegalArgumentException("Seek position must be finite and non-negative.");
        long targetMs = (long) (seconds * 1000.0);
        if (!admitHeavyRequest(sender, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1, "another audio preparation")) return;
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        PlaybackEngine.SeekStart result = playback.seek(player.getUniqueId(), admin, targetMs, notification -> {
            if (player.isOnline()) sender.sendMessage(msg(notification));
        });
        switch (result) {
            case NOT_FOUND -> throw new IllegalArgumentException("No controllable session found.");
            case SEEKED -> sender.sendMessage(msg("&aSeeked to " + format(targetMs) + "."));
            case PREPARING -> sender.sendMessage(msg(
                    "&ePreparing audio from " + format(targetMs) + ". Playback will continue after its temporary pack loads."));
            case BEDROCK_UNSUPPORTED -> throw new IllegalArgumentException(
                    "Timestamp-based seeking is not available while a Bedrock player is in this session. Use segmented mode for Bedrock-compatible seeking.");
            case UNSUPPORTED -> throw new IllegalArgumentException(
                    "Streamed seeking requires audio.playback-mode: resumable. The continuous mode can only restart from the beginning.");
            case ALREADY_PREPARING -> sender.sendMessage(msg("&eAn audio seek or resume is already being prepared."));
        }
    }

    private void playlist(CommandSender sender, String[] args) throws SQLException {
        if (!plugin.getConfig().getBoolean("playlists.enabled", true)) throw new IllegalArgumentException("Playlists are disabled.");
        if (args.length < 2) throw new IllegalArgumentException("Usage: /music playlist <create/add/list/play> ...");
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
                int maximumTracks = Math.max(1, plugin.getConfig().getInt("limits.max-playlist-tracks", 500));
                repository.addPlaylistTrack(args[2], track.id(), maximumTracks);
                sender.sendMessage(msg("&aAdded " + track.title() + " to " + args[2]));
            }
            case "list" -> sender.sendMessage(msg("&5Playlists: &f" + String.join(", ", repository.listPlaylists())));
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

    public Optional<String> discordThumbnailUrl(String trackId) {
        try {
            return repository.getYouTubeVideoId(trackId)
                    .filter(id -> id.matches("[A-Za-z0-9_-]{6,32}"))
                    .map(id -> "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
        } catch (SQLException error) {
            plugin.getLogger().log(java.util.logging.Level.FINE,
                    "Could not resolve the YouTube thumbnail for Discord /info.", error);
            return Optional.empty();
        }
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
        sender.sendMessage(msg("&5Musicolide commands:"));
        sender.sendMessage(msg("&f/link &7- create a five-minute one-use Discord pairing code"));
        sender.sendMessage(msg("&f/unlink &7- unlink your Discord account"));
        sender.sendMessage(msg("&f/music import <file> [title] &7- import one authorized audio file"));
        sender.sendMessage(msg("&f/music import all &7- import every supported file in incoming/"));
        sender.sendMessage(msg("&f/music import-url <url> --rights-confirmed [title] &7- import an allowlisted source"));
        sender.sendMessage(msg("&f/music diagnostics &7- inspect native audio tools (admin)"));
        sender.sendMessage(msg("&f/music download &7- retry or request your music library"));
        sender.sendMessage(msg("&f/music list [search] &7- list tracks"));
        sender.sendMessage(msg("&f/music search <query> &7- search local tracks first, then YouTube metadata"));
        sender.sendMessage(msg("&f/music select <number> &7- play a local result or import a YouTube result"));
        sender.sendMessage(msg("&f/music page <2-" + configuredSearchMaxPages() + "> &7- load the next six results only when requested"));
        sender.sendMessage(msg("&f/music import <file> [title] [--id id] &7- set a stable custom track ID/name"));
        sender.sendMessage(msg("&f/music import-url <url> --rights-confirmed [title] [--id id] &7- import with a custom ID/name"));
        sender.sendMessage(msg("&f/play <track, query or URL> [@a/@p/@s/player] [--target selector] [--id custom_id] [--circle r/--square WxL/--region id] [--mode mono/stereo] [--fade n]"));
        sender.sendMessage(msg("&7Stereo is the default. Mono uses one-channel audio; circle/square mono zones fade over 10 blocks by default (set playback.mono.fade-distance or override with --fade)."));
        sender.sendMessage(msg("&7Spatial play/search defaults to @a inside the zone; without a zone it defaults to @s. WorldGuard regions do not support distance fade."));
        sender.sendMessage(msg("&f/music search <query> [target] [zone options] &7- search with the same zone/target options"));
        sender.sendMessage(msg("&f/music pause/resume/skip/stop/queue <track>"));
        sender.sendMessage(msg("&f/music playlist create/add/list/play ..."));
        sender.sendMessage(msg("&f/music volume <0-100>/speed <0.5-2.0>/mute/unmute/seek <seconds>/nowplaying"));
        sender.sendMessage(msg("&f/music sync &7- re-synchronize your own audio with the active session"));
        sender.sendMessage(msg("&f/music sync <seconds> &7- set the shared sync offset (admin)"));
        sender.sendMessage(msg("&f/music alias add <url> <track-id> &7- map a URL to local audio"));
        sender.sendMessage(msg("&f/music priority <number> &7- admin session priority"));
    }

    private boolean admitHeavyRequest(CommandSender sender, String action,
                                      String actorCooldownPath, long defaultActorCooldown,
                                      String globalCooldownPath, long defaultGlobalCooldown,
                                      String description) {
        String actor = sender instanceof Player player
                ? player.getUniqueId().toString() : "console:" + sender.getName();
        long actorCooldown = Math.max(0L, plugin.getConfig().getLong(actorCooldownPath, defaultActorCooldown));
        long globalCooldown = Math.max(0L, plugin.getConfig().getLong(globalCooldownPath, defaultGlobalCooldown));
        long remaining = requestCooldowns.tryAcquire(action, actor, actorCooldown, globalCooldown);
        if (remaining > 0) {
            sender.sendMessage(msg("&cPlease wait &f" + remaining
                    + " second(s) &cbefore requesting " + description + " again."));
            return false;
        }
        return true;
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
        if (sender instanceof Player player
                && playback.isIgnoredBedrockPlayer(player.getUniqueId())) return List.of();
        if (args.length == 1) return partial(List.of("help", "link", "unlink", "import", "import-url", "diagnostics", "download", "list", "search", "select", "page", "play", "confirm", "pause", "resume",
                "skip", "stop", "queue", "nowplaying", "progress", "volume", "mute", "unmute", "seek",
                "playlist", "alias", "priority", "sync", "speed", "reload"), args[0]);
        if (args.length >= 4 && args[0].equalsIgnoreCase("play")
                && args[args.length - 2].equalsIgnoreCase("--target")) {
            List<String> targets = new ArrayList<>(List.of("@s", "@p", "@a"));
            Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
            return partial(targets, args[args.length - 1]);
        }
        if (args.length >= 4 && args[0].equalsIgnoreCase("play")
                && args[args.length - 2].equalsIgnoreCase("--mode")) {
            return partial(List.of("stereo", "mono"), args[args.length - 1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("page")) {
            return partial(java.util.stream.IntStream.rangeClosed(2, configuredSearchMaxPages())
                    .mapToObj(Integer::toString).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("select") && sender instanceof Player player) {
            PendingSearch search = pendingSearches.get(player.getUniqueId());
            if (search != null && System.currentTimeMillis() - search.createdAtMillis <= SEARCH_RESULT_TTL_MILLIS) {
                List<String> numbers = new ArrayList<>();
                for (int i = 1; i <= search.pageResults.size(); i++) numbers.add(Integer.toString(i));
                return partial(numbers, args[1]);
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed"))
            return partial(List.of("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("volume"))
            return partial(List.of("0", "25", "50", "75", "100"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("import"))
            return partial(List.of("all"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("playlist"))
            return partial(List.of("create", "add", "list", "play"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("play")) {
            List<String> targets = new ArrayList<>();
            targets.add("@a");
            if (sender instanceof Player) {
                targets.add("@p");
                targets.add("@s");
            }
            Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(targets::add);
            targets.addAll(List.of("--target", "--id", "--circle", "--square", "--region", "--fade", "--mode"));
            return partial(targets, args[2]);
        }
        if (args.length >= 4 && args[0].equalsIgnoreCase("play")
                && args[args.length - 1].startsWith("--")) {
            return partial(List.of("--target", "--id", "--circle", "--square", "--region", "--fade", "--mode"), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> partial(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private record PlayRequest(MusicZone zone, boolean all, Collection<UUID> listeners, int priority,
                               String inputUrl, String customId, String audioMode) {}
    private record PendingPlay(MusicTrack track, PlayRequest request) {}
    private static final int SEARCH_PAGE_SIZE = 6;
    private static final class PendingSearch {
        private final String query;
        private final List<MusicTrack> localTracks = new ArrayList<>();
        private final PlayRequest request;
        private final long createdAtMillis;
        private List<YtDlpDownloader.SearchResult> remoteResults = List.of();
        private List<SearchEntry> pageResults = List.of();
        private int currentPage;
        private boolean loading;
        private boolean selecting;
        private boolean remoteExhausted;
        private boolean localExhausted;

        private PendingSearch(String query, PlayRequest request, long createdAtMillis) {
            this.query = query;
            this.request = request;
            this.createdAtMillis = createdAtMillis;
        }
    }
    private record SearchEntry(MusicTrack localTrack, YtDlpDownloader.SearchResult remoteResult) {
        private static SearchEntry local(MusicTrack track) { return new SearchEntry(track, null); }
        private static SearchEntry remote(YtDlpDownloader.SearchResult result) { return new SearchEntry(null, result); }
    }

}
