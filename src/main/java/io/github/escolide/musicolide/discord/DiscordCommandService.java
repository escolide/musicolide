package io.github.escolide.musicolide.discord;

import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.link.DiscordAccountLinkManager;
import io.github.escolide.musicolide.playback.DiscordPlaybackSnapshot;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.util.TextUtil;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
                Commands.slash("play", "Search for and play a track from the server's authorized audio sources")
                        .addOption(OptionType.STRING, "query", "Song, artist or NCS search", true, true),
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
        switch (event.getName()) {
            case "info" -> showInfo(event);
            case "play" -> runLinkedCommand(event, "play " + event.getOption("query").getAsString());
            case "music" -> runLinkedCommand(event, event.getOption("command").getAsString());
            default -> reply(event, "Unknown Musicolide command.");
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("play") || !event.getFocusedOption().getName().equals("query")
                || !plugin.getConfig().getBoolean("discord.commands.enabled", true)) {
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
        musicCommand.discordPlaySuggestions(query)
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
        if (!allowOtpAttempt(discordUserId)) {
            sendDmNotice(event, "Too many code attempts. Wait one minute before trying again.");
            return;
        }
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
        synchronized (otpAttempts) {
            AttemptWindow window = otpAttempts.get(discordUserId);
            if (window == null || now - window.startedAtNanos >= TimeUnit.MINUTES.toNanos(1)) {
                otpAttempts.put(discordUserId, new AttemptWindow(now, 1));
                if (otpAttempts.size() > 10_000) {
                    otpAttempts.entrySet().removeIf(entry ->
                            now - entry.getValue().startedAtNanos >= TimeUnit.MINUTES.toNanos(1));
                }
                return true;
            }
            if (window.attempts >= 5) return false;
            otpAttempts.put(discordUserId, new AttemptWindow(window.startedAtNanos, window.attempts + 1));
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
        event.deferReply(true).queue(hook -> {
            List<String> lines = new CopyOnWriteArrayList<>();
            AtomicBoolean first = new AtomicBoolean(true);
            Consumer<String> output = message -> {
                if (message == null || message.isBlank()) return;
                lines.add(safe(stripLegacyColors(message)));
                while (lines.size() > 12) lines.remove(0);
                String description = String.join("\n", lines);
                if (first.getAndSet(false)) {
                    hook.editOriginalEmbeds(embed().setDescription(truncate(description, 3900)).build()).queue();
                } else {
                    hook.editOriginalEmbeds(embed().setDescription(truncate(description, 3900)).build()).queue(
                            ignored -> {}, error -> plugin.getLogger().log(Level.FINE,
                                    "Discord command response update failed.", error));
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
                boolean elevateAdmin = authorizeAdmin(player, discordAdmin);
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
        if (member == null) return false;
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
        if (selected.isEmpty() || selected.get().track() == null) {
            builder.setDescription("No eligible Musicolide session is currently selected for this Discord channel. "
                    + "The server may still have local or spatial playback outside the configured broadcast scope.");
        } else {
            DiscordPlaybackSnapshot snapshot = selected.get();
            String state = snapshot.paused() ? "Paused"
                    : snapshot.preparing() ? "Preparing to resume"
                    : snapshot.isAudible() ? "Playing" : "Waiting to start";
            builder.setDescription("**" + state + "**");
            builder.addField("Track", truncate(safe(snapshot.track().title()), 1024), false);
            if (snapshot.track().artist() != null && !snapshot.track().artist().isBlank()) {
                builder.addField("Artist", truncate(safe(snapshot.track().artist()), 1024), true);
            }
            builder.addField("Position", formatTime(snapshot.elapsedMs()) + " / "
                    + formatTime(snapshot.track().durationMs()), true);
        }

        if (plugin.getConfig().getBoolean("discord.info.show-server-address", false)) {
            String host = plugin.getConfig().getString("discord.info.server-ip", "").trim();
            int port = plugin.getConfig().getInt("discord.info.server-port", 25565);
            if (!host.isBlank() && port > 0 && port <= 65535) {
                builder.addField("Minecraft server", truncate(safe(host) + ":" + port, 1024), false);
            } else {
                builder.addField("Minecraft server", "Server address is not configured.", false);
            }
        }
        event.replyEmbeds(builder.build()).setEphemeral(true).queue();
    }

    private void reply(SlashCommandInteractionEvent event, String description) {
        event.replyEmbeds(embed().setDescription(safe(description)).build()).setEphemeral(true).queue();
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
