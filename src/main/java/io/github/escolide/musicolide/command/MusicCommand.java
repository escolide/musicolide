package io.github.escolide.musicolide.command;

import io.github.escolide.musicolide.audio.AudioProcessor;
import io.github.escolide.musicolide.audio.YtDlpDownloader;
import io.github.escolide.musicolide.gui.MusicGui;
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
import org.bukkit.scheduler.BukkitTask;

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
import java.util.logging.Level;

public final class MusicCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final AudioProcessor processor;
    private final PlaybackEngine playback;
    private final DiscordAccountLinkManager accountLinks;
    private final MusicGui gui;
    private final ExternalMetadataResolver metadataResolver = new ExternalMetadataResolver();
    private final Map<UUID, PendingPlay> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSearch> pendingSearches = new ConcurrentHashMap<>();
    private static final long SEARCH_RESULT_TTL_MILLIS = 5 * 60 * 1000L;
    private final RequestCooldowns requestCooldowns = new RequestCooldowns();
    private final Set<PlaylistRunner> playlistRunners = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedDiscordAutocomplete> discordAutocompleteCache = new ConcurrentHashMap<>();
    private final Set<String> discordAutocompleteInFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingDiscordAutocomplete> discordAutocompletePending = new ConcurrentHashMap<>();
    private final Map<String, String> discordAutocompleteLatestQuery = new ConcurrentHashMap<>();


    public MusicCommand(JavaPlugin plugin, TrackRepository repository, AudioProcessor processor, PlaybackEngine playback,
                        DiscordAccountLinkManager accountLinks) {
        this.plugin = plugin;
        this.repository = repository;
        this.processor = processor;
        this.playback = playback;
        this.accountLinks = accountLinks;
        this.gui = new MusicGui(plugin, repository, playback, this);
    }

    public boolean onPlayCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("save")) {
            String[] delegated = new String[args.length];
            System.arraycopy(args, 0, delegated, 0, args.length);
            return onCommand(sender, command, "music", delegated);
        }
        String[] delegated = new String[args.length + 1];
        delegated[0] = "play";
        System.arraycopy(args, 0, delegated, 1, args.length);
        return onCommand(sender, command, "music", delegated);
    }

    public List<String> onPlayTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("save")) {
            return onTabComplete(sender, command, "music", args);
        }
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
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sender instanceof Player player && plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin
                && musicPlugin.isPlayerMusicDisabled(player.getUniqueId()) && !sub.equals("enable")) {
            sender.sendMessage(msg("&eMusicolide is disabled for you. Use &f/music enable &eto re-enable it."));
            return true;
        }
        if (args.length == 0 || sub.equals("help")) { help(sender); return true; }
        try {
            switch (sub) {
                case "link" -> link(sender);
                case "unlink" -> unlink(sender);
                case "import" -> importTrack(sender, args);
                case "import-url" -> importUrl(sender, args);
                case "save" -> saveTrack(sender, args);
                case "delete" -> deleteTrack(sender, args);
                case "clear" -> clearTemporaryTracks(sender, args);
                case "disable" -> disableForSelf(sender);
                case "enable" -> enableForSelf(sender);
                case "unload" -> unloadForSelf(sender);
                case "discord" -> discordToggle(sender, args);
                case "diagnostics" -> diagnostics(sender);
                case "download" -> downloadLibrary(sender);
                case "list" -> listTracks(sender, args);
                case "search" -> searchYouTube(sender, args);
                case "select" -> selectYouTubeResult(sender, args);
                case "page" -> showNextSearchPage(sender, args);
                case "play" -> play(sender, args);
                case "confirm" -> confirm(sender);
                case "pause", "resume", "skip", "stop" -> control(sender, sub, args);
                case "gui" -> openGui(sender, args);
                case "queue" -> queue(sender, args);
                case "nowplaying", "progress" -> nowPlaying(sender);
                case "volume" -> volume(sender, args);
                case "speed" -> speed(sender, args);
                case "mute" -> mute(sender, true, args);
                case "unmute" -> mute(sender, false, args);
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

    /**
     * Returns local/cached choices immediately and starts remote YouTube metadata
     * lookup in the background. Discord autocomplete must be acknowledged quickly;
     * waiting for yt-dlp here can exceed Discord's interaction response deadline.
     */
    public CompletableFuture<List<DiscordPlaySuggestion>> discordPlaySuggestions(String query) {
        return discordPlaySuggestions(query, true, "default");
    }

    public CompletableFuture<List<DiscordPlaySuggestion>> discordPlaySuggestions(String query, boolean allowRemoteLookup) {
        return discordPlaySuggestions(query, allowRemoteLookup, "default");
    }

    public CompletableFuture<List<DiscordPlaySuggestion>> discordPlaySuggestions(
            String query, boolean allowRemoteLookup, String requesterKey) {
        String cleaned = normalizeSearchQuery(query);
        if (cleaned.length() < 2 || cleaned.length() > 100) {
            return CompletableFuture.completedFuture(List.of());
        }

        final List<DiscordPlaySuggestion> local;
        try {
            local = repository.searchForPlayback(cleaned, 10, 0).stream()
                    .map(track -> new DiscordPlaySuggestion(
                            TextUtil.truncate("[" + displayTrackId(track) + "] " + track.title()
                                    + (track.artist() == null || track.artist().isBlank()
                                    ? "" : " — " + track.artist()), 100),
                            displayTrackId(track)))
                    .toList();
        } catch (SQLException error) {
            return CompletableFuture.failedFuture(error);
        }

        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            return CompletableFuture.completedFuture(local);
        }

        long now = System.currentTimeMillis();
        CachedDiscordAutocomplete cached = discordAutocompleteCache.get(requesterKey);
        boolean cacheValid = cached != null && !cached.results().isEmpty()
                && now - cached.createdAtMillis() <= 30_000L;
        boolean cachedQueryCoversCurrent = cacheValid
                && (cleaned.equals(cached.query()) || cached.query().startsWith(cleaned));
        List<DiscordPlaySuggestion> cachedRemote = cacheValid
                && (cachedQueryCoversCurrent || cleaned.startsWith(cached.query()))
                ? cached.results() : List.of();
        List<DiscordPlaySuggestion> immediate = mergeDiscordSuggestions(local, cachedRemote);

        // An exact result or a more-specific cached query is already useful.
        // When the user refines a broader cached query, show it provisionally
        // while refreshing YouTube results for the new, narrower query.
        boolean exactCache = cacheValid && cleaned.equals(cached.query());
        if (!allowRemoteLookup || cleaned.length() < 3 || local.size() >= 25
                || exactCache || cachedQueryCoversCurrent) {
            return CompletableFuture.completedFuture(immediate);
        }

        discordAutocompleteLatestQuery.put(requesterKey, cleaned);
        CompletableFuture<List<DiscordPlaySuggestion>> response = new CompletableFuture<>();
        int remoteLimit = Math.max(1, 25 - local.size());
        CompletableFuture.delayedExecutor(300L, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (!cleaned.equals(discordAutocompleteLatestQuery.get(requesterKey))) {
                response.complete(immediate);
                return;
            }
            startDiscordAutocompleteLookup(requesterKey, cleaned, local, immediate, remoteLimit, response);
        });

        // Keep Discord's response bounded. If an older query is already being
        // searched, the newest query is coalesced and cached as soon as that
        // lookup finishes, so a later keystroke can use the correct suggestions.
        return response.copy().completeOnTimeout(immediate, 2_100L,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void startDiscordAutocompleteLookup(
            String requesterKey, String query, List<DiscordPlaySuggestion> local,
            List<DiscordPlaySuggestion> immediate, int remoteLimit,
            CompletableFuture<List<DiscordPlaySuggestion>> response) {
        synchronized (discordAutocompleteInFlight) {
            if (!discordAutocompleteInFlight.add(requesterKey)) {
                PendingDiscordAutocomplete replaced = discordAutocompletePending.put(requesterKey,
                        new PendingDiscordAutocomplete(query, local, immediate, remoteLimit, response));
                if (replaced != null) replaced.response().complete(replaced.immediate());
                return;
            }
        }
        runDiscordAutocompleteLookup(requesterKey, query, local, immediate, remoteLimit, response);
    }

    /** Runs while this requester owns the single in-flight search slot. */
    private void runDiscordAutocompleteLookup(
            String requesterKey, String query, List<DiscordPlaySuggestion> local,
            List<DiscordPlaySuggestion> immediate, int remoteLimit,
            CompletableFuture<List<DiscordPlaySuggestion>> response) {
        try {
            processor.searchYouTube(query, Math.min(6, remoteLimit))
                    .handle((results, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "YouTube autocomplete search failed for query '" + query + "'.", error);
                            return immediate;
                        }
                        if (results == null || results.isEmpty()) return immediate;

                        List<DiscordPlaySuggestion> remote = new ArrayList<>();
                        Set<String> existing = new HashSet<>();
                        for (DiscordPlaySuggestion suggestion : local) existing.add(suggestion.value());
                        for (YtDlpDownloader.SearchResult result : results) {
                            if (remote.size() + local.size() >= 25) break;
                            String url = result.url();
                            if (url == null || url.isBlank()) {
                                url = "https://www.youtube.com/watch?v=" + result.videoId();
                            }
                            if (url.length() > 100 || !existing.add(url)) continue;
                            String title = result.title() == null || result.title().isBlank()
                                    ? "Untitled" : result.title();
                            String uploader = result.uploader() == null ? "" : result.uploader();
                            String label = title + (uploader.isBlank() ? "" : " — " + uploader);
                            if (result.durationSeconds() > 0) {
                                long seconds = result.durationSeconds();
                                label += " • " + String.format(Locale.ROOT, "%d:%02d",
                                        seconds / 60, seconds % 60);
                            }
                            remote.add(new DiscordPlaySuggestion(TextUtil.truncate(label, 100), url));
                        }
                        if (!remote.isEmpty()) {
                            discordAutocompleteCache.put(requesterKey,
                                    new CachedDiscordAutocomplete(query, List.copyOf(remote),
                                            System.currentTimeMillis()));
                            if (discordAutocompleteCache.size() > 2_000) {
                                long completedAt = System.currentTimeMillis();
                                discordAutocompleteCache.entrySet().removeIf(entry ->
                                        completedAt - entry.getValue().createdAtMillis() > 60_000L);
                                discordAutocompleteLatestQuery.keySet().removeIf(key ->
                                        !discordAutocompleteInFlight.contains(key)
                                                && !discordAutocompleteCache.containsKey(key));
                            }
                        }
                        return mergeDiscordSuggestions(local, remote);
                    })
                    .whenComplete((suggestions, error) -> {
                        response.complete(error == null && suggestions != null ? suggestions : immediate);

                        PendingDiscordAutocomplete pending;
                        synchronized (discordAutocompleteInFlight) {
                            pending = discordAutocompletePending.remove(requesterKey);
                            if (pending == null || !pending.query().equals(
                                    discordAutocompleteLatestQuery.get(requesterKey))) {
                                discordAutocompleteInFlight.remove(requesterKey);
                            }
                        }

                        if (pending == null) {
                            discordAutocompleteLatestQuery.remove(requesterKey, query);
                        } else if (!pending.query().equals(discordAutocompleteLatestQuery.get(requesterKey))) {
                            pending.response().complete(pending.immediate());
                            discordAutocompleteLatestQuery.remove(requesterKey, query);
                        } else {
                            // Keep ownership of the per-user slot while the latest
                            // query runs; newer keystrokes replace its pending entry.
                            runDiscordAutocompleteLookup(requesterKey, pending.query(), pending.local(),
                                    pending.immediate(), pending.remoteLimit(), pending.response());
                        }
                    });
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not start background YouTube autocomplete lookup.", error);
            response.complete(immediate);

            PendingDiscordAutocomplete pending;
            synchronized (discordAutocompleteInFlight) {
                pending = discordAutocompletePending.remove(requesterKey);
                if (pending == null || !pending.query().equals(
                        discordAutocompleteLatestQuery.get(requesterKey))) {
                    discordAutocompleteInFlight.remove(requesterKey);
                }
            }
            if (pending != null && pending.query().equals(discordAutocompleteLatestQuery.get(requesterKey))) {
                runDiscordAutocompleteLookup(requesterKey, pending.query(), pending.local(),
                        pending.immediate(), pending.remoteLimit(), pending.response());
            } else if (pending != null) {
                pending.response().complete(pending.immediate());
                discordAutocompleteLatestQuery.remove(requesterKey, query);
            } else {
                discordAutocompleteLatestQuery.remove(requesterKey, query);
            }
        }
    }

    private List<DiscordPlaySuggestion> mergeDiscordSuggestions(
            List<DiscordPlaySuggestion> local, List<DiscordPlaySuggestion> remote) {
        List<DiscordPlaySuggestion> merged = new ArrayList<>(local);
        Set<String> existing = new HashSet<>();
        for (DiscordPlaySuggestion suggestion : local) existing.add(suggestion.value());
        for (DiscordPlaySuggestion suggestion : remote) {
            if (merged.size() >= 25) break;
            if (suggestion.value() != null && existing.add(suggestion.value())) merged.add(suggestion);
        }
        return List.copyOf(merged);
    }

    private record CachedDiscordAutocomplete(String query,
                                              List<DiscordPlaySuggestion> results,
                                              long createdAtMillis) {}

    private record PendingDiscordAutocomplete(String query,
                                                List<DiscordPlaySuggestion> local,
                                                List<DiscordPlaySuggestion> immediate,
                                                int remoteLimit,
                                                CompletableFuture<List<DiscordPlaySuggestion>> response) {}

    public record DiscordPlaySuggestion(String label, String value) {}

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


    private void importTrack(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(msg("&eUsage: /music import <filename> [title] [id:music100] or /music import all"));
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
        sender.sendMessage(msg("&ePreparing &f" + args[1] + "&e from SavedMusic/..."));
        processor.importFile(args[1], title.isBlank() ? null : title, customId).whenComplete((track, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cImport failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aSaved &f" + track.title() + " &7[TrackID: &f" + displayTrackId(track) + "&7]"));
                }));
    }

    private void importUrl(CommandSender sender, String[] args) throws SQLException {
        if (!admin(sender)) return;
        if (args.length < 3 || !(args[2].equalsIgnoreCase("rights")
                || args[2].equalsIgnoreCase("rights-confirmed")
                || args[2].equalsIgnoreCase("--rights-confirmed"))) {
            sender.sendMessage(msg("&eUsage: /music import-url <http(s)-url> rights [title] [id:music100]"));
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
        sender.sendMessage(msg("&eImporting authorized audio into the permanent SavedMusic library..."));
        processor.importUrlPermanent(args[1], title.isBlank() ? null : title, customId).whenComplete((track, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cURL import failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aSaved &f" + track.title() + " &7[TrackID: &f" + displayTrackId(track) + "&7]"));
                }));
    }

    private String optionValue(String[] args, int start, String option) {
        for (int i = Math.max(0, start); i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase(option)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException(option + " requires a value.");
                }
                return args[i + 1];
            }
            if (option.equalsIgnoreCase("--id") && token.toLowerCase(Locale.ROOT).startsWith("id:")) {
                String value = token.substring(3);
                if (value.isBlank()) throw new IllegalArgumentException("id: requires a value.");
                return value;
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
            } else if (option.equalsIgnoreCase("--id")
                    && args[i].toLowerCase(Locale.ROOT).startsWith("id:")) {
                if (args[i].substring(3).isBlank()) throw new IllegalArgumentException("id: requires a value.");
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
     * Imports every supported audio file in SavedMusic sequentially.
     * Sequential processing avoids launching an unbounded number of FFmpeg jobs
     * and gives hosts with strict CPU/RAM limits a predictable workload.
     */
    private void importAllTracks(CommandSender sender) {
        // Admit before scanning the directory so a repeated command cannot turn
        // directory enumeration itself into a disk-I/O workload.
        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;

        Path incoming = plugin.getDataFolder().toPath().resolve("SavedMusic");
        int maximumFiles = Math.max(1, Math.min(200,
                plugin.getConfig().getInt("audio.processing.max-bulk-import-files", 50)));
        final List<String> files;
        final boolean truncated;
        try {
            Files.createDirectories(incoming);
            try (var stream = Files.list(incoming)) {
                // Limit before sorting/materializing so a directory containing
                // hundreds of thousands of files cannot create an enormous list.
                List<String> discovered = stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(MusicCommand::isSupportedAudioFile)
                        .filter(fileName -> !hasTrackMetadataSidecar(incoming, fileName))
                        .limit((long) maximumFiles + 1L)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                truncated = discovered.size() > maximumFiles;
                files = truncated
                        ? List.copyOf(discovered.subList(0, maximumFiles))
                        : discovered;
            }
        } catch (IOException ex) {
            sender.sendMessage(msg("&cCould not scan SavedMusic/: " + ex.getMessage()));
            return;
        }

        if (files.isEmpty()) {
            sender.sendMessage(msg("&eNo supported audio files were found in plugins/Musicolide/SavedMusic/."));
            return;
        }

        sender.sendMessage(msg("&aBulk import started for &f" + files.size()
                + " &aaudio file(s). Files are processed one at a time."
                + (truncated ? " &eThe configured batch limit was reached; additional files remain in SavedMusic/." : "")));
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (String file : files) {
            chain = chain.thenCompose(ignored -> processor.importFile(file, null).handle((track, error) -> {
                if (error == null) {
                    imported.incrementAndGet();
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                            msg("&aSaved &f" + track.title() + " &7[TrackID: " + displayTrackId(track) + "]")));
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
                        + " &7| &cFailed: &f" + failed.get() + " &7| &5Selected: &f" + files.size()
                        + (truncated ? " &e| More files remain; this batch was capped at " + maximumFiles + "." : "")))));
    }

    private static boolean hasTrackMetadataSidecar(Path directory, String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return false;
        String base = fileName.substring(0, dot);
        return base.matches("[a-f][0-9]{2}") && Files.isRegularFile(directory.resolve(base + ".yml"));
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
        sender.sendMessage(msg("&5Musicolide library &8• &f" + tracks.size() + " &7track(s):"));
        for (MusicTrack track : tracks) {
            String title = TextUtil.truncate(track.title(), 48);
            String artist = TextUtil.truncate(track.artist(), 28);
            String details = artist.isBlank() ? title : title + " &7by &f" + artist;
            sender.sendMessage(msg("&7• &d" + displayTrackId(track) + " &8| &f" + details
                    + " &8| &7" + format(track.durationMs())));
        }
    }


    private void play(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(msg("&eUsage: /play <TrackID, title, URL or query> [@a/@p/@s/player] [target:@a] [circle:60/square:50x50/region:spawn] [fade10] [mode:mono] [--center] [id:music100]"));
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

            int configuredPlaylistItems = Math.max(1, Math.min(20,
                    plugin.getConfig().getInt("audio.yt-dlp.max-playlist-items", 10)));
            final int maximumItems = Math.min(configuredPlaylistItems, Math.max(1, Math.min(500,
                    plugin.getConfig().getInt("limits.max-playlist-tracks", 500))));
            sender.sendMessage(msg("&eReading playlist entries. Only the first track will be imported initially; the next tracks will be prepared gradually."));
            processor.listPlaylistUrl(rawInput, maximumItems).whenComplete((listing, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sender instanceof Player player && !player.isOnline()) return;
                        if (error != null) {
                            sender.sendMessage(msg("&cYouTube playlist lookup failed: " + rootMessage(error)));
                            return;
                        }
                        if (listing.entries().isEmpty()) {
                            sender.sendMessage(msg("&cThe playlist did not contain any importable tracks."));
                            return;
                        }
                        String summary = "&aFound &f" + listing.entries().size()
                                + " &eplaylist entries. Importing the first track now.";
                        if (listing.truncated()) {
                            summary += " &7The source playlist exceeds the configured limit (" + maximumItems
                                    + "); only the first allowed entries will be played.";
                        }
                        sender.sendMessage(msg(summary));
                        importFirstPlaylistTrack(sender, listing.entries(), request);
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
        resolveOrImportPlayUrl(sender, rawInput, request);
        return;
    }

    private void resolveOrImportPlayUrl(CommandSender sender, String rawUrl, PlayRequest request) {
        boolean metadataEnabled = plugin.getConfig().getBoolean("matching.enabled", true)
                && plugin.getConfig().getBoolean("matching.metadata-lookup", true);
        if (!metadataEnabled) {
            continueTemporaryUrlImport(sender, rawUrl, request);
            return;
        }
        if (!admitHeavyRequest(sender, "metadata",
                "audio.processing.metadata-cooldown-seconds", 5,
                "audio.processing.global-metadata-cooldown-seconds", 1,
                "another external metadata lookup")) return;
        sender.sendMessage(msg("&eChecking the local library by source and metadata before downloading..."));
        metadataResolver.resolve(rawUrl).thenAccept(metadata -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            if (metadata.isPresent()) {
                try {
                    List<MusicTrack> candidates = repository.searchForPlayback(metadata.get().title(), 25, 0);
                    Optional<CatalogMatcher.Match> match = CatalogMatcher.bestCompatibleArtistMatch(
                            metadata.get().title(), metadata.get().creator(), candidates);
                    if (match.isPresent()) {
                        sender.sendMessage(msg("&aFound a matching local download: &f" + match.get().track().title()
                                + " &7[TrackID: " + displayTrackId(match.get().track()) + "]."));
                        offerMatch(sender, match.get().track(), request, match.get().confidence());
                        return;
                    }
                } catch (SQLException error) {
                    sender.sendMessage(msg("&cCould not check the local catalogue: " + error.getMessage()));
                    return;
                }
            }
            continueTemporaryUrlImport(sender, rawUrl, request);
        }));
    }

    private void continueTemporaryUrlImport(CommandSender sender, String rawUrl, PlayRequest request) {
        if (!isYouTubeUrl(rawUrl)) {
            sender.sendMessage(msg("&cNo matching local track was found for this source."));
            sender.sendMessage(msg("&7Spotify and SoundCloud remain metadata/alias-only. Add an alias to a local TrackID with /music alias add."));
            return;
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)) {
            sender.sendMessage(msg("&cNo saved local match was found and URL auto-import is disabled."));
            sender.sendMessage(msg("&7An administrator can enable audio.yt-dlp.auto-import-on-play for configured, rights-cleared hosts."));
            sender.sendMessage(msg("&7To permanently add an authorized URL, use /music import-url <url> rights."));
            return;
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false)) {
            sender.sendMessage(msg("&cURL importing is disabled by the server administrator."));
            return;
        }
        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        sender.sendMessage(msg("&eNo local match found. Importing a temporary playback copy..."));
        processor.importUrl(rawUrl, null, request.customId).whenComplete((imported, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isOnline()) return;
                    if (error != null) sender.sendMessage(msg("&cURL import failed: " + rootMessage(error)));
                    else {
                        sender.sendMessage(msg("&aReady: &f" + imported.title() + " &7[TrackID: "
                                + displayTrackId(imported) + "] &a(temporary)."));
                        start(sender, imported, request);
                    }
                }));
    }

    private int firstPlayModifierIndex(CommandSender sender, String[] args) {
        for (int i = 2; i < args.length; i++) {
            String value = args[i];
            String normalized = value.toLowerCase(Locale.ROOT);
            boolean compact = normalized.startsWith("target:") || normalized.startsWith("id:")
                    || normalized.startsWith("mode:") || normalized.startsWith("circle:")
                    || normalized.startsWith("square:") || normalized.startsWith("region:")
                    // Bare "center" is ordinary search text; use --center to opt in.
                    || normalized.matches("fade(?::)?[0-9]+(?:\\.[0-9]+)?");
            if (normalized.startsWith("--") || compact || normalized.equals("mono")
                    || normalized.equals("stereo") || normalized.equals("@a")
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
        beginYouTubeSearch(sender, query, request, true);
    }

    private void beginYouTubeSearch(CommandSender sender, String rawQuery, PlayRequest request) {
        beginYouTubeSearch(sender, rawQuery, request, false);
    }

    private void beginYouTubeSearch(CommandSender sender, String rawQuery, PlayRequest request,
                                    boolean permanentSelection) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cSearch result selection is player-specific; run /music search in game."));
            return;
        }
        String query = normalizeSearchQuery(rawQuery);
        long now = System.currentTimeMillis();
        pendingSearches.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis > SEARCH_RESULT_TTL_MILLIS);
        PendingSearch search = new PendingSearch(query, request, now, permanentSelection);
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
                        + " &8[TrackID: " + displayTrackId(track) + "] &7[" + format(track.durationMs()) + "]"));
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
            if (!search.permanentSelection) {
                start(sender, selected.localTrack, search.request);
                return;
            }
            sender.sendMessage(msg("&eSaving the selected local track to SavedMusic..."));
            processor.saveTrackPermanently(selected.localTrack.id()).whenComplete((saved, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) sender.sendMessage(msg("&cCould not save track: " + rootMessage(error)));
                        else {
                            sender.sendMessage(msg("&aSaved &f" + saved.title() + " &7[TrackID: "
                                    + displayTrackId(saved) + "&7]."));
                            start(sender, saved, search.request);
                        }
                    }));
            return;
        }
        YtDlpDownloader.SearchResult remote = selected.remoteResult;
        List<MusicTrack> metadataCandidates = repository.searchForPlayback(remote.title(), 25, 0);
        Optional<CatalogMatcher.Match> metadataMatch = CatalogMatcher.bestCompatibleArtistMatch(
                remote.title(), remote.uploader(), metadataCandidates);
        if (metadataMatch.isPresent()) {
            pendingSearches.remove(player.getUniqueId(), search);
            MusicTrack match = metadataMatch.get().track();
            sender.sendMessage(msg("&aMatched an existing local download: &f" + match.title()
                    + " &7[TrackID: " + displayTrackId(match) + "]."));
            if (search.permanentSelection) {
                processor.saveTrackPermanently(match.id()).whenComplete((saved, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            if (error != null) sender.sendMessage(msg("&cCould not save the matched track: " + rootMessage(error)));
                            else start(sender, saved, search.request);
                        }));
            } else {
                start(sender, match, search.request);
            }
            return;
        }
        Optional<String> aliasKey = LinkNormalizer.normalize(remote.url());
        if (aliasKey.isPresent()) {
            Optional<MusicTrack> existing = repository.resolveAlias(aliasKey.get());
            if (existing.isPresent()) {
                pendingSearches.remove(player.getUniqueId(), search);
                MusicTrack matched = existing.get();
                if (search.permanentSelection) {
                    processor.saveTrackPermanently(matched.id()).whenComplete((saved, error) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!player.isOnline()) return;
                                if (error != null) sender.sendMessage(msg("&cCould not save the existing track: " + rootMessage(error)));
                                else {
                                    sender.sendMessage(msg("&aReusing existing audio &f" + saved.title()
                                            + " &7[TrackID: " + displayTrackId(saved) + "]."));
                                    start(sender, saved, search.request);
                                }
                            }));
                } else {
                    sender.sendMessage(msg("&aAlready imported: &f" + matched.title()
                            + " &a. Starting the saved copy without another download."));
                    start(sender, matched, search.request);
                }
                return;
            }
        }
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.enabled", false))
            throw new IllegalArgumentException("URL importing is disabled by the server administrator.");
        if (!plugin.getConfig().getBoolean("audio.yt-dlp.auto-import-on-play", false)) {
            sender.sendMessage(msg("&cThis result is not in your local library and YouTube importing is disabled."));
            sender.sendMessage(msg("&7An administrator can enable audio.yt-dlp.auto-import-on-play after confirming redistribution rights."));
            return;
        }
        if (!admitHeavyRequest(sender, "import",
                "audio.processing.import-cooldown-seconds", 10,
                "audio.processing.global-import-cooldown-seconds", 2, "another import")) return;
        search.selecting = true;
        sender.sendMessage(msg("&eImporting selected result: &f" + safeChat(remote.title())));
        CompletableFuture<MusicTrack> selectedImport = search.permanentSelection
                ? processor.importUrlPermanent(remote.url(), null, search.request.customId)
                : processor.importUrl(remote.url(), null, search.request.customId);
        selectedImport.whenComplete((imported, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    search.selecting = false;
                    if (!player.isOnline()) return;
                    if (error != null) sender.sendMessage(msg("&cSelected result could not be imported: " + rootMessage(error)));
                    else {
                        pendingSearches.remove(player.getUniqueId(), search);
                        sender.sendMessage(msg(search.permanentSelection
                                ? "&aSaved &f" + imported.title() + " &7[TrackID: " + displayTrackId(imported) + "]&a; starting playback."
                                : "&aImported temporary copy &f" + imported.title() + " &7[TrackID: " + displayTrackId(imported) + "]&a; starting playback."));
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

    static boolean isExplicitCenterOption(String option) {
        return option != null && option.equalsIgnoreCase("--center");
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
        boolean centeredMono = false;
        double fade = 0;
        String customId = null;
        String audioMode = "stereo";

        for (int i = 2; i < args.length; i++) {
            String option = args[i].toLowerCase(Locale.ROOT);
            if (option.startsWith("target:")) {
                if (selector != null) throw new IllegalArgumentException("Specify only one target.");
                selector = option.substring("target:".length());
                if (selector.isBlank()) throw new IllegalArgumentException("Use target:@a, target:@p, target:@s or target:<player>.");
                continue;
            }
            if (option.startsWith("id:")) {
                customId = option.substring(3);
                if (customId.isBlank()) throw new IllegalArgumentException("Use id:<custom-name>, for example id:music100.");
                continue;
            }
            if (option.startsWith("mode:")) {
                audioMode = option.substring(5);
                if (!audioMode.equals("mono") && !audioMode.equals("stereo"))
                    throw new IllegalArgumentException("Use mode:mono or mode:stereo.");
                continue;
            }
            if (isExplicitCenterOption(option)) {
                centeredMono = true;
                continue;
            }
            if (option.startsWith("circle:")) {
                radius = Double.parseDouble(option.substring(7));
                circle = true; square = false; region = null;
                continue;
            }
            if (option.startsWith("square:")) {
                String[] dims = option.substring(7).split("x", 2);
                if (dims.length != 2) throw new IllegalArgumentException("Use square:WIDTHxLENGTH, for example square:50x50.");
                width = Double.parseDouble(dims[0]); length = Double.parseDouble(dims[1]);
                square = true; circle = false; region = null;
                continue;
            }
            if (option.startsWith("region:")) {
                region = option.substring(7);
                if (region.isBlank()) throw new IllegalArgumentException("Use region:<WorldGuard-region-ID>.");
                circle = false; square = false;
                continue;
            }
            java.util.regex.Matcher fadeArgument = java.util.regex.Pattern
                    .compile("fade(?::)?([0-9]+(?:\\.[0-9]+)?)").matcher(option);
            if (fadeArgument.matches()) {
                fadeSpecified = true;
                fade = Double.parseDouble(fadeArgument.group(1));
                fadeEnabled = fade > 0;
                if (fadeEnabled && !circle && !square && region == null) circle = true;
                continue;
            }

            switch (option) {
                case "--all", "--player" -> throw new IllegalArgumentException(
                        "Use @a, @p, @s, or a player name instead of --all/--player.");
                case "--target" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--target requires a target.");
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
                case "--center" -> centeredMono = true;
                case "mono", "stereo" -> audioMode = option;
                case "--circle" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--circle requires a radius.");
                    radius = Double.parseDouble(args[i]); circle = true; square = false; region = null;
                }
                case "--square" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--square requires WIDTHxLENGTH.");
                    String[] dims = args[i].toLowerCase(Locale.ROOT).split("x", 2);
                    if (dims.length != 2) throw new IllegalArgumentException("Square dimensions must look like 50x50.");
                    width = Double.parseDouble(dims[0]); length = Double.parseDouble(dims[1]);
                    square = true; circle = false; region = null;
                }
                case "--fade" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--fade requires a distance.");
                    fadeSpecified = true; fade = Double.parseDouble(args[i]); fadeEnabled = fade > 0;
                    if (fadeEnabled && !circle && !square && region == null) circle = true;
                }
                case "--region" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--region requires a region ID.");
                    region = args[i]; circle = false; square = false;
                }
                default -> {
                    if (option.startsWith("--")) throw new IllegalArgumentException("Unknown play option: " + args[i]);
                    if (selector != null) throw new IllegalArgumentException("Specify only one target: @a, @p, @s, or one player name.");
                    selector = args[i];
                }
            }
        }

        if (centeredMono && !audioMode.equals("mono")) {
            throw new IllegalArgumentException("The center option is only available with mode:mono.");
        }
        if (centeredMono && region != null) {
            throw new IllegalArgumentException("The center option supports circle/square mono zones, not WorldGuard regions.");
        }
        if (centeredMono && !circle && !square) circle = true;

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

        boolean spatialOption = circle || square || region != null || fadeEnabled || centeredMono;
        boolean hasAdmin = sender.hasPermission(plugin.getConfig().getString(
                "permissions.admin", "musicolide.admin"));
        boolean allowSelfZones = plugin.getConfig().getBoolean("permissions.allow-player-self-zones", true);
        boolean allowAllTargets = plugin.getConfig().getBoolean("permissions.allow-player-all-target", false);
        if (selector == null) {
            if (sender instanceof Player && spatialOption && !hasAdmin && allowSelfZones) {
                selector = "@s";
            } else {
                selector = PlayTargetDefaults.defaultSelector(sender instanceof Player, spatialOption);
            }
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

        boolean isSelfTarget = sender instanceof Player source && !all && !targets.isEmpty()
                && targets.get(0).getUniqueId().equals(source.getUniqueId());
        if (!(sender instanceof Player) && !hasAdmin) {
            throw new IllegalArgumentException("Console requires the Musicolide admin permission.");
        }
        if (spatialOption && !(sender instanceof Player)) {
            throw new IllegalArgumentException("Spatial zones require an in-game player so Musicolide can use the execution location as their center.");
        }
        if (!hasAdmin && all && !allowAllTargets) {
            throw new IllegalArgumentException("Using target:@a requires the Musicolide admin permission.");
        }
        if (!hasAdmin && spatialOption && !(isSelfTarget && allowSelfZones)) {
            throw new IllegalArgumentException("Normal players may use zones only when targeting themselves. Use target:@s or ask an admin.");
        }
        if (!hasAdmin && !all && !isSelfTarget) {
            throw new IllegalArgumentException("Targeting another player requires the Musicolide admin permission.");
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
                zone = centeredMono
                        ? MusicZone.centeredSquare(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                            width, length, rotation, limited, vertical, fadeEnabled, fade)
                        : MusicZone.square(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                            width, length, rotation, limited, vertical, fadeEnabled, fade);
            } else {
                zone = centeredMono
                        ? MusicZone.centeredCircle(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
                            radius, limited, vertical, fadeEnabled, fade)
                        : MusicZone.circle(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(),
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

    private void importFirstPlaylistTrack(CommandSender sender,
                                           List<YtDlpDownloader.PlaylistEntry> entries,
                                           PlayRequest request) {
        YtDlpDownloader.PlaylistEntry first = entries.get(0);
        sender.sendMessage(msg("&eImporting first playlist track: &f" + first.title()));
        processor.importPlaylistEntry(first).thenCompose(track ->
                "mono".equalsIgnoreCase(request.audioMode)
                        ? processor.ensureMonoVariant(track)
                        : CompletableFuture.completedFuture(track))
                .whenComplete((track, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isOnline()) return;
                    if (error != null) {
                        sender.sendMessage(msg("&cCould not import the first playlist track: " + rootMessage(error)));
                        return;
                    }
                    UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
                    boolean mono = "mono".equalsIgnoreCase(request.audioMode);
                    PlaybackSession session;
                    try {
                        session = playback.startSession(owner, sender.getName(), request.zone,
                                request.all, request.listeners, List.of(track), request.priority, mono);
                    } catch (IllegalStateException limit) {
                        sender.sendMessage(msg("&cCould not start playlist: " + limit.getMessage()));
                        return;
                    }
                    if (session.isEmpty()) {
                        sender.sendMessage(msg("&cThe first playlist track could not be queued."));
                        return;
                    }
                    session.queuePending(true);
                    String playlistDelivery = plugin.getConfig().getString(
                            "resource-packs.playlist-playback-system", "per-track");
                    boolean playlistPerTrack = playlistDelivery == null
                            || playlistDelivery.trim().equalsIgnoreCase("per-track");
                    session.configureUrlPlaylist(playlistPerTrack);
                    sender.sendMessage(msg("&aStarted playlist with &f" + track.title()
                            + "&a. The server will prepare up to two upcoming tracks gradually; players receive only the active track when per-track playlist delivery is selected."));
                    PlaylistRunner runner = new PlaylistRunner(sender, request, session, entries, 1);
                    playlistRunners.add(runner);
                    runner.start();
                }));
    }

    /**
     * Keeps a URL playlist flowing without downloading the entire list up front.
     * Only a bounded number of future tracks are imported, and each new item is
     * added to the playback queue rather than delivered ahead of the current song.
     */
    private final class PlaylistRunner implements Runnable {
        private final CommandSender sender;
        private final PlayRequest request;
        private final PlaybackSession session;
        private final List<YtDlpDownloader.PlaylistEntry> entries;
        private final boolean mono;
        private final boolean repeat;
        private final int importIntervalSeconds;
        private final int maximumAhead;
        private int nextIndex;
        private int inFlight;
        private int skipped;
        private boolean closed;
        private long observedTimelineGeneration = Long.MIN_VALUE;
        private boolean observedClockStarted;
        private long nextImportAtMillis = Long.MAX_VALUE;
        private BukkitTask task;

        private PlaylistRunner(CommandSender sender, PlayRequest request, PlaybackSession session,
                               List<YtDlpDownloader.PlaylistEntry> entries, int nextIndex) {
            this.sender = sender;
            this.request = request;
            this.session = session;
            this.entries = List.copyOf(entries);
            this.nextIndex = nextIndex;
            this.mono = "mono".equalsIgnoreCase(request.audioMode);
            this.repeat = plugin.getConfig().getBoolean("playlists.repeat", false);
            this.importIntervalSeconds = Math.max(10, Math.min(20,
                    plugin.getConfig().getInt("playlists.preimport.interval-seconds", 15)));
            this.maximumAhead = Math.max(1, Math.min(8,
                    plugin.getConfig().getInt("playlists.preimport.max-preimported-tracks", 2)));
        }

        private void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 5L);
        }

        @Override
        public void run() {
            if (closed) return;
            if (!playback.isSessionActive(session)) {
                close(false);
                return;
            }

            long now = System.currentTimeMillis();
            MusicTrack current = session.current();
            long generation = session.timelineGeneration();
            if (generation != observedTimelineGeneration) {
                observedTimelineGeneration = generation;
                observedClockStarted = false;
                nextImportAtMillis = Long.MAX_VALUE;
            }
            if (current != null && session.clockStarted() && !observedClockStarted) {
                // Start the cooldown from actual playback, not from the time the
                // first track was imported or its resource pack began downloading.
                observedClockStarted = true;
                nextImportAtMillis = now + importIntervalSeconds * 1000L;
            }

            if (session.paused() || session.resumePreparing()) return;
            boolean currentMissing = current == null;
            long elapsed = session.elapsedMs();
            boolean shouldImport = PlaylistImportPolicy.shouldStartImport(
                    session.clockStarted(), currentMissing,
                    current == null ? 0L : current.durationMs(), elapsed,
                    now, nextImportAtMillis, importIntervalSeconds,
                    session.queueSize(), inFlight, maximumAhead);
            if (shouldImport && inFlight == 0) {
                startNextImport(now);
            }

            if (!repeat && nextIndex >= entries.size() && inFlight == 0) {
                session.queuePending(false);
                close(false);
                if (skipped > 0) sender.sendMessage(msg("&eSkipped " + skipped
                        + " playlist track(s) that could not be imported."));
                sender.sendMessage(msg("&aPlaylist importing finished. Remaining queued tracks will play in order."));
            }
        }

        private void startNextImport(long now) {
            if (nextIndex >= entries.size()) {
                if (!repeat) return;
                nextIndex = 0;
            }
            YtDlpDownloader.PlaylistEntry entry = entries.get(nextIndex++);
            inFlight++;
            nextImportAtMillis = now + importIntervalSeconds * 1000L;
            processor.importPlaylistEntry(entry)
                    .thenCompose(track -> mono ? processor.ensureMonoVariant(track)
                            : CompletableFuture.completedFuture(track))
                    .whenComplete((track, error) ->
                            Bukkit.getScheduler().runTask(plugin, () -> completeEntry(entry, track, error)));
        }

        private void completeEntry(YtDlpDownloader.PlaylistEntry entry, MusicTrack track, Throwable error) {
            if (closed) return;
            inFlight = Math.max(0, inFlight - 1);
            if (!playback.isSessionActive(session)) {
                close(false);
                return;
            }
            if (error != null || track == null) {
                skipped++;
                sender.sendMessage(msg("&eSkipped playlist entry &f" + TextUtil.truncate(entry.title(), 55)
                        + "&e: " + rootMessage(error == null
                        ? new IllegalStateException("No track was returned.") : error)));
                return;
            }
            session.enqueue(track);
            sender.sendMessage(msg("&7Prepared next playlist track: &f" + TextUtil.truncate(track.title(), 55)
                    + " &8(" + session.queueSize() + " queued)"));
        }

        private void close(boolean clearPending) {
            if (closed) return;
            closed = true;
            if (task != null) task.cancel();
            playlistRunners.remove(this);
            if (clearPending) session.queuePending(false);
        }
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

    private void saveTrack(CommandSender sender, String[] args) throws SQLException {
        if (!libraryManager(sender)) return;
        if (args.length > 2) throw new IllegalArgumentException("Usage: /music save [TrackID]");
        MusicTrack track;
        if (args.length == 2) {
            track = repository.getTrack(args[1])
                    .orElseThrow(() -> new IllegalArgumentException("TrackID not found. Use /music list to see saved tracks."));
        } else {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("Console must specify a TrackID. In-game, /music save saves your current or most recently played track.");
            }
            track = playback.mostRecentTrack(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("You have not played a track yet. Specify a TrackID instead."));
        }
        sender.sendMessage(msg("&eSaving &f" + track.title() + " &eto the permanent SavedMusic library..."));
        processor.saveTrackPermanently(track.id()).whenComplete((saved, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cCould not save track: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aPermanently saved &f" + saved.title()
                            + " &7[TrackID: " + displayTrackId(saved) + "]."));
                }));
    }

    private void clearTemporaryTracks(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length != 1) throw new IllegalArgumentException("Usage: /music clear");
        if (!(plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin)) {
            throw new IllegalStateException("Temporary track cleanup is unavailable.");
        }
        sender.sendMessage(msg("&eClearing unused temporary tracks. Active or queued tracks and tracks being downloaded will be kept."));
        musicPlugin.clearTemporaryTracks().whenComplete((count, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cTemporary track cleanup failed: " + rootMessage(error)));
                    else sender.sendMessage(msg("&aCleared &f" + count + " &aunused temporary track(s)."));
                }));
    }

    private void disableForSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only an in-game player can disable Musicolide for themselves.");
        if (!(plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin)) {
            throw new IllegalStateException("Player-specific disable is unavailable.");
        }
        if (!musicPlugin.disablePlayerMusic(player)) {
            sender.sendMessage(msg("&eMusicolide is already disabled for you. No pack changes were made."));
            return;
        }
        sender.sendMessage(msg("&eMusicolide has been disabled for you. Your Musicolide packs were unloaded."));
        sender.sendMessage(msg("&7Use /music enable to turn it back on."));
    }

    private void enableForSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only an in-game player can enable Musicolide for themselves.");
        if (!(plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin)) {
            throw new IllegalStateException("Player-specific enable is unavailable.");
        }
        if (!musicPlugin.enablePlayerMusic(player)) {
            sender.sendMessage(msg("&aMusicolide is already enabled for you. No resource-pack reload was requested."));
            return;
        }
        sender.sendMessage(msg("&aMusicolide is enabled for you again. Your next playback will request the required packs."));
    }

    private void unloadForSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only an in-game player can unload your music packs.");
        if (!(plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin)) {
            throw new IllegalStateException("Pack unloading is unavailable.");
        }
        if (!musicPlugin.unloadPlayerMusic(player)) {
            sender.sendMessage(msg("&eYour Musicolide packs are already unloaded. No request was made."));
            return;
        }
        sender.sendMessage(msg("&eYour Musicolide library and temporary packs were requested to unload."));
        sender.sendMessage(msg("&7The library will be requested again when a different track starts."));
    }

    private void discordToggle(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length != 2) throw new IllegalArgumentException("Usage: /music discord <enable|disable>");
        boolean enabled = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "enable", "on" -> true;
            case "disable", "off" -> false;
            default -> throw new IllegalArgumentException("Use /music discord enable or /music discord disable.");
        };
        if (!(plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin)) {
            throw new IllegalStateException("Discord runtime controls are unavailable.");
        }
        if (!musicPlugin.setDiscordEnabled(enabled)) {
            sender.sendMessage(msg(enabled
                    ? "&eDiscord broadcasting is already enabled. No reconnect was triggered."
                    : "&eDiscord broadcasting is already disabled. No action was needed."));
            return;
        }
        sender.sendMessage(msg(enabled
                ? "&aDiscord broadcasting has been enabled. It will start in the background."
                : "&eDiscord broadcasting has been disabled."));
    }

    private void deleteTrack(CommandSender sender, String[] args) throws SQLException {
        if (!admin(sender)) return;
        if (args.length != 2) throw new IllegalArgumentException("Usage: /music delete <TrackID>");
        MusicTrack track = repository.getTrack(args[1])
                .orElseThrow(() -> new IllegalArgumentException("TrackID not found. Use /music list to check the ID."));
        if (playback.isTrackInUse(track.id())) {
            throw new IllegalArgumentException("That track is currently playing or queued. Stop or remove it before deleting.");
        }
        if (plugin instanceof io.github.escolide.musicolide.MusicolidePlugin musicPlugin
                && musicPlugin.isTrackPackDownloadInProgress(track.packId())) {
            throw new IllegalArgumentException("A player is downloading this track's library pack. Try again after it finishes.");
        }
        sender.sendMessage(msg("&eDeleting &f" + track.title() + " &7[TrackID: " + displayTrackId(track)
                + "&7] &efrom SavedMusic and the music library..."));
        processor.deleteTrack(track.id()).whenComplete((deleted, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) sender.sendMessage(msg("&cCould not delete track: " + rootMessage(error)));
                    else if (Boolean.TRUE.equals(deleted)) sender.sendMessage(msg("&aDeleted &f" + track.title()
                            + " &7[TrackID: " + displayTrackId(track) + "]."));
                    else sender.sendMessage(msg("&eThat track was already deleted."));
                }));
    }

    private String displayTrackId(MusicTrack track) {
        try {
            return repository.getTrackCode(track.id()).orElse(track.id());
        } catch (SQLException error) {
            plugin.getLogger().warning("Could not read TrackID for " + track.id() + ": " + error.getMessage());
            return track.id();
        }
    }

    private void control(CommandSender sender, String action, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cPlayback control requires an in-game player."));
            return;
        }
        UUID actor = player.getUniqueId();
        boolean admin = isGuiAdmin(player);
        if (args.length > 2) {
            sender.sendMessage(msg("&eUsage: /music " + action + (action.equals("skip") ? "" : " [TrackID]")));
            return;
        }
        if (args.length == 2) {
            if (action.equals("skip")) {
                sender.sendMessage(msg("&eSkip does not accept a TrackID."));
                return;
            }
            if (!admin) {
                sender.sendMessage(msg("&cOnly admins may target another session by TrackID."));
                return;
            }
            MusicTrack selected = repository.getTrack(args[1])
                    .orElseThrow(() -> new IllegalArgumentException("TrackID not found."));
            List<PlaybackSession> targets = playback.findSessionsForTrack(selected.id());
            if (targets.isEmpty()) {
                sender.sendMessage(msg("&cThat TrackID is not currently playing in any session."));
                return;
            }
            if (action.equals("resume") && targets.stream().anyMatch(this::buildsResumeAudioTail)
                    && !admitHeavyRequest(sender, "transform",
                    "audio.processing.transform-cooldown-seconds", 3,
                    "audio.processing.global-transform-cooldown-seconds", 1,
                    "another audio preparation")) return;
            int applied = 0;
            for (PlaybackSession target : targets) {
                if (applyControlToSession(player, target, action, true)) applied++;
            }
            sender.sendMessage(msg(applied == 0
                    ? "&eNo matching session needed that action."
                    : "&a" + action + " applied to &f" + applied + " session(s) &afor TrackID &f"
                    + displayTrackId(selected) + "&a."));
            return;
        }

        if (action.equals("skip")) {
            if (!playback.control(actor, admin, action)) {
                sender.sendMessage(msg("&cNo session is available to skip."));
            } else sender.sendMessage(msg("&aPlayback skipped."));
            return;
        }
        PlaybackSession own = playback.findOwned(actor).orElse(null);
        if (own == null || own.current() == null) {
            sender.sendMessage(msg("&cYou do not own an active track. Admins must specify a TrackID to control someone else's track."));
            return;
        }
        if (action.equals("resume") && buildsResumeAudioTail(own)
                && !admitHeavyRequest(sender, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1,
                "another audio preparation")) return;
        if (!applyControlToSession(player, own, action, false)) {
            sender.sendMessage(msg("&eThat action is not available for your current track."));
        } else {
            sender.sendMessage(msg("&a" + action.substring(0, 1).toUpperCase(Locale.ROOT)
                    + action.substring(1) + " applied to your track."));
        }
    }

    private boolean buildsResumeAudioTail(PlaybackSession session) {
        return session != null && session.paused() && session.current() != null
                && session.current().isStreamed()
                && "resumable".equalsIgnoreCase(plugin.getConfig().getString("audio.playback-mode", "resumable"));
    }

    private boolean applyControlToSession(Player player, PlaybackSession session, String action, boolean admin) {
        if (session == null || !playback.isSessionActive(session)) return false;
        if (action.equals("resume")) {
            PlaybackEngine.ResumeStart result = playback.resumeSession(player.getUniqueId(), admin, session.id(),
                    notification -> {
                        if (player.isOnline()) player.sendMessage(msg(notification));
                    });
            switch (result) {
                case NOT_FOUND -> player.sendMessage(msg("&cThat session no longer exists or you cannot control it."));
                case RESUMED -> player.sendMessage(msg("&aPlayback resumed."));
                case PREPARING -> player.sendMessage(msg("&ePreparing the remaining audio; playback will continue when its pack is ready."));
                case BEDROCK_FALLBACK -> player.sendMessage(msg("&eA Bedrock player is in this session, so playback restarted from the beginning for compatibility."));
                case ALREADY_PREPARING -> player.sendMessage(msg("&eThe remaining audio is already being prepared."));
                case ALREADY_PLAYING -> player.sendMessage(msg("&eThat track is already playing."));
            }
            return result == PlaybackEngine.ResumeStart.RESUMED
                    || result == PlaybackEngine.ResumeStart.PREPARING
                    || result == PlaybackEngine.ResumeStart.BEDROCK_FALLBACK;
        }
        return playback.controlSession(player.getUniqueId(), admin, session.id(), action);
    }

    private void openGui(CommandSender sender, String[] args) throws SQLException {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&cThe music GUI can only be opened in-game."));
            return;
        }
        if (!player.hasPermission(plugin.getConfig().getString("permissions.use", "musicolide.use"))) {
            sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("Usage: /music gui [TrackID]");
        PlaybackSession selected = null;
        if (args.length == 2) {
            if (!isGuiAdmin(player)) {
                sender.sendMessage(msg("&cOnly admins may open a session by TrackID."));
                return;
            }
            MusicTrack track = repository.getTrack(args[1])
                    .orElseThrow(() -> new IllegalArgumentException("TrackID not found."));
            selected = playback.findSessionsForTrack(track.id()).stream()
                    .max(Comparator.comparingLong(PlaybackSession::startedAt)).orElse(null);
            if (selected == null) {
                sender.sendMessage(msg("&cThat TrackID is not currently playing."));
                return;
            }
        } else {
            selected = playback.findOwned(player.getUniqueId())
                    .orElseGet(() -> playback.sessionAffectingPlayer(player).orElse(null));
        }
        gui.open(player, selected);
    }

    /** Used by the GUI to render permission-aware states and recheck them on clicks. */
    public boolean isGuiAdmin(Player player) {
        return player != null && player.hasPermission(plugin.getConfig().getString(
                "permissions.admin", "musicolide.admin"));
    }

    public boolean canControlGui(Player player, PlaybackSession session) {
        return player != null && session != null
                && player.hasPermission(plugin.getConfig().getString("permissions.dj", "musicolide.dj"))
                && (isGuiAdmin(player) || session.isOwner(player.getUniqueId()));
    }

    public void guiControl(Player player, UUID sessionId, String action) {
        PlaybackSession session = playback.findSession(sessionId).orElse(null);
        if (session == null) {
            player.sendMessage(msg("&cThat playback session has ended."));
            return;
        }
        if (!canControlGui(player, session)) {
            player.sendMessage(msg("&cOnly the track executor or an admin may control this track."));
            return;
        }
        if (action.equals("resume") && buildsResumeAudioTail(session)
                && !admitHeavyRequest(player, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1,
                "another audio preparation")) return;
        if (!applyControlToSession(player, session, action, isGuiAdmin(player))) {
            player.sendMessage(msg("&eThat action is not available for this track."));
        }
    }

    public void guiToggleDiscord(Player player, UUID sessionId) {
        if (!admin(player)) return;
        PlaybackSession session = playback.findSession(sessionId).orElse(null);
        if (session == null || session.current() == null) {
            player.sendMessage(msg("&cNo active track is available to broadcast."));
            return;
        }
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) {
            player.sendMessage(msg("&cDiscord integration is disabled in the server configuration."));
            return;
        }
        Optional<Boolean> state = playback.toggleDiscordBroadcast(sessionId);
        if (state.isEmpty()) player.sendMessage(msg("&cCould not change Discord broadcast for this track."));
        else player.sendMessage(msg(state.get()
                ? "&aDiscord broadcasting enabled for this track only."
                : "&cDiscord broadcasting disabled for this track. The next track follows the server default."));
    }

    public void guiBroadcast(Player player, UUID sessionId) {
        if (!admin(player)) return;
        PlaybackEngine.PromotionResult result = playback.promoteSessionToServerWide(sessionId);
        switch (result) {
            case NOT_FOUND -> player.sendMessage(msg("&cThat playback session has ended."));
            case ALREADY_GLOBAL -> player.sendMessage(msg("&eThis track is already broadcasting to the entire server."));
            case PROMOTED -> {
                player.sendMessage(msg("&aThis track is now broadcasting server-wide. Its previous audience returns on the next track."));
                gui.open(player, playback.findSession(sessionId).orElse(null));
            }
        }
    }

    private void queue(CommandSender sender, String[] args) throws SQLException {
        if (!dj(sender)) return;
        if (args.length < 2) { sender.sendMessage(msg("&eUsage: /music queue <track-id>")); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only.")); return; }
        MusicTrack track = resolveTrack(args[1]).orElseThrow(() -> new IllegalArgumentException("Track not found."));
        boolean admin = sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"));
        if (!playback.enqueue(player.getUniqueId(), admin, track)) sender.sendMessage(msg("&cNo session found or queue is full."));
        else sender.sendMessage(msg("&aQueued: &f" + track.title()
                + " &7[TrackID: " + displayTrackId(track) + "]"));
    }

    private void nowPlaying(CommandSender sender) {
        if (playback.sessions().isEmpty()) { sender.sendMessage(msg("&eNo active music sessions.")); return; }
        for (PlaybackSession session : playback.sessions()) {
            MusicTrack track = session.current();
            String title = track == null ? "Unknown" : TextUtil.truncate(track.title(), 48);
            String artist = track == null ? "" : TextUtil.truncate(track.artist(), 28);
            sender.sendMessage(msg("&5" + (track == null ? "" : "[" + displayTrackId(track) + "] ")
                    + title + (artist.isBlank() ? "" : " &7by &f" + artist)
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
        if (playback.getPlayerVolume(player) == value) {
            sender.sendMessage(msg("&eYour music volume is already " + value + "%. No playback changes were made."));
            return;
        }
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
            Optional<PlaybackSession> active = playback.findControllableSession(actor, admin);
            if (active.isEmpty()) {
                sender.sendMessage(msg("&5Default playback speed: &f"
                        + String.format(Locale.ROOT, "%.2f", playback.playbackSpeedDefault(actor)) + "x"));
                sender.sendMessage(msg("&7Set with /music speed <0.5-2.0>; it applies to your future sessions."));
                return;
            }
            PlaybackSession session = active.get();
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
            case NO_SESSION -> throw new IllegalArgumentException("A player session is required to set a personal speed default.");
            case UNCHANGED -> sender.sendMessage(msg("&ePlayback speed is already set to &f"
                    + String.format(Locale.ROOT, "%.2f", value) + "x&e. No change was made."));
            case DEFAULT_SET -> sender.sendMessage(msg("&aDefault playback speed set to &f"
                    + String.format(Locale.ROOT, "%.2f", value) + "x&a. It will apply to your next session."));
            case APPLIED_NOW -> sender.sendMessage(msg("&aPlayback speed set to &f"
                    + String.format(Locale.ROOT, "%.2f", value) + "x&a."));
            case NEXT_TRACK -> sender.sendMessage(msg("&aPlayback speed set to &f"
                    + String.format(Locale.ROOT, "%.2f", value)
                    + "x&a. It will take effect with the next track."));
        }
    }

    private void mute(CommandSender sender, boolean muted, String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("Usage: /music " + (muted ? "mute" : "unmute") + " [player]");
        Player target;
        if (args.length == 2) {
            if (!admin(sender)) return;
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) throw new IllegalArgumentException("That player is not online.");
        } else {
            if (!(sender instanceof Player player)) { sender.sendMessage(msg("&cPlayers only. Use /music " + (muted ? "mute" : "unmute") + " <player> as an admin.")); return; }
            target = player;
        }
        boolean selfTarget = sender instanceof Player actorPlayer
                && actorPlayer.getUniqueId().equals(target.getUniqueId());
        if (playback.isPlayerMuted(target) == muted) {
            sender.sendMessage(msg("&e" + target.getName() + (muted
                    ? " is already muted. No playback change was made."
                    : " is already unmuted. No playback change was made.")));
            return;
        }
        if (!muted && "precise".equalsIgnoreCase(plugin.getConfig().getString(
                "playback.volume-change-mode", "next-track"))
                && !admitHeavyRequest(sender, "transform",
                "audio.processing.transform-cooldown-seconds", 3,
                "audio.processing.global-transform-cooldown-seconds", 1,
                "another audio preparation")) return;
        Player finalTarget = target;
        PlaybackEngine.VolumeChangeResult result = playback.setPlayerMuted(target, muted, notification -> {
            if (finalTarget.isOnline()) finalTarget.sendMessage(msg(notification));
        });
        sender.sendMessage(msg((muted ? "&eMuted " : "&aUnmuted ") + "&f" + target.getName() + "&a."));
        if (!selfTarget) target.sendMessage(msg(muted ? "&eAn administrator muted your Musicolide audio." : "&aAn administrator unmuted your Musicolide audio."));
        if (!muted && result == PlaybackEngine.VolumeChangeResult.RESYNCHRONIZING) {
            target.sendMessage(msg("&eYour current track is being reloaded at your saved volume."));
        } else if (!muted && result == PlaybackEngine.VolumeChangeResult.NEXT_TRACK) {
            target.sendMessage(msg("&eYour saved volume will apply from the next track."));
        }
    }

    public boolean onDeafenCommand(CommandSender sender, Command command, String label, String[] args) {
        mute(sender, true, new String[]{"mute"});
        return true;
    }

    public boolean onUndeafenCommand(CommandSender sender, Command command, String label, String[] args) {
        mute(sender, false, new String[]{"unmute"});
        return true;
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
                sender.sendMessage(msg("&aAdded &f" + track.title() + " &7[TrackID: "
                        + displayTrackId(track) + "] &ato playlist &f" + args[2] + "."));
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
                sender.sendMessage(msg("&aStarted playlist &f" + args[2] + " &7(" + tracks.size()
                        + " tracks; packs load gradually)."));
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
        MusicTrack track = resolveTrack(args[3])
                .orElseThrow(() -> new IllegalArgumentException("Local TrackID not found."));
        repository.addAlias(key, track.id());
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

    private boolean libraryManager(CommandSender sender) {
        String djPermission = plugin.getConfig().getString("permissions.dj", "musicolide.dj");
        String adminPermission = plugin.getConfig().getString("permissions.admin", "musicolide.admin");
        if (sender.hasPermission(djPermission) || sender.hasPermission(adminPermission)) return true;
        sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
        return false;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"))) return true;
        sender.sendMessage(msg(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
        return false;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(msg("&5&lMusicolide &8• &7Quick command guide"));
        sender.sendMessage(msg("&dPLAYBACK &8| &7Most-used commands"));
        sender.sendMessage(msg("&f/play <TrackID/title/query/URL> &8- &7play for yourself"));
        sender.sendMessage(msg("&f/play <track> @a &8- &7play for everyone (admin)"));
        sender.sendMessage(msg("&f/music gui &8- &7open the playback controls for your current/affected track"));
        sender.sendMessage(msg("&f/music pause [TrackID] &8• &fresume [TrackID] &8• &fstop [TrackID]"));
        sender.sendMessage(msg("&7TrackID targeting is admin-only; without an ID, controls affect only your own track."));
        sender.sendMessage(msg("&f/music skip &8• &fqueue &8- &7manage your current playback"));
        sender.sendMessage(msg("&f/music nowplaying &8• &fprogress &8• &fvolume <0-100> &8• &fspeed <0.5-2>"));
        sender.sendMessage(msg("&f/music mute &8• &funmute [player] &8• &f/deafen &8• &f/undeafen"));
        sender.sendMessage(msg("&f/music speed <0.5-2> &8- &7sets a personal default when no session is active"));
        sender.sendMessage(msg("&dLIBRARY &8| &7Search and manage audio"));
        sender.sendMessage(msg("&f/music list [query] &8- &7browse tracks and their short TrackIDs"));
        sender.sendMessage(msg("&f/music search <song/artist> &8- &7search local library and YouTube metadata"));
        sender.sendMessage(msg("&f/music select <number> &8- &7save the selected result permanently, then play"));
        sender.sendMessage(msg("&f/music page <number> &8- &7load the next result page"));
        sender.sendMessage(msg("&f/music save [TrackID] &8- &7save a temporary track, or your current/recent track when omitted"));
        sender.sendMessage(msg("&f/music delete <TrackID> &8- &7delete a track and its saved files (admin)"));
        sender.sendMessage(msg("&f/music clear &8- &7clear unused temporary tracks (admin)"));
        sender.sendMessage(msg("&f/music playlist create/add/list/play ... &8- &7manage playlists"));
        sender.sendMessage(msg("&dIMPORTS &8| &7Server library files"));
        sender.sendMessage(msg("&f/music import <file> [title] [id:name] &8- &7import from SavedMusic/"));
        sender.sendMessage(msg("&f/music import all &8- &7import supported files in SavedMusic/"));
        sender.sendMessage(msg("&f/music import-url <URL> rights [title] [id:name] &8- &7permanently import an authorized URL"));
        sender.sendMessage(msg("&7Only import audio you own or have explicit rights to redistribute."));
        sender.sendMessage(msg("&dPLAY OPTIONS &8| &7Compact, command-specific arguments"));
        sender.sendMessage(msg("&f target:@a &8• &f target:@p &8• &f target:@s &8• &f target:<player>"));
        sender.sendMessage(msg("&f circle:60 &8• &f square:50x50 &8• &f region:<id> &8• &f fade10"));
        sender.sendMessage(msg("&f mode:mono &8• &f mode:stereo &8• &f --center &8• &f id:custom_name"));
        sender.sendMessage(msg("&7Example: /play lo-fi radio circle:60 fade10 mode:stereo"));
        sender.sendMessage(msg("&7Stereo is default. Mono circle/square zones use configured fade unless fadeN overrides it."));
        sender.sendMessage(msg("&7Admins using a zone default to @a; ordinary players default to @s when self-zones are enabled."));
        sender.sendMessage(msg("&7The --center option is mono-only and opt-in; by default each listener uses their own playback-start position."));
        sender.sendMessage(msg("&dADMIN &8| &7Server configuration and diagnostics"));
        sender.sendMessage(msg("&f/music diagnostics &8• &f/music download &8• &f/music reload"));
        sender.sendMessage(msg("&f/music disable &8• &fenable &8• &funload &8- &7manage your own music packs"));
        sender.sendMessage(msg("&f/music discord enable|disable &8- &7toggle Discord broadcasting (admin)"));
        sender.sendMessage(msg("&f/music sync <seconds> &8• &f/music priority <number> &8• &f/music alias add <URL> <TrackID>"));
        sender.sendMessage(msg("&f/link &8• &f/unlink &8- &7connect or disconnect your Discord account"));
        sender.sendMessage(msg("&8TrackIDs use a00-f99 (600 active slots); delete/expiry frees a code. Existing long internal IDs remain accepted."));
        sender.sendMessage(msg("&8Legacy --option syntax is still accepted for backward compatibility."));
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
        if (args.length == 1) return partial(List.of("help", "play", "pause", "resume", "skip", "stop", "gui", "queue",
                "nowplaying", "progress", "volume", "speed", "mute", "unmute", "seek", "sync",
                "list", "search", "select", "page", "save", "delete", "playlist", "import", "import-url",
                "download", "clear", "disable", "enable", "unload", "discord",
                "link", "unlink", "diagnostics", "alias", "priority", "reload"), args[0]);
        if (args.length >= 3 && args[0].equalsIgnoreCase("play")) {
            String prefix = args[args.length - 1];
            String previous = args.length >= 2 ? args[args.length - 2].toLowerCase(Locale.ROOT) : "";
            if (previous.equals("--target")) {
                List<String> targets = new ArrayList<>(List.of("@s", "@p"));
                boolean canTargetAll = sender.hasPermission(plugin.getConfig().getString(
                        "permissions.admin", "musicolide.admin"))
                        || plugin.getConfig().getBoolean("permissions.allow-player-all-target", false);
                if (canTargetAll) targets.add("@a");
                Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(targets::add);
                return partial(targets, prefix);
            }
            if (previous.equals("--mode")) return partial(List.of("stereo", "mono"), prefix);
            if (previous.equals("--circle") || previous.equals("--fade")) return partial(List.of("0", "10", "20", "40", "60", "100"), prefix);
            if (previous.equals("--square")) return partial(List.of("25x25", "50x50", "100x100"), prefix);
            if (previous.equals("--region")) return List.of();
            if (prefix.startsWith("--")) {
                return partial(List.of("--target", "--id", "--circle", "--square", "--region", "--fade", "--mode", "--center"), prefix);
            }
            boolean canTargetAll = sender.hasPermission(plugin.getConfig().getString(
                    "permissions.admin", "musicolide.admin"))
                    || plugin.getConfig().getBoolean("permissions.allow-player-all-target", false);
            List<String> options = new ArrayList<>(List.of(
                    "target:@s", "target:@p", "id:", "circle:", "square:",
                    "region:", "fade10", "fade0", "mode:mono", "mode:stereo", "--center",
                    "@s", "@p", "--target", "--id", "--circle", "--square", "--region", "--fade", "--mode", "--center"));
            if (canTargetAll) {
                options.add("target:@a");
                options.add("@a");
            }
            Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).forEach(options::add);
            return partial(options, prefix);
        }
        if (args.length == 2 && List.of("pause", "resume", "stop").contains(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"))) {
            try {
                return partial(repository.allTracks(600).stream().map(this::displayTrackId).toList(), args[1]);
            } catch (SQLException ignored) {
                return List.of();
            }
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
        if (args.length == 2 && (args[0].equalsIgnoreCase("save")
                || args[0].equalsIgnoreCase("delete"))) {
            try {
                List<String> codes = repository.allTracks(100).stream()
                        .map(this::displayTrackId).toList();
                return partial(codes, args[1]);
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed"))
            return partial(List.of("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0"), args[1]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("unmute"))) {
            if (!sender.hasPermission(plugin.getConfig().getString("permissions.admin", "musicolide.admin"))) return List.of();
            return partial(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("discord"))
            return partial(List.of("enable", "disable"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("volume"))
            return partial(List.of("0", "25", "50", "75", "100"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("import"))
            return partial(List.of("all"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("playlist"))
            return partial(List.of("create", "add", "list", "play"), args[1]);
        if (args.length == 4 && args[0].equalsIgnoreCase("playlist")
                && args[1].equalsIgnoreCase("add")) {
            try {
                return partial(repository.allTracks(100).stream().map(this::displayTrackId).toList(), args[3]);
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("play")) {
            List<String> targets = new ArrayList<>();
            targets.add("@a");
            if (sender instanceof Player) {
                targets.add("@p");
                targets.add("@s");
            }
            Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(targets::add);
            targets.addAll(List.of("target:@a", "target:@p", "target:@s", "id:", "circle:", "square:", "region:", "fade10", "mode:mono", "mode:stereo"));
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
        private final boolean permanentSelection;
        private List<YtDlpDownloader.SearchResult> remoteResults = List.of();
        private List<SearchEntry> pageResults = List.of();
        private int currentPage;
        private boolean loading;
        private boolean selecting;
        private boolean remoteExhausted;
        private boolean localExhausted;

        private PendingSearch(String query, PlayRequest request, long createdAtMillis,
                              boolean permanentSelection) {
            this.query = query;
            this.request = request;
            this.createdAtMillis = createdAtMillis;
            this.permanentSelection = permanentSelection;
        }
    }
    private record SearchEntry(MusicTrack localTrack, YtDlpDownloader.SearchResult remoteResult) {
        private static SearchEntry local(MusicTrack track) { return new SearchEntry(track, null); }
        private static SearchEntry remote(YtDlpDownloader.SearchResult result) { return new SearchEntry(null, result); }
    }

}
