package io.github.escolide.musicolide.discord;

import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.link.DiscordAccountLinkManager;
import io.github.escolide.musicolide.playback.DiscordPlaybackSnapshot;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.util.TextUtil;
import io.github.escolide.musicolide.util.RequestCooldowns;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Discord command and DM-link adapter. Every game command is dispatched on the
 * Bukkit thread as the linked, currently-online Minecraft Player.
 */
public final class DiscordCommandService extends ListenerAdapter {
    private final JavaPlugin plugin;
    private final PlaybackEngine playback;
    private final MusicCommand musicCommand;
    private final DiscordAccountLinkManager accountLinks;
    private final Map<String, AttemptWindow> otpAttempts = new HashMap<>();
    private final RequestCooldowns autocompleteCooldowns = new RequestCooldowns();
    private final Map<String, Long> slashCommandLastAttempt = new HashMap<>();
    private long slashCommandGlobalLastAttemptNanos;
    private long otpGlobalWindowStartedAtNanos;
    private int otpGlobalAttempts;

    public DiscordCommandService(JavaPlugin plugin, PlaybackEngine playback,
                                 MusicCommand musicCommand,
                                 DiscordAccountLinkManager accountLinks) {
        this.plugin = plugin;
        this.playback = playback;
        this.musicCommand = musicCommand;
        this.accountLinks = accountLinks;
    }

    @Override
    public void onReady(net.dv8tion.jda.api.events.session.ReadyEvent event) {
        if (!plugin.getConfig().getBoolean("discord.commands.enabled", true)) return;
        List<CommandData> commands = List.of(
                Commands.slash("info", "Show the current Musicolide track, timestamp and server information"),
                Commands.slash("help", "Explain Musicolide Discord commands and playback options"),
                Commands.slash("play", "Search for and play a track from the server's authorized audio sources")
                        .addOption(OptionType.STRING, "query", "Song, artist or NCS search", true, true)
                        .addOption(OptionType.STRING, "target", "Optional target: @s, @p, @a, or an online player name", false)
                        .addOption(OptionType.STRING, "id", "Optional custom track ID/name for a new import", false)
                        .addOption(OptionType.STRING, "zone", "Optional zone: circle 50, square 50x50, or region <id>", false)
                        .addOption(OptionType.STRING, "mode", "Audio mode: stereo (default) or mono", false)
                        .addOption(OptionType.NUMBER, "fade", "Optional spatial fade distance", false),
                Commands.slash("music", "Run an existing Musicolide game command as your linked Minecraft account")
                        .addOption(OptionType.STRING, "command", "For example: pause, queue <track-id>, playlist list", true)
        );

        String guildId = plugin.getConfig().getString("discord.commands.guild-id", "").trim();
        if (!guildId.isBlank()) {
            var guild = event.getJDA().getGuildById(guildId);
            if (guild == null) {
                plugin.getLogger().warning("Discord command-guild-id is not visible to the bot; slash commands were not registered.");
                return;
            }
            CommandListUpdateAction update = guild.updateCommands().addCommands(commands);
            update.queue(ignored -> plugin.getLogger().info("Registered Musicolide Discord commands in guild " + guildId + "."),
                    error -> plugin.getLogger().log(Level.WARNING, "Could not register Musicolide Discord guild commands.", error));
        } else {
            event.getJDA().updateCommands().addCommands(commands).queue(
                    ignored -> plugin.getLogger().info("Registered global Musicolide Discord commands."),
                    error -> plugin.getLogger().log(Level.WARNING, "Could not register Musicolide Discord commands.", error));
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!plugin.getConfig().getBoolean("discord.commands.enabled", true)) {
            reply(event, "Discord commands are disabled on this server.");
            return;
        }
        if (!isAllowedGuild(event)) {
            reply(event, "Musicolide Discord commands are restricted to the configured server.");
            return;
        }
        if (!allowSlashCommand(event.getUser().getId())) {
            reply(event, "Please wait a moment before using another Musicolide command.");
            return;
        }
        switch (event.getName()) {
            case "info" -> showInfo(event);
            case "help" -> showHelp(event);
            case "play" -> {
                try { runLinkedCommand(event, buildPlayCommand(event)); }
                catch (IllegalArgumentException error) { reply(event, error.getMessage()); }
            }
            case "music" -> runLinkedCommand(event, event.getOption("command").getAsString());
            default -> reply(event, "Unknown Musicolide command.");
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("play") || !event.getFocusedOption().getName().equals("query")
                || !plugin.getConfig().getBoolean("discord.commands.enabled", true)
                || !isAllowedGuild(event)) {
            event.replyChoices(List.of()).queue();
            return;
        }
        try {
            if (accountLinks.findByDiscord(event.getUser().getId()).isEmpty()) {
                event.replyChoices(List.of()).queue();
                return;
            }
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Discord account-link lookup failed during autocomplete.", error);
            event.replyChoices(List.of()).queue();
            return;
        }

        String query = event.getFocusedOption().getValue();
        long autocompleteCooldown = Math.max(0L,
                plugin.getConfig().getLong("discord.commands.autocomplete-cooldown-seconds", 5L));
        long autocompleteGlobalCooldown = Math.max(0L,
                plugin.getConfig().getLong("discord.commands.autocomplete-global-cooldown-seconds", 1L));
        boolean allowRemoteSearch = autocompleteCooldowns.tryAcquire(
                "discord-autocomplete", event.getUser().getId(),
                autocompleteCooldown, autocompleteGlobalCooldown) == 0L;
        musicCommand.discordPlaySuggestions(query, allowRemoteSearch)
                .completeOnTimeout(List.of(), 2200, TimeUnit.MILLISECONDS)
                .exceptionally(error -> List.of())
                .thenAccept(suggestions -> {
                    List<Command.Choice> choices = new ArrayList<>();
                    for (MusicCommand.DiscordPlaySuggestion suggestion : suggestions) {
                        if (suggestion.value() == null || suggestion.value().length() > 100) continue;
                        choices.add(new Command.Choice(TextUtil.truncate(suggestion.label(), 100), suggestion.value()));
                        if (choices.size() == 25) break;
                    }
                    event.replyChoices(choices).queue(
                            ignored -> {},
                            error -> plugin.getLogger().log(Level.FINE, "Discord play autocomplete response expired.", error));
                });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromType(ChannelType.PRIVATE)
                || !plugin.getConfig().getBoolean("discord.commands.enabled", true)
                || accountLinks == null) return;
        String code = event.getMessage().getContentRaw().trim();
        if (!code.matches("[0-9]{8}")) return;

        String discordUserId = event.getAuthor().getId();
        // Silently discard excess attempts. Replying to every blocked message
        // would itself let an attacker turn the bot into a Discord DM spammer.
        if (!allowOtpAttempt(discordUserId)) return;
        try {
            DiscordAccountLinkManager.Completion completion =
                    accountLinks.completeLink(discordUserId, code);
            switch (completion.status()) {
                case LINKED -> {
                    synchronized (otpAttempts) {
                        otpAttempts.remove(discordUserId);
                    }
                    event.getChannel().sendMessageEmbeds(embed()
                            .setTitle("Minecraft account linked")
                            .setDescription("Your Discord account is now linked to **"
                                    + safe(completion.minecraftName()) + "**.")
                            .build()).queue();
                }
                case DISCORD_ALREADY_LINKED -> sendDmNotice(event,
                        "This Discord account is already linked to a Minecraft account. Unlink it in Minecraft before linking another.");
                case MINECRAFT_ALREADY_LINKED -> sendDmNotice(event,
                        "That Minecraft account is already linked to a Discord account. Use /unlink in Minecraft first.");
                case EXPIRED, INVALID_CODE -> sendDmNotice(event,
                        "That code is invalid, expired, or has already been used. Generate a new one with /link in Minecraft.");
            }
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Could not complete a Discord account link.", error);
            sendDmNotice(event, "The account link could not be saved. Please ask the server administrator to check the plugin logs.");
        }
    }

    private boolean allowOtpAttempt(String discordUserId) {
        long now = System.nanoTime();
        long oneMinute = TimeUnit.MINUTES.toNanos(1);
        int perUserLimit = Math.max(1, Math.min(20,
                plugin.getConfig().getInt("discord.commands.otp-user-attempts-per-minute", 5)));
        int globalLimit = Math.max(1, Math.min(500,
                plugin.getConfig().getInt("discord.commands.otp-global-attempts-per-minute", 30)));
        synchronized (otpAttempts) {
            if (otpGlobalWindowStartedAtNanos == 0L
                    || now - otpGlobalWindowStartedAtNanos >= oneMinute) {
                otpGlobalWindowStartedAtNanos = now;
                otpGlobalAttempts = 0;
            }
            if (otpGlobalAttempts >= globalLimit) return false;

            AttemptWindow window = otpAttempts.get(discordUserId);
            if (window == null || now - window.startedAtNanos >= oneMinute) {
                otpAttempts.put(discordUserId, new AttemptWindow(now, 1));
            } else {
                if (window.attempts >= perUserLimit) return false;
                otpAttempts.put(discordUserId, new AttemptWindow(window.startedAtNanos, window.attempts + 1));
            }
            otpGlobalAttempts++;
            if (otpAttempts.size() > 10_000) {
                otpAttempts.entrySet().removeIf(entry ->
                        now - entry.getValue().startedAtNanos >= oneMinute);
            }
            return true;
        }
    }

    /**
     * Resolves the bot's trusted Musicolide guild. Explicit allowed-guild-ids
     * take precedence; otherwise the configured slash-command guild or the
     * guild containing the configured broadcast voice channel is trusted.
     * Global command registration does not make every guild an authority.
     */
    private boolean isAllowedGuild(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) return false;
        return isGuildAllowed(event.getGuild().getId(), event.getJDA());
    }

    private boolean isAllowedGuild(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null) return false;
        return isGuildAllowed(event.getGuild().getId(), event.getJDA());
    }

    private boolean isGuildAllowed(String guildId, JDA jda) {
        List<String> configured = plugin.getConfig().getStringList("discord.commands.allowed-guild-ids")
                .stream().filter(value -> value != null && value.matches("[0-9]{5,25}")).toList();
        if (!configured.isEmpty()) return configured.contains(guildId);

        String commandGuild = plugin.getConfig().getString("discord.commands.guild-id", "").trim();
        if (!commandGuild.isBlank()) return commandGuild.equals(guildId);

        String voiceChannelId = plugin.getConfig().getString("discord.voice-channel-id", "").trim();
        if (!voiceChannelId.isBlank()) {
            var voiceChannel = jda.getVoiceChannelById(voiceChannelId);
            return voiceChannel != null && voiceChannel.getGuild().getId().equals(guildId);
        }
        return false;
    }

    private boolean allowSlashCommand(String discordUserId) {
        long now = System.nanoTime();
        long userCooldown = TimeUnit.MILLISECONDS.toNanos(Math.max(0L,
                Math.min(60_000L, plugin.getConfig().getLong(
                        "discord.commands.user-cooldown-ms", 1000L))));
        long globalCooldown = TimeUnit.MILLISECONDS.toNanos(Math.max(0L,
                Math.min(10_000L, plugin.getConfig().getLong(
                        "discord.commands.global-cooldown-ms", 100L))));
        synchronized (slashCommandLastAttempt) {
            Long previous = slashCommandLastAttempt.get(discordUserId);
            if (previous != null && now - previous < userCooldown) return false;
            if (globalCooldown > 0 && slashCommandGlobalLastAttemptNanos != 0L
                    && now - slashCommandGlobalLastAttemptNanos < globalCooldown) return false;
            slashCommandLastAttempt.put(discordUserId, now);
            if (globalCooldown > 0) slashCommandGlobalLastAttemptNanos = now;
            if (slashCommandLastAttempt.size() > 10_000) {
                slashCommandLastAttempt.entrySet().removeIf(entry -> now - entry.getValue()
                        >= TimeUnit.MINUTES.toNanos(10));
            }
            return true;
        }
    }

    private void runLinkedCommand(SlashCommandInteractionEvent event, String commandLine) {
        final DiscordAccountLinkManager.LinkedAccount link;
        try {
            Optional<DiscordAccountLinkManager.LinkedAccount> found =
                    accountLinks.findByDiscord(event.getUser().getId());
            if (found.isEmpty()) {
                reply(event, "Link your account first: use **/link** in Minecraft, then DM the generated code to this bot.");
                return;
            }
            link = found.get();
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Could not look up a linked Discord account.", error);
            reply(event, "Account linking is temporarily unavailable.");
            return;
        }

        boolean discordAdmin = isDiscordAdministrator(event.getMember());
        boolean discordAdmin = isDiscordAdministrator(event.getMember());
        event.deferReply(isEphemeral(event.getName())).queue(hook -> {
            List<String> lines = new ArrayList<>();
            Object linesLock = new Object();
            AtomicBoolean first = new AtomicBoolean(true);
            AtomicBoolean flushScheduled = new AtomicBoolean(false);
            Consumer<String> output = message -> {
                if (message == null || message.isBlank()) return;
                String description;
                synchronized (linesLock) {
                    lines.add(safe(stripLegacyColors(message)));
                    while (lines.size() > 24) lines.remove(0);
                    description = String.join("\n", lines);
                }
                if (first.compareAndSet(true, false)) {
                    hook.editOriginalEmbeds(embed().setDescription(truncate(description, 3900)).build()).queue(
                            ignored -> {}, error -> plugin.getLogger().log(Level.FINE,
                                    "Discord command response update failed.", error));
                    return;
                }
                // Bulk imports and other multi-message operations can emit many
                // callbacks. Coalesce those into at most one Discord edit per second.
                if (flushScheduled.compareAndSet(false, true)) {
                    try {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            flushScheduled.set(false);
                            String latest;
                            synchronized (linesLock) { latest = String.join("\n", lines); }
                            hook.editOriginalEmbeds(embed().setDescription(truncate(latest, 3900)).build()).queue(
                                    ignored -> {}, error -> plugin.getLogger().log(Level.FINE,
                                            "Discord command response update failed.", error));
                        }, 20L);
                    } catch (IllegalStateException shutdown) {
                        flushScheduled.set(false);
                    }
                }
            };

            // Bukkit player lookup and all permission checks must remain on the
            // server thread; JDA event callbacks run asynchronously.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(link.minecraftUuid());
                if (player == null || !player.isOnline()) {
                    output.accept("Your linked Minecraft account (**" + safe(link.minecraftName())
                            + "**) must be online to run game commands through Discord.");
                    return;
                }
                boolean elevateAdmin = authorizeAdmin(player,
                        isDiscordAdministrator(event.getMember()) && isAllowedGuild(event));
                try {
                    musicCommand.executeDiscordCommand(player, commandLine, elevateAdmin, output);
                    if (lines.isEmpty()) output.accept("Command processed.");
                } catch (RuntimeException error) {
                    output.accept("Command could not be processed: " + safe(error.getMessage()));
                }
            });
        }, error -> plugin.getLogger().log(Level.FINE,
                "Discord command interaction could not be deferred.", error));
    }

    private boolean isDiscordAdministrator(Member member) {
        if (member == null || member.getGuild() == null) return false;
        // Only roles/administrator status in the explicitly trusted Musicolide
        // guild can grant Musicolide admin access.
        String trustedGuildId = member.getGuild().getId();
        if (!isGuildAllowed(trustedGuildId, member.getJDA())) return false;
        if (member.hasPermission(Permission.ADMINISTRATOR)) return true;
        Set<String> configured = Set.copyOf(plugin.getConfig().getStringList(
                "discord.commands.admin-role-ids"));
        if (configured.isEmpty()) return false;
        for (Role role : member.getRoles()) {
            if (configured.contains(role.getId())) return true;
        }
        return false;
    }

    private boolean authorizeAdmin(Player player, boolean discordAdmin) {
        String minecraftPermission = plugin.getConfig().getString(
                "permissions.admin", "musicolide.admin");
        boolean minecraftOp = player.isOp() || player.hasPermission(minecraftPermission);
        String mode = plugin.getConfig().getString(
                "discord.commands.admin-authorization", "minecraft-op").trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "discord-administrator" -> discordAdmin;
            case "either" -> minecraftOp || discordAdmin;
            case "both" -> minecraftOp && discordAdmin;
            default -> minecraftOp;
        };
    }

    private void showInfo(SlashCommandInteractionEvent event) {
        if (!plugin.getConfig().getBoolean("discord.info.enabled", true)) {
            reply(event, "The /info command is disabled by the server administrator.");
            return;
        }
        EmbedBuilder builder = embed()
                .setTitle(plugin.getConfig().getString("discord.info.server-name", "Minecraft Server")
                        + " — Musicolide status");
        Optional<DiscordPlaybackSnapshot> selected = playback.discordBroadcastSnapshot();
        DiscordPlaybackSnapshot snapshot = selected.filter(value -> value.track() != null).orElse(null);
        String state = "No eligible playback";
        String description;
        if (snapshot == null) {
            description = "No eligible Musicolide session is currently selected for this Discord channel. "
                    + "The server may still have local or spatial playback outside the configured broadcast scope.";
        } else {
            state = snapshot.paused() ? "Paused"
                    : snapshot.preparing() ? "Preparing to resume"
                    : snapshot.isAudible() ? "Playing" : "Waiting to start";
            description = "**" + state + "**";
            builder.addField("Track", truncate(safe(snapshot.track().title()), 1024), false);
            if (snapshot.track().artist() != null && !snapshot.track().artist().isBlank()) {
                builder.addField("Artist", truncate(safe(snapshot.track().artist()), 1024), true);
            }
            builder.addField("Position", formatTime(snapshot.elapsedMs()) + " / "
                    + formatTime(snapshot.track().durationMs()), true);
            if (plugin.getConfig().getBoolean("discord.info.show-thumbnail", true)) {
                musicCommand.discordThumbnailUrl(snapshot.track().id()).ifPresent(builder::setThumbnail);
            }
        }
        String customMessage = plugin.getConfig().getString("discord.info.custom-message", "").trim();
        builder.setDescription(truncate(description, 4096));

        if (plugin.getConfig().getBoolean("discord.info.show-server-address", false)) {
            String host = plugin.getConfig().getString("discord.info.server-ip", "").trim();
            int port = plugin.getConfig().getInt("discord.info.server-port", 25565);
            if (!host.isBlank() && port > 0 && port <= 65535) {
                builder.addField("Minecraft server", truncate(safe(host) + ":" + port, 1024), false);
            } else {
                builder.addField("Minecraft server", "Server address is not configured.", false);
            }
        }
        if (!customMessage.isBlank()) {
            builder.addField("Additional message",
                    truncate(safe(expandInfoMessage(customMessage, snapshot, state)), 1024), false);
        }
        event.replyEmbeds(builder.build()).setEphemeral(isEphemeral("info")).queue();
    }

    private String expandInfoMessage(String message, DiscordPlaybackSnapshot snapshot, String state) {
        String title = snapshot == null ? "No track playing" : snapshot.track().title();
        String artist = snapshot == null || snapshot.track().artist() == null ? "" : snapshot.track().artist();
        String position = snapshot == null ? "—" : formatTime(snapshot.elapsedMs());
        String duration = snapshot == null ? "—" : formatTime(snapshot.track().durationMs());
        return message.replace("{state}", state).replace("{track}", title).replace("{title}", title)
                .replace("{artist}", artist).replace("{position}", position).replace("{duration}", duration)
                .replace("{server}", plugin.getConfig().getString("discord.info.server-name", "Minecraft Server"));
    }

    private void showHelp(SlashCommandInteractionEvent event) {
        EmbedBuilder builder = embed().setTitle("Musicolide Discord help")
                .setDescription("Use /link in Minecraft and DM the one-time code to this bot before running player commands.");
        builder.addField("/play query", "Searches saved server tracks first, then YouTube. Choose a result using /music command with select <number>.", false);
        builder.addField("Optional /play options", "target: @s, @p, @a or an online player. Other-player targets and zones require Minecraft admin permission. id: custom ID for a new import. zone: circle 50, square 50x50, or region <id>. fade: optional fade distance. mode: stereo (default) or mono, using one-channel audio. Circle/square mono zones fade over 10 blocks by default; the fade distance is configurable.", false);
        builder.addField("/music command", "Runs an existing Musicolide command as your linked Minecraft player, such as pause, resume, queue <id>, page 2, select 1, or playlist list.", false);
        builder.addField("/info", "Shows current broadcast playback, optional server details and the YouTube thumbnail when available.", false);
        builder.addField("Search pages", "After a search, request page 2, then page 3, and so on. Pages contain six results and must be opened in order.", false);
        event.replyEmbeds(builder.build()).setEphemeral(isEphemeral("help")).queue();
    }

    private String buildPlayCommand(SlashCommandInteractionEvent event) {
        String query = event.getOption("query").getAsString();
        StringBuilder command = new StringBuilder("play ").append(quoteCommandArgument(query));
        String target = option(event, "target");
        String customId = option(event, "id");
        String zone = option(event, "zone");
        String mode = option(event, "mode");
        Double fade = event.getOption("fade") == null ? null : event.getOption("fade").getAsDouble();
        if (target != null && !target.isBlank()) command.append(" --target ").append(quoteCommandArgument(target.trim()));
        if (customId != null && !customId.isBlank()) command.append(" --id ").append(quoteCommandArgument(customId.trim()));
        if (zone != null && !zone.isBlank()) command.append(' ').append(parseZoneOption(zone));
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toLowerCase(Locale.ROOT);
            if (!normalized.equals("mono") && !normalized.equals("stereo")) {
                throw new IllegalArgumentException("Audio mode must be mono or stereo.");
            }
            command.append(" --mode ").append(normalized);
        }
        if (fade != null) {
            if (!Double.isFinite(fade) || fade < 0) throw new IllegalArgumentException("Fade distance must be a finite, non-negative number.");
            command.append(" --fade ").append(fade);
        }
        return command.toString();
    }

    private String parseZoneOption(String value) {
        String[] parts = value.trim().split("\\s+", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("Zone must look like circle 50, square 50x50, or region <id>.");
        }
        return switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "circle" -> "--circle " + quoteCommandArgument(parts[1]);
            case "square" -> "--square " + quoteCommandArgument(parts[1]);
            case "region" -> "--region " + quoteCommandArgument(parts[1]);
            default -> throw new IllegalArgumentException("Supported zones are circle, square, and region.");
        };
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        return event.getOption(name) == null ? null : event.getOption(name).getAsString();
    }

    private static String quoteCommandArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private boolean isEphemeral(String commandName) {
        String visibility = plugin.getConfig().getString("discord.commands.visibility."
                + commandName.toLowerCase(Locale.ROOT), "requester");
        return !visibility.equalsIgnoreCase("everyone") && !visibility.equalsIgnoreCase("public");
    }

    private void reply(SlashCommandInteractionEvent event, String description) {
        event.replyEmbeds(embed().setDescription(safe(description)).build())
                .setEphemeral(isEphemeral(event.getName())).queue();
    }

    private void sendDmNotice(MessageReceivedEvent event, String description) {
        event.getChannel().sendMessageEmbeds(embed().setDescription(safe(description)).build()).queue(
                ignored -> {}, error -> plugin.getLogger().log(Level.FINE, "Could not send Discord account-link response.", error));
    }

    private EmbedBuilder embed() {
        String raw = plugin.getConfig().getString("discord.embed-color", "#9B59B6").trim();
        Color color = new Color(155, 89, 182);
        try {
            String hex = raw.startsWith("#") ? raw.substring(1) : raw;
            if (hex.matches("[0-9a-fA-F]{6}")) color = new Color(Integer.parseInt(hex, 16));
        } catch (RuntimeException ignored) {
            // Invalid colour values safely fall back to the default purple.
        }
        return new EmbedBuilder().setColor(color).setFooter("Musicolide");
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("@", "@\u200B");
    }

    private static String stripLegacyColors(String value) {
        return value == null ? "" : value.replaceAll("(?i)&[0-9a-fk-or]", "");
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private record AttemptWindow(long startedAtNanos, int attempts) {}

    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }
}
