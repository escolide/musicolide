# Musicolide

**Server-side music playback for Minecraft Java Edition.**

Musicolide lets a Paper server play music to ordinary, unmodified Java clients. Players do not need a client mod, browser or separate audio application: Musicolide generates Minecraft resource packs on demand and uses the vanilla sound system for playback.

The first public release targets **Java Edition only**. Bedrock/Geyser playback is experimental and is not part of the supported release scope.

---

## Contents

- [Features](#features)
- [Compatibility](#compatibility)
- [Installation and first setup](#installation-and-first-setup)
- [How playback works](#how-playback-works)
- [Audio sources and usage rights](#audio-sources-and-usage-rights)
- [Commands](#commands)
- [Permissions](#permissions)
- [Optional integrations](#optional-integrations)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Build from source](#build-from-source)

## Features

- **Vanilla Java playback:** no client-side mods or browser required.
- **Spatial audio zones:** target players, circles, squares and optional WorldGuard regions.
- **Stereo and mono audio:** per-session audio modes, with a configurable fade band for mono circle/square zones.
- **Three playback pipelines:** segmented, continuous and resumable audio.
- **Playback controls:** pause, resume, skip, stop, volume, speed, seek and synchronization.
- **Queues and playlists:** individual queues, looping, private playlists and shared server playlists.
- **Resource-pack delivery:** automatically generated, version-aware packs with caching and bounded prefetch.
- **Local and remote search:** search the server catalogue and YouTube metadata.
- **Optional Discord integration:** linked-account slash commands and a passive voice-channel broadcast.
- **Optional WorldGuard integration:** use named regions as playback zones.
- **Usage analytics:** configurable bStats metrics.

## Compatibility

| Component | Support |
|---|---|
| Minecraft Java Edition | Paper 1.21.1–26.3 |
| Server software | Paper (primary target); Purpur is expected to work through Paper compatibility |
| Java runtime | Java 21 for Minecraft 1.21.x; use the Java version required by newer Minecraft releases (Java 25 for 26.1+) |
| Spigot | Not supported |
| Bedrock / Geyser | Experimental; not supported in the first Java-only release |

Musicolide is compiled against the Paper 1.21.1 API, the oldest supported API in the stated range. It detects the running server version and generates the matching resource-pack metadata.

If a proxy allows clients on a different Minecraft version from the backend server, test that mixed-version setup separately. Resource-pack compatibility is selected using the **server's** version, not each connecting client's version.

## Installation and first setup

### 1. Install the plugin

1. Download the universal Musicolide JAR from the release page.
2. Place it in the server's `plugins/` directory.
3. Start the Paper server once so Musicolide can create its data directory and configuration.
4. Allow the first-start tool installer to finish.

The same universal JAR is used across the supported operating systems and architectures.

### 2. Add music to the library

Place audio files that you are authorized to use in:

```text
plugins/Musicolide/SavedMusic/
```

Then run these commands as an administrator:

```text
/music import all
/music list
```

You can also import one file:

```text
/music import <filename> [title] [id:custom_name]
```

Musicolide processes imports through its audio worker, prepares the audio assets, and builds the resource-pack library. Successfully imported local files become permanent library entries and receive a public TrackID.

### 3. Set the public resource-pack address

Musicolide's default hosting mode serves resource packs through the existing Minecraft TCP listener. It automatically detects the bound listener port and can attempt to discover the server's public IP address.

For a normal server where the public hostname and port lead directly to the Minecraft listener, automatic detection may be sufficient. If the host uses a proxy hostname, NAT, port translation or a different public port, set the public address in `plugins/Musicolide/config.yml`:

```yaml
server:
  auto-detect-public-address: true
  public-base-url: "http://your-public-host.example"
  auto-detect-port: true
  public-port: -1
```

Use the hostname players actually connect to. Set `public-base-url` manually when automatic IP discovery cannot identify the hosting provider's public hostname. Set `public-port` when the externally reachable port differs from the detected Minecraft listener port. The full set of options and comments is in [config.yml](src/main/resources/config.yml).

### 4. Start playback

```text
/play <TrackID-or-title>
```

Players can also search by text using `/play <query>`. Musicolide searches the local catalogue first and can then return YouTube metadata results when the required tools are available.

A player must accept the required Musicolide resource pack before hearing tracks that depend on it. Musicolide requests packs for eligible playback recipients and synchronizes players when their required pack is ready.

### First-start downloads and server requirements

The universal JAR is intentionally small (the build enforces a size limit below 30 MiB). On first startup, Musicolide detects the operating system and CPU architecture, then downloads pinned, checksum-verified versions of:

- **FFmpeg** for audio probing, encoding and generated resume/synchronization audio.
- **yt-dlp** for supported YouTube metadata lookup and URL ingestion.
- **QuickJS** for yt-dlp's JavaScript challenge support.

The installer supports Linux x86_64/ARM64, Windows x86_64/ARM64 and macOS x86_64/ARM64. Tools are stored under `plugins/Musicolide/native/` and remain installed across restarts. No Python installation, shell script, system tar program or manual native-tool installation is required.

The server needs outbound HTTPS access to the relevant GitHub release assets during first-time installation. YouTube extraction may also need GitHub access later when yt-dlp retrieves or refreshes its official JavaScript challenge components. FFmpeg archives can be large (roughly 120–175 MB for current Linux/Windows upstream packages), so allow sufficient disk space.

If the host blocks automatic downloads, set `audio.tools.download-on-first-start: false` and provide compatible executables through the configured executable paths or the server's PATH. Native-tool license notices are installed alongside the tools; check `licenses/NATIVE-TOOLS-NOTICES.txt` before redistributing any of those binaries. The macOS build includes a mixture of GPL/LGPL codecs.

## How playback works

### Audio preparation modes

The mode is selected in `audio.playback-mode` and applies to **new imports**. Existing tracks keep the format with which they were imported.

| Mode | Audio layout | Resume / seek behavior |
|---|---|---|
| `segmented` | A sequence of short OGG segments | Segment-based position and resume; mono fade can be recalculated at segment boundaries |
| `continuous` | One streamed OGG | Resume restarts the track from the beginning |
| `resumable` | One streamed OGG plus generated timestamp tails when needed | FFmpeg prepares a temporary audio tail for the requested position; Java playback waits for its resource pack |

Minecraft's vanilla sound API does not provide a true pause or seek operation for an already-playing sound. Musicolide uses the selected audio mode to work around that limitation. Resumable-mode seek and resume are asynchronous; preparation time depends on the server's available CPU and storage.

### Stereo, mono and fade

Each imported track contains optimized stereo and true one-channel mono OGG variants. The playback session selects the requested variant.

- Stereo is the standard playback mode.
- Mono is useful for spatial playback where each listener should receive a sound attached to their own player entity.
- Mono circle/square zones support an additional fade band. Configure `playback.mono.fade-distance` or pass `fade:10 mode:mono`; use `fade:0 mode:mono` to disable the band.
- The optional `center:true mode:mono` setting uses a fixed zone-centred source instead of a listener-relative source.
- The fade option requires `mode:mono`. WorldGuard polygon regions have a hard boundary; edge-distance fading is not calculated for those regions.

The vanilla sound API cannot continuously change the volume of an already-playing streamed sound. Streamed playback samples fade gain when the sound starts; legacy segmented playback can recalculate it at segment boundaries. `playback.volume-change-mode` controls how saved volume changes are applied. The default `next-track` mode affects the next track; `restart-segment` can update the current segment in legacy segmented mode. Optional precise Java volume changes can use a generated timestamp-aligned audio tail.

### Spatial zones and overlapping sessions

Playback can target:

- An individual player or a selector such as `@s`, `@p` or `@a`.
- A circle with a configurable radius.
- A square with configurable width, length and rotation.
- A named WorldGuard region, when WorldGuard integration is enabled.

A spatial zone is centered on the command sender's location and world. When a player uses a spatial option without an explicit target, the configured player-target rules determine the audience. An explicit target always takes precedence. Console commands cannot create player-centered zones; specify a target for non-spatial console playback.

Only one eligible session is selected for each player at a time. By default, the first-started session wins in overlapping areas; manual priority can be used to change arbitration. Music outside the overlap can continue for players who are only inside that session's exclusive area.

### Queues, looping and playlists

Musicolide supports session queues, track loops and playlist playback. A single-track loop repeats that track and locks its matching queue until the session is stopped. A playlist loop repeats the playlist as a whole. A loop can be queued after ordinary tracks, but tracks cannot be appended after that loop entry.

There are two playlist types:

- **Shared playlists:** visible to everyone; administrators create and edit them, while DJs can browse and play them.
- **Private playlists:** each DJ manages their own playlists; ownership is tied to the creator's Minecraft UUID.

For URL playlists, Musicolide reads playlist metadata first, imports the first track, and then prepares upcoming tracks sequentially on the server. The pre-import interval is configurable (15 seconds by default, clamped to 10–20 seconds), and the default future-track limit is two. In per-track playlist delivery mode, players receive the pack for the track that is currently needed rather than downloading future tracks in advance.

### Resource-pack delivery and caching

Musicolide generates version-aware Java resource packs and serves them from the server. The aggregate library mode places imported tracks in one library pack; the optional per-track mode creates immutable packs for individual tracks. URL playlists have an independent delivery-mode setting.

Packs are requested only for eligible playback recipients and the configured prefetch window. With `resource-packs.download-mode: on-play`, players outside a playback zone do not trigger automatic pack requests. Stable pack IDs and hashes let Minecraft reuse cached content when a pack has not changed. The default cache behavior keeps applied packs active for the connection to avoid repeated resource-stack reloads.

A Java start barrier coordinates playback with resource-pack readiness without allowing a slow client to hold the session indefinitely. Players who enter or re-enter a zone mid-track are synchronized to the live session timeline once their required pack is ready. Reconnecting Java players do not restart streamed tracks from 00:00.

## Audio sources and usage rights

### Local audio

Local files are the simplest source. Import only audio that you own or are authorized to download, cache and distribute to your players. The fact that yt-dlp or another tool can retrieve a source does **not** grant redistribution rights.

Permanent local imports are stored in Musicolide's library and receive TrackIDs. The original source is handled according to the import/cleanup settings. Permanent track archives and metadata are kept under `plugins/Musicolide/SavedMusic/`.

### YouTube search and URL imports

YouTube search is metadata-only: it does not download audio by itself. The server uses yt-dlp and QuickJS for remote search. Search results are shown six at a time; `/music page <number>` opens later pages in sequence. The default maximum is four pages and can be raised to eight with `audio.yt-dlp.search.max-pages`. `audio.yt-dlp.search.prioritize-songs` can heuristically boost official audio, music videos, lyrics and Topic-channel uploads. Selecting a remote result from `/play` creates a temporary import; selecting a result through the administrator-only `/music search` workflow saves it permanently.

Direct URL importing is controlled by the `audio.yt-dlp` settings:

- `enabled` controls yt-dlp integration.
- `auto-import-on-play` controls whether players with DJ permission can use `/play <url>`.
- `allow-playlists` separately controls YouTube playlist URLs.
- `allowed-hosts` limits which hosts may be imported.
- `max-playlist-items` bounds each imported playlist (hard-capped at 20 and also limited by `limits.max-playlist-tracks`).

Check the values in your server's own `config.yml`; the administrator is responsible for ensuring that every enabled source is lawful to download and redistribute. URL imports initiated from `/play` are temporary by default and become eligible for cleanup after the configured retention period (15 minutes by default). Active, queued or downloading tracks are retained until safe to remove. `/music import-url` and results saved through `/music search` are permanent.

Spotify and SoundCloud are not direct audio-download sources. They can be used as metadata/alias references to an already imported local track; Musicolide does not fetch a stream from those services. It does not bypass DRM, subscriptions, region restrictions or platform protections.

## Commands

The main commands are grouped below. Use the in-game tab completion for the available arguments and current suggestions.

### Playback and personal controls

| Command | Purpose |
|---|---|
| `/play <TrackID/title/query/URL> [options]` | Search the local catalogue, select a remote result, or play an imported track |
| `/queue add <TrackID>` | Add a track to the current session queue |
| `/queue remove <TrackID>` | Remove an upcoming track |
| `/queue clear` | Clear the current session queue |
| `/music pause`, `resume`, `skip`, `stop` | Control the current playback session |
| `/music nowplaying`, `/music progress`, `/music queue` | Inspect current playback and queue state |
| `/music volume <0-100>` | Set playback volume |
| `/music speed <0.5-2.0>` | Change playback speed (also changes pitch) |
| `/music mute`, `/music unmute` | Mute or restore your Musicolide audio |
| `/music seek <seconds>`, `/music sync` | Seek or synchronize Java playback where supported |
| `/music gui` | Open the in-game playback GUI |
| `/deafen`, `/undeafen` | Mute or unmute Musicolide audio for yourself |

### Library and search (administrator commands unless noted)

| Command | Purpose |
|---|---|
| `/music list [search]` | List saved tracks and their public TrackIDs |
| `/music search <query>` | Search local and YouTube metadata for permanent library additions (admin) |
| `/music select <number>` | Select a result; admin for permanent search results, DJ for temporary `/play` results |
| `/music page <number>` | Open the next six search results in sequence |
| `/music save <TrackID>` | Make a temporary track permanent (admin) |
| `/music delete <TrackID>` | Delete a saved entry and its TrackID-named source files (admin) |
| `/music import <filename> [title] [id:custom_id]` | Import one local file permanently (admin) |
| `/music import all` | Import supported files from `SavedMusic/` sequentially (admin) |
| `/music import-url <url> [title...] [id:custom_id]` | Permanently import one allowlisted URL (admin) |
| `/music alias add <url> <TrackID>` | Associate an external metadata URL with a saved track (admin) |

### Playlists and administration

| Command | Purpose |
|---|---|
| `/music playlist create/add/remove/delete/list/show/play <...>` | Manage and play shared playlists; editing is admin-only |
| `/music playlist private create/add/remove/delete/list/show/play <...>` | Manage and play your own private playlists |
| `/music diagnostics` | Display diagnostic information |
| `/music download` | Request the aggregate library resource pack |
| `/music reload` | Reload settings that support runtime reload |
| `/music priority <number>` | Set manual playback priority |
| `/link`, `/unlink` | Link or unlink your Minecraft account with Discord |

### Playback options

The `/play` command and shared/private playlist `play` commands accept compact `key:value` options:

| Option | Example | Meaning |
|---|---|---|
| Target | `target:@a` | Send playback to `@s`, `@p`, `@a` or a player name |
| Custom ID | `id:ambient_mix` | Assign a custom internal track/sound ID when importing or playing a new remote source |
| Circle | `circle:60` | Create a circular zone with a 60-block radius |
| Square | `square:50x30` | Create a square/rectangular zone |
| WorldGuard | `region:spawn` | Use a WorldGuard region |
| Audio mode | `mode:mono` / `mode:stereo` | Select the track's audio layout |
| Fade | `fade:10` | Configure mono circle/square fade distance; requires `mode:mono` |
| Center | `center:true` | Use a fixed zone-centred sound origin for mono playback |
| Loop | `loop:true` / `loop:false` | Repeat or disable repetition |

A track loop repeats that track. On playlist playback, `loop:true|false` applies to the playlist as a whole; `id:` is not used for playlist playback. Zone and range options selected during a YouTube search are retained when a result is selected.

Examples:

```text
/play summer nights circle:40
/play synth mix square:50x30 target:@a
/play ambient region:spawn
/play ambient fade:10 mode:mono
/play summer nights mode:mono
/music search lo-fi beats circle:60
/music select 2
/music save a20
/music delete a20
```

The public TrackID format is `a00` through `f99` (up to 600 active tracks). A short TrackID can be reused after its track is deleted or expires; existing internal IDs remain accepted as aliases. Custom IDs use lowercase letters, numbers and underscores, from one to 45 characters, except for the reserved short TrackID pattern.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `musicolide.use` | Everyone | Use personal playback and account-linking features |
| `musicolide.dj` | Everyone | Start/control sessions, use queues and play existing playlists |
| `musicolide.admin` | OP | Manage the permanent library, import/save/delete tracks, and edit shared playlists |

The permission inheritance is `admin → dj → use`. LuckPerms or another permission manager can grant or deny these nodes explicitly. Non-admin players can manage their own private playlists; they cannot edit shared server playlists. Player self-zones and `target:@a` are additionally controlled by the relevant permission configuration.

## Optional integrations

### WorldGuard

When WorldGuard is installed on the same Paper server and `worldguard.enabled` is enabled, a named region can be used as a playback zone:

```text
/play ambient region:spawn
```

WorldGuard is optional; Musicolide can start without it. WorldGuard region polygon boundaries currently have hard edges and do not support the mono edge-distance fade calculation used for circle/square zones.

### Discord bot and voice broadcast

Discord integration is optional and is disabled by default. It can provide linked-account slash commands and mirror one eligible Musicolide session into one configured Discord voice channel. Minecraft remains authoritative: Discord cannot change the Minecraft playback clock or control playback if the bot disconnects.

Basic setup in `plugins/Musicolide/config.yml`:

```yaml
discord:
  enabled: true
  bot-token: "YOUR_DISCORD_BOT_TOKEN"
  voice-channel-id: "YOUR_VOICE_CHANNEL_ID"
  commands:
    enabled: true
    guild-id: "YOUR_DISCORD_GUILD_ID"
```

Keep the bot token private. Do not commit it to source control or share it in screenshots/logs. The bot needs only View Channel, Connect and Speak in the configured voice channel, plus the application-command integration and access to DMs for account pairing. Do not grant it Administrator or moderation permissions.

Slash commands are registered only in trusted guilds. `allowed-guild-ids` can define an explicit allowlist; otherwise Musicolide uses `guild-id`, then the guild containing `voice-channel-id`. Commands are never globally registered. Restart the Minecraft server after changing the bot token or voice channel.

Players run `/link` in Minecraft and privately DM the one-time code to the bot. The code expires after five minutes. Musicolide stores the Discord user ID and Minecraft account identifiers needed for linking; it does not request Microsoft passwords, Minecraft passwords or session tokens. `/unlink` removes the link.

Linked users can use Discord `/play`, `/help`, `/info` and `/music command`. The command bridge executes permitted Musicolide commands as the linked, online Minecraft player and applies the normal Minecraft permission checks. Depending on `discord.commands.admin-authorization`, privileged Discord actions can require Minecraft OP, Discord Administrator/configured roles, or both. The default policy is `minecraft-op`.

The gateway stays online for slash commands. The voice connection opens only when an eligible Minecraft playback session is active; a paused session keeps the bot connected without sending audio. The default `broadcast-scope: global-only` mirrors sessions addressed to `@a` without a spatial zone. `zone-aware` can mirror an eligible spatial session when at least one Java listener is receiving it, but Discord cannot reproduce multiple player-specific spatial mixes.

Discord presence and `/info` are metadata-only. Presence templates support `{title}`, `{artist}`, `{elapsed}`, `{duration}` and `{state}`. Server-address display is disabled by default. Command response visibility is configurable per command.

When enabled, the plugin downloads checksum-pinned JDA/DAVE Java dependencies and only the matching native library into `plugins/Musicolide/runtime/discord/`. These downloads run in the background and are not performed when Discord is disabled. Supported DAVE native targets are Linux x86_64 (glibc), Linux ARM64 (glibc), Windows x86_64 and macOS Intel/Apple Silicon. Linux musl and Windows ARM64 are not included.

### bStats analytics

Musicolide includes the official bStats Bukkit metrics library. Its configured bStats plugin ID is **35412**. The integration can be disabled with `bstats.enabled: false`, individual Musicolide charts can be disabled under `bstats.charts`, and server owners can disable bStats globally in `plugins/bStats/config.yml`.

The custom chart IDs to add on the Musicolide bStats page (Edit → Add chart) are:

| Chart ID | Reports |
|---|---|
| `playback_mode` | Segmented, continuous or resumable playback configuration |
| `audio_profile` | Balanced, compact, high or custom audio profile |
| `resource_pack_delivery` | Configured universal resource-pack delivery mode |
| `playlist_delivery` | Per-track or library playlist delivery |
| `remote_import_enabled` | Whether URL importing is enabled |
| `discord_enabled` | Whether Discord integration is enabled |
| `geyser_integration` | Whether Geyser integration is available/enabled |
| `worldguard_integration` | Whether WorldGuard integration is available/enabled |

The custom charts report fixed configuration categories or integration availability, not player usernames, UUIDs, chat, command history, track names, source URLs, server addresses or secrets. bStats also provides its standard aggregated server/software/version metrics. See the [bStats server-owner privacy information](https://bstats.org/docs/server-owners).

### Bedrock / Geyser

Bedrock/Geyser is **not supported in the first Java-only release**. The repository contains an experimental integration, but it has not been validated as a supported release feature. The shipped configuration excludes Geyser-identified Bedrock players from Musicolide output. Do not advertise Bedrock playback as supported until it has a separate release and client acceptance process.

See [docs/GEYSER.md](docs/GEYSER.md) for the experimental implementation and its limitations.

## Configuration

The complete, commented configuration template is [src/main/resources/config.yml](src/main/resources/config.yml). After the first startup, the live file is created at `plugins/Musicolide/config.yml`. The configuration covers:

- Public resource-pack address, listener mode and download limits.
- Native-tool installation and executable paths.
- Audio import, playback mode, encoding profiles and processing limits.
- Temporary-track retention and permanent-library storage.
- Resource-pack delivery, caching, download prompts and prefetch.
- Playback volume, synchronization, spatial ranges, fades and overlap priority.
- Playlist behavior, URL playlist pre-import and matching.
- Permissions, command limits and player-facing messages.
- Optional WorldGuard, Geyser exclusion and Discord integrations.
- bStats reporting and custom charts.

Use `/music reload` for settings that support runtime reload. Changes that affect listener setup/hosting mode, bound ports, or integrations initialized at plugin startup require a full server restart. When in doubt, restart after changing a startup-level setting.

## Troubleshooting

| Symptom | Checks |
|---|---|
| No music is heard | Confirm the player accepted the current required resource pack; check that the player is an eligible recipient and that the track/session is active |
| Resource pack cannot download | Check `server.public-base-url`, public DNS, the externally reachable TCP port, host port mapping and firewall rules |
| First startup tools fail to install | Confirm outbound HTTPS access to GitHub release assets, sufficient disk space and write access to the plugin directory |
| YouTube search has no remote results | Check `audio.yt-dlp.enabled`, yt-dlp/QuickJS availability, outbound connectivity and the server log for the remote search error |
| URL import is rejected | Check `auto-import-on-play`, `allow-playlists`, `allowed-hosts` and the sender's DJ/admin permission |
| WorldGuard region is not found | Confirm WorldGuard is installed on the same Paper server, enabled in Musicolide's config and that the region ID/world are correct |
| Discord commands do not register | Check the bot token, `discord.commands.enabled`, trusted guild configuration, bot invitation/application-command permissions and restart after configuration changes |

The plugin's default shared-port resource-pack handler distinguishes HTTP GET/HEAD requests from Minecraft traffic before Paper's normal packet handling. This uses guarded integration with Paper/Purpur's internal Netty listener layout, which is not a stable Bukkit API. If a hosting provider's proxy or port-forwarding setup does not pass both Minecraft and the pack HTTP requests to the same listener, configure an appropriate public endpoint or use the optional dedicated-port hosting mode if the host provides another public TCP port. The plugin itself does not terminate TLS on the Minecraft listener.

## Build from source

Requirements: JDK 21 or newer and Git.

```bash
git clone https://github.com/escolide/musicolide.git
cd musicolide
./gradlew clean test build
```

The deployable artifact is:

```text
build/libs/musicolide-0.1.0-universal.jar
```

The build runs the test suite and enforces the universal JAR size limit. The JAR contains the plugin, bundled runtime resources and pinned dependency manifests; platform-specific audio and Discord native tools are downloaded and verified at runtime.

For deeper implementation notes, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).