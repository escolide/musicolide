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
- `/music search <query>` and an unmatched `/play <query>` use yt-dlp's YouTube search extractor to show a small, player-private result list. Search fetches metadata only; `/music select <number>` is the explicit import/play action. Results expire after five minutes. `audio.yt-dlp.search.result-count` defaults to six (bounded to 1–20), and `audio.yt-dlp.search.prioritize-songs` defaults to true. Song prioritization is a heuristic that boosts official audio/music videos, lyrics and Topic channels while de-emphasizing reaction/tutorial/compilation results; turn it off to preserve YouTube's returned order.
- FFmpeg probes input duration and prepares audio according to `audio.playback-mode`: legacy OGG segments, one continuous streamed OGG, or a streamed OGG with timestamp-based resume generation. Player-specific `/music sync` and optional `precise` Java volume changes can build a temporary tail from either the streamed OGG or stored legacy segments without pausing other listeners. The default `playback.volume-change-mode: next-track` changes the saved volume without generating a file; the currently playing sound keeps its original volume until the next track. The optional `restart-segment` mode can apply volume immediately in legacy segmented mode by restarting the current segment; streamed tracks fall back to next-track. Bedrock volume changes always take effect on the next track. Fade zones adjust per-player stereo volume instead of generating duplicate mono audio.
- `/music speed <0.5-2.0>` adjusts the session's playback rate through Minecraft's sound-pitch parameter, without creating or downloading audio files. A live change is staged for the next track because an already-playing sound cannot have its pitch changed in place. This also changes the music's tonal pitch. Vanilla Java clients clamp sound pitch below 0.5, so accurate 0.25x playback is not available without client-side support or separately processed audio. The session clock and track transitions follow the selected speed.
- By default, generated resource packs are served locally through the existing Minecraft TCP listener. Musicolide inspects the first bytes of each accepted connection before Minecraft's packet handlers: HTTP GET/HEAD requests for the configured pack path are served from the local packs directory, while all other connections are passed to Paper unchanged. No second public port, external storage, VPS or upload account is required. A legacy dedicated-port HTTP mode remains available.
- Resource packs are requested only for current eligible playback recipients and their rolling prefetch window (four upcoming tracks by default); with `resource-packs.download-mode: on-play`, players outside a spatial zone do not trigger a pack request. The explicit `on-join` mode and a player's manual `/music download` request intentionally send the library outside a zone. Client-side pack hashes allow cached packs to be reused. The bounded Java start barrier waits for the first playable recipient. Listeners who enter or re-enter a zone mid-track are synchronized from the live session timeline using `playback.synchronization.late-join.delay-seconds` (default 4 seconds) after their library pack is ready. Leaving a zone invalidates the previous automatic catch-up attempt, so re-entry can generate a fresh synchronized tail. Reconnecting Java players are treated as late entrants and do not restart a streamed track from 00:00. Bedrock late entrants cannot receive private mid-track tail packs and wait until the next track.
- Imports update the aggregate music library on the server immediately. A Java player who is already hearing a track keeps their currently loaded library revision while that track plays; the latest aggregate pack is requested after the current track ends, before the queued track begins. This prevents an import or a `/play` queue addition from interrupting the current song with an unexpected mid-track pack reload. Generated resume/synchronization packs stay active on the client for the connection after use; the server deletes their ZIP files once downloads finish, rather than removing an applied pack at every track boundary and triggering another resource-stack reload.
- Spatial zones support circles, world-aligned or player-facing squares, optional vertical limits, and optional volume-based stereo fade outside the main zone. Fade settings remain supported: with the current stereo output they attenuate per-player stereo volume by distance; the settings are retained for future mono/mix modes.
- Overlap is resolved per player. The default policy is first-started session wins; lower-priority music continues in the exclusive part of its zone.

## Audio source and rights

YouTube URL ingestion is supported through yt-dlp when the server administrator explicitly enables URL auto-import and the source host is allowlisted. The default config prelists youtube.com and youtu.be, but keeps audio.yt-dlp.auto-import-on-play disabled. Once an administrator enables that setting and configures authorized hosts, users with the DJ permission can run /play <url> in-game to import the source and start playback after the resource pack is generated. From console, non-spatial playback must specify @a or an online player name; player-centered spatial options require an in-game sender. The administrator is responsible for ensuring that the server has permission to download, cache and distribute the audio to players. Spotify and SoundCloud remain metadata/alias-only sources. Musicolide does not bypass DRM, subscriptions, region controls or platform protections.

Import only audio that you own or are authorized to distribute to your players. The presence of yt-dlp does not grant redistribution rights.
YouTube search itself does not download audio. Selecting a result follows the same `audio.yt-dlp.auto-import-on-play` opt-in, allowed-host checks and redistribution-rights responsibility as `/play <YouTube URL>`. When auto-import is disabled, players can still inspect the metadata results, but selection is blocked until an administrator enables the setting.

## Build and native-tool setup

1. Install JDK 21 or newer.
2. For development, run `./gradlew clean test build`.

The **universal JAR stays below 30 MiB**. It does not contain hundreds of megabytes of platform executables. Instead, on the first server startup, Musicolide detects the OS/CPU architecture and downloads the matching pinned versions of yt-dlp, FFmpeg and QuickJS. Downloads are streamed to disk, SHA-256 verified, and extracted into `plugins/Musicolide/native/<platform>/` using Java archive libraries. No shell, system tar program, Python installation, or manual native-tool installation is required.

Automatic first-start downloads are provided for Linux x86_64/ARM64, Windows x86_64/ARM64 and macOS x86_64/ARM64. The tools remain installed across restarts. FFmpeg archives can be large (roughly 120–175 MB for the current Linux/Windows upstream packages); the installer streams them instead of loading them into RAM and deletes the archive after extracting the FFmpeg executable. Keep sufficient free disk space for the download and extracted tools.

The server needs outbound HTTPS access to GitHub release assets during first-time setup. YouTube extraction also enables yt-dlp's official `ejs:github` remote component, so the server needs GitHub access when yt-dlp first retrieves or refreshes the JavaScript challenge scripts. If the host blocks outbound downloads, set `audio.tools.download-on-first-start: false` and configure compatible executables through the existing executable settings or PATH.

Linux and Windows use the FFmpeg-Builds LGPL variants. The macOS installer uses the publisher's macOS builds; those include a mixture of GPL/LGPL codecs, so consult the installed `licenses/NATIVE-TOOLS-NOTICES.txt` before redistributing those tools. yt-dlp and QuickJS notices are also recorded under the installed native directory.

The single deployable universal JAR has a build-time size check enforcing a strict limit below 30 MiB. It contains only a compact Discord runtime manifest with pinned artifact versions, Maven Central URLs and SHA-256 hashes, not the third-party Discord dependency JARs or platform-native binaries. When Discord is enabled, Musicolide downloads the Java dependencies and only the matching DAVE native JAR into the server's runtime cache, verifying each artifact before loading. The FFmpeg/yt-dlp/QuickJS tools are also downloaded and checksum-verified on first startup.

The deployable plugin is `build/libs/musicolide-0.1.0-universal.jar`; use this same artifact across supported hosting providers and operating systems.
## Discord voice broadcast (optional)

Musicolide can broadcast the server's selected playback session into one Discord voice channel. It is **one-way only**: the bot does not register chat, slash-command, or interaction controls, and Discord members cannot play, pause, resume, skip, seek, change volume, or edit the Minecraft queue.

After the first server start, configure the local `plugins/Musicolide/config.yml`:

```yaml
discord:
  enabled: true
  bot-token: "YOUR_DISCORD_BOT_TOKEN"
  voice-channel-id: "YOUR_VOICE_CHANNEL_ID"
  reconnect-and-resync: true
```

Restart the Minecraft server after changing these settings; the Discord connection is initialized once during plugin startup.

Keep the real token private. Do not commit it to a repository or share it in logs/screenshots. The bot needs **View Channel**, **Connect**, and **Speak** in the configured voice channel. It uses the same imported track archive already stored by Musicolide and FFmpeg to decode it for Discord; it does not re-download the source audio.

The Minecraft session clock is authoritative. Pause, resume, seek, skip, track changes, playback speed and reconnect recovery are derived from read-only snapshots of that clock. If the bot disconnects, Minecraft continues uninterrupted. With `reconnect-and-resync: true`, the bot rejoins and starts from the current server timer instead of replaying stale buffered audio. Discord members joining or leaving the voice channel do not change the session.

A single configured Discord channel can broadcast only one session at a time. A session is eligible only while at least one Java player targeted by that session is currently in its zone, unmuted, and has the required pack ready; Bedrock players and pack-rejected/unready players do not make it eligible. When a global and a spatial session are both eligible at equal manual priority, the spatial session is preferred; manual priority still takes precedence, and oldest-session ordering breaks ties. Discord cannot reproduce different per-player zone mixes because it has no Minecraft player position. This feature requires FFmpeg and the DAVE native runtime. The universal JAR includes a compact manifest containing pinned Maven Central artifact URLs and SHA-256 hashes. When Discord is enabled, Musicolide downloads the JDA/DAVE Java dependency set into `plugins/Musicolide/runtime/discord/lib/` and downloads only the matching platform-native JAR into the `native/` subdirectory. Each downloaded artifact is hash-verified before it can be loaded, and verified files are cached for later restarts. No Discord dependencies are downloaded or extracted while Discord is disabled. Supported native targets are Linux x86_64 (glibc), Linux ARM64 (glibc), Windows x86_64, and macOS Intel/Apple Silicon. Linux musl, Windows ARM64 and other architectures are not currently included. Runtime bootstrap and downloads run on a background thread, never the Bukkit main thread; a failure disables only Discord output and does not stop Minecraft playback.

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
    search:
      result-count: 6
      prioritize-songs: true
    allowed-hosts:
      - "youtube.com"
      - "youtu.be"
```

Then run `/music reload` (or restart the server). A player with the `musicolide.dj` permission can run `/play <YouTube URL>` in-game after the server administrator enables this setting. The import downloads to a temporary folder, runs yt-dlp first, then FFmpeg converts the source using the configured `audio.playback-mode`, builds the resource pack, saves the temporary track, and starts a playback session. Console usage must include `--all` or `--player <name>`. Only use this for audio the server is authorized to download and redistribute; this setting does not grant those rights.

## Commands

- /music help
- /music import <filename> [title] (admin)
- /music import all (admin; imports supported files from incoming/ one at a time)
- /music import-url <url> --rights-confirmed [title] (admin; requires yt-dlp and an allowlisted source host)
- /play <track-or-url> (DJ for saved tracks; appends to the sender's existing session when its audience and zone match; URL auto-import additionally requires the administrator to enable audio.yt-dlp.auto-import-on-play and allow the source host)
- /music diagnostics (admin; reports native audio-tool availability)
- /music list [search]
- /music search <query> (DJ; YouTube metadata search, no audio download)
- /music select <number> (DJ; imports and plays one result from your latest search)
- /play <track/query/URL> [@a|@p|@s|player] [--circle radius | --square widthxlength | --region id] [--fade distance]
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

YouTube search settings are `audio.yt-dlp.search.result-count` (default 6, maximum 20) and `audio.yt-dlp.search.prioritize-songs` (default true). Song ranking is approximate and can be disabled. Search only reads result metadata; selecting a result is subject to the existing URL auto-import opt-in and allowlist.

Important settings include `audio.playback-mode` (`segmented`, `continuous`, or `resumable`), range geometry, prefetch depth, legacy segment duration, resource-pack hosting mode, public host/optional port override, overlap priority, fade behavior, permissions and messages. URL auto-import is off by default. The server operator must explicitly enable `audio.yt-dlp.auto-import-on-play` after confirming redistribution rights; once enabled, users with the DJ permission can run `/play <url>`. The separate `/music import-url` command remains admin-only and requires `--rights-confirmed`. Spotify and SoundCloud remain blocked as direct audio sources. URL-imported tracks are temporary by default and become eligible for expiry 15 minutes after a successful import (configurable with audio.url-import-retention-minutes); active sessions and in-flight Java pack downloads delay deletion until the track is safe to remove. Local file imports remain in the catalogue. On startup, Musicolide also removes abandoned processing intermediates from interrupted imports while preserving ordinary incoming uploads and finished packs. Cloud processing remains a design only and is not implemented.

## Status

This repository is under active development. The shared-port listener integration uses Paper/Purpur's internal Netty acceptor layout through guarded reflection; it is not a stable Bukkit API. Unit tests cover HTTP-versus-Minecraft prefix detection and public URL port selection, but those tests do not replace live server tests. Test on staging servers for 1.21.1, 1.21.9, 1.21.11 and 26.2/26.3 before production use, particularly the Netty hook, mixed client versions, resource-pack downloads, resource-pack stacking and positional audio transitions. The server still needs to be reachable on its normal Minecraft TCP port, and the configured public host must resolve to that server. HTTP is used for the shared-port download endpoint; the plugin does not terminate HTTPS on the Minecraft listener.
