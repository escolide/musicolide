package io.github.escolide.musicolide.gui;

import io.github.escolide.musicolide.command.MusicCommand;
import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;
import io.github.escolide.musicolide.playback.PlaybackEngine;
import io.github.escolide.musicolide.playback.PlaybackSession;
import io.github.escolide.musicolide.storage.TrackRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class MusicGui implements Listener {
    private static final int SIZE = 54;
    private static final int PAPER_SLOT = 22;
    private static final int DISCORD_SLOT = 10;
    private static final int BROADCAST_SLOT = 16;
    private static final int INFO_SLOT = 37;
    private static final int PAUSE_SLOT = 39;
    private static final int RESUME_SLOT = 41;
    private static final int STOP_SLOT = 43;
    private static final int BACK_SLOT = 49;

    private final JavaPlugin plugin;
    private final TrackRepository repository;
    private final PlaybackEngine playback;
    private final MusicCommand commands;

    public MusicGui(JavaPlugin plugin, TrackRepository repository, PlaybackEngine playback,
                    MusicCommand commands) {
        this.plugin = plugin;
        this.repository = repository;
        this.playback = playback;
        this.commands = commands;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                refreshOpenInventories();
            } catch (RuntimeException error) {
                // Never let one transient server/inventory exception cancel future GUI refreshes.
                plugin.getLogger().log(Level.WARNING, "Musicolide GUI refresh tick failed; it will retry next tick.", error);
            }
        }, 20L, 20L);
    }

    /** Opens the same session view for the executor and every online affected listener. */
    public void open(Player executor, PlaybackSession session) {
        Set<UUID> viewers = new LinkedHashSet<>();
        viewers.add(executor.getUniqueId());
        if (session != null) playback.affectedPlayers(session).forEach(player -> viewers.add(player.getUniqueId()));
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline() && !playback.isIgnoredBedrockPlayer(viewerId)) {
                openMain(viewer, session == null ? null : session.id());
            }
        }
    }

    private void openMain(Player player, UUID sessionId) {
        GuiHolder holder = new GuiHolder(sessionId, false);
        holder.viewerId = player.getUniqueId();
        holder.inventory = Bukkit.createInventory(holder, SIZE, ChatColor.DARK_PURPLE + "Musicolide");
        renderMain(holder);
        player.openInventory(holder.inventory);
    }

    private void openInfo(Player player, GuiHolder source) {
        // Use a separate inventory/holder for the metadata page. Reusing the
        // live control inventory could leave the previous view visible during
        // client-side inventory transitions or rapid clicks.
        GuiHolder details = new GuiHolder(source.sessionId, true);
        details.viewerId = player.getUniqueId();
        details.inventory = Bukkit.createInventory(details, SIZE, ChatColor.DARK_PURPLE + "Track information");
        renderInfo(details);
        player.openInventory(details.inventory);
    }

    private void renderMain(GuiHolder holder) {
        fill(holder.inventory);
        PlaybackSession session = holder.sessionId == null ? null
                : playback.findSession(holder.sessionId).orElse(null);
        MusicTrack track = session == null ? null : session.current();
        boolean hasTrack = track != null;
        boolean paused = hasTrack && session.paused();
        boolean playing = hasTrack && session.clockStarted() && !paused && !session.resumePreparing();
        // The inventory's per-viewer permission state is refreshed on every open/click.
        Player viewer = holder.viewerId == null ? null : Bukkit.getPlayer(holder.viewerId);
        boolean canControl = viewer != null && session != null && commands.canControlGui(viewer, session);
        boolean admin = viewer != null && commands.isGuiAdmin(viewer);

        List<String> paperLore = hasTrack
                ? List.of(ChatColor.WHITE + clean(track.title()),
                        gray(time(session.elapsedMs()) + " / " + time(track.durationMs())))
                : List.of(gray("No songs playing."));
        setItemIfChanged(holder.inventory, PAPER_SLOT, item(Material.PAPER, "Now Playing", paperLore, playing));

        setControlItem(holder.inventory, PAUSE_SLOT, Material.RED_BANNER, "Pause playback",
                hasTrack && !paused && !session.resumePreparing(), canControl,
                !hasTrack ? "No songs playing."
                        : paused ? "Already paused."
                        : session.resumePreparing() ? "Resume is still being prepared."
                        : !canControl ? "Permission: track owner or admin required."
                        : "Click to pause this track.");
        setControlItem(holder.inventory, RESUME_SLOT, Material.GREEN_BANNER, "Resume playback",
                hasTrack && paused && !session.resumePreparing(), canControl,
                !hasTrack ? "No songs playing."
                        : !paused ? "Track is not paused."
                        : session.resumePreparing() ? "Resume is already being prepared."
                        : !canControl ? "Permission: track owner or admin required."
                        : "Click to resume this track.");
        setControlItem(holder.inventory, STOP_SLOT, Material.BARRIER, "Stop playback",
                hasTrack, canControl,
                !hasTrack ? "No songs playing."
                        : !canControl ? "Permission: track owner or admin required."
                        : "Stops this session and its listeners.");

        List<String> infoLore = new ArrayList<>();
        infoLore.add(gray(hasTrack ? "View metadata, TrackID and pack details." : "No active track information."));
        infoLore.add(gray("Available to all viewers."));
        holder.setItemIfChanged(inventory, INFO_SLOT, item(Material.WRITABLE_BOOK, "Song information", infoLore, false));

        boolean discordConfigured = plugin.getConfig().getBoolean("discord.enabled", false);
        boolean discordEnabled = hasTrack && playback.effectiveDiscordBroadcast(session);
        List<String> discordLore = new ArrayList<>();
        if (!admin) discordLore.add(red("Permission: admin required."));
        else if (!discordConfigured) discordLore.add(red("Discord integration is not enabled."));
        else if (!hasTrack) discordLore.add(gray("No songs playing."));
        else {
            discordLore.add(gray("Current track: ") + state(discordEnabled ? "Enabled" : "Disabled", discordEnabled));
            discordLore.add(gray("Override: ") + ChatColor.WHITE
                    + (session.discordBroadcastOverride() == null ? "Follow server default" : "This track only"));
            discordLore.add(gray("Next track: ") + ChatColor.WHITE + "Follows server default.");
            discordLore.add(gray("Click to toggle Discord voice broadcast for this track."));
        }
        setItemIfChanged(holder.inventory, DISCORD_SLOT, item(Material.DROPPER, "Discord broadcast", discordLore, discordEnabled));

        List<String> broadcastLore = new ArrayList<>();
        boolean globallyBroadcast = hasTrack && session.isServerWideGlobalSession();
        if (!admin) broadcastLore.add(red("Permission: admin required."));
        else if (!hasTrack) broadcastLore.add(gray("No songs playing."));
        else if (globallyBroadcast) broadcastLore.add(green("Already broadcasting to everyone."));
        else {
            broadcastLore.add(gray("Status: ") + red("Not server-wide"));
            broadcastLore.add(gray("Expands this track to all online players."));
            broadcastLore.add(gray("The original audience returns on the next track."));
            broadcastLore.add(gray("Loaded packs are reused; no forced pack reload."));
        }
        setItemIfChanged(holder.inventory, BROADCAST_SLOT,
                item(Material.BEACON, "Broadcast to entire server", broadcastLore, globallyBroadcast));
    }

    private void setControlItem(Inventory inventory, int slot, Material material, String name,
                                boolean available, boolean permitted, String status) {
        List<String> lore = new ArrayList<>();
        lore.add(gray("Status: ") + (available && permitted ? green("Available") : red("Unavailable")));
        lore.add(status.startsWith("Permission:") ? red(status) : gray(status));
        if (available && permitted) lore.add(green("Click to apply."));
        else if (available) lore.add(red("You cannot control this session."));
        setItemIfChanged(inventory, slot, item(material, name, lore, false));
    }

    private void renderInfo(GuiHolder holder) {
        fill(holder.inventory);
        PlaybackSession session = holder.sessionId == null ? null
                : playback.findSession(holder.sessionId).orElse(null);
        MusicTrack track = session == null ? null : session.current();
        holder.renderedTrackId = track == null ? null : track.id();
        holder.metadataRetryRequired = false;
        holder.metadataRetryAtMillis = 0L;

        if (track == null) {
            setItemIfChanged(holder.inventory, 22, item(Material.PAPER, "No song information",
                    List.of(gray("No songs playing in this session.")), false));
        } else {
            String shortId = track.id();
            String storage = "Not available";
            String youtubeId = "Not available";
            try {
                shortId = repository.getTrackCode(track.id()).orElse(track.id());
                boolean temporary = repository.isTemporaryTrack(track.id());
                storage = temporary ? green("Temporary URL import") : green("Saved library track");
                youtubeId = repository.getYouTubeVideoId(track.id()).orElse("Not available");
            } catch (SQLException error) {
                holder.metadataRetryRequired = true;
                holder.metadataRetryAtMillis = System.currentTimeMillis() + 5_000L;
                plugin.getLogger().log(Level.WARNING,
                        "Could not load complete metadata for GUI track " + track.id()
                                + "; the details page will retry automatically.", error);
            }

            detailCard(holder.inventory, 10, "Track title", track.title());
            detailCard(holder.inventory, 11, "Artist / uploader", blankAsUnknown(track.artist()));
            detailCard(holder.inventory, 12, "TrackID", shortId);
            detailCard(holder.inventory, 13, "Internal track ID", track.id());
            detailCard(holder.inventory, 14, "Storage", storage);
            detailCard(holder.inventory, 15, "Duration", time(track.durationMs()));
            detailCard(holder.inventory, 16, "Audio mode", session.isMono() ? "Mono" : "Stereo");
            detailCard(holder.inventory, 19, "Audio format", track.isStreamed() ? "Streamed OGG" : "Segmented OGG");
            detailCard(holder.inventory, 20, "Sound namespace", track.namespace());
            detailCard(holder.inventory, 21, "Pack file", track.packFileName());
            detailCard(holder.inventory, 22, "Pack SHA-1", track.sha1Hex());
            detailCard(holder.inventory, 23, "Pack size", bytes(track.packSizeBytes()));
            detailCard(holder.inventory, 24, "Segment length", track.segmentMs() + " ms");
            detailCard(holder.inventory, 25, "Segment count", Integer.toString(track.segmentCount()));
            detailCard(holder.inventory, 37, "YouTube video ID", youtubeId);
        }
        refreshInfoDynamic(holder, session);
        setItemIfChanged(holder.inventory, BACK_SLOT,
                item(Material.ARROW, "Back to playback controls",
                        List.of(gray("Return to the music controls.")), false));
    }

    private void refreshInfoDynamic(GuiHolder holder, PlaybackSession session) {
        if (session == null || session.current() == null) {
            detailCard(holder.inventory, 28, "Playback state", red("No songs playing"));
            detailCard(holder.inventory, 29, "Position", "Not available");
            detailCard(holder.inventory, 30, "Speed", "Not available");
            detailCard(holder.inventory, 31, "Executor", "Not available");
            detailCard(holder.inventory, 32, "Executor UUID", "Not available");
            detailCard(holder.inventory, 33, "Session UUID", "Not available");
            detailCard(holder.inventory, 34, "Audience", "Not available");
            detailCard(holder.inventory, 35, "Discord broadcast", "Not available");
            return;
        }
        String state = session.paused() ? "Paused"
                : session.resumePreparing() ? "Preparing to resume"
                : session.clockStarted() ? "Playing" : "Waiting for listeners";
        detailCard(holder.inventory, 28, "Playback state", state);
        detailCard(holder.inventory, 29, "Position",
                time(session.elapsedMs()) + " / " + time(session.current().durationMs()));
        detailCard(holder.inventory, 30, "Playback speed",
                String.format(java.util.Locale.ROOT, "%.2fx", session.speed()));
        detailCard(holder.inventory, 31, "Executor", session.ownerName());
        detailCard(holder.inventory, 32, "Executor UUID",
                session.owner() == null ? "Console / system" : session.owner().toString());
        detailCard(holder.inventory, 33, "Playback session ID", session.id().toString());
        detailCard(holder.inventory, 34, "Audience", session.isServerWideGlobalSession()
                ? "Entire server (@a)"
                : session.zone().shape().name() + (session.zone().worldId() == null
                ? "" : " in world " + session.zone().worldId()));
        detailCard(holder.inventory, 35, "Discord broadcast",
                playback.effectiveDiscordBroadcast(session) ? green("Enabled") : red("Disabled"));
    }

    private void detailCard(Inventory inventory, int slot, String label, String value) {
        String safeValue = value == null || value.isBlank() ? "Not available" : value;
        setItemIfChanged(inventory, slot, item(Material.PAPER, label,
                List.of(safeValue), false));
    }

    private void refreshOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (!(top.getHolder() instanceof GuiHolder holder)) continue;
                holder.viewerId = player.getUniqueId();
                if (!holder.details) {
                    renderMain(holder);
                } else {
                    PlaybackSession session = holder.sessionId == null ? null
                            : playback.findSession(holder.sessionId).orElse(null);
                    String currentTrackId = session == null || session.current() == null
                            ? null : session.current().id();
                    if (!java.util.Objects.equals(holder.renderedTrackId, currentTrackId)
                            || holder.metadataRetryRequired
                            && System.currentTimeMillis() >= holder.metadataRetryAtMillis) {
                        renderInfo(holder);
                    } else {
                        refreshInfoDynamic(holder, session);
                    }
                }
            } catch (RuntimeException error) {
                // A single unexpected inventory/metadata failure must not cancel
                // the repeating task and leave every open Musicolide GUI stale.
                plugin.getLogger().log(Level.WARNING,
                        "Could not refresh Musicolide GUI for " + player.getName() + ".", error);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != top) return;
        try {
            holder.viewerId = player.getUniqueId();
            int slot = event.getRawSlot();
            if (holder.details) {
                if (slot == BACK_SLOT) openMain(player, holder.sessionId);
                return;
            }
            PlaybackSession session = holder.sessionId == null ? null
                    : playback.findSession(holder.sessionId).orElse(null);
            if (slot == INFO_SLOT) {
                openInfo(player, holder);
                return;
            } else if (session != null && slot == PAUSE_SLOT) {
                commands.guiControl(player, session.id(), "pause");
            } else if (session != null && slot == RESUME_SLOT) {
                commands.guiControl(player, session.id(), "resume");
            } else if (session != null && slot == STOP_SLOT) {
                commands.guiControl(player, session.id(), "stop");
            } else if (session != null && slot == DISCORD_SLOT) {
                commands.guiToggleDiscord(player, session.id());
            } else if (session != null && slot == BROADCAST_SLOT) {
                commands.guiBroadcast(player, session.id());
            }
            renderMain(holder);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not process Musicolide GUI interaction from " + player.getName() + ".", error);
            player.sendMessage(ChatColor.RED + "Musicolide could not update this menu. Try reopening /music gui.");
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) event.setCancelled(true);
    }

    private void setItemIfChanged(Inventory inventory, int slot, ItemStack item) {
        ItemStack current = inventory.getItem(slot);
        if (current == null ? item != null : item == null || !current.equals(item)) {
            inventory.setItem(slot, item);
        }
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        ItemStack border = item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int slot = 0; slot < SIZE; slot++) {
            setItemIfChanged(inventory, slot, slot < 9 || slot >= 45 ? border : filler);
        }
    }

    private ItemStack item(Material material, String name, List<String> lore, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(lore);
        if (glow) meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private void add(List<String> lore, String key, String value) {
        lore.add(gray(key + ": ") + ChatColor.WHITE + clean(blankAsUnknown(value)));
    }

    private String shortTrackId(MusicTrack track) {
        try {
            return repository.getTrackCode(track.id()).orElse(track.id());
        } catch (SQLException error) {
            return track.id();
        }
    }

    private String gray(String text) { return ChatColor.GRAY + text; }
    private String green(String text) { return ChatColor.GREEN + text; }
    private String red(String text) { return ChatColor.RED + text; }
    private String state(String text, boolean enabled) { return (enabled ? ChatColor.GREEN : ChatColor.RED) + text; }
    private String clean(String text) { return text == null ? "" : ChatColor.stripColor(text); }
    private String blankAsUnknown(String text) { return text == null || text.isBlank() ? "Unknown" : text; }
    private String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private String bytes(long count) {
        if (count < 1024) return count + " B";
        if (count < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", count / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", count / (1024.0 * 1024.0));
    }

    private static final class GuiHolder implements InventoryHolder {
        private final UUID sessionId;
        private boolean details;
        private UUID viewerId;
        private String renderedTrackId;
        private boolean metadataRetryRequired;
        private long metadataRetryAtMillis;
        private Inventory inventory;

        private GuiHolder(UUID sessionId, boolean details) {
            this.sessionId = sessionId;
            this.details = details;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
