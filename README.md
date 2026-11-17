# Musicolide

Musicolide is a Paper plugin for server-managed music playback through ordinary Minecraft Java clients. Players do not need a client mod or a browser. The client downloads generated resource packs on demand and plays custom sounds through Minecraft's audio engine.

## Supported versions

- **Paper 1.21.1 through 26.3**, using one plugin JAR.
- **Java 21 bytecode**. Minecraft 1.21.x servers run on Java 21; Minecraft 26.1+ servers must use their required Java 25 runtime.
- Purpur is expected to work because it is Paper-compatible.
- This release uses Paper's resource-pack stacking API. Spigot is not currently supported.

The plugin is compiled against Paper 1.21.1, the oldest supported API, to avoid accidentally linking methods introduced in newer releases. Its plugin API version is set to 1.21.1.

## Version-aware resource packs

Musicolide detects the running server version and writes the resource-pack metadata format that version expects:

| Minecraft release | Resource-pack metadata |
|---|---|
| 1.21.1 | pack_format: 34 |
| 1.21.2–1.21.3 | pack_format: 42 |
| 1.21.4 | pack_format: 46 |
| 1.21.5 | pack_format: 55 |
| 1.21.6 | pack_format: 63 |
| 1.21.7–1.21.8 | pack_format: 64 |
| 1.21.9–1.21.10 | min_format: [69, 0], max_format: 69 |
| 1.21.11 | min_format: [75, 0], max_format: 75 |
| 26.1 | min_format: [84, 0], max_format: 84 |
| 26.2 | min_format: [88, 0], max_format: 88 |
| 26.3 | min_format: [97, 1], max_format: 97 |

This is selected from the **server's** Minecraft version. If a proxy permits clients from a different Minecraft version to join the server (for example, via a protocol translation plugin), their resource-pack compatibility is not guaranteed; test those mixed-version clients separately.

## Design

- Java plugin owns commands, sessions, permissions, zones, queues and player arbitration.
- SQLite stores track metadata, external URL aliases, playlists and user preferences.
- An unmatched `/play <query>` searches the local catalogue first, then YouTube metadata. Saved tracks are labelled `[Local]` and are played directly without being imported again. Text-search underscores are treated as spaces; an exact TrackID and URL are preserved as entered. Search pages contain six results. `/music page 2` requests the next page, and pages must be opened in sequence. The default maximum is four pages; `audio.yt-dlp.search.max-pages` can be raised to eight. Only the remote result depth needed for the requested page is fetched. Search itself reads metadata only. Selecting a remote result from `/play` creates a temporary import; the admin-only `/music search` workflow can save selected results permanently to SavedMusic. Results expire after five minutes. `audio.yt-dlp.search.prioritize-songs` defaults to true and heuristically boosts official audio/music videos, lyrics and Topic channels while de-emphasizing reaction/tutorial/compilation results; turn it off to preserve YouTube's returned order.
- FFmpeg probes input duration and prepares audio according to `audio.playback-mode`: legacy OGG segments, one continuous streamed OGG, or a streamed OGG with timestamp-based resume generation. Player-specific `/music sync` and optional `precise` Java volume changes can build a temporary tail from either the streamed OGG or stored legacy segments without pausing other listeners. The default `playback.volume-change-mode: next-track` changes the saved volume without generating a file; the currently playing sound keeps its original volume until the next track. The optional `restart-segment` mode can apply volume immediately in legacy segmented mode by restarting the current segment; streamed tracks fall back to next-track. Bedrock volume changes always take effect on the next track. Each imported track contains optimized stereo and true one-channel mono OGG variants. The player/session selects one sound event, so changing mode does not generate audio on demand for newly imported tracks. Mono circle/square zones apply a configurable 10-block fade band; set playback.mono.fade-distance or override per /play with fade:10 (use fade:0 to disable). The fade option requires `mode:mono`. Ordinary mono sound is attached to each listener entity so its source follows that player; only the explicit `center:true` option with `mode:mono` uses a fixed zone-centred source. Fade gain is sampled when a streamed OGG starts; the vanilla sound API cannot continuously alter the gain of an already-playing stream. Legacy segmented tracks recalculate the gain at segment boundaries.
- `/music speed <0.5-2.0>` adjusts the session's playback rate through Minecraft's sound-pitch parameter, without creating or downloading audio files. A live change is staged for the next track because an already-playing sound cannot have its pitch changed in place. This also changes the music's tonal pitch. Vanilla Java clients clamp sound pitch below 0.5, so accurate 0.25x playback is not available without client-side support or separately processed audio. The session clock and track transitions follow the selected speed.
- By default, generated resource packs are served locally through the existing Minecraft TCP listener. Musicolide inspects the first bytes of each accepted connection before Minecraft's packet handlers: HTTP GET/HEAD requests for the configured pack path are served from the local packs directory, while all other connections are passed to Paper unchanged. No second public port, external storage, VPS or upload account is required. A legacy dedicated-port HTTP mode remains available.
- Resource packs are requested only for eligible playback recipients and their configured rolling prefetch window; with `resource-packs.download-mode: on-play`, players outside a spatial zone do not trigger automatic pack requests. `resource-packs.universal-playback-system` controls ordinary playback (`library` by default or experimental `per-track`). URL playlists can independently override it with `resource-packs.playlist-playback-system` (`per-track` by default or `library`). In playlist `per-track` mode, the server prepares upcoming playlist entries, but Java players receive only the active track pack when that track becomes current; the ordinary rolling player prefetch is disabled for that playlist session. In playlist `library` mode, imports update the aggregate library on the server and the latest revision is applied at a track boundary, so several imports completed during one song are coalesced into one pack application. Manual `/music download` continues to request the aggregate library. Stable pack hashes and IDs let vanilla reuse cached bytes on reconnects and repeated plays. The bounded Java start barrier waits for the active track pack in per-track mode, or the aggregate library in library mode. Listeners entering or re-entering a zone mid-track are synchronized from the live session timeline after their required library or track pack is ready. Leaving a zone invalidates the previous catch-up attempt so re-entry can generate a fresh synchronized tail. Reconnecting Java players do not restart streamed tracks from 00:00. Bedrock late entrants wait until the next track.
- In universal `library` mode, imports update the aggregate music library immediately. A Java player already hearing a track keeps the loaded library revision until that track ends; the latest aggregate pack is requested before the queued track begins, allowing multiple playlist imports completed during the current song to be combined into one update. In universal `per-track` mode, an import creates a separate immutable pack and does not invalidate previously delivered track packs. Both modes avoid replacing an applied pack during an active song. Generated resume/synchronization packs stay active for the connection after use; the server deletes their ZIP files once downloads finish rather than removing an applied pack at each track boundary and triggering another resource-stack reload.
- Spatial zones support circles, world-aligned or player-facing squares, optional vertical limits, and optional per-player fade outside the main zone. For mono circle/square sessions, the default fade distance is 10 blocks and is configured with playback.mono.fade-distance; /play fade10 mode:mono overrides it and fade0 disables it. The command rejects fade options unless mode:mono is specified. WorldGuard polygon regions currently have a hard boundary because their arbitrary edge distance is not calculated.
- Overlap is resolved per player. The default policy is first-started session wins; lower-priority music continues in the exclusive part of its zone.

## Audio source and rights

YouTube URL ingestion is supported through yt-dlp when the server administrator explicitly enables URL auto-import and the source host is allowlisted. URL playlist metadata is read first; the first track is imported immediately, then additional entries are imported one at a time with `playlists.preimport.interval-seconds` (15 seconds by default, clamped to 10–20 seconds). Short tracks or a current track nearing its end bypass the cooldown to prevent queue gaps. At most two future tracks are kept ready by default (`playlists.preimport.max-preimported-tracks`). The default config prelists youtube.com and youtu.be, but keeps audio.yt-dlp.auto-import-on-play disabled. Once enabled, users with the DJ permission can run `/play <url>` in-game to import one video. YouTube playlist URLs are separately opt-in with `audio.yt-dlp.allow-playlists: true` (default `false`); `audio.yt-dlp.max-playlist-items` defaults to 10 and is hard-capped at 20, as well as being limited by `limits.max-playlist-tracks`. Playlist metadata is inspected first, then each selected video is downloaded and processed sequentially through the single-video `--no-playlist` path. Ordinary video URLs that contain a playlist context still import only that video. From console, non-spatial playback must specify @a or an online player name; player-centered spatial options require an in-game sender. The administrator is responsible for ensuring that the server has permission to download, cache and distribute the audio to players. Spotify and SoundCloud remain metadata/alias-only sources. Musicolide does not bypass DRM, subscriptions, region controls or platform protections.

Import only audio that you own or are authorized to distribute to your players. The presence of yt-dlp does not grant redistribution rights.
YouTube search itself does not download audio. Only admins can use `/music search`; a selected result is saved permanently. A result selected from `/play` stays temporary. Both use the server's `audio.yt-dlp.auto-import-on-play` opt-in, allowed-host checks and redistribution-rights safeguards; a strong metadata match already in the local catalogue is reused without another download. When auto-import is disabled, players can still inspect metadata and select local matches, but a new remote download is blocked until an administrator enables the setting. Playlist import is a separate option and remains disabled unless `audio.yt-dlp.allow-playlists: true` is configured. It only accepts a bounded number of entries and downloads them one at a time.

## Build and native-tool setup

1. Install JDK 21 or newer.
2. For development, run `./gradlew clean test build`.

The **universal JAR stays below 30 MiB**. It does not contain hundreds of megabytes of platform executables. Instead, on the first server startup, Musicolide detects the OS/CPU architecture and downloads the matching pinned versions of yt-dlp, FFmpeg and QuickJS. Downloads are streamed to disk, SHA-256 verified, and extracted into `plugins/Musicolide/native/<platform>/` using Java archive libraries. No shell, system tar program, Python installation, or manual native-tool installation is required.

Automatic first-start downloads are provided for Linux x86_64/ARM64, Windows x86_64/ARM64 and macOS x86_64/ARM64. The tools remain installed across restarts. FFmpeg archives can be large (roughly 120–175 MB for the current Linux/Windows upstream packages); the installer streams them instead of loading them into RAM and deletes the archive after extracting the FFmpeg executable. Keep sufficient free disk space for the download and extracted tools.

The server needs outbound HTTPS access to GitHub release assets during first-time setup. YouTube extraction also enables yt-dlp's official `ejs:github` remote component, so the server needs GitHub access when yt-dlp first retrieves or refreshes the JavaScript challenge scripts. If the host blocks outbound downloads, set `audio.tools.download-on-first-start: false` and configure compatible executables through the existing executable settings or PATH.

Linux and Windows use the FFmpeg-Builds LGPL variants. The macOS installer uses the publisher's macOS builds; those include a mixture of GPL/LGPL codecs, so consult the installed `licenses/NATIVE-TOOLS-NOTICES.txt` before redistributing those tools. yt-dlp and QuickJS notices are also recorded under the installed native directory.

The single deployable universal JAR has a build-time size check enforcing a strict limit below 30 MiB. It contains only a compact Discord runtime manifest with pinned artifact versions, Maven Central URLs and SHA-256 hashes, not the third-party Discord dependency JARs or platform-native binaries. When Discord is enabled, Musicolide downloads the Java dependencies and only the matching DAVE native JAR into the server's runtime cache, verifying each artifact before loading. The FFmpeg/yt-dlp/QuickJS tools are also downloaded and checksum-verified on first startup.

The deployable plugin is `build/libs/musicolide-0.1.0-universal.jar`; use this same artifact across supported hosting providers and operating systems.
## Discord bot and voice broadcast (optional)

Musicolide can broadcast its selected server playback session into one configured Discord voice channel. Minecraft remains authoritative: Discord cannot alter a song's clock or affect Minecraft if Discord is disconnected. The bot's gateway stays online for slash commands, but its **voice connection is opened only after a Musicolide playback clock has started**. It leaves when no eligible playback session remains. A paused session keeps the bot in voice without sending audio; after resume it restarts from the current Minecraft timeline. If a resume is waiting for its resource-pack/start barrier, an already-established voice connection is held rather than being dropped and rejoined.

Configure the local `plugins/Musicolide/config.yml`:

```yaml
discord:
  enabled: true
  bot-token: "YOUR_DISCORD_BOT_TOKEN"
  voice-channel-id: "YOUR_VOICE_CHANNEL_ID"
  broadcast-scope: "global-only" # or "zone-aware"
  reconnect-and-resync: true
  embed-color: "#9B59B6"
  presence:
    enabled: true
    template: "{title} • {elapsed}"
    update-interval-seconds: 15
  info:
    enabled: true
    server-name: "Minecraft Server"
    show-server-address: false
    server-ip: ""
    server-port: 25565
  commands:
    enabled: true
    guild-id: "" # blank = voice-channel guild; slash commands are not globally registered
    allowed-guild-ids: [] # optional explicit allowlist; otherwise guild-id or voice-channel-id's guild is trusted
    admin-authorization: "minecraft-op" # minecraft-op / discord-administrator / either / both
    admin-role-ids: []
    user-cooldown-ms: 1000
    global-cooldown-ms: 100
    autocomplete-cooldown-seconds: 5
    autocomplete-global-cooldown-seconds: 1
    autocomplete-response-cooldown-ms: 250
    autocomplete-global-response-cooldown-ms: 50
    otp-user-attempts-per-minute: 5
    otp-global-attempts-per-minute: 30
    link-code-cooldown-seconds: 30
    global-link-code-cooldown-seconds: 1
    playlist-play-cooldown-seconds: 30
    global-playlist-play-cooldown-seconds: 2
```

Keep the real token private. Do not commit it to a repository or share it in logs/screenshots. The bot needs only **View Channel**, **Connect**, and **Speak** in the configured voice channel, plus the normal application-command integration and access to DMs for account pairing. Do not grant the bot Administrator, Manage Server, Manage Roles, Manage Messages, Kick Members, Ban Members or other moderation permissions. Musicolide's Discord code does not implement moderation actions or arbitrary Bukkit command dispatch; its gateway intents are limited to voice states and direct messages; it does not request guild-message, guild-member or message-content intents. Slash commands are registered only in trusted guilds: `allowed-guild-ids` takes precedence and supports an explicit multi-guild allowlist; otherwise `guild-id` is used, then the guild containing `voice-channel-id`. A non-empty but invalid allowlist fails closed rather than falling back to another guild. Per-user command and autocomplete throttles are scoped by guild, so an unrelated guild cannot consume the trusted guild's user budget. A global command-ingress throttle runs before account-link lookups or Bukkit task scheduling, including for unlinked users. Legacy global Musicolide slash commands are cleared when the gateway becomes ready; the bot refuses global slash-command registration when no trusted guild can be resolved. Restart the Minecraft server after changing the bot token or voice channel. The runtime bootstrap/download occurs on a background thread.

### Presence and /info

The bot can display the current track and elapsed time using Discord listening activity. The presence template supports `{title}`, `{artist}`, `{elapsed}`, `{duration}`, and `{state}`. Discord presence updates are rate-limited to at least 15 seconds, but a track or playback-state change is sent immediately. Set `presence.enabled: false` to disable activity.

Discord `/info` shows the selected Musicolide track, artist, current timestamp, duration and playback state. Set `info.show-server-address: true` and configure `server-ip` plus `server-port` to show a public server address; the address is hidden by default. `info.show-thumbnail: true` displays a YouTube thumbnail only inside Discord when a saved YouTube source ID exists. It does not add an image to Java or Bedrock resource packs, so player downloads are unchanged. `info.custom-message` adds a final embed field after the standard details; it supports `{state}`, `{track}`/`{title}`, `{artist}`, `{position}`, `{duration}`, and `{server}`. The embed accent is configured through `embed-color` (six-digit hex, default purple). Per-command visibility is controlled by `discord.commands.visibility.<command>`: `requester` (private/ephemeral, default) or `everyone` (public).

### In-game playback GUI and session controls

Run `/music gui` to open the 54-slot chest-style player. It opens for the command executor and the online players currently inside that session's intended audience/zone. The paper shows the current title and elapsed/duration time, with an enchantment glint only while playback is actively running. The book opens the track metadata page, including the short and internal TrackIDs, saved/temporary status, audio format, pack details, playback state, owner and session ID.

The red and green banners pause and resume; the barrier stops the selected session. Non-admins can use these controls only for a session they started. Admins can target another active session explicitly with `/music pause <TrackID>`, `/music resume <TrackID>`, or `/music stop <TrackID>`. When a TrackID is active in more than one session, the admin command applies to each matching active session. The same ownership check is used by linked Discord `/music command` controls and GUI clicks.

The beacon expands the current track to all players and restores its original audience when the next track begins. Promotion does not rebuild or re-request the shared library pack; its normal readiness/cache check is reused. Existing listeners continue without a pack reload. Players newly added mid-track use the existing late-entry synchronization path; streamed tracks may need a separate generated remainder/tail pack to start at the shared playback time, but that is not a download of the shared library again.

The now-playing action bar uses a purple `🎵` icon and shows only the track title by default. Set `playback.synchronization.action-bar-show-artist: true` to append the artist.

### Secure account linking and controls

A player runs `/link` in Minecraft. Musicolide privately displays an eight-digit, one-use code valid for five minutes. The player DMs that code to the bot. The plugin stores only the Discord user ID, Minecraft UUID/name and link timestamp; it does not ask for or store Microsoft passwords, Minecraft passwords, session tokens or other authentication secrets. Each Discord account and each Minecraft account can be linked to only one counterpart. The player runs `/unlink` in Minecraft to remove the link. Pending codes are memory-only and are invalidated on restart.

Linked players can use Discord `/play`, `/help`, `/info`, and `/music command:<command text>`. Discord `/play` accepts optional `target`, `id`, `zone` (for example `circle 50`, `square 50x50`, or `region spawn`), `mode` (`stereo` by default or `mono` for one-channel audio), and `fade` options. Mono circle/square zones use listener-relative sound origins by default; the optional `center:true` setting opts into a zone-centred origin. The same rule applies to Minecraft `/play center:true`. Mono circle/square zones use a configurable 10-block fade by default; WorldGuard regions retain a hard boundary. As in Minecraft, targeting another player/all players or selecting a spatial zone requires the linked Minecraft account to have the configured admin permission; self-target playback continues to use the linked player. The `/music command` option routes existing Musicolide commands through the normal plugin command handler as the linked Minecraft player, so the Minecraft account must be online and existing DJ/use permissions continue to apply. The Discord `/help` command explains this system. `/play` autocomplete combines local catalogue matches with debounced YouTube metadata suggestions when yt-dlp and QuickJS are available; the server's existing URL auto-import and allowlist settings still control whether a selected remote result can be imported. New imports contain both channel layouts, so selecting a mode does not need a separate audio conversion. Older stereo-only imports are converted to mono once when first requested; this rebuilds the shared resource-pack library and can cause one resource-pack reapplication for players who already downloaded the old library. The selected mode applies to the entire playback session and its queue. Stop the session before switching its mode. For server-wide sessions, the passive Discord voice mirror uses the same mono/stereo variant. Geyser/Bedrock clients receive an updated Bedrock library on their next connection, so an already-connected Bedrock player should reconnect after a legacy mono conversion or another library revision.

Admin authorization is configurable:
- `minecraft-op` (default): the linked Minecraft account must be OP or have the configured `permissions.admin` node.
- `discord-administrator`: Discord Administrator permission or a role listed in `admin-role-ids` grants the plugin's admin/DJ permission for that command.
- `either`: either of those checks is sufficient.
- `both`: both must be satisfied.

Role IDs are configured explicitly; ordinary Discord roles do not automatically confer Minecraft OP. Discord Administrator or configured admin-role checks grant Musicolide's admin/DJ permission only when the caller is in the trusted Musicolide guild and the selected authorization mode permits it. The default remains `minecraft-op`. The bot never performs Discord moderation actions. Keep the bot role least-privileged; the code does not need Administrator permission. Additional protections include per-user/global slash-command cooldowns, rate-limited remote autocomplete, per-user/global one-time-code attempt limits, a cooldown on `/link` code creation, a fixed Musicolide command-root allowlist, and coalesced Discord response edits. The command bridge cannot invoke arbitrary Discord moderation operations or unrelated Bukkit commands.

### Voice broadcast selection and runtime

The Minecraft session clock is authoritative. Pause, resume, seek, skip, track changes, playback speed and reconnect recovery are derived from read-only snapshots. Joining/leaving Discord voice does not change the Minecraft session. With `reconnect-and-resync: true`, an unexpected disconnect rejoins and starts from the current server timer instead of replaying stale buffered audio.

A single configured Discord channel can broadcast only one session at a time. By default, `broadcast-scope: "global-only"` mirrors only sessions explicitly addressed to all players (`@a`) with a global listening zone. The optional `"zone-aware"` mode allows an eligible spatial session when at least one Java player is actually receiving it inside its zone. Discord cannot reproduce multiple per-player zone mixes because it has no Minecraft player position. In either mode, active playback must have an eligible Java listener with its required pack ready; Bedrock and pack-rejected/unready players do not make a session eligible. Paused sessions retain the existing voice connection.

Musicolide uses the same imported track archive and FFmpeg to decode Discord audio; it does not re-download the source. The universal JAR includes a compact manifest with pinned Maven Central artifact URLs and SHA-256 hashes. When Discord is enabled, Musicolide downloads the JDA/DAVE Java dependencies and only the matching platform-native JAR into `plugins/Musicolide/runtime/discord/`, verifies each artifact and caches it. No Discord dependencies are downloaded when Discord is disabled. Supported native targets are Linux x86_64 (glibc), Linux ARM64 (glibc), Windows x86_64, and macOS Intel/Apple Silicon. Linux musl, Windows ARM64 and other architectures are not included. Runtime bootstrap and downloads run away from the Bukkit main thread; Discord failures do not stop Minecraft playback.

## Server setup

1. Install the JAR in plugins/ and start Paper or Purpur once.
2. Put authorized source files in plugins/Musicolide/SavedMusic/.
3. Set server.public-base-url to the public IP address or hostname players use to join the server. The default hosting mode is shared-port, and Musicolide automatically detects the bound Minecraft TCP listener port. The port written in an existing public-base-url is replaced when auto-detection is enabled.
4. If the host maps the public TCP port to a different number than the port bound inside the Minecraft process, set server.public-port to that external port. Leave it at -1 for automatic detection.
5. Run /music import <filename> [title] as an administrator, or use /music import all to process every supported file in SavedMusic/ sequentially.
6. Use /music list to see imported tracks.
7. Start playback with /music play <id-or-title>, or /play <url> when allowlisted URL auto-import is enabled.

No additional inbound port is needed in the default shared-port mode. The plugin keeps pack ZIP files in plugins/Musicolide/packs/ and serves them through the existing Minecraft TCP listener. The HTTP download URL must use the same public host and TCP port that players use to reach the Minecraft listener.

Hosts that provide an additional public TCP port can opt into the legacy separate listener with server.hosting-mode: "dedicated-port"; configure server.port and include that port in server.public-base-url. This mode is not needed for normal shared-port deployments.

## Enable direct YouTube playback

To enable the administrator-triggered `/play <url>` import path, edit `plugins/Musicolide/config.yml` after the first startup:

```yaml
audio:
  yt-dlp:
    enabled: true
    auto-import-on-play: false
    allow-playlists: false
    max-playlist-items: 10 # hard maximum 20; playlist batch jobs are serialized
    search:
      result-count: 6
      prioritize-songs: true
    allowed-hosts:
      - "youtube.com"
      - "youtu.be"
```

Then run `/music reload` (or restart the server). A player with the `musicolide.dj` permission can run `/play <YouTube URL>` in-game after the server administrator enables this setting. The import downloads to a temporary folder, runs yt-dlp first, then FFmpeg converts the source using the configured `audio.playback-mode`, builds the resource pack, saves the temporary track, and starts a playback session. Playlist URLs are separately controlled by `audio.yt-dlp.allow-playlists` and are disabled by default; when enabled, `/play <YouTube playlist URL>` imports up to `max-playlist-items` tracks (default 10, hard maximum 20) one at a time. Only one playlist batch can run at once, in addition to the normal single-worker/queue limit. The admin-only `/music import-url` path permanently imports one video at a time. Console usage must include `--all` or `--player <name>`. Only use this for audio the server is authorized to download and redistribute; these settings do not grant that authorization.

## Permissions

- `musicolide.use` and `musicolide.dj` are granted to **everyone by default**. Every player can start and control their own playback sessions, use queues, browse/search through `/play`, and play existing server playlists.
- `musicolide.admin` remains **OP-only by default**. Admins manage the permanent music library, including imports, permanent saves, permanent YouTube search selections, deletion and aliases.
- Shared server playlists are visible to everyone; only admins can create, edit, remove entries from, or delete them. Every DJ can create and manage personal playlists under `/music playlist private`; private playlists are keyed to the owner's Minecraft UUID and are not visible to other players. Playlist playback uses the same target and zone rules as `/play`, and defaults to the executor only.
- LuckPerms can still grant or deny these permission nodes explicitly. Admin inherits DJ, and DJ inherits use.

## Commands

- /play <TrackID/title/query/URL> [target:@a] [circle:60|square:50x50|region:spawn] [fade10 mode:mono] [loop:true|false] (fade requires mono; a loop repeats this single track and locks its matching queue until that session is stopped)
- /music pause, resume, skip, stop, queue, nowplaying, progress
- /queue add <TrackID>, /queue remove <TrackID>, /queue clear (manage upcoming tracks in your current session; a queued loop can be removed before it starts)
- /music volume <0-100>, /music speed <0.5-2.0>, /music mute, /music unmute, /music seek <seconds>, /music sync
- /music list [search] (shows the public TrackID for each library item)
- /music search <query> (admin; search the local catalogue and YouTube for permanent library additions)
- /music select <number> (admin for /music search results; DJ for temporary /play search results)
- /music page <number> (opens the next six results in sequence)
- /music save <TrackID> (admin; makes a temporary track permanent)
- /music delete <TrackID> (admin; removes the database entry and its TrackID-named SavedMusic files; cannot delete a queued/playing or currently downloading track)
- /music import <filename> [title] [id:custom_id] and /music import all (admin; permanently import files from SavedMusic/)
- /music import-url <url> [title...] [id:custom_id] (admin; permanently import one URL from an allowlisted host)
- /music playlist create <name>, add <name> <TrackID>, remove <name> <entry>, delete <name>, list, show <name>, play <name> [play options] (admin manages shared server playlists; DJs can list/show/play)
- /music playlist private create <name>, add <name> <TrackID>, remove <name> <entry>, delete <name>, list, show <name>, play <name> [play options] (each DJ manages only their own private playlists)
- /music diagnostics, /music download, /music reload, /music alias add <url> <TrackID>, /music priority <number>
- /link and /unlink manage Discord account pairing.

Compact play options also include target:<player>, id:custom_name, circle:<radius>, square:<width>x<length>, region:<WorldGuard-ID>, fade10 or fade:10 (both require mode:mono), mode:mono/mode:stereo, and loop:true/loop:false. Shared and private playlist `play` commands reuse the same target, spatial-zone, mono/stereo, fade and center options as `/play`. For playlist playback, `loop:true|false` repeats or disables repeating of the playlist as a whole; `id:` is not applicable. A loop can be queued after ordinary tracks, but nothing can be appended after that loop entry; once it starts, use /music stop to end it. URL-playlist import sessions keep their separate managed queue. Legacy --option forms remain accepted for backwards compatibility. Public TrackIDs use the unique a00-f99 format (up to 600 active tracks). A code is freed and may be reused after a track is deleted or expires; existing internal IDs remain accepted as aliases.

For /play and /music search, zone/range modifiers are retained when the YouTube result is selected. When a player supplies a spatial option without an explicit target, the default audience is @a, filtered by the zone; without a spatial option, the historical default remains @s. An explicit @s, @p, @a or player-name target always overrides that default. The zone center is the command sender's location and world. Console playback cannot create a player-centered spatial zone and must specify a target for non-spatial playback.

Examples:
```
/play summer nights circle:40
/play synth mix square:50x30 target:@a
/play ambient region:spawn
/play ambient fade10 mode:mono
/play summer nights mode:mono
/music search lo-fi beats circle:60
/music select 2
/music save a20
/music delete a20
```
## Server setup and aliases

For example, an administrator can map a Spotify or YouTube Music URL to a locally imported, authorized track:

    /music alias add https://open.spotify.com/track/EXAMPLE track-id
    /music play https://open.spotify.com/track/EXAMPLE

Musicolide warns that an alias may point to a different recording and can require confirmation. It does not fetch the stream from the external platform.

## Geyser and Bedrock

The Geyser integration in this branch is experimental. When Geyser-Spigot is installed on the same Paper server, Musicolide builds one aggregate Bedrock mcpack from imported track archives and registers it for Bedrock sessions through Geyser's public resource-pack event. Bedrock players use the same Musicolide session and custom sound identifiers, but they do not receive Java Edition resource packs.

Administrators can set `geyser.ignore-bedrock-players: true` to exclude Bedrock clients entirely. Musicolide will not register its Bedrock pack, request Java or temporary packs for them, select them as playback recipients, send them playback/action-bar notifications, or return command feedback/tab suggestions to their in-game commands. They are also excluded from shared start-barrier and resume-fallback decisions, so Java playback behaves as though those Bedrock connections were not present. The default is `false`, preserving the existing Bedrock integration. Geyser must be installed on the same Paper server so Musicolide can identify Bedrock UUIDs.

Enable this before Bedrock players connect. If it is enabled while a Bedrock session is already connected, Musicolide stops any existing Musicolide sound and suppresses future Musicolide output; however, a pack already offered by Geyser in that connection cannot be recalled without sending a pack-removal/update operation. Have those players reconnect after changing the option so the new connection starts without the Musicolide pack.

Geyser's current custom-sound translator forwards unmapped custom Java sound identifiers. That is the basis of the shared playback trigger, but it has not yet been validated with a live Bedrock client. The Bedrock library is delivered when a player connects; when a new track changes the pack version, already-connected Bedrock players must reconnect to receive it. A player who has not received the current pack is held out of playback rather than sent silent audio.

This branch does not currently support a separately hosted Geyser proxy without the Geyser plugin/API being available on the Paper server. See docs/GEYSER.md for requirements and the remaining client acceptance tests.

## Client behavior

By default, the first time a Java player needs music, Musicolide sends one aggregate library resource pack containing the imported tracks. Its content hash gives each immutable library revision a stable download URL, so Minecraft can reuse a cached copy when the contents have not changed. Set `resource-packs.java-delivery-mode: per-track` to try the experimental alternative: Musicolide sends separate immutable packs for the current track and the rolling prefetch window, so a newly imported song does not require rebuilding and downloading the full Java library. Repeated plays and reconnects use each track's stable pack ID and SHA-1 so vanilla can reuse cached bytes. The trade-off is that a first-time track may cause another resource-pack application and client resource-stack reload; this mode has unit-level coverage but still needs live client testing and is not the default. `resource-packs.download-mode: on-join` does not send the aggregate pack in per-track mode; an explicit `/music download` still requests that full aggregate fallback. Geyser/Bedrock always continues to use its aggregate Bedrock pack. Generated per-player audio tails used for synchronization or resumable playback are delivered separately. With `resource-packs.cache.remove-pack-after-session: false` (the default), applied packs remain active for the connection to avoid repeated stack reloads; enabling it removes active packs when the player's session ends. Declining the current required Java pack prevents playback of a track that depends on it.

Minecraft's vanilla sound API does not expose a true pause/seek operation for an already-playing sound. Musicolide therefore offers three import-time playback modes in `audio.playback-mode`: `segmented` keeps the legacy short-OGG-segment approach (resume/seek are segment-based); `continuous` uses one streamed OGG and restarts it from the beginning on resume; and `resumable` uses one streamed OGG, then invokes FFmpeg on `/resume` or `/music seek <seconds>` to generate audio from the saved/requested timestamp and builds a temporary resource pack. In resumable mode, Java clients wait for that pack to load before playback continues. Resuming is asynchronous and may take a short time depending on the host. The mode affects new imports only; existing tracks retain their original format. Geyser/Bedrock clients cannot receive these per-resume Java packs, so mixed sessions use the compatible restart-from-beginning fallback where applicable. Bedrock players who enter or re-enter after a track has started are held out until the next track rather than being given mismatched audio.

## Configuration

See the fully commented config.yml for all available settings. The resource-pack format and shared Minecraft listener port are detected automatically; do not manually set a pack format or the Minecraft port. Set server.public-base-url to the public host players use. If external port mapping differs from the local Minecraft listener, set server.public-port explicitly. Use /music reload after changing reloadable settings. Hosting mode and shared-listener injection require a full server restart.

YouTube search settings include `audio.yt-dlp.search.prioritize-songs` (default true) and `audio.yt-dlp.search.max-pages` (default 4; configurable from one to eight). Pages contain six results and the plugin fetches deeper YouTube results only when the player requests the next page. Search reads metadata only; selecting a new remote result is subject to the existing URL auto-import opt-in and allowlist. `/play` and import commands accept `id:custom_name` to define the internal track/sound ID and displayed title; custom IDs use lowercase letters, numbers and underscores (one to 45 characters), except the reserved short TrackID pattern. Each track also receives a public three-character TrackID from `a00` through `f99`, used by list, play, save, delete, playlists and aliases. The aggregate Java resource pack contains only its required `pack.mcmeta` and sound assets/definitions; Bedrock retains its required `manifest.json` and sound definitions. No thumbnail or unrelated metadata is included in player packs.

Important settings include `audio.playback-mode` (`segmented`, `continuous`, or `resumable`), `audio.stereo-quality`, `audio.mono-quality`, `playback.mono.fade-distance`, `resource-packs.universal-playback-system` (`library` by default), `resource-packs.playlist-playback-system` (`per-track` by default), `playlists.preimport.interval-seconds`, `resource-packs.prefetch.tracks-ahead`, `resource-packs.prefetch.max-concurrent-downloads`, and `resource-packs.cache.remove-pack-after-session`, range geometry, prefetch depth, legacy segment duration, resource-pack hosting mode, public host/optional port override, overlap priority, fade behavior, permissions and messages. Heavy command requests are rate-limited with per-player and server-wide cooldowns: imports (`audio.processing.import-cooldown-seconds`, default 10; `global-import-cooldown-seconds`, default 2), remote search (`search-cooldown-seconds`, default 3; `global-search-cooldown-seconds`, default 1), and manual seek/sync, resumable-resume and precise-volume audio-tail preparation (`transform-cooldown-seconds`, default 3; `global-transform-cooldown-seconds`, default 1). Ordinary pause/stop and default next-track volume changes are not throttled. External URL metadata lookups have their own cooldown (`metadata-cooldown-seconds`, default 5; `global-metadata-cooldown-seconds`, default 1); responses are capped at 64 KiB and source URLs at 2 KiB. Bulk local import counts as one admitted request and then processes its files sequentially. `/music import all` selects at most `audio.processing.max-bulk-import-files` files (default 50, hard maximum 200); it applies the request cooldown before scanning and bounds the file list before sorting. The shared single-thread audio worker and `audio.processing.max-queued-jobs` remain additional backpressure. `limits.max-playlist-tracks` (default 500) is enforced when adding tracks as well as when loading a playlist for playback. YouTube URL playlist imports require `audio.yt-dlp.allow-playlists: true` (default false); `audio.yt-dlp.max-playlist-items` defaults to 10 and is hard-capped at 20. Only one YouTube playlist batch may run at a time. Discord slash-command, autocomplete, link-code generation and OTP attempts also have per-user and global throttles configured under `discord.commands`. URL auto-import is off by default. The server operator must explicitly enable `audio.yt-dlp.auto-import-on-play` after confirming redistribution rights; once enabled, users with the DJ permission can run `/play <url>`. The separate `/music import-url` command remains admin-only and permanently imports a single URL; it does not require an extra confirmation word. Spotify and SoundCloud remain blocked as direct audio sources. Only URL imports initiated from /play are temporary by default and become eligible for expiry 15 minutes after a successful import (configurable with audio.yt-dlp.url-import-retention-minutes); active sessions and in-flight Java pack downloads delay deletion until the track is safe to remove. /music import-url and YouTube results selected through /music search are permanent. Permanent tracks have a TrackID-named audio archive and YAML metadata sidecar under plugins/Musicolide/SavedMusic/; they are not expired on restart. Local file imports are permanent and their source files are renamed by TrackID after a successful import. Existing incoming/ regular files are moved into SavedMusic/ on startup without overwriting a same-name file. On startup, Musicolide also removes abandoned processing intermediates from interrupted imports while preserving SavedMusic files and finished packs. Cloud processing remains a design only and is not implemented.

## Status

This repository is under active development. The shared-port listener integration uses Paper/Purpur's internal Netty acceptor layout through guarded reflection; it is not a stable Bukkit API. Unit tests cover HTTP-versus-Minecraft prefix detection and public URL port selection, but those tests do not replace live server tests. Test on staging servers for 1.21.1, 1.21.9, 1.21.11 and 26.2/26.3 before production use, particularly the Netty hook, mixed client versions, resource-pack downloads, resource-pack stacking and positional audio transitions. The server still needs to be reachable on its normal Minecraft TCP port, and the configured public host must resolve to that server. HTTP is used for the shared-port download endpoint; the plugin does not terminate HTTPS on the Minecraft listener.
