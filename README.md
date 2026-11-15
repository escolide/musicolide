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
- An unmatched `/play <query>` searches the local catalogue first, then YouTube metadata. Saved tracks are labelled `[Local]` and are played directly without being imported again. Text-search underscores are treated as spaces; an exact saved track ID and URL are preserved as entered. Search pages contain six results. `/music page 2` requests the next page, and pages must be opened in sequence. The default maximum is four pages; `audio.yt-dlp.search.max-pages` can be raised to eight. Only the remote result depth needed for the requested page is fetched. Search metadata only; choosing a remote result with `/music select <number>` is the import/play action. Results expire after five minutes. `audio.yt-dlp.search.prioritize-songs` defaults to true and heuristically boosts official audio/music videos, lyrics and Topic channels while de-emphasizing reaction/tutorial/compilation results; turn it off to preserve YouTube's returned order.
- FFmpeg probes input duration and prepares audio according to `audio.playback-mode`: legacy OGG segments, one continuous streamed OGG, or a streamed OGG with timestamp-based resume generation. Player-specific `/music sync` and optional `precise` Java volume changes can build a temporary tail from either the streamed OGG or stored legacy segments without pausing other listeners. The default `playback.volume-change-mode: next-track` changes the saved volume without generating a file; the currently playing sound keeps its original volume until the next track. The optional `restart-segment` mode can apply volume immediately in legacy segmented mode by restarting the current segment; streamed tracks fall back to next-track. Bedrock volume changes always take effect on the next track. Each imported track contains optimized stereo and true one-channel mono OGG variants. The player/session selects one sound event, so changing mode does not generate audio on demand for newly imported tracks. Mono circle/square zones apply a configurable 10-block default fade band; set playback.mono.fade-distance or override per /play with --fade <distance> (use --fade 0 to disable).
- `/music speed <0.5-2.0>` adjusts the session's playback rate through Minecraft's sound-pitch parameter, without creating or downloading audio files. A live change is staged for the next track because an already-playing sound cannot have its pitch changed in place. This also changes the music's tonal pitch. Vanilla Java clients clamp sound pitch below 0.5, so accurate 0.25x playback is not available without client-side support or separately processed audio. The session clock and track transitions follow the selected speed.
- By default, generated resource packs are served locally through the existing Minecraft TCP listener. Musicolide inspects the first bytes of each accepted connection before Minecraft's packet handlers: HTTP GET/HEAD requests for the configured pack path are served from the local packs directory, while all other connections are passed to Paper unchanged. No second public port, external storage, VPS or upload account is required. A legacy dedicated-port HTTP mode remains available.
- The aggregate Java library pack is requested only for eligible playback recipients when they need it; with `resource-packs.download-mode: on-play`, players outside a spatial zone do not trigger a pack request. The explicit `on-join` mode and a player's manual `/music download` request intentionally send the library outside a zone. Client-side pack hashes allow cached packs to be reused. Manual `/music download` retries have a configurable per-player cooldown (`resource-packs.request-cooldown-seconds`, default 30 seconds); automatic playback pack delivery is not throttled by that manual-command cooldown. The prefetch queue keeps up to two temporary generated packs ahead by default (`resource-packs.prefetch.tracks-ahead`); normal catalogue songs already live in the aggregate pack. The bounded Java start barrier waits for the first playable recipient. Listeners who enter or re-enter a zone mid-track are synchronized from the live session timeline using `playback.synchronization.late-join.delay-seconds` (default 4 seconds) after their library pack is ready. Leaving a zone invalidates the previous automatic catch-up attempt, so re-entry can generate a fresh synchronized tail. Reconnecting Java players are treated as late entrants and do not restart a streamed track from 00:00. Bedrock late entrants cannot receive private mid-track tail packs and wait until the next track.
- Imports update the aggregate music library on the server immediately. A Java player who is already hearing a track keeps their currently loaded library revision while that track plays; the latest aggregate pack is requested after the current track ends, before the queued track begins. This prevents an import or a `/play` queue addition from interrupting the current song with an unexpected mid-track pack reload. Generated resume/synchronization packs stay active on the client for the connection after use; the server deletes their ZIP files once downloads finish, rather than removing an applied pack at every track boundary and triggering another resource-stack reload.
- Spatial zones support circles, world-aligned or player-facing squares, optional vertical limits, and optional per-player fade outside the main zone. For mono circle/square sessions, the default fade distance is 10 blocks and is configured with playback.mono.fade-distance; /play --fade <distance> overrides it and --fade 0 disables it. WorldGuard polygon regions currently have a hard boundary because their arbitrary edge distance is not calculated.
- Overlap is resolved per player. The default policy is first-started session wins; lower-priority music continues in the exclusive part of its zone.

## Audio source and rights

YouTube URL ingestion is supported through yt-dlp when the server administrator explicitly enables URL auto-import and the source host is allowlisted. The default config prelists youtube.com and youtu.be, but keeps audio.yt-dlp.auto-import-on-play disabled. Once enabled, users with the DJ permission can run `/play <url>` in-game to import one video. YouTube playlist URLs are separately opt-in with `audio.yt-dlp.allow-playlists: true` (default `false`); `audio.yt-dlp.max-playlist-items` defaults to 10 and is hard-capped at 20, as well as being limited by `limits.max-playlist-tracks`. Playlist metadata is inspected first, then each selected video is downloaded and processed sequentially through the single-video `--no-playlist` path. Ordinary video URLs that contain a playlist context still import only that video. From console, non-spatial playback must specify @a or an online player name; player-centered spatial options require an in-game sender. The administrator is responsible for ensuring that the server has permission to download, cache and distribute the audio to players. Spotify and SoundCloud remain metadata/alias-only sources. Musicolide does not bypass DRM, subscriptions, region controls or platform protections.

Import only audio that you own or are authorized to distribute to your players. The presence of yt-dlp does not grant redistribution rights.
YouTube search itself does not download audio. Selecting a result follows the same `audio.yt-dlp.auto-import-on-play` opt-in, allowed-host checks and redistribution-rights responsibility as `/play <YouTube URL>`. When auto-import is disabled, players can still inspect the metadata results, but selection is blocked until an administrator enables the setting. Playlist import is a separate option and remains disabled unless `audio.yt-dlp.allow-playlists: true` is configured. It only accepts a bounded number of entries and downloads them one at a time.

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
```

Keep the real token private. Do not commit it to a repository or share it in logs/screenshots. The bot needs only **View Channel**, **Connect**, and **Speak** in the configured voice channel, plus the normal application-command integration and access to DMs for account pairing. Do not grant the bot Administrator, Manage Server, Manage Roles, Manage Messages, Kick Members, Ban Members or other moderation permissions. Musicolide's Discord code does not implement moderation actions or arbitrary Bukkit command dispatch; its gateway intents are limited to voice state and direct messages. Slash commands are registered only in trusted guilds: `allowed-guild-ids` takes precedence and supports an explicit multi-guild allowlist; otherwise `guild-id` is used, then the guild containing `voice-channel-id`. The bot refuses global slash-command registration when no trusted guild can be resolved. Restart the Minecraft server after changing the bot token or voice channel. The runtime bootstrap/download occurs on a background thread.

### Presence and /info

The bot can display the current track and elapsed time using Discord listening activity. The presence template supports `{title}`, `{artist}`, `{elapsed}`, `{duration}`, and `{state}`. Discord presence updates are rate-limited to at least 15 seconds, but a track or playback-state change is sent immediately. Set `presence.enabled: false` to disable activity.

Discord `/info` shows the selected Musicolide track, artist, current timestamp, duration and playback state. Set `info.show-server-address: true` and configure `server-ip` plus `server-port` to show a public server address; the address is hidden by default. `info.show-thumbnail: true` displays a YouTube thumbnail only inside Discord when a saved YouTube source ID exists. It does not add an image to Java or Bedrock resource packs, so player downloads are unchanged. `info.custom-message` adds a final embed field after the standard details; it supports `{state}`, `{track}`/`{title}`, `{artist}`, `{position}`, `{duration}`, and `{server}`. The embed accent is configured through `embed-color` (six-digit hex, default purple). Per-command visibility is controlled by `discord.commands.visibility.<command>`: `requester` (private/ephemeral, default) or `everyone` (public).

### Secure account linking and controls

A player runs `/link` in Minecraft. Musicolide privately displays an eight-digit, one-use code valid for five minutes. The player DMs that code to the bot. The plugin stores only the Discord user ID, Minecraft UUID/name and link timestamp; it does not ask for or store Microsoft passwords, Minecraft passwords, session tokens or other authentication secrets. Each Discord account and each Minecraft account can be linked to only one counterpart. The player runs `/unlink` in Minecraft to remove the link. Pending codes are memory-only and are invalidated on restart.

Linked players can use Discord `/play`, `/help`, `/info`, and `/music command:<command text>`. Discord `/play` accepts optional `target`, `id`, `zone` (for example `circle 50`, `square 50x50`, or `region spawn`), `mode` (`stereo` by default or `mono` for one-channel audio), and `fade` options. Mono circle/square zones use a configurable 10-block fade by default; WorldGuard regions retain a hard boundary. As in Minecraft, targeting another player/all players or selecting a spatial zone requires the linked Minecraft account to have the configured admin permission; self-target playback continues to use the linked player. The `/music command` option routes existing Musicolide commands through the normal plugin command handler as the linked Minecraft player, so the Minecraft account must be online and existing DJ/use permissions continue to apply. The Discord `/help` command explains this system. `/play` autocomplete uses the local catalogue; the server's existing URL auto-import and allowlist settings still control whether remote YouTube results can be imported. New imports contain both channel layouts, so selecting a mode does not need a separate audio conversion. Older stereo-only imports are converted to mono once when first requested; this rebuilds the shared resource-pack library and can cause one resource-pack reapplication for players who already downloaded the old library. The selected mode applies to the entire playback session and its queue. Stop the session before switching its mode. For server-wide sessions, the passive Discord voice mirror uses the same mono/stereo variant. Geyser/Bedrock clients receive an updated Bedrock library on their next connection, so an already-connected Bedrock player should reconnect after a legacy mono conversion or another library revision.

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
2. Put authorized source files in plugins/Musicolide/incoming/.
3. Set server.public-base-url to the public IP address or hostname players use to join the server. The default hosting mode is shared-port, and Musicolide automatically detects the bound Minecraft TCP listener port. The port written in an existing public-base-url is replaced when auto-detection is enabled.
4. If the host maps the public TCP port to a different number than the port bound inside the Minecraft process, set server.public-port to that external port. Leave it at -1 for automatic detection.
5. Run /music import <filename> [title] as an administrator, or use /music import all to process every supported file in incoming/ sequentially.
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

Then run `/music reload` (or restart the server). A player with the `musicolide.dj` permission can run `/play <YouTube URL>` in-game after the server administrator enables this setting. The import downloads to a temporary folder, runs yt-dlp first, then FFmpeg converts the source using the configured `audio.playback-mode`, builds the resource pack, saves the temporary track, and starts a playback session. Playlist URLs are separately controlled by `audio.yt-dlp.allow-playlists` and are disabled by default; when enabled, `/play <YouTube playlist URL>` imports up to `max-playlist-items` tracks (default 10, hard maximum 20) one at a time. Only one playlist batch can run at once, in addition to the normal single-worker/queue limit. The admin-only `/music import-url` path remains a single-video importer. Console usage must include `--all` or `--player <name>`. Only use this for audio the server is authorized to download and redistribute; these settings do not grant those rights.

## Commands

- /music help
- /music import <filename> [title] [--id custom_id] (admin)
- /music import all (admin; imports supported files from incoming/ one at a time)
- /music import-url <url> --rights-confirmed [title] [--id custom_id] (admin; requires yt-dlp and an allowlisted source host)
- /play <track-or-url-or-playlist> [--id custom_id] (DJ; YouTube playlist URLs additionally require audio.yt-dlp.allow-playlists: true and are bounded by audio.yt-dlp.max-playlist-items; imported URLs are reused without another download when already in the catalogue)
- /music diagnostics (admin; reports native audio-tool availability)
- /music list [search]
- /music search <query> (DJ; local catalogue first, then YouTube metadata; no audio download)
- /music select <number> (DJ; plays a local result directly or imports a selected remote result)
- /music page <2-4> (DJ; opens the next six results in sequence; maximum is configurable up to eight)
- /play <track/query/URL> [@a/@p/@s/player] [--target selector] [--id custom_id] [--circle radius/--square widthxlength/--region id] [--mode mono/stereo] [--fade distance]
- /music playlist create <name>
- /music playlist add <name> <track-id>
- /music playlist list
- /music playlist play <name> [zone options]
- /music queue <track-id-or-title>
- /music pause, /music resume, /music skip, /music stop
- /music nowplaying, /music progress
- /music volume <0-100>, /music mute, /music unmute
- /music speed <0.5-2.0> (DJ; applies immediately before playback starts or while paused, otherwise on the next track)
- /music seek <seconds>
- /music sync (player; rebuilds only your audio from the active session timeline)
- /music sync <seconds> (admin; updates the shared `playback.synchronization.late-join.delay-seconds` offset, default 4 seconds)
- /music alias add <external-url> <track-id> (admin)
- /music reload (admin)

For `/play` and `/music search`, zone/range modifiers are retained when the YouTube result is selected. When a player supplies a spatial option without an explicit target, the default audience is `@a`, filtered by the zone; without a spatial option, the historical default remains `@s`. An explicit `@s`, `@p`, `@a` or player-name target always overrides that default. The zone center is the command sender's location and world. Console playback cannot create a player-centered spatial zone and must specify a target for non-spatial playback.

Examples:
```
/play summer nights --circle 40
/play synth mix --square 50x30 @a
/play ambient --region spawn
/music search lo-fi beats --circle 60
```

The first command searches YouTube and then applies the same 40-block circle to the selected result. Players entering the active zone receive the required pack and are synchronized using the configured late-join delay.

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

The first time a Java player needs music, Musicolide sends one aggregate library resource pack containing the imported tracks. Its content hash gives each immutable library revision a stable download URL, so Minecraft can reuse a cached copy when the contents have not changed. Temporary per-player audio tails used for synchronization or resumable playback are delivered separately and cleaned up after they are no longer needed. The base library can be kept active for the connection or removed after a session with resource-packs.cache.remove-pack-after-session. Declining the library pack prevents custom audio playback.

Minecraft's vanilla sound API does not expose a true pause/seek operation for an already-playing sound. Musicolide therefore offers three import-time playback modes in `audio.playback-mode`: `segmented` keeps the legacy short-OGG-segment approach (resume/seek are segment-based); `continuous` uses one streamed OGG and restarts it from the beginning on resume; and `resumable` uses one streamed OGG, then invokes FFmpeg on `/resume` or `/music seek <seconds>` to generate audio from the saved/requested timestamp and builds a temporary resource pack. In resumable mode, Java clients wait for that pack to load before playback continues. Resuming is asynchronous and may take a short time depending on the host. The mode affects new imports only; existing tracks retain their original format. Geyser/Bedrock clients cannot receive these per-resume Java packs, so mixed sessions use the compatible restart-from-beginning fallback where applicable. Bedrock players who enter or re-enter after a track has started are held out until the next track rather than being given mismatched audio.

## Configuration

See the fully commented config.yml for all available settings. The resource-pack format and shared Minecraft listener port are detected automatically; do not manually set a pack format or the Minecraft port. Set server.public-base-url to the public host players use. If external port mapping differs from the local Minecraft listener, set server.public-port explicitly. Use /music reload after changing reloadable settings. Hosting mode and shared-listener injection require a full server restart.

YouTube search settings include `audio.yt-dlp.search.prioritize-songs` (default true) and `audio.yt-dlp.search.max-pages` (default 4; configurable from one to eight). Pages contain six results and the plugin fetches deeper YouTube results only when the player requests the next page. Search reads metadata only; selecting a remote result is subject to the existing URL auto-import opt-in and allowlist. `/play` and import commands accept an optional `--id` to define the new track's ID and displayed title; custom IDs use lowercase letters, numbers and underscores (one to 45 characters). With no custom ID, the existing generated ID and source title behavior is retained. The aggregate Java resource pack contains only its required `pack.mcmeta` and sound assets/definitions; Bedrock retains its required `manifest.json` and sound definitions. No thumbnail or unrelated metadata is included in player packs.

Important settings include `audio.playback-mode` (`segmented`, `continuous`, or `resumable`), `audio.stereo-quality`, `audio.mono-quality`, `playback.mono.fade-distance`, range geometry, prefetch depth, legacy segment duration, resource-pack hosting mode, public host/optional port override, overlap priority, fade behavior, permissions and messages. Heavy command requests are rate-limited with per-player and server-wide cooldowns: imports (`audio.processing.import-cooldown-seconds`, default 10; `global-import-cooldown-seconds`, default 2), remote search (`search-cooldown-seconds`, default 3; `global-search-cooldown-seconds`, default 1), and manual seek/sync audio preparation (`transform-cooldown-seconds`, default 3; `global-transform-cooldown-seconds`, default 1). Bulk local import counts as one admitted request and then processes its files sequentially. The shared single-thread audio worker and `audio.processing.max-queued-jobs` remain additional backpressure. `limits.max-playlist-tracks` (default 500) is enforced when adding tracks as well as when loading a playlist for playback. YouTube URL playlist imports require `audio.yt-dlp.allow-playlists: true` (default false); `audio.yt-dlp.max-playlist-items` defaults to 10 and is hard-capped at 20. Only one YouTube playlist batch may run at a time. Discord slash-command, autocomplete, link-code generation and OTP attempts also have per-user and global throttles configured under `discord.commands`. URL auto-import is off by default. The server operator must explicitly enable `audio.yt-dlp.auto-import-on-play` after confirming redistribution rights; once enabled, users with the DJ permission can run `/play <url>`. The separate `/music import-url` command remains admin-only and requires `--rights-confirmed`. Spotify and SoundCloud remain blocked as direct audio sources. URL-imported tracks are temporary by default and become eligible for expiry 15 minutes after a successful import (configurable with audio.url-import-retention-minutes); active sessions and in-flight Java pack downloads delay deletion until the track is safe to remove. Local file imports remain in the catalogue. On startup, Musicolide also removes abandoned processing intermediates from interrupted imports while preserving ordinary incoming uploads and finished packs. Cloud processing remains a design only and is not implemented.

## Status

This repository is under active development. The shared-port listener integration uses Paper/Purpur's internal Netty acceptor layout through guarded reflection; it is not a stable Bukkit API. Unit tests cover HTTP-versus-Minecraft prefix detection and public URL port selection, but those tests do not replace live server tests. Test on staging servers for 1.21.1, 1.21.9, 1.21.11 and 26.2/26.3 before production use, particularly the Netty hook, mixed client versions, resource-pack downloads, resource-pack stacking and positional audio transitions. The server still needs to be reachable on its normal Minecraft TCP port, and the configured public host must resolve to that server. HTTP is used for the shared-port download endpoint; the plugin does not terminate HTTPS on the Minecraft listener.
